package com.example.data.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.db.TransferDao
import com.example.data.db.TransferEntity
import com.example.data.network.ConnectionManager
import com.example.data.network.NetworkClient
import com.example.data.network.NetworkServer
import com.example.data.network.TlsTransport
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolConstants
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.security.SecurityValidator
import com.example.domain.model.AppErrorMapper
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.Device
import com.example.domain.model.DeviceType
import com.example.domain.model.ErrorCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferPriority
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.presentation.transfer.SelectedFile
import com.example.util.NetworkUtils
import com.example.util.PerformanceMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TransferManager(
    private val context: Context,
    private val server: NetworkServer,
    private val client: NetworkClient,
    private val storageManager: StorageManager,
    private val transferDao: TransferDao,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val connectionManager: ConnectionManager,
    private val notificationHelper: TransferNotificationHelper,
    private val myDeviceIdProvider: () -> String,
    private val performanceMonitor: PerformanceMonitor? = null
) {
    companion object {
        private const val TAG = "TransferManager"
        private const val MAX_CONCURRENT_CAP = 5
        private const val APPROVAL_TIMEOUT_MS = 60_000L
        private const val CONFLICT_PROMPT_TIMEOUT_MS = 30_000L
        private const val MAX_AUTO_RETRIES = 2
    }

    private data class QueuedSlotWaiter(
        val transferId: String,
        val deferred: CompletableDeferred<Unit>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slotMutex = Mutex()
    private val conflictPromptMutex = Mutex()
    private var activeTransferSlots = 0
    private val slotWaiters = mutableListOf<QueuedSlotWaiter>()
    private val queueSequence = AtomicInteger(1)
    private val interactiveConflictUiAvailable = AtomicBoolean(false)

    private val _activeTransfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val activeTransfers: StateFlow<List<TransferItem>> = _activeTransfers.asStateFlow()

    private val _incomingRequest = MutableStateFlow<IncomingTransferRequest?>(null)
    val incomingRequest: StateFlow<IncomingTransferRequest?> = _incomingRequest.asStateFlow()

    private val _activeConflictPrompt = MutableStateFlow<ConflictPrompt?>(null)
    val activeConflictPrompt: StateFlow<ConflictPrompt?> = _activeConflictPrompt.asStateFlow()

    private val _stats = MutableStateFlow(TransferStats())
    val stats: StateFlow<TransferStats> = _stats.asStateFlow()

    val transferHistory = transferDao.getAll().map { list ->
        list.map { entity -> entityToTransferItem(entity) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    var onStateChangedListener: (() -> Unit)? = null

    private val transferCancellationFlags = ConcurrentHashMap<String, Boolean>()
    private val transferPauseFlags = ConcurrentHashMap<String, Boolean>()
    private val retryableSendMap = ConcurrentHashMap<String, Pair<SelectedFile, Device>>()
    private val recoverableNetworkFailureIds = ConcurrentHashMap.newKeySet<String>()
    private val activeTransferSockets = ConcurrentHashMap<String, Socket>()
    private val activeTransferPeers = ConcurrentHashMap<String, Pair<String, Int>>()
    private val sessionPeerEndpoints = ConcurrentHashMap<String, Pair<String, Int>>()
    private val pendingApprovalResponses = ConcurrentHashMap<String, CompletableDeferred<ProtocolMessage>>()
    private val pendingOutgoingApprovals = ConcurrentHashMap<String, CompletableDeferred<ProtocolMessage>>()
    private val pendingConflictDecisions = ConcurrentHashMap<String, CompletableDeferred<ConflictDecision>>()
    private val sessionConflictPolicies = ConcurrentHashMap<String, ConflictAction>()
    private val requestQueueLock = Any()
    private val incomingRequestQueue = ArrayDeque<IncomingTransferRequest>()

    init {
        scope.launch {
            settingsRepository.settings.collect { currentSettings ->
                storageManager.setCustomDownloadTreeUri(currentSettings.customDownloadTreeUri)
            }
        }
        scope.launch {
            transferHistory.collect {
                recalculateStats()
            }
        }
    }

    fun setInteractiveConflictUiAvailable(available: Boolean) {
        interactiveConflictUiAvailable.set(available)
    }

    fun resolveFileConflict(fileId: String, decision: ConflictDecision) {
        val current = _activeConflictPrompt.value
        if (current?.fileId == fileId) {
            _activeConflictPrompt.value = null
        }
        pendingConflictDecisions.remove(fileId)?.complete(decision)
    }

    private fun entityToTransferItem(entity: TransferEntity): TransferItem {
        val status = try {
            TransferStatus.valueOf(entity.status)
        } catch (_: Exception) {
            TransferStatus.COMPLETED
        }
        val direction = try {
            TransferDirection.valueOf(entity.direction)
        } catch (_: Exception) {
            TransferDirection.RECEIVE
        }
        val priority = try {
            TransferPriority.valueOf(entity.priority)
        } catch (_: Exception) {
            TransferPriority.NORMAL
        }
        val progress = when {
            status == TransferStatus.COMPLETED -> 1.0f
            entity.fileSize > 0L && entity.bytesTransferred > 0L ->
                (entity.bytesTransferred.toDouble() / entity.fileSize.toDouble()).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }
        return TransferItem(
            id = entity.id,
            fileName = entity.fileName,
            fileSize = entity.fileSize,
            direction = direction,
            status = status,
            progress = progress,
            speed = entity.speed ?: "",
            peerDeviceName = entity.peerDeviceName,
            peerAddress = entity.peerAddress,
            timestamp = entity.timestamp,
            filePath = entity.filePath,
            mimeType = entity.mimeType,
            senderDevice = entity.senderDevice,
            receiverDevice = entity.receiverDevice,
            startTime = entity.startTime,
            completionTime = entity.completionTime,
            durationMs = entity.durationMs,
            checksum = entity.checksum,
            fileExtension = entity.fileExtension.ifEmpty { entity.fileName.substringAfterLast('.', "") },
            errorMessage = entity.errorMessage,
            relativePath = entity.relativePath,
            avgSpeedBytesPerSec = entity.avgSpeedBytesPerSec,
            peakSpeedBytesPerSec = entity.peakSpeedBytesPerSec,
            priority = priority,
            queueOrder = entity.queueOrder,
            bytesTransferred = entity.bytesTransferred,
            isPaused = entity.isPaused,
            retryCount = entity.retryCount,
            isDirectory = entity.isDirectory,
            partialChecksum = entity.partialChecksum,
            sourceUri = entity.sourceUri
        )
    }

    suspend fun recoverInterruptedTransfers(): Set<String> = withContext(Dispatchers.IO) {
        val recoverableEntities = try {
            transferDao.getRecoverableTransfers()
        } catch (_: Exception) {
            emptyList()
        }
        if (recoverableEntities.isEmpty()) return@withContext emptySet()

        val recoveredItems = mutableListOf<TransferItem>()
        val preservedIds = mutableSetOf<String>()

        for (entity in recoverableEntities) {
            val item = entityToTransferItem(entity)
            if (item.status == TransferStatus.COMPLETED || item.status == TransferStatus.CANCELLED) continue

            val resumedItem = if (item.direction == TransferDirection.RECEIVE) {
                val tempMeta = storageManager.readResumeMetadata(item.id)
                val tempFile = storageManager.createTempFile(item.id)
                val validBytes = if (tempMeta != null && tempFile.exists() && tempFile.length() >= tempMeta.bytesWritten) {
                    preservedIds.add(item.id)
                    tempMeta.bytesWritten
                } else {
                    0L
                }
                val prog = if (item.fileSize > 0L) {
                    (validBytes.toDouble() / item.fileSize.toDouble()).toFloat().coerceIn(0f, 1f)
                } else 0f
                item.copy(
                    status = TransferStatus.PENDING,
                    isPaused = true,
                    bytesTransferred = validBytes,
                    progress = prog,
                    speed = if (validBytes > 0L) "Resumable (${TransferItem.formatBytes(validBytes)})" else "Paused",
                    partialChecksum = tempMeta?.prefixSha256
                )
            } else {
                // Reconstruct retryable handle if sourceUri is still readable
                val uriStr = item.sourceUri
                if (!uriStr.isNullOrBlank()) {
                    try {
                        val parsedUri = Uri.parse(uriStr)
                        val selected = SelectedFile.fromUri(context, parsedUri, item.relativePath)
                        if (selected.isAccessible) {
                            val parts = item.peerAddress.split(':')
                            val host = parts.getOrNull(0) ?: ""
                            val port = parts.getOrNull(1)?.toIntOrNull() ?: 53317
                            if (host.isNotBlank()) {
                                val targetDevice = Device(
                                    id = item.peerDeviceName,
                                    alias = item.peerDeviceName,
                                    deviceType = DeviceType.UNKNOWN,
                                    ipAddress = host,
                                    port = port
                                )
                                retryableSendMap[item.id] = selected to targetDevice
                            }
                        }
                    } catch (_: Exception) {}
                }
                preservedIds.add(item.id)
                item.copy(
                    status = TransferStatus.PENDING,
                    isPaused = true,
                    speed = "Paused (Interrupted)"
                )
            }

            recoveredItems.add(resumedItem)
            transferPauseFlags[resumedItem.id] = true
            persistActiveStateEntity(resumedItem)
        }

        if (recoveredItems.isNotEmpty()) {
            _activeTransfers.update { current ->
                val existingIds = current.map { it.id }.toSet()
                current + recoveredItems.filter { it.id !in existingIds }
            }
            recalculateStats()
        }
        preservedIds
    }

    private suspend fun persistActiveStateEntity(item: TransferItem, sessionId: String = "") {
        val myName = settingsRepository.settings.value.deviceName
        try {
            transferDao.insert(
                TransferEntity(
                    id = item.id,
                    sessionId = sessionId,
                    fileName = item.fileName,
                    fileExtension = item.fileExtension.ifBlank { item.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) },
                    mimeType = item.mimeType,
                    fileSize = item.fileSize,
                    senderDevice = item.senderDevice.ifBlank {
                        if (item.direction == TransferDirection.SEND) myName else item.peerDeviceName
                    },
                    receiverDevice = item.receiverDevice.ifBlank {
                        if (item.direction == TransferDirection.SEND) item.peerDeviceName else myName
                    },
                    direction = item.direction.name,
                    status = item.status.name,
                    startTime = item.startTime,
                    completionTime = item.completionTime,
                    durationMs = item.durationMs,
                    speed = item.speed,
                    checksum = item.checksum,
                    filePath = item.filePath,
                    peerDeviceName = item.peerDeviceName,
                    peerAddress = item.peerAddress,
                    errorMessage = item.errorMessage,
                    timestamp = item.timestamp,
                    relativePath = item.relativePath,
                    avgSpeedBytesPerSec = item.avgSpeedBytesPerSec,
                    peakSpeedBytesPerSec = item.peakSpeedBytesPerSec,
                    priority = item.priority.name,
                    queueOrder = item.queueOrder,
                    bytesTransferred = item.bytesTransferred,
                    isPaused = item.isPaused,
                    retryCount = item.retryCount,
                    isDirectory = item.isDirectory,
                    partialChecksum = item.partialChecksum,
                    sourceUri = item.sourceUri
                )
            )
        } catch (_: Exception) {}
    }

    private suspend fun acquireTransferSlot(transferId: String, limitProvider: () -> Int) {
        while (true) {
            val waiter = slotMutex.withLock {
                val limit = limitProvider().coerceIn(1, MAX_CONCURRENT_CAP)
                val paused = transferPauseFlags[transferId] == true
                if (!paused && activeTransferSlots < limit) {
                    // Check if a higher-priority waiter exists
                    val itemsById = _activeTransfers.value.associateBy { it.id }
                    val myItem = itemsById[transferId]
                    val myWeight = myItem?.priority?.weight ?: TransferPriority.NORMAL.weight
                    val myOrder = myItem?.queueOrder ?: Int.MAX_VALUE
                    val higherWaiterExists = slotWaiters.any { w ->
                        w.transferId != transferId &&
                            transferPauseFlags[w.transferId] != true &&
                            compareWaiters(itemsById[w.transferId], myWeight, myOrder) < 0
                    }
                    if (!higherWaiterExists) {
                        activeTransferSlots++
                        return
                    }
                }
                val deferred = CompletableDeferred<Unit>()
                slotWaiters.add(QueuedSlotWaiter(transferId, deferred))
                deferred
            }
            waiter.await()
        }
    }

    private fun compareWaiters(candidate: TransferItem?, refWeight: Int, refOrder: Int): Int {
        val cWeight = candidate?.priority?.weight ?: TransferPriority.NORMAL.weight
        if (cWeight != refWeight) return refWeight.compareTo(cWeight) // Higher weight first -> negative if candidate > ref
        val cOrder = candidate?.queueOrder ?: Int.MAX_VALUE
        return cOrder.compareTo(refOrder)
    }

    private suspend fun releaseTransferSlot() {
        slotMutex.withLock {
            activeTransferSlots = (activeTransferSlots - 1).coerceAtLeast(0)
            wakeSlotWaitersLocked()
        }
    }

    private fun wakeSlotWaitersLocked() {
        val itemsById = _activeTransfers.value.associateBy { it.id }
        val sortedWaiters = slotWaiters.sortedWith(
            compareByDescending<QueuedSlotWaiter> { itemsById[it.transferId]?.priority?.weight ?: TransferPriority.NORMAL.weight }
                .thenBy { itemsById[it.transferId]?.queueOrder ?: Int.MAX_VALUE }
        )
        slotWaiters.clear()
        sortedWaiters.forEach { it.deferred.complete(Unit) }
    }

    private fun wakeSlotWaitersAsync() {
        scope.launch {
            slotMutex.withLock {
                wakeSlotWaitersLocked()
            }
        }
    }

    private fun enqueueIncomingRequest(request: IncomingTransferRequest) {
        synchronized(requestQueueLock) {
            if (incomingRequestQueue.none { it.sessionId == request.sessionId }) {
                incomingRequestQueue.addLast(request)
            }
            _incomingRequest.value = incomingRequestQueue.firstOrNull()
        }
    }

    private fun dequeueIncomingRequest(sessionId: String) {
        synchronized(requestQueueLock) {
            val iter = incomingRequestQueue.iterator()
            while (iter.hasNext()) {
                if (iter.next().sessionId == sessionId) {
                    iter.remove()
                }
            }
            _incomingRequest.value = incomingRequestQueue.firstOrNull()
        }
    }

    suspend fun handleProtocolMessage(
        message: ProtocolMessage,
        socket: Socket,
        input: BufferedInputStream? = null
    ): ProtocolMessage? {
        return when (message.type) {
            ProtocolMessageType.TRANSFER_REQUEST -> handleIncomingTransferRequest(message, socket)
            ProtocolMessageType.TRANSFER_ACCEPTED,
            ProtocolMessageType.TRANSFER_REJECTED -> {
                val sessionId = message.payload["sessionId"] as? String
                if (!sessionId.isNullOrBlank()) {
                    pendingOutgoingApprovals.remove(sessionId)?.complete(message)
                }
                null
            }
            ProtocolMessageType.TRANSFER_PAUSED -> handleIncomingTransferPaused(message)
            ProtocolMessageType.TRANSFER_RESUMED -> handleIncomingTransferResumed(message)
            ProtocolMessageType.TRANSFER_CANCELLED -> handleIncomingTransferCancelled(message)
            ProtocolMessageType.TRANSFER_STARTED -> {
                handleIncomingTransferStarted(message, socket, input)
                null
            }
            else -> null
        }
    }

    private suspend fun handleIncomingTransferStarted(
        message: ProtocolMessage,
        socket: Socket,
        input: BufferedInputStream?
    ) {
        val payload = message.payload
        val sessionId = payload["sessionId"] as? String ?: UUID.randomUUID().toString()
        val fileId = payload["fileId"] as? String ?: UUID.randomUUID().toString()
        val rawName = payload["fileName"] as? String ?: "file_${System.currentTimeMillis()}"
        val fileName = FileMetadata.sanitizeFileName(rawName)
        val rawRel = payload["relativePath"] as? String
        val relativePath = FileMetadata.sanitizeRelativePath(rawRel)
        val fileSize = (payload["fileSize"] as? Number)?.toLong() ?: 0L
        val mimeType = payload["mimeType"] as? String
        val senderName = payload["senderName"] as? String ?: "Remote Device"
        val isDirectory = payload["isDirectory"] as? Boolean == true
        val supportsResume = payload["supportsResume"] as? Boolean == true
        val expectReady = payload["expectReady"] as? Boolean == true
        val expectCompletionAck = payload["expectCompletionAck"] as? Boolean == true
        val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(message.version)
            ?: ProtocolConstants.VERSION_V1

        val metadata = FileMetadata(
            fileId = fileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType ?: "application/octet-stream",
            relativePath = relativePath,
            isDirectory = isDirectory
        )

        if (isDirectory) {
            try {
                if (relativePath != null) {
                    storageManager.createDirectoryFromRelativePath(relativePath)
                }
                val peerDevice = Device(
                    id = message.senderId,
                    alias = senderName,
                    deviceType = DeviceType.UNKNOWN,
                    ipAddress = socket.inetAddress?.hostAddress ?: ""
                )
                recordTransferCompleted(
                    id = fileId,
                    sessionId = sessionId,
                    fileName = fileName,
                    fileSize = 0L,
                    mimeType = mimeType,
                    direction = TransferDirection.RECEIVE,
                    peer = peerDevice,
                    filePath = relativePath,
                    startTime = System.currentTimeMillis(),
                    relativePath = relativePath,
                    isDirectory = true
                )
                if (expectCompletionAck && !socket.isClosed) {
                    val out = BufferedOutputStream(socket.getOutputStream())
                    MessageFraming.writeFrame(
                        out,
                        ProtocolMessage(
                            version = negotiatedVersion,
                            type = ProtocolMessageType.TRANSFER_COMPLETED,
                            senderId = myDeviceIdProvider(),
                            receiverId = message.senderId,
                            payload = mapOf(
                                "sessionId" to sessionId,
                                "fileId" to fileId,
                                "bytesVerified" to 0L,
                                "isDirectory" to true
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating directory marker $relativePath", e)
                if (expectCompletionAck && !socket.isClosed) {
                    try {
                        val out = BufferedOutputStream(socket.getOutputStream())
                        MessageFraming.writeFrame(
                            out,
                            ProtocolMessage(
                                version = negotiatedVersion,
                                type = ProtocolMessageType.TRANSFER_FAILED,
                                senderId = myDeviceIdProvider(),
                                receiverId = message.senderId,
                                payload = mapOf(
                                    "sessionId" to sessionId,
                                    "fileId" to fileId,
                                    "code" to ProtocolConstants.ErrorCode.ERR_PATH_UNSAFE,
                                    "reason" to (e.message ?: "Failed to create directory")
                                )
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
            return
        }

        receiveIncomingFileStream(
            socket = socket,
            fileMetadata = metadata,
            senderDeviceName = senderName,
            sessionId = sessionId,
            existingInputStream = input,
            senderId = message.senderId,
            negotiateResumeReady = supportsResume && expectReady,
            expectCompletionAck = expectCompletionAck,
            negotiatedVersion = negotiatedVersion
        )
    }

    /**
     * Estimates transfer time in seconds using measured historical average throughput.
     * Returns null when no real baseline speed has been observed yet.
     */
    fun estimateTransferSeconds(totalBytes: Long): Long? {
        if (totalBytes <= 0L) return null
        val avg = _stats.value.averageSpeedBytesPerSec
        if (avg <= 0L) return null
        return (totalBytes / avg).coerceAtLeast(1L)
    }

    fun sendFiles(files: List<SelectedFile>, targets: List<Device>) {
        if (files.isEmpty() || targets.isEmpty()) return

        scope.launch {
            for (target in targets) {
                launch {
                    sendFilesToTarget(files, target)
                }
            }
        }
    }

    private suspend fun sendFilesToTarget(files: List<SelectedFile>, target: Device) {
        val accessibleFiles = files.filter { it.isAccessible || it.isDirectory }
        if (accessibleFiles.isEmpty()) return

        val sessionId = UUID.randomUUID().toString()
        val totalBytes = accessibleFiles.sumOf { it.size }
        val myId = myDeviceIdProvider()
        val settings = settingsRepository.settings.value
        val myIp = NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"

        val metadataList = accessibleFiles.mapIndexed { index, file ->
            val fileId = UUID.randomUUID().toString()
            if (!file.isDirectory) {
                retryableSendMap[fileId] = file to target
            }
            FileMetadata(
                fileId = fileId,
                fileName = if (file.isDirectory || settings.preserveOriginalNames) {
                    file.name
                } else {
                    "file_${index}_${System.currentTimeMillis()}.${file.extension}"
                },
                fileSize = if (file.isDirectory) 0L else file.size,
                mimeType = file.mimeType,
                relativePath = FileMetadata.sanitizeRelativePath(file.relativePath),
                isDirectory = file.isDirectory
            )
        }

        val now = System.currentTimeMillis()
        val initialItems = accessibleFiles.mapIndexed { index, file ->
            val meta = metadataList[index]
            TransferItem(
                id = meta.fileId,
                fileName = meta.fileName,
                fileSize = meta.fileSize,
                direction = TransferDirection.SEND,
                status = TransferStatus.PENDING,
                progress = 0f,
                speed = "Waiting for approval…",
                peerDeviceName = target.alias,
                peerAddress = "${target.ipAddress}:${target.port}",
                timestamp = now,
                mimeType = meta.mimeType,
                senderDevice = settings.deviceName,
                receiverDevice = target.alias,
                startTime = now,
                relativePath = meta.relativePath,
                priority = TransferPriority.NORMAL,
                queueOrder = queueSequence.getAndIncrement(),
                isDirectory = file.isDirectory,
                sourceUri = file.uri.toString()
            )
        }

        _activeTransfers.update { initialItems + it }
        initialItems.forEach { persistActiveStateEntity(it, sessionId) }
        recalculateStats()

        val pairedPeer = try { pairedDeviceRepository.getPairedDevice(target.id) } catch (_: Exception) { null }
        val useTls = settings.tlsPreferred && (target.tlsSupported || pairedPeer?.tlsSupported == true)
        val expectedFingerprint = target.certFingerprint?.takeIf { it.isNotBlank() }
            ?: pairedPeer?.certFingerprint?.takeIf { it.isNotBlank() }
        val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(target.protocolVersion)
            ?: ProtocolConstants.VERSION_V1

        val requestMessage = ProtocolMessage(
            version = negotiatedVersion,
            type = ProtocolMessageType.TRANSFER_REQUEST,
            senderId = myId,
            receiverId = target.id,
            payload = mapOf(
                "sessionId" to sessionId,
                "senderName" to settings.deviceName,
                "senderPlatform" to "android",
                "senderType" to "android",
                "senderIp" to myIp,
                "senderPort" to settings.port,
                "totalBytes" to totalBytes,
                "totalSize" to totalBytes,
                "fileCount" to metadataList.size,
                "tlsPreferred" to settings.tlsPreferred,
                "supportsResume" to true,
                "files" to FileMetadata.toJsonArray(metadataList)
            )
        )

        updateTransferItemsStatus(metadataList.map { it.fileId }, TransferStatus.PENDING, "Requesting approval…")

        val approvalTimeoutMs = settings.connectionTimeoutMs.coerceAtLeast(APPROVAL_TIMEOUT_MS.toInt() + 5_000)
        val asyncDeferred = CompletableDeferred<ProtocolMessage>()
        pendingOutgoingApprovals[sessionId] = asyncDeferred

        val result = client.sendMessage(
            host = target.ipAddress,
            port = target.port,
            message = requestMessage,
            timeoutMs = approvalTimeoutMs,
            useTls = useTls,
            expectedCertFingerprint = expectedFingerprint
        )

        val resolvedResult: Result<ProtocolMessage> = if (result.isSuccess) {
            pendingOutgoingApprovals.remove(sessionId)
            result
        } else {
            val asyncMsg = withTimeoutOrNull(5_000L) { asyncDeferred.await() }
            pendingOutgoingApprovals.remove(sessionId)
            if (asyncMsg != null) Result.success(asyncMsg) else result
        }

        resolvedResult.fold(
            onSuccess = { response ->
                when (response.type) {
                    ProtocolMessageType.TRANSFER_ACCEPTED -> {
                        val peerSupportsResume = response.payload["supportsResume"] as? Boolean == true
                        streamFilesToPeer(
                            sessionId = sessionId,
                            metadataList = metadataList,
                            files = accessibleFiles,
                            target = target,
                            peerSupportsResume = peerSupportsResume
                        )
                    }
                    ProtocolMessageType.TRANSFER_REJECTED -> {
                        val reason = response.payload["reason"] as? String ?: "Transfer was declined by peer"
                        failTransferItems(metadataList.map { it.fileId }, reason, target)
                    }
                    else -> {
                        failTransferItems(metadataList.map { it.fileId }, "Unexpected response: ${response.type}", target)
                    }
                }
            },
            onFailure = { error ->
                failTransferItems(metadataList.map { it.fileId }, "Connection error: ${error.localizedMessage ?: "Timeout"}", target)
            }
        )
    }

    private suspend fun streamFilesToPeer(
        sessionId: String,
        metadataList: List<FileMetadata>,
        files: List<SelectedFile>,
        target: Device,
        peerSupportsResume: Boolean
    ) = coroutineScope {
        for (i in files.indices) {
            val file = files[i]
            val meta = metadataList[i]
            val transferId = meta.fileId

            launch {
                streamSingleTransferWithRetries(
                    sessionId = sessionId,
                    fileIndex = i,
                    file = file,
                    meta = meta,
                    transferId = transferId,
                    target = target,
                    peerSupportsResume = peerSupportsResume
                )
            }
        }
    }

    private suspend fun streamSingleTransferWithRetries(
        sessionId: String,
        fileIndex: Int,
        file: SelectedFile,
        meta: FileMetadata,
        transferId: String,
        target: Device,
        peerSupportsResume: Boolean,
        existingTransferIdMode: Boolean = false
    ) {
        val myId = myDeviceIdProvider()
        var attempt = 0

        while (true) {
            if (transferCancellationFlags[transferId] == true) {
                markTransferCancelled(transferId, target, "Cancelled by user")
                return
            }

            acquireTransferSlot(transferId) { settingsRepository.settings.value.maxConcurrentTransfers }
            var shouldRetry = false
            var retryDelayMs = 1500L

            try {
                val settings = settingsRepository.settings.value
                val pairedPeer = try { pairedDeviceRepository.getPairedDevice(target.id) } catch (_: Exception) { null }
                val useTlsForData = settings.tlsPreferred && (target.tlsSupported || pairedPeer?.tlsSupported == true)
                val expectedCertFp = target.certFingerprint?.takeIf { it.isNotBlank() }
                    ?: pairedPeer?.certFingerprint?.takeIf { it.isNotBlank() }
                val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(target.protocolVersion)
                    ?: ProtocolConstants.VERSION_V1
                val requestCompletionAck = negotiatedVersion == ProtocolConstants.VERSION_V2

                val fileStartTime = System.currentTimeMillis()
                activeTransferPeers[transferId] = target.ipAddress to target.port

                if (meta.isDirectory) {
                    var dirSocket: Socket? = null
                    try {
                        val plainDirSocket = Socket().apply {
                            connect(InetSocketAddress(target.ipAddress, target.port), settings.connectionTimeoutMs)
                            soTimeout = settings.connectionTimeoutMs
                        }
                        dirSocket = if (useTlsForData) {
                            TlsTransport.upgradeClientSocketToTls(
                                plainSocket = plainDirSocket,
                                host = target.ipAddress,
                                port = target.port,
                                expectedFingerprintSha256 = expectedCertFp
                            )
                        } else {
                            plainDirSocket
                        }
                        val out = BufferedOutputStream(dirSocket.getOutputStream())
                        val dirInput = BufferedInputStream(dirSocket.getInputStream())
                        val dirPayload = mutableMapOf<String, Any>(
                            "sessionId" to sessionId,
                            "fileId" to meta.fileId,
                            "fileIndex" to fileIndex,
                            "fileName" to meta.fileName,
                            "fileSize" to 0L,
                            "mimeType" to meta.mimeType,
                            "senderName" to settings.deviceName,
                            "isDirectory" to true,
                            "expectCompletionAck" to requestCompletionAck
                        )
                        meta.relativePath?.let { dirPayload["relativePath"] = it }
                        MessageFraming.writeFrame(
                            out,
                            ProtocolMessage(
                                version = negotiatedVersion,
                                type = ProtocolMessageType.TRANSFER_STARTED,
                                senderId = myId,
                                receiverId = target.id,
                                payload = dirPayload
                            )
                        )
                        if (requestCompletionAck) {
                            val ack = try { MessageFraming.readFrame(dirInput) } catch (_: Exception) { null }
                            if (ack?.type == ProtocolMessageType.TRANSFER_FAILED || ack?.type == ProtocolMessageType.ERROR) {
                                val reason = ack.payload["reason"] as? String
                                    ?: ack.payload["message"] as? String
                                    ?: "Remote peer failed creating directory"
                                throw IllegalStateException(reason)
                            }
                        }
                        recordTransferCompleted(
                            id = transferId,
                            sessionId = sessionId,
                            fileName = meta.fileName,
                            fileSize = 0L,
                            mimeType = meta.mimeType,
                            direction = TransferDirection.SEND,
                            peer = target,
                            startTime = fileStartTime,
                            relativePath = meta.relativePath,
                            isDirectory = true
                        )
                    } finally {
                        try {
                            dirSocket?.close()
                        } catch (_: Exception) {}
                        activeTransferPeers.remove(transferId)
                    }
                    return
                }

                updateTransferItem(transferId) {
                    it.copy(status = TransferStatus.ACTIVE, isPaused = false, speed = "Connecting…", etaSeconds = null)
                }

                var socket: Socket? = null
                try {
                    FileTransferService.start(context)
                    val plainSocket = Socket().apply {
                        connect(InetSocketAddress(target.ipAddress, target.port), settings.connectionTimeoutMs)
                        soTimeout = maxOf(settings.connectionTimeoutMs, 120_000)
                    }
                    socket = if (useTlsForData) {
                        TlsTransport.upgradeClientSocketToTls(
                            plainSocket = plainSocket,
                            host = target.ipAddress,
                            port = target.port,
                            expectedFingerprintSha256 = expectedCertFp
                        )
                    } else {
                        plainSocket
                    }
                    performanceMonitor?.onSocketOpened()
                    activeTransferSockets[transferId] = socket
                    val output = BufferedOutputStream(socket.getOutputStream())
                    val streamInput = BufferedInputStream(socket.getInputStream())

                    val payloadMap = mutableMapOf<String, Any>(
                        "sessionId" to sessionId,
                        "fileId" to meta.fileId,
                        "fileIndex" to fileIndex,
                        "fileName" to meta.fileName,
                        "fileSize" to meta.fileSize,
                        "mimeType" to meta.mimeType,
                        "senderName" to settings.deviceName,
                        "expectCompletionAck" to requestCompletionAck
                    )
                    if (peerSupportsResume) {
                        payloadMap["supportsResume"] = true
                        payloadMap["expectReady"] = true
                    }
                    if (!meta.relativePath.isNullOrBlank()) {
                        payloadMap["relativePath"] = meta.relativePath
                    }
                    val startMsg = ProtocolMessage(
                        version = negotiatedVersion,
                        type = ProtocolMessageType.TRANSFER_STARTED,
                        senderId = myId,
                        receiverId = target.id,
                        payload = payloadMap
                    )
                    MessageFraming.writeFrame(output, startMsg)

                    var resumeOffset = 0L
                    var expectedPrefixSha: String? = null
                    if (peerSupportsResume) {
                        val readyFrame = MessageFraming.readFrame(streamInput)
                        if (readyFrame?.type == ProtocolMessageType.TRANSFER_READY) {
                            val offeredOffset = (readyFrame.payload["acceptedOffset"] as? Number)?.toLong() ?: 0L
                            val peerPrefix = readyFrame.payload["prefixSha256"] as? String
                            if (offeredOffset in 1L..file.size && !peerPrefix.isNullOrBlank()) {
                                val senderPrefixCheck = computeUriPrefixSha256(file.uri, offeredOffset)
                                if (senderPrefixCheck != null && senderPrefixCheck.equals(peerPrefix, ignoreCase = true)) {
                                    resumeOffset = offeredOffset
                                    expectedPrefixSha = peerPrefix
                                } else {
                                    Log.w(TAG, "Resume prefix hash mismatch for ${meta.fileName}; rejecting stale offset")
                                }
                            }
                        }
                    }

                    val (checksumHex, peakSpeedBytes) = streamSingleFile(
                        file = file,
                        fileIndex = fileIndex,
                        transferId = transferId,
                        output = output,
                        startOffset = resumeOffset,
                        verifiedPrefixSha256 = expectedPrefixSha
                    )

                    if (requestCompletionAck) {
                        try {
                            socket.soTimeout = 10_000
                        } catch (_: Exception) {}
                        while (true) {
                            val ackFrame = try {
                                MessageFraming.readFrame(streamInput)
                            } catch (_: SocketTimeoutException) {
                                null
                            }
                            if (ackFrame == null) break
                            when (ackFrame.type) {
                                ProtocolMessageType.FILE_CONFLICT -> continue
                                ProtocolMessageType.TRANSFER_COMPLETED -> break
                                ProtocolMessageType.TRANSFER_FAILED,
                                ProtocolMessageType.ERROR -> {
                                    val reason = (ackFrame.payload["reason"] as? String)
                                        ?: (ackFrame.payload["message"] as? String)
                                        ?: "Remote peer reported transfer verification failure"
                                    throw IllegalStateException(reason)
                                }
                                else -> break
                            }
                        }
                    }

                    if (existingTransferIdMode) {
                        performanceMonitor?.recordRecoveryAttempt(true)
                    }

                    recordTransferCompleted(
                        id = transferId,
                        sessionId = sessionId,
                        fileName = meta.fileName,
                        fileSize = meta.fileSize,
                        mimeType = meta.mimeType,
                        direction = TransferDirection.SEND,
                        peer = target,
                        checksum = checksumHex,
                        startTime = fileStartTime,
                        relativePath = meta.relativePath,
                        peakSpeedBytesPerSec = peakSpeedBytes
                    )

                    if (settings.showNotifications && settings.notifyTransferComplete) {
                        notificationHelper.showTransferComplete(
                            notificationId = transferId.hashCode(),
                            fileName = meta.fileName,
                            totalSize = TransferItem.formatBytes(meta.fileSize),
                            isSend = true
                        )
                    }
                    recoverableNetworkFailureIds.remove(transferId)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Stream failed for ${meta.fileName} (attempt=$attempt)", e)
                    if (transferCancellationFlags[transferId] == true) {
                        markTransferCancelled(transferId, target, "Cancelled by user")
                        return
                    }

                    val recoverable = isRecoverableNetworkException(e)
                    if (recoverable && attempt < MAX_AUTO_RETRIES) {
                        attempt++
                        performanceMonitor?.recordRetry()
                        shouldRetry = true
                        retryDelayMs = 1_500L * attempt
                        updateTransferItem(transferId) {
                            it.copy(
                                status = TransferStatus.PENDING,
                                isPaused = false,
                                retryCount = attempt,
                                speed = "Reconnecting ($attempt/$MAX_AUTO_RETRIES)…"
                            )
                        }
                    } else {
                        if (existingTransferIdMode) {
                            performanceMonitor?.recordRecoveryAttempt(false)
                        }
                        if (recoverable) {
                            recoverableNetworkFailureIds.add(transferId)
                        }
                        val appError = AppErrorMapper.fromThrowable(
                            category = ErrorCategory.TRANSFER,
                            throwable = e,
                            targetLabel = target.alias,
                            address = "${target.ipAddress}:${target.port}"
                        )
                        AppErrorReporter.report(appError)
                        val reason = appError.userMessage
                        recordTransferFailed(
                            id = transferId,
                            sessionId = sessionId,
                            fileName = meta.fileName,
                            fileSize = meta.fileSize,
                            mimeType = meta.mimeType,
                            direction = TransferDirection.SEND,
                            peer = target,
                            error = reason,
                            startTime = fileStartTime,
                            relativePath = meta.relativePath,
                            sourceUri = file.uri.toString(),
                            retryCount = attempt
                        )
                        if (settings.showNotifications && settings.notifyTransferComplete) {
                            notificationHelper.showTransferFailed(
                                notificationId = transferId.hashCode(),
                                transferId = transferId,
                                fileName = meta.fileName,
                                reason = reason,
                                canRetry = retryableSendMap.containsKey(transferId)
                            )
                        }
                        return
                    }
                } finally {
                    val removed = activeTransferSockets.remove(transferId)
                    if (removed != null) {
                        performanceMonitor?.onSocketClosed()
                    }
                    activeTransferPeers.remove(transferId)
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                }
            } finally {
                releaseTransferSlot()
            }

            if (shouldRetry) {
                delay(retryDelayMs)
            }
        }
    }

    private fun isRecoverableNetworkException(e: Throwable): Boolean {
        return e is SocketTimeoutException ||
            e is ConnectException ||
            e is IOException ||
            e.message?.contains("closed", ignoreCase = true) == true ||
            e.message?.contains("reset", ignoreCase = true) == true ||
            e.message?.contains("timed out", ignoreCase = true) == true
    }

    private fun computeUriPrefixSha256(uri: Uri, byteCount: Long): String? {
        if (byteCount <= 0L) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var remaining = byteCount
            context.contentResolver.openInputStream(uri)?.use { input ->
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                    remaining -= read
                }
            } ?: return null
            if (remaining != 0L) return null
            FileStreamFraming.bytesToHex(digest.digest())
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun streamSingleFile(
        file: SelectedFile,
        fileIndex: Int,
        transferId: String,
        output: BufferedOutputStream,
        startOffset: Long = 0L,
        verifiedPrefixSha256: String? = null
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        val inputStream: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Could not open input stream for ${file.name}")

        val chunkSizeBytes = (settingsRepository.settings.value.transferBufferSizeKb * 1024)
            .coerceIn(32 * 1024, 256 * 1024)
        val buffer = ByteArray(chunkSizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L
        val fileSize = file.size.coerceAtLeast(1L)

        var lastSpeedCalcTime = System.currentTimeMillis()
        var lastBytesRead = startOffset
        var peakSpeedBytesPerSec = 0L

        try {
            // If resuming from verified offset, hash the skipped prefix bytes into full-file digest
            if (startOffset > 0L && !verifiedPrefixSha256.isNullOrBlank()) {
                var remainingToSkip = startOffset
                while (remainingToSkip > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remainingToSkip).toInt()
                    val n = inputStream.read(buffer, 0, toRead)
                    if (n <= 0) throw IllegalStateException("Unexpected EOF while fast-forwarding resume prefix")
                    digest.update(buffer, 0, n)
                    totalRead += n
                    remainingToSkip -= n
                }
                val resumedProgress = (totalRead.toDouble() / fileSize.toDouble()).toFloat().coerceIn(0f, 1f)
                updateTransferItem(transferId) {
                    it.copy(
                        progress = resumedProgress,
                        bytesTransferred = totalRead,
                        speed = "Resuming from ${TransferItem.formatBytes(totalRead)}…"
                    )
                }
            }

            while (isActive) {
                while (transferPauseFlags[transferId] == true && isActive) {
                    updateTransferItem(transferId) {
                        it.copy(status = TransferStatus.PENDING, isPaused = true, speed = "Paused", etaSeconds = null)
                    }
                    delay(400)
                }

                if (transferCancellationFlags[transferId] == true) {
                    throw IllegalStateException("Transfer cancelled by user")
                }

                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                digest.update(buffer, 0, bytesRead)
                FileStreamFraming.writeChunk(output, fileIndex, totalRead, buffer, bytesRead)

                totalRead += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastSpeedCalcTime
                if (elapsed >= 500L) {
                    val bytesSinceLast = totalRead - lastBytesRead
                    val speedBytesPerSec = if (elapsed > 0L) (bytesSinceLast * 1000L) / elapsed else 0L
                    if (speedBytesPerSec > peakSpeedBytesPerSec) {
                        peakSpeedBytesPerSec = speedBytesPerSec
                    }
                    performanceMonitor?.recordMeasuredSendSpeed(speedBytesPerSec)
                    val currentSpeedStr = "${TransferItem.formatBytes(speedBytesPerSec)}/s"
                    val etaSec = if (speedBytesPerSec > 0L && fileSize > totalRead) {
                        ((fileSize - totalRead) / speedBytesPerSec).coerceAtLeast(1L)
                    } else null

                    lastSpeedCalcTime = now
                    lastBytesRead = totalRead

                    val progress = (totalRead.toDouble() / fileSize.toDouble()).toFloat().coerceIn(0f, 1f)
                    updateTransferItem(transferId) {
                        it.copy(
                            progress = progress,
                            bytesTransferred = totalRead,
                            speed = currentSpeedStr,
                            status = TransferStatus.ACTIVE,
                            isPaused = false,
                            etaSeconds = etaSec
                        )
                    }

                    val liveSettings = settingsRepository.settings.value
                    if (liveSettings.showNotifications && liveSettings.notifyTransferProgress) {
                        val activeCount = _activeTransfers.value.count {
                            it.status == TransferStatus.ACTIVE || (it.status == TransferStatus.PENDING && !it.isPaused)
                        }
                        notificationHelper.showTransferProgress(
                            notificationId = transferId.hashCode(),
                            transferId = transferId,
                            fileName = file.name,
                            progressPercent = (progress * 100).toInt(),
                            speedStr = currentSpeedStr,
                            activeCount = activeCount
                        )
                    }
                }
            }

            val sha256Bytes = digest.digest()
            FileStreamFraming.writeEof(output, fileIndex, sha256Bytes)
            FileStreamFraming.bytesToHex(sha256Bytes) to peakSpeedBytesPerSec
        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleIncomingTransferRequest(message: ProtocolMessage, socket: Socket): ProtocolMessage? {
        val payload = message.payload
        val negotiatedVersion = ProtocolConstants.negotiateProtocolVersion(message.version)
            ?: ProtocolConstants.VERSION_V1
        val sessionId = payload["sessionId"] as? String ?: UUID.randomUUID().toString()
        val senderName = payload["senderName"] as? String ?: "Remote Device"
        val senderPlatform = (payload["senderPlatform"] as? String)
            ?: (payload["senderType"] as? String)
            ?: "unknown"
        val senderIp = (payload["senderIp"] as? String)
            ?: (payload["ipAddress"] as? String)
            ?: socket.inetAddress?.hostAddress
            ?: "127.0.0.1"
        val senderPort = (payload["senderPort"] as? Number)?.toInt()
            ?: (payload["port"] as? Number)?.toInt()
            ?: ProtocolConstants.DEFAULT_PORT
        val totalBytes = (payload["totalBytes"] as? Number)?.toLong()
            ?: (payload["totalSize"] as? Number)?.toLong()
            ?: 0L

        val filesArray = payload["files"] as? JSONArray
        val files = FileMetadata.fromJsonList(filesArray)
        val settings = settingsRepository.settings.value

        if (!SecurityValidator.isFileSizeAllowed(totalBytes, settings.maxAcceptedFileSizeMb)) {
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.TRANSFER_REJECTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "code" to ProtocolConstants.ErrorCode.ERR_FILE_TOO_LARGE,
                    "reason" to "Transfer exceeds maximum accepted file size limit (${settings.maxAcceptedFileSizeMb} MB)"
                )
            )
        }

        if (!storageManager.hasAvailableStorage(totalBytes)) {
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.TRANSFER_REJECTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "code" to ProtocolConstants.ErrorCode.ERR_INSUFFICIENT_SPACE,
                    "reason" to "Insufficient storage space on receiving device"
                )
            )
        }

        val pairedPeer = pairedDeviceRepository.getPairedDevice(message.senderId)
        val isPaired = pairedPeer != null

        val request = IncomingTransferRequest(
            sessionId = sessionId,
            senderId = message.senderId,
            senderName = senderName,
            senderPlatform = senderPlatform,
            senderIp = senderIp,
            senderPort = senderPort,
            files = files,
            totalBytes = totalBytes,
            isTrustedPeer = isPaired
        )

        val allowAutoAccept = (settings.autoAcceptTrusted || (pairedPeer?.autoAccept == true)) &&
            isPaired && !request.hasSuspiciousFiles
        if (allowAutoAccept) {
            prepareIncomingReceiving(request)
            return ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.TRANSFER_ACCEPTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "accepted" to true,
                    "supportsResume" to true,
                    "tlsSupported" to settings.tlsPreferred
                )
            )
        }

        sessionPeerEndpoints[sessionId] = senderIp to senderPort
        request.files.forEach { fileMeta ->
            activeTransferPeers[fileMeta.fileId] = senderIp to senderPort
        }

        val approvalDeferred = CompletableDeferred<ProtocolMessage>()
        pendingApprovalResponses[sessionId] = approvalDeferred
        enqueueIncomingRequest(request)
        if (settings.showNotifications && settings.notifyIncomingRequests) {
            notificationHelper.showIncomingTransferRequest(request)
        }

        return try {
            val decision = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                approvalDeferred.await()
            }
            decision ?: ProtocolMessage(
                version = negotiatedVersion,
                type = ProtocolMessageType.TRANSFER_REJECTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "code" to ProtocolConstants.ErrorCode.ERR_TRANSFER_REJECTED,
                    "reason" to "Approval request timed out"
                )
            )
        } finally {
            pendingApprovalResponses.remove(sessionId)
            dequeueIncomingRequest(sessionId)
        }
    }

    fun acceptIncomingTransfer(request: IncomingTransferRequest) {
        dequeueIncomingRequest(request.sessionId)
        notificationHelper.cancelIncomingRequestNotification()
        prepareIncomingReceiving(request)

        val settings = settingsRepository.settings.value
        val acceptMsg = ProtocolMessage(
            version = settings.protocolVersion,
            type = ProtocolMessageType.TRANSFER_ACCEPTED,
            senderId = myDeviceIdProvider(),
            receiverId = request.senderId,
            payload = mapOf(
                "sessionId" to request.sessionId,
                "accepted" to true,
                "supportsResume" to true,
                "tlsSupported" to settings.tlsPreferred
            )
        )

        val deferred = pendingApprovalResponses.remove(request.sessionId)
        if (deferred != null) {
            deferred.complete(acceptMsg)
        } else {
            scope.launch {
                val peer = try { pairedDeviceRepository.getPairedDevice(request.senderId) } catch (_: Exception) { null }
                client.sendOneWay(
                    host = request.senderIp,
                    port = request.senderPort,
                    message = acceptMsg,
                    useTls = settings.tlsPreferred && (peer?.tlsSupported == true),
                    expectedCertFingerprint = peer?.certFingerprint
                )
            }
        }
    }

    fun rejectIncomingTransfer(request: IncomingTransferRequest, reason: String = "Declined by user") {
        dequeueIncomingRequest(request.sessionId)
        notificationHelper.cancelIncomingRequestNotification()
        request.files.forEach { activeTransferPeers.remove(it.fileId) }
        sessionPeerEndpoints.remove(request.sessionId)

        val settings = settingsRepository.settings.value
        val rejectMsg = ProtocolMessage(
            version = settings.protocolVersion,
            type = ProtocolMessageType.TRANSFER_REJECTED,
            senderId = myDeviceIdProvider(),
            receiverId = request.senderId,
            payload = mapOf(
                "sessionId" to request.sessionId,
                "code" to ProtocolConstants.ErrorCode.ERR_TRANSFER_REJECTED,
                "reason" to reason
            )
        )

        val deferred = pendingApprovalResponses.remove(request.sessionId)
        if (deferred != null) {
            deferred.complete(rejectMsg)
        } else {
            scope.launch {
                val peer = try { pairedDeviceRepository.getPairedDevice(request.senderId) } catch (_: Exception) { null }
                client.sendOneWay(
                    host = request.senderIp,
                    port = request.senderPort,
                    message = rejectMsg,
                    useTls = settings.tlsPreferred && (peer?.tlsSupported == true),
                    expectedCertFingerprint = peer?.certFingerprint
                )
            }
        }
    }

    private fun prepareIncomingReceiving(request: IncomingTransferRequest) {
        val myDeviceName = settingsRepository.settings.value.deviceName
        val now = System.currentTimeMillis()
        val items = request.files.map { meta ->
            TransferItem(
                id = meta.fileId,
                fileName = meta.fileName,
                fileSize = meta.fileSize,
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.ACTIVE,
                progress = 0f,
                speed = "Receiving…",
                peerDeviceName = request.senderName,
                peerAddress = "${request.senderIp}:${request.senderPort}",
                timestamp = now,
                mimeType = meta.mimeType,
                senderDevice = request.senderName,
                receiverDevice = myDeviceName,
                startTime = now,
                relativePath = meta.relativePath,
                queueOrder = queueSequence.getAndIncrement(),
                isDirectory = meta.isDirectory
            )
        }

        _activeTransfers.update { existing ->
            val incomingIds = items.map { it.id }.toSet()
            items + existing.filter { it.id !in incomingIds }
        }
        items.forEach { item ->
            scope.launch { persistActiveStateEntity(item, request.sessionId) }
        }
        recalculateStats()
    }

    private fun snapshotDigestHex(digest: MessageDigest, tempFile: File, receivedBytes: Long): String? {
        return try {
            val cloned = digest.clone() as MessageDigest
            FileStreamFraming.bytesToHex(cloned.digest())
        } catch (_: CloneNotSupportedException) {
            storageManager.computeFilePrefixSha256(tempFile, receivedBytes)
        } catch (_: Exception) {
            storageManager.computeFilePrefixSha256(tempFile, receivedBytes)
        }
    }

    suspend fun receiveIncomingFileStream(
        socket: Socket,
        fileMetadata: FileMetadata,
        senderDeviceName: String,
        sessionId: String,
        existingInputStream: InputStream? = null,
        senderId: String = "",
        negotiateResumeReady: Boolean = false,
        expectCompletionAck: Boolean = false,
        negotiatedVersion: String = ProtocolConstants.PREFERRED_VERSION
    ) = withContext(Dispatchers.IO) {
        val receiveStartTime = System.currentTimeMillis()
        val settings = settingsRepository.settings.value
        val peerDevice = Device(
            id = senderId.ifBlank { sessionId },
            alias = senderDeviceName,
            deviceType = DeviceType.UNKNOWN,
            ipAddress = socket.inetAddress?.hostAddress ?: ""
        )

        if (!SecurityValidator.isFileSizeAllowed(fileMetadata.fileSize, settings.maxAcceptedFileSizeMb)) {
            val reason = "File exceeds max size limit (${settings.maxAcceptedFileSizeMb} MB)"
            if (expectCompletionAck && !socket.isClosed) {
                try {
                    val out = BufferedOutputStream(socket.getOutputStream())
                    MessageFraming.writeFrame(
                        out,
                        ProtocolMessage(
                            version = negotiatedVersion,
                            type = ProtocolMessageType.TRANSFER_FAILED,
                            senderId = myDeviceIdProvider(),
                            receiverId = peerDevice.id,
                            payload = mapOf(
                                "sessionId" to sessionId,
                                "fileId" to fileMetadata.fileId,
                                "code" to ProtocolConstants.ErrorCode.ERR_FILE_TOO_LARGE,
                                "reason" to reason
                            )
                        )
                    )
                } catch (_: Exception) {}
            }
            recordTransferFailed(
                id = fileMetadata.fileId,
                sessionId = sessionId,
                fileName = fileMetadata.fileName,
                fileSize = fileMetadata.fileSize,
                mimeType = fileMetadata.mimeType,
                direction = TransferDirection.RECEIVE,
                peer = peerDevice,
                error = reason,
                startTime = receiveStartTime
            )
            return@withContext
        }

        FileTransferService.start(context)
        activeTransferSockets[fileMetadata.fileId] = socket
        sessionPeerEndpoints[sessionId]?.let { activeTransferPeers.putIfAbsent(fileMetadata.fileId, it) }

        val tempFile = storageManager.createTempFile(fileMetadata.fileId)
        var acceptedResumeOffset = 0L
        var verifiedPrefixSha: String? = null

        if (negotiateResumeReady) {
            val savedMeta = storageManager.readResumeMetadata(fileMetadata.fileId)
            if (savedMeta != null &&
                tempFile.exists() &&
                savedMeta.senderId == peerDevice.id &&
                savedMeta.fileName == fileMetadata.fileName &&
                savedMeta.fileSize == fileMetadata.fileSize &&
                savedMeta.bytesWritten in 1L..fileMetadata.fileSize &&
                tempFile.length() >= savedMeta.bytesWritten
            ) {
                val actualPrefixSha = storageManager.computeFilePrefixSha256(tempFile, savedMeta.bytesWritten)
                if (actualPrefixSha != null && actualPrefixSha.equals(savedMeta.prefixSha256, ignoreCase = true)) {
                    if (tempFile.length() > savedMeta.bytesWritten) {
                        java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                            raf.setLength(savedMeta.bytesWritten)
                        }
                    }
                    acceptedResumeOffset = savedMeta.bytesWritten
                    verifiedPrefixSha = actualPrefixSha
                } else {
                    storageManager.deleteTempAndMetadata(fileMetadata.fileId)
                }
            } else {
                storageManager.deleteTempAndMetadata(fileMetadata.fileId)
            }

            val readyPayload = mutableMapOf<String, Any>(
                "fileId" to fileMetadata.fileId,
                "acceptedOffset" to acceptedResumeOffset
            )
            verifiedPrefixSha?.let { readyPayload["prefixSha256"] = it }
            val readyOut = BufferedOutputStream(socket.getOutputStream())
            MessageFraming.writeFrame(
                readyOut,
                ProtocolMessage(
                    version = negotiatedVersion,
                    type = ProtocolMessageType.TRANSFER_READY,
                    senderId = myDeviceIdProvider(),
                    receiverId = peerDevice.id,
                    payload = readyPayload
                )
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        if (acceptedResumeOffset > 0L) {
            val seedBuf = ByteArray(64 * 1024)
            var rem = acceptedResumeOffset
            FileInputStream(tempFile).use { fis ->
                while (rem > 0L) {
                    val toRead = minOf(seedBuf.size.toLong(), rem).toInt()
                    val r = fis.read(seedBuf, 0, toRead)
                    if (r <= 0) break
                    digest.update(seedBuf, 0, r)
                    rem -= r
                }
            }
        }

        val fileOutputStream = FileOutputStream(tempFile, acceptedResumeOffset > 0L)
        val buffer = ByteArray(FileStreamFraming.DEFAULT_CHUNK_SIZE * 8)

        var receivedBytes = acceptedResumeOffset
        val fileSize = fileMetadata.fileSize.coerceAtLeast(1L)
        var lastCalcTime = System.currentTimeMillis()
        var lastBytes = acceptedResumeOffset
        var lastMetaPersistBytes = acceptedResumeOffset
        var peakSpeedBytesPerSec = 0L

        try {
            try {
                socket.soTimeout = 120_000
            } catch (_: Exception) {}
            val input = if (existingInputStream is BufferedInputStream) {
                existingInputStream
            } else {
                BufferedInputStream(existingInputStream ?: socket.getInputStream())
            }

            while (isActive && !socket.isClosed) {
                if (transferCancellationFlags[fileMetadata.fileId] == true) {
                    throw IllegalStateException("Transfer cancelled")
                }

                val frameResult = FileStreamFraming.readNextFrame(input, buffer)
                when (frameResult) {
                    is FileStreamFraming.FrameReadResult.Chunk -> {
                        if (!SecurityValidator.isFileSizeAllowed(receivedBytes + frameResult.length, settings.maxAcceptedFileSizeMb)) {
                            throw IllegalStateException("Incoming stream exceeded maximum accepted file size")
                        }
                        fileOutputStream.write(frameResult.data, 0, frameResult.length)
                        digest.update(frameResult.data, 0, frameResult.length)
                        receivedBytes += frameResult.length

                        // O(1) incremental digest checkpointing every 256 KB
                        if (negotiateResumeReady && (receivedBytes - lastMetaPersistBytes >= 256L * 1024L)) {
                            fileOutputStream.flush()
                            val currentPrefixSha = snapshotDigestHex(digest, tempFile, receivedBytes)
                            if (currentPrefixSha != null) {
                                storageManager.writeResumeMetadata(
                                    ResumeMetadata(
                                        fileId = fileMetadata.fileId,
                                        senderId = peerDevice.id,
                                        fileName = fileMetadata.fileName,
                                        fileSize = fileMetadata.fileSize,
                                        bytesWritten = receivedBytes,
                                        prefixSha256 = currentPrefixSha,
                                        relativePath = fileMetadata.relativePath
                                    )
                                )
                            }
                            lastMetaPersistBytes = receivedBytes
                        }

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastCalcTime
                        if (elapsed >= 500L) {
                            val speed = if (elapsed > 0L) ((receivedBytes - lastBytes) * 1000L) / elapsed else 0L
                            if (speed > peakSpeedBytesPerSec) {
                                peakSpeedBytesPerSec = speed
                            }
                            performanceMonitor?.recordMeasuredReceiveSpeed(speed)
                            val speedStr = "${TransferItem.formatBytes(speed)}/s"
                            val etaSec = if (speed > 0L && fileSize > receivedBytes) {
                                ((fileSize - receivedBytes) / speed).coerceAtLeast(1L)
                            } else null
                            lastCalcTime = now
                            lastBytes = receivedBytes

                            val progress = (receivedBytes.toDouble() / fileSize.toDouble()).toFloat().coerceIn(0f, 1f)
                            updateTransferItem(fileMetadata.fileId) {
                                it.copy(
                                    progress = progress,
                                    bytesTransferred = receivedBytes,
                                    speed = speedStr,
                                    etaSeconds = etaSec
                                )
                            }

                            if (settings.showNotifications && settings.notifyTransferProgress) {
                                val activeCount = _activeTransfers.value.count {
                                    it.status == TransferStatus.ACTIVE || (it.status == TransferStatus.PENDING && !it.isPaused)
                                }
                                notificationHelper.showTransferProgress(
                                    notificationId = fileMetadata.fileId.hashCode(),
                                    transferId = fileMetadata.fileId,
                                    fileName = fileMetadata.fileName,
                                    progressPercent = (progress * 100).toInt(),
                                    speedStr = speedStr,
                                    activeCount = activeCount
                                )
                            }
                        }
                    }

                    is FileStreamFraming.FrameReadResult.Eof -> {
                        fileOutputStream.flush()
                        fileOutputStream.close()

                        val calculatedHashHex = FileStreamFraming.bytesToHex(digest.digest())
                        val expectedHashHex = frameResult.sha256Hex

                        if (expectedHashHex.isNotBlank() && !calculatedHashHex.equals(expectedHashHex, ignoreCase = true)) {
                            storageManager.deleteTempAndMetadata(fileMetadata.fileId)
                            throw IllegalStateException("SHA-256 checksum mismatch! File corrupted during transfer.")
                        }

                        val effectivePolicy = resolveDuplicatePolicyForIncoming(
                            fileMetadata = fileMetadata,
                            sessionId = sessionId,
                            settings = settingsRepository.settings.value
                        )

                        val resolution = storageManager.resolveDestinationWithPolicy(
                            fileName = fileMetadata.fileName,
                            duplicateHandling = effectivePolicy,
                            relativePath = fileMetadata.relativePath,
                            organizeByType = settings.organizeReceivedByType,
                            mimeType = fileMetadata.mimeType
                        )

                        when (resolution) {
                            is StorageManager.DestinationResolution.Skipped -> {
                                storageManager.deleteTempAndMetadata(fileMetadata.fileId)
                                if (expectCompletionAck && !socket.isClosed) {
                                    try {
                                        val ackOut = BufferedOutputStream(socket.getOutputStream())
                                        MessageFraming.writeFrame(
                                            ackOut,
                                            ProtocolMessage(
                                                version = negotiatedVersion,
                                                type = ProtocolMessageType.FILE_CONFLICT,
                                                senderId = myDeviceIdProvider(),
                                                receiverId = peerDevice.id,
                                                payload = mapOf(
                                                    "sessionId" to sessionId,
                                                    "fileId" to fileMetadata.fileId,
                                                    "requestedFileName" to fileMetadata.fileName,
                                                    "policyApplied" to "skip"
                                                )
                                            )
                                        )
                                        MessageFraming.writeFrame(
                                            ackOut,
                                            ProtocolMessage(
                                                version = negotiatedVersion,
                                                type = ProtocolMessageType.TRANSFER_COMPLETED,
                                                senderId = myDeviceIdProvider(),
                                                receiverId = peerDevice.id,
                                                payload = mapOf(
                                                    "sessionId" to sessionId,
                                                    "fileId" to fileMetadata.fileId,
                                                    "sha256" to calculatedHashHex,
                                                    "bytesVerified" to receivedBytes,
                                                    "skipped" to true
                                                )
                                            )
                                        )
                                    } catch (_: Exception) {}
                                }
                                markTransferCancelled(
                                    transferId = fileMetadata.fileId,
                                    peer = peerDevice,
                                    reason = "Skipped (duplicate file already exists)",
                                    fileName = fileMetadata.fileName,
                                    fileSize = fileMetadata.fileSize,
                                    direction = TransferDirection.RECEIVE
                                )
                                return@withContext
                            }
                            is StorageManager.DestinationResolution.Ready -> {
                                val destFile = resolution.file
                                val finalPath = storageManager.finalizeReceivedFile(
                                    tempFile = tempFile,
                                    destinationFile = destFile,
                                    mimeType = fileMetadata.mimeType
                                ) ?: throw IllegalStateException("Could not save to ${destFile.name}")

                                storageManager.getTempMetaFile(fileMetadata.fileId).delete()

                                if (expectCompletionAck && !socket.isClosed) {
                                    try {
                                        val ackOut = BufferedOutputStream(socket.getOutputStream())
                                        if (destFile.name != fileMetadata.fileName) {
                                            MessageFraming.writeFrame(
                                                ackOut,
                                                ProtocolMessage(
                                                    version = negotiatedVersion,
                                                    type = ProtocolMessageType.FILE_CONFLICT,
                                                    senderId = myDeviceIdProvider(),
                                                    receiverId = peerDevice.id,
                                                    payload = mapOf(
                                                        "sessionId" to sessionId,
                                                        "fileId" to fileMetadata.fileId,
                                                        "requestedFileName" to fileMetadata.fileName,
                                                        "resolvedFileName" to destFile.name,
                                                        "policyApplied" to effectivePolicy
                                                    )
                                                )
                                            )
                                        }
                                        MessageFraming.writeFrame(
                                            ackOut,
                                            ProtocolMessage(
                                                version = negotiatedVersion,
                                                type = ProtocolMessageType.TRANSFER_COMPLETED,
                                                senderId = myDeviceIdProvider(),
                                                receiverId = peerDevice.id,
                                                payload = mapOf(
                                                    "sessionId" to sessionId,
                                                    "fileId" to fileMetadata.fileId,
                                                    "sha256" to calculatedHashHex,
                                                    "bytesVerified" to receivedBytes,
                                                    "finalFileName" to destFile.name,
                                                    "skipped" to false
                                                )
                                            )
                                        )
                                    } catch (_: Exception) {}
                                }

                                recordTransferCompleted(
                                    id = fileMetadata.fileId,
                                    sessionId = sessionId,
                                    fileName = destFile.name,
                                    fileSize = receivedBytes.coerceAtLeast(fileMetadata.fileSize),
                                    mimeType = fileMetadata.mimeType,
                                    direction = TransferDirection.RECEIVE,
                                    peer = peerDevice,
                                    filePath = finalPath,
                                    checksum = calculatedHashHex,
                                    startTime = receiveStartTime,
                                    relativePath = fileMetadata.relativePath,
                                    peakSpeedBytesPerSec = peakSpeedBytesPerSec
                                )

                                if (settings.showNotifications && settings.notifyTransferComplete) {
                                    notificationHelper.showTransferComplete(
                                        notificationId = fileMetadata.fileId.hashCode(),
                                        fileName = destFile.name,
                                        totalSize = TransferItem.formatBytes(fileMetadata.fileSize),
                                        isSend = false
                                    )
                                }

                                if (settings.autoOpenCompleted) {
                                    storageManager.openFilePath(finalPath)
                                }
                                return@withContext
                            }
                        }
                    }

                    is FileStreamFraming.FrameReadResult.ConnectionClosed -> {
                        throw IllegalStateException("Connection closed before EOF frame")
                    }
                }
            }
        } catch (e: Exception) {
            val isChecksumOrSecurity = e is SecurityException ||
                e.message?.contains("checksum", ignoreCase = true) == true ||
                e.message?.contains("exceeded maximum", ignoreCase = true) == true
            val isCancelled = transferCancellationFlags[fileMetadata.fileId] == true

            if (isCancelled || isChecksumOrSecurity || !negotiateResumeReady) {
                storageManager.deleteTempAndMetadata(fileMetadata.fileId)
            } else {
                try {
                    fileOutputStream.flush()
                    fileOutputStream.close()
                } catch (_: Exception) {}
                val partialSha = if (receivedBytes > 0L) {
                    snapshotDigestHex(digest, tempFile, receivedBytes)
                } else null
                if (partialSha != null) {
                    storageManager.writeResumeMetadata(
                        ResumeMetadata(
                            fileId = fileMetadata.fileId,
                            senderId = peerDevice.id,
                            fileName = fileMetadata.fileName,
                            fileSize = fileMetadata.fileSize,
                            bytesWritten = receivedBytes,
                            prefixSha256 = partialSha,
                            relativePath = fileMetadata.relativePath
                        )
                    )
                }
            }

            if (expectCompletionAck && !socket.isClosed) {
                try {
                    val failCode = when {
                        e is SecurityException -> ProtocolConstants.ErrorCode.ERR_PATH_UNSAFE
                        e.message?.contains("checksum", ignoreCase = true) == true -> ProtocolConstants.ErrorCode.ERR_CHECKSUM_MISMATCH
                        else -> ProtocolConstants.ErrorCode.ERR_TRANSFER_FAILED
                    }
                    val out = BufferedOutputStream(socket.getOutputStream())
                    MessageFraming.writeFrame(
                        out,
                        ProtocolMessage(
                            version = negotiatedVersion,
                            type = ProtocolMessageType.TRANSFER_FAILED,
                            senderId = myDeviceIdProvider(),
                            receiverId = peerDevice.id,
                            payload = mapOf(
                                "sessionId" to sessionId,
                                "fileId" to fileMetadata.fileId,
                                "code" to failCode,
                                "reason" to (e.message ?: "Receive failed")
                            )
                        )
                    )
                } catch (_: Exception) {}
            }

            if (isCancelled) {
                markTransferCancelled(
                    transferId = fileMetadata.fileId,
                    peer = peerDevice,
                    reason = "Cancelled",
                    fileName = fileMetadata.fileName,
                    fileSize = fileMetadata.fileSize,
                    direction = TransferDirection.RECEIVE
                )
            } else {
                val category = when {
                    e is SecurityException || e.message?.contains("save", ignoreCase = true) == true || e.message?.contains("space", ignoreCase = true) == true -> ErrorCategory.STORAGE
                    e.message?.contains("checksum", ignoreCase = true) == true -> ErrorCategory.PROTOCOL
                    else -> ErrorCategory.TRANSFER
                }
                val appError = AppErrorMapper.fromThrowable(
                    category = category,
                    throwable = e,
                    targetLabel = peerDevice.alias,
                    address = peerDevice.ipAddress
                )
                AppErrorReporter.report(appError)
                val reason = appError.userMessage
                recordTransferFailed(
                    id = fileMetadata.fileId,
                    sessionId = sessionId,
                    fileName = fileMetadata.fileName,
                    fileSize = fileMetadata.fileSize,
                    mimeType = fileMetadata.mimeType,
                    direction = TransferDirection.RECEIVE,
                    peer = peerDevice,
                    error = reason,
                    startTime = receiveStartTime,
                    relativePath = fileMetadata.relativePath,
                    bytesTransferred = if (negotiateResumeReady && !isChecksumOrSecurity) receivedBytes else 0L
                )
                if (settings.showNotifications && settings.notifyTransferComplete) {
                    notificationHelper.showTransferFailed(
                        notificationId = fileMetadata.fileId.hashCode(),
                        transferId = fileMetadata.fileId,
                        fileName = fileMetadata.fileName,
                        reason = reason,
                        canRetry = false
                    )
                }
            }
        } finally {
            activeTransferSockets.remove(fileMetadata.fileId)
            activeTransferPeers.remove(fileMetadata.fileId)
            try {
                fileOutputStream.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun resolveDuplicatePolicyForIncoming(
        fileMetadata: FileMetadata,
        sessionId: String,
        settings: com.example.domain.model.AppSettings
    ): String {
        val existingFile = storageManager.peekExistingDestination(
            fileName = fileMetadata.fileName,
            relativePath = fileMetadata.relativePath,
            organizeByType = settings.organizeReceivedByType,
            mimeType = fileMetadata.mimeType
        ) ?: return settings.duplicateHandling

        val sessionAction = sessionConflictPolicies[sessionId]
        if (sessionAction != null) {
            return actionToPolicy(sessionAction)
        }

        val shouldPromptUser = settings.duplicateHandling.equals("ask", ignoreCase = true) ||
            (settings.askOnConflictInUi && interactiveConflictUiAvailable.get())

        if (!shouldPromptUser) {
            return settings.duplicateHandling
        }

        return conflictPromptMutex.withLock {
            val recheckSession = sessionConflictPolicies[sessionId]
            if (recheckSession != null) {
                return@withLock actionToPolicy(recheckSession)
            }

            val prompt = ConflictPrompt(
                fileId = fileMetadata.fileId,
                fileName = fileMetadata.fileName,
                incomingSize = fileMetadata.fileSize,
                existingSize = existingFile.length(),
                existingLastModified = existingFile.lastModified(),
                existingPath = existingFile.absolutePath,
                remainingConflictCount = 0
            )
            val deferred = CompletableDeferred<ConflictDecision>()
            pendingConflictDecisions[fileMetadata.fileId] = deferred
            _activeConflictPrompt.value = prompt

            val decision = try {
                withTimeoutOrNull(CONFLICT_PROMPT_TIMEOUT_MS) {
                    deferred.await()
                }
            } finally {
                pendingConflictDecisions.remove(fileMetadata.fileId)
                if (_activeConflictPrompt.value?.fileId == fileMetadata.fileId) {
                    _activeConflictPrompt.value = null
                }
            }

            if (decision == null) {
                if (settings.duplicateHandling.equals("ask", ignoreCase = true)) "rename" else settings.duplicateHandling
            } else {
                if (decision.applyToAll) {
                    sessionConflictPolicies[sessionId] = decision.action
                }
                actionToPolicy(decision.action)
            }
        }
    }

    private fun actionToPolicy(action: ConflictAction): String {
        return when (action) {
            ConflictAction.REPLACE -> "overwrite"
            ConflictAction.SKIP -> "skip"
            ConflictAction.RENAME -> "rename"
            ConflictAction.KEEP_BOTH -> "keep_both"
        }
    }

    /**
     * Validates an existing partial temp file before any resume attempt.
     * Deletes corrupted/unverified temp artifacts and returns true only if the hash matches [expectedSha256Hex].
     */
    fun validatePartialTempFile(tempFile: File, expectedSha256Hex: String?): Boolean {
        if (!tempFile.exists() || tempFile.length() <= 0L || expectedSha256Hex.isNullOrBlank()) {
            storageManager.cleanTempFile(tempFile)
            return false
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(32 * 1024)
            FileInputStream(tempFile).use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    digest.update(buf, 0, read)
                }
            }
            val actualHex = FileStreamFraming.bytesToHex(digest.digest())
            val valid = actualHex.equals(expectedSha256Hex, ignoreCase = true)
            if (!valid) {
                storageManager.cleanTempFile(tempFile)
            }
            valid
        } catch (_: Exception) {
            storageManager.cleanTempFile(tempFile)
            false
        }
    }

    private fun notifyPeerTransferControl(transferId: String, type: ProtocolMessageType) {
        val peer = activeTransferPeers[transferId] ?: return
        scope.launch {
            val msg = ProtocolMessage(
                type = type,
                senderId = myDeviceIdProvider(),
                payload = mapOf("fileId" to transferId)
            )
            client.sendOneWay(peer.first, peer.second, msg, timeoutMs = 4_000)
        }
    }

    private fun handleIncomingTransferPaused(message: ProtocolMessage): ProtocolMessage? {
        val fileId = message.payload["fileId"] as? String
        if (!fileId.isNullOrBlank()) {
            transferPauseFlags[fileId] = true
            updateTransferItem(fileId) {
                it.copy(status = TransferStatus.PENDING, isPaused = true, speed = "Paused by peer")
            }
        }
        return null
    }

    private fun handleIncomingTransferResumed(message: ProtocolMessage): ProtocolMessage? {
        val fileId = message.payload["fileId"] as? String
        if (!fileId.isNullOrBlank()) {
            transferPauseFlags[fileId] = false
            wakeSlotWaitersAsync()
            updateTransferItem(fileId) {
                it.copy(status = TransferStatus.ACTIVE, isPaused = false, speed = "Receiving…")
            }
        }
        return null
    }

    private fun handleIncomingTransferCancelled(message: ProtocolMessage): ProtocolMessage? {
        val fileId = message.payload["fileId"] as? String
        if (fileId != null) {
            transferCancellationFlags[fileId] = true
            try {
                activeTransferSockets.remove(fileId)?.close()
            } catch (_: Exception) {}
            storageManager.deleteTempAndMetadata(fileId)
            val existing = _activeTransfers.value.find { it.id == fileId }
            updateTransferItem(fileId) {
                it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by peer", speed = "Cancelled")
            }
            if (existing != null) {
                scope.launch {
                    persistCancelled(existing, "Cancelled by peer")
                }
            }
        }
        return null
    }

    fun pauseTransfer(transferId: String) {
        transferPauseFlags[transferId] = true
        updateTransferItem(transferId) {
            val updated = it.copy(status = TransferStatus.PENDING, isPaused = true, speed = "Paused")
            scope.launch { persistActiveStateEntity(updated) }
            updated
        }
        notifyPeerTransferControl(transferId, ProtocolMessageType.TRANSFER_PAUSED)
    }

    fun resumeTransfer(transferId: String) {
        transferPauseFlags[transferId] = false
        wakeSlotWaitersAsync()
        val activeItem = _activeTransfers.value.find { it.id == transferId }
        val retryEntry = retryableSendMap[transferId]
        if (activeItem != null && activeTransferSockets[transferId] == null && retryEntry != null && activeItem.direction == TransferDirection.SEND) {
            val meta = FileMetadata(
                fileId = transferId,
                fileName = activeItem.fileName,
                fileSize = retryEntry.first.size,
                mimeType = retryEntry.first.mimeType,
                relativePath = activeItem.relativePath
            )
            scope.launch {
                streamSingleTransferWithRetries(
                    sessionId = UUID.randomUUID().toString(),
                    fileIndex = 0,
                    file = retryEntry.first,
                    meta = meta,
                    transferId = transferId,
                    target = retryEntry.second,
                    peerSupportsResume = true,
                    existingTransferIdMode = true
                )
            }
            return
        }
        updateTransferItem(transferId) {
            val updated = it.copy(status = TransferStatus.ACTIVE, isPaused = false, speed = "Resuming…")
            scope.launch { persistActiveStateEntity(updated) }
            updated
        }
        notifyPeerTransferControl(transferId, ProtocolMessageType.TRANSFER_RESUMED)
    }

    fun setTransferPriority(transferId: String, priority: TransferPriority) {
        updateTransferItem(transferId) {
            val updated = it.copy(priority = priority)
            scope.launch { persistActiveStateEntity(updated) }
            updated
        }
        wakeSlotWaitersAsync()
    }

    fun moveTransferUp(transferId: String) {
        val list = _activeTransfers.value
        val activeOrPending = list.filter { it.status == TransferStatus.PENDING || it.status == TransferStatus.ACTIVE }
        val idx = activeOrPending.indexOfFirst { it.id == transferId }
        if (idx <= 0) return
        val currentItem = activeOrPending[idx]
        val prevItem = activeOrPending[idx - 1]
        val newOrderCurrent = prevItem.queueOrder
        val newOrderPrev = if (currentItem.queueOrder == prevItem.queueOrder) prevItem.queueOrder + 1 else currentItem.queueOrder
        _activeTransfers.update { items ->
            items.map {
                when (it.id) {
                    currentItem.id -> it.copy(queueOrder = newOrderCurrent)
                    prevItem.id -> it.copy(queueOrder = newOrderPrev)
                    else -> it
                }
            }
        }
        wakeSlotWaitersAsync()
        recalculateStats()
    }

    fun moveTransferDown(transferId: String) {
        val list = _activeTransfers.value
        val activeOrPending = list.filter { it.status == TransferStatus.PENDING || it.status == TransferStatus.ACTIVE }
        val idx = activeOrPending.indexOfFirst { it.id == transferId }
        if (idx == -1 || idx >= activeOrPending.lastIndex) return
        val currentItem = activeOrPending[idx]
        val nextItem = activeOrPending[idx + 1]
        val newOrderCurrent = if (nextItem.queueOrder == currentItem.queueOrder) currentItem.queueOrder + 1 else nextItem.queueOrder
        val newOrderNext = currentItem.queueOrder
        _activeTransfers.update { items ->
            items.map {
                when (it.id) {
                    currentItem.id -> it.copy(queueOrder = newOrderCurrent)
                    nextItem.id -> it.copy(queueOrder = newOrderNext)
                    else -> it
                }
            }
        }
        wakeSlotWaitersAsync()
        recalculateStats()
    }

    fun pauseAllTransfers() {
        val targets = _activeTransfers.value.filter { it.status == TransferStatus.ACTIVE || (it.status == TransferStatus.PENDING && !it.isPaused) }
        targets.forEach { pauseTransfer(it.id) }
    }

    fun resumeAllTransfers() {
        val paused = _activeTransfers.value.filter { it.status == TransferStatus.PENDING && it.isPaused }
        paused.forEach { resumeTransfer(it.id) }
    }

    fun cancelAllTransfers() {
        val running = _activeTransfers.value.filter { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
        running.forEach { cancelTransfer(it.id) }
    }

    fun retryAllFailed() {
        val failedIds = (_activeTransfers.value + transferHistory.value)
            .filter { it.status == TransferStatus.FAILED }
            .map { it.id }
            .distinct()
        failedIds.forEach { id ->
            if (retryableSendMap.containsKey(id)) {
                retryFailedTransfer(id)
            }
        }
    }

    fun triggerAutoRetryOnNetworkRestored() {
        val ids = recoverableNetworkFailureIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            delay(1_200L)
            ids.forEach { id ->
                if (retryableSendMap.containsKey(id)) {
                    performanceMonitor?.recordRecoveryAttempt(false)
                    retryFailedTransfer(id)
                }
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        transferCancellationFlags[transferId] = true
        notifyPeerTransferControl(transferId, ProtocolMessageType.TRANSFER_CANCELLED)
        try {
            activeTransferSockets.remove(transferId)?.close()
        } catch (_: Exception) {}
        storageManager.deleteTempAndMetadata(transferId)
        val existing = _activeTransfers.value.find { it.id == transferId }
        updateTransferItem(transferId) {
            it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by user", speed = "Cancelled")
        }
        notificationHelper.cancelNotification(transferId.hashCode())
        if (existing != null) {
            scope.launch {
                persistCancelled(existing, "Cancelled by user")
            }
        }
    }

    fun retryFailedTransfer(transferId: String) {
        val retryEntry = retryableSendMap[transferId]
        if (retryEntry != null) {
            transferCancellationFlags.remove(transferId)
            transferPauseFlags.remove(transferId)
            recoverableNetworkFailureIds.remove(transferId)
            storageManager.deleteTempAndMetadata(transferId)
            _activeTransfers.update { list -> list.filter { it.id != transferId } }
            scope.launch {
                transferDao.deleteById(transferId)
                sendFilesToTarget(listOf(retryEntry.first), retryEntry.second)
            }
        } else if (transferPauseFlags[transferId] == true) {
            resumeTransfer(transferId)
        } else {
            updateTransferItem(transferId) {
                it.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = "Original file handle unavailable after session end; re-select file to resend",
                    speed = "Failed"
                )
            }
        }
    }

    fun deleteHistoryEntry(id: String) {
        scope.launch {
            transferDao.deleteById(id)
            _activeTransfers.update { list -> list.filter { it.id != id } }
            recalculateStats()
        }
    }

    fun clearCompleted() {
        scope.launch {
            transferDao.deleteCompleted()
            _activeTransfers.update { list ->
                list.filter { it.status != TransferStatus.COMPLETED }
            }
            recalculateStats()
        }
    }

    fun clearAllHistory() {
        scope.launch {
            transferDao.deleteAll()
            _activeTransfers.update { list ->
                list.filter { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
            }
            recalculateStats()
        }
    }

    private fun updateTransferItem(id: String, transform: (TransferItem) -> TransferItem) {
        _activeTransfers.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        recalculateStats()
    }

    private fun updateTransferItemsStatus(ids: List<String>, status: TransferStatus, speed: String) {
        _activeTransfers.update { list ->
            list.map { if (it.id in ids) it.copy(status = status, speed = speed) else it }
        }
        recalculateStats()
    }

    private fun failTransferItems(ids: List<String>, reason: String, peer: Device) {
        val myDevice = settingsRepository.settings.value.deviceName
        val now = System.currentTimeMillis()
        val snapshot = _activeTransfers.value.associateBy { it.id }

        _activeTransfers.update { list ->
            list.map {
                if (it.id in ids) {
                    it.copy(status = TransferStatus.FAILED, errorMessage = reason, speed = "Failed", completionTime = now)
                } else it
            }
        }

        ids.forEach { id ->
            val existing = snapshot[id]
            val fileName = existing?.fileName ?: "Transfer"
            val fileSize = existing?.fileSize ?: 0L
            val start = existing?.startTime ?: now
            scope.launch {
                transferDao.insert(
                    TransferEntity(
                        id = id,
                        sessionId = "",
                        fileName = fileName,
                        fileExtension = fileName.substringAfterLast('.', ""),
                        mimeType = existing?.mimeType,
                        fileSize = fileSize,
                        senderDevice = myDevice,
                        receiverDevice = peer.alias,
                        direction = TransferDirection.SEND.name,
                        status = TransferStatus.FAILED.name,
                        startTime = start,
                        completionTime = now,
                        durationMs = (now - start).coerceAtLeast(1L),
                        speed = "Failed",
                        peerDeviceName = peer.alias,
                        peerAddress = "${peer.ipAddress}:${peer.port}",
                        errorMessage = reason,
                        timestamp = now,
                        relativePath = existing?.relativePath,
                        priority = existing?.priority?.name ?: TransferPriority.NORMAL.name,
                        queueOrder = existing?.queueOrder ?: 0,
                        sourceUri = existing?.sourceUri
                    )
                )
            }
        }
        connectionManager.recordError(
            targetLabel = peer.alias,
            address = "${peer.ipAddress}:${peer.port}",
            message = reason,
            category = ErrorCategory.TRANSFER
        )
        recalculateStats()
    }

    private fun markTransferCancelled(
        transferId: String,
        peer: Device,
        reason: String,
        fileName: String? = null,
        fileSize: Long? = null,
        direction: TransferDirection = TransferDirection.SEND
    ) {
        val existing = _activeTransfers.value.find { it.id == transferId }
        updateTransferItem(transferId) {
            it.copy(status = TransferStatus.CANCELLED, errorMessage = reason, speed = "Cancelled")
        }
        notificationHelper.cancelNotification(transferId.hashCode())
        scope.launch {
            if (existing != null) {
                persistCancelled(existing, reason)
            } else {
                val myName = settingsRepository.settings.value.deviceName
                val now = System.currentTimeMillis()
                val name = fileName ?: "Transfer"
                transferDao.insert(
                    TransferEntity(
                        id = transferId,
                        sessionId = "",
                        fileName = name,
                        fileExtension = name.substringAfterLast('.', ""),
                        fileSize = fileSize ?: 0L,
                        senderDevice = if (direction == TransferDirection.SEND) myName else peer.alias,
                        receiverDevice = if (direction == TransferDirection.SEND) peer.alias else myName,
                        direction = direction.name,
                        status = TransferStatus.CANCELLED.name,
                        startTime = now,
                        completionTime = now,
                        durationMs = 0L,
                        speed = "Cancelled",
                        peerDeviceName = peer.alias,
                        peerAddress = "${peer.ipAddress}:${peer.port}",
                        errorMessage = reason,
                        timestamp = now
                    )
                )
            }
        }
    }

    private suspend fun persistCancelled(item: TransferItem, reason: String) {
        val myName = settingsRepository.settings.value.deviceName
        val now = System.currentTimeMillis()
        transferDao.insert(
            TransferEntity(
                id = item.id,
                sessionId = "",
                fileName = item.fileName,
                fileExtension = item.fileExtension.ifBlank { item.fileName.substringAfterLast('.', "") },
                mimeType = item.mimeType,
                fileSize = item.fileSize,
                senderDevice = item.senderDevice.ifBlank {
                    if (item.direction == TransferDirection.SEND) myName else item.peerDeviceName
                },
                receiverDevice = item.receiverDevice.ifBlank {
                    if (item.direction == TransferDirection.SEND) item.peerDeviceName else myName
                },
                direction = item.direction.name,
                status = TransferStatus.CANCELLED.name,
                startTime = item.startTime,
                completionTime = now,
                durationMs = (now - item.startTime).coerceAtLeast(0L),
                speed = "Cancelled",
                filePath = item.filePath,
                peerDeviceName = item.peerDeviceName,
                peerAddress = item.peerAddress,
                errorMessage = reason,
                timestamp = now,
                relativePath = item.relativePath
            )
        )
    }

    private suspend fun recordTransferCompleted(
        id: String,
        sessionId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String? = null,
        direction: TransferDirection,
        peer: Device,
        filePath: String? = null,
        checksum: String? = null,
        startTime: Long = System.currentTimeMillis(),
        relativePath: String? = null,
        peakSpeedBytesPerSec: Long = 0L,
        isDirectory: Boolean = false
    ) {
        val completionTime = System.currentTimeMillis()
        val durationMs = (completionTime - startTime).coerceAtLeast(1L)
        val avgBytesPerSec = if (fileSize > 0L) (fileSize * 1000L) / durationMs else 0L
        val peak = maxOf(peakSpeedBytesPerSec, avgBytesPerSec)
        val avgSpeed = if (isDirectory) "Folder Created" else "${TransferItem.formatBytes(avgBytesPerSec)}/s"
        val myName = settingsRepository.settings.value.deviceName
        val senderDevice = if (direction == TransferDirection.SEND) myName else peer.alias
        val receiverDevice = if (direction == TransferDirection.SEND) peer.alias else myName

        updateTransferItem(id) {
            it.copy(
                status = TransferStatus.COMPLETED,
                progress = 1.0f,
                bytesTransferred = fileSize,
                isPaused = false,
                speed = avgSpeed,
                filePath = filePath ?: it.filePath,
                checksum = checksum ?: it.checksum,
                startTime = startTime,
                completionTime = completionTime,
                durationMs = durationMs,
                relativePath = relativePath ?: it.relativePath,
                avgSpeedBytesPerSec = avgBytesPerSec,
                peakSpeedBytesPerSec = peak,
                isDirectory = isDirectory
            )
        }

        val entity = TransferEntity(
            id = id,
            sessionId = sessionId,
            fileName = fileName,
            fileExtension = if (isDirectory) "" else fileName.substringAfterLast('.', "").lowercase(Locale.ROOT),
            mimeType = mimeType,
            fileSize = fileSize,
            senderDevice = senderDevice,
            receiverDevice = receiverDevice,
            direction = direction.name,
            status = TransferStatus.COMPLETED.name,
            startTime = startTime,
            completionTime = completionTime,
            durationMs = durationMs,
            speed = avgSpeed,
            checksum = checksum,
            filePath = filePath,
            peerDeviceName = peer.alias,
            peerAddress = "${peer.ipAddress}:${peer.port}",
            timestamp = completionTime,
            relativePath = relativePath,
            avgSpeedBytesPerSec = avgBytesPerSec,
            peakSpeedBytesPerSec = peak,
            bytesTransferred = fileSize,
            isPaused = false,
            isDirectory = isDirectory
        )
        transferDao.insert(entity)
        retryableSendMap.remove(id)
        try {
            pairedDeviceRepository.incrementUsage(peer.id)
            if (settingsRepository.settings.value.rememberLastDestination && direction == TransferDirection.SEND) {
                settingsRepository.setLastDestinationDeviceId(peer.id)
            }
        } catch (_: Exception) {}
        recalculateStats()
    }

    private suspend fun recordTransferFailed(
        id: String,
        sessionId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String? = null,
        direction: TransferDirection,
        peer: Device,
        error: String,
        startTime: Long = System.currentTimeMillis(),
        relativePath: String? = null,
        sourceUri: String? = null,
        bytesTransferred: Long = 0L,
        retryCount: Int = 0
    ) {
        val completionTime = System.currentTimeMillis()
        val durationMs = (completionTime - startTime).coerceAtLeast(1L)
        val myName = settingsRepository.settings.value.deviceName
        val senderDevice = if (direction == TransferDirection.SEND) myName else peer.alias
        val receiverDevice = if (direction == TransferDirection.SEND) peer.alias else myName

        updateTransferItem(id) {
            it.copy(
                status = TransferStatus.FAILED,
                errorMessage = error,
                speed = "Failed",
                isPaused = false,
                startTime = startTime,
                completionTime = completionTime,
                durationMs = durationMs,
                bytesTransferred = bytesTransferred,
                retryCount = retryCount
            )
        }

        val entity = TransferEntity(
            id = id,
            sessionId = sessionId,
            fileName = fileName,
            fileExtension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT),
            mimeType = mimeType,
            fileSize = fileSize,
            senderDevice = senderDevice,
            receiverDevice = receiverDevice,
            direction = direction.name,
            status = TransferStatus.FAILED.name,
            startTime = startTime,
            completionTime = completionTime,
            durationMs = durationMs,
            speed = "Failed",
            peerDeviceName = peer.alias,
            peerAddress = "${peer.ipAddress}:${peer.port}",
            errorMessage = error,
            timestamp = completionTime,
            relativePath = relativePath,
            bytesTransferred = bytesTransferred,
            retryCount = retryCount,
            sourceUri = sourceUri
        )
        transferDao.insert(entity)
        val errCategory = when {
            error.contains("checksum", ignoreCase = true) -> ErrorCategory.PROTOCOL
            error.contains("space", ignoreCase = true) || error.contains("storage", ignoreCase = true) -> ErrorCategory.STORAGE
            else -> ErrorCategory.TRANSFER
        }
        connectionManager.recordError(
            targetLabel = peer.alias,
            address = "${peer.ipAddress}:${peer.port}",
            message = error,
            category = errCategory
        )
        recalculateStats()
    }

    private fun recalculateStats() {
        val activeList = _activeTransfers.value
        val historyList = transferHistory.value
        val historyIds = historyList.map { it.id }.toSet()

        val activeCount = activeList.count { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
        val completedItems = historyList.filter { it.status == TransferStatus.COMPLETED } +
            activeList.filter { it.status == TransferStatus.COMPLETED && it.id !in historyIds }
        val completedCount = completedItems.size
        val failedCount = historyList.count { it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED } +
            activeList.count { (it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED) && it.id !in historyIds }

        val totalBytes = completedItems.sumOf { it.fileSize }
        val totalDurationMs = completedItems.sumOf { it.durationMs }
        val avgSpeedBytesPerSec = if (totalDurationMs > 0L && totalBytes > 0L) {
            (totalBytes * 1000L) / totalDurationMs
        } else {
            0L
        }
        val peakSpeedBytesPerSec = completedItems.maxOfOrNull {
            maxOf(it.peakSpeedBytesPerSec, it.avgSpeedBytesPerSec)
        } ?: 0L

        _stats.value = TransferStats(
            activeCount = activeCount,
            completedCount = completedCount,
            failedCount = failedCount,
            totalFilesTransferred = completedCount,
            totalBytesTransferred = totalBytes,
            averageSpeedBytesPerSec = avgSpeedBytesPerSec,
            peakSpeedBytesPerSec = peakSpeedBytesPerSec
        )
        onStateChangedListener?.invoke()
    }

    fun cleanup() {
        scope.cancel()
        activeTransferSockets.values.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        activeTransferSockets.clear()
        pendingApprovalResponses.clear()
        pendingOutgoingApprovals.clear()
        pendingConflictDecisions.clear()
    }
}
