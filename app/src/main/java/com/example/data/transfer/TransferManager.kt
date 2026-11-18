package com.example.data.transfer

import android.content.Context
import android.util.Log
import com.example.data.db.TransferDao
import com.example.data.db.TransferEntity
import com.example.data.network.ConnectionManager
import com.example.data.network.NetworkClient
import com.example.data.network.NetworkServer
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.security.SecurityValidator
import com.example.domain.model.Device
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.presentation.transfer.SelectedFile
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val myDeviceIdProvider: () -> String
) {
    companion object {
        private const val TAG = "TransferManager"
        private const val MAX_CONCURRENT_CAP = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrencySemaphore = Semaphore(MAX_CONCURRENT_CAP)

    private val _activeTransfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val activeTransfers: StateFlow<List<TransferItem>> = _activeTransfers.asStateFlow()

    private val _incomingRequest = MutableStateFlow<IncomingTransferRequest?>(null)
    val incomingRequest: StateFlow<IncomingTransferRequest?> = _incomingRequest.asStateFlow()

    private val _stats = MutableStateFlow(TransferStats())
    val stats: StateFlow<TransferStats> = _stats.asStateFlow()

    // History from Room database
    val transferHistory = transferDao.getAll().map { list ->
        list.map { entity ->
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
            TransferItem(
                id = entity.id,
                fileName = entity.fileName,
                fileSize = entity.fileSize,
                direction = direction,
                status = status,
                progress = if (status == TransferStatus.COMPLETED) 1.0f else 0f,
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
                peakSpeedBytesPerSec = entity.peakSpeedBytesPerSec
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    var onStateChangedListener: (() -> Unit)? = null

    private val transferCancellationFlags = ConcurrentHashMap<String, Boolean>()
    private val transferPauseFlags = ConcurrentHashMap<String, Boolean>()
    private val retryableSendMap = ConcurrentHashMap<String, Pair<SelectedFile, Device>>()

    init {
        scope.launch {
            // Recovery cleanup of orphaned temp files on startup
            storageManager.cleanAllTempFiles()
        }
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

    suspend fun handleProtocolMessage(
        message: ProtocolMessage,
        socket: Socket,
        input: BufferedInputStream? = null
    ): ProtocolMessage? {
        return when (message.type) {
            ProtocolMessageType.TRANSFER_REQUEST -> handleIncomingTransferRequest(message, socket)
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

        val metadata = FileMetadata(
            fileId = fileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType ?: "application/octet-stream",
            relativePath = relativePath
        )

        receiveIncomingFileStream(
            socket = socket,
            fileMetadata = metadata,
            senderDeviceName = senderName,
            sessionId = sessionId,
            existingInputStream = input
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
        val sessionId = UUID.randomUUID().toString()
        val totalBytes = files.sumOf { it.size }
        val myId = myDeviceIdProvider()
        val settings = settingsRepository.settings.value
        val myIp = NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"

        val metadataList = files.mapIndexed { index, file ->
            val fileId = UUID.randomUUID().toString()
            retryableSendMap[fileId] = file to target
            FileMetadata(
                fileId = fileId,
                fileName = if (settings.preserveOriginalNames) file.name else "file_${index}_${System.currentTimeMillis()}.${file.extension}",
                fileSize = file.size,
                mimeType = file.mimeType,
                relativePath = FileMetadata.sanitizeRelativePath(file.relativePath)
            )
        }

        val now = System.currentTimeMillis()
        val initialItems = files.mapIndexed { index, _ ->
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
                relativePath = meta.relativePath
            )
        }

        _activeTransfers.update { initialItems + it }
        recalculateStats()

        val requestMessage = ProtocolMessage(
            type = ProtocolMessageType.TRANSFER_REQUEST,
            senderId = myId,
            receiverId = target.id,
            payload = mapOf(
                "sessionId" to sessionId,
                "senderName" to settings.deviceName,
                "senderPlatform" to "android",
                "senderIp" to myIp,
                "senderPort" to settings.port,
                "totalBytes" to totalBytes,
                "files" to FileMetadata.toJsonArray(metadataList)
            )
        )

        updateTransferItemsStatus(metadataList.map { it.fileId }, TransferStatus.PENDING, "Requesting approval…")

        val timeoutMs = settings.connectionTimeoutMs.coerceIn(5_000, 60_000)
        val result = client.sendMessage(target.ipAddress, target.port, requestMessage, timeoutMs = timeoutMs)

        result.fold(
            onSuccess = { response ->
                when (response.type) {
                    ProtocolMessageType.TRANSFER_ACCEPTED -> {
                        streamFilesToPeer(sessionId, metadataList, files, target)
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
        target: Device
    ) {
        val myId = myDeviceIdProvider()
        val settings = settingsRepository.settings.value
        val maxConcurrent = settings.maxConcurrentTransfers.coerceIn(1, MAX_CONCURRENT_CAP)
        val extraPermitsToHold = (MAX_CONCURRENT_CAP - maxConcurrent).coerceAtLeast(0)

        for (i in files.indices) {
            val file = files[i]
            val meta = metadataList[i]
            val transferId = meta.fileId

            if (transferCancellationFlags[transferId] == true) {
                markTransferCancelled(transferId, target, "Cancelled by user")
                continue
            }

            // Respect configured concurrency cap
            repeat(extraPermitsToHold) { concurrencySemaphore.acquire() }
            concurrencySemaphore.acquire()
            try {
                updateTransferItem(transferId) {
                    it.copy(status = TransferStatus.ACTIVE, speed = "Connecting…")
                }

                var socket: Socket? = null
                val fileStartTime = System.currentTimeMillis()
                try {
                    FileTransferService.start(context)
                    socket = Socket()
                    socket.connect(InetSocketAddress(target.ipAddress, target.port), settings.connectionTimeoutMs)
                    socket.soTimeout = settings.connectionTimeoutMs.coerceAtLeast(30_000)
                    val output = BufferedOutputStream(socket.getOutputStream())

                    val payloadMap = mutableMapOf<String, Any>(
                        "sessionId" to sessionId,
                        "fileId" to meta.fileId,
                        "fileIndex" to i,
                        "fileName" to meta.fileName,
                        "fileSize" to meta.fileSize,
                        "mimeType" to meta.mimeType,
                        "senderName" to settings.deviceName
                    )
                    if (!meta.relativePath.isNullOrBlank()) {
                        payloadMap["relativePath"] = meta.relativePath
                    }
                    val startMsg = ProtocolMessage(
                        type = ProtocolMessageType.TRANSFER_STARTED,
                        senderId = myId,
                        receiverId = target.id,
                        payload = payloadMap
                    )
                    MessageFraming.writeFrame(output, startMsg)

                    val (checksumHex, peakSpeedBytes) = streamSingleFile(
                        file = file,
                        fileIndex = i,
                        transferId = transferId,
                        output = output
                    )

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

                    if (settings.showNotifications) {
                        notificationHelper.showTransferComplete(
                            notificationId = transferId.hashCode(),
                            fileName = meta.fileName,
                            totalSize = TransferItem.formatBytes(meta.fileSize),
                            isSend = true
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stream failed for ${meta.fileName}", e)
                    if (transferCancellationFlags[transferId] == true) {
                        markTransferCancelled(transferId, target, "Cancelled by user")
                    } else {
                        val reason = e.localizedMessage ?: "Stream error"
                        recordTransferFailed(
                            id = transferId,
                            sessionId = sessionId,
                            fileName = meta.fileName,
                            fileSize = meta.fileSize,
                            mimeType = meta.mimeType,
                            direction = TransferDirection.SEND,
                            peer = target,
                            error = reason,
                            startTime = fileStartTime
                        )
                        if (settings.showNotifications) {
                            notificationHelper.showTransferFailed(
                                notificationId = transferId.hashCode(),
                                transferId = transferId,
                                fileName = meta.fileName,
                                reason = reason,
                                canRetry = retryableSendMap.containsKey(transferId)
                            )
                        }
                    }
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                }
            } finally {
                concurrencySemaphore.release()
                repeat(extraPermitsToHold) { concurrencySemaphore.release() }
            }
        }
    }

    private suspend fun streamSingleFile(
        file: SelectedFile,
        fileIndex: Int,
        transferId: String,
        output: BufferedOutputStream
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
        var lastBytesRead = 0L
        var peakSpeedBytesPerSec = 0L

        try {
            while (isActive) {
                while (transferPauseFlags[transferId] == true && isActive) {
                    updateTransferItem(transferId) {
                        it.copy(status = TransferStatus.PENDING, speed = "Paused")
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
                    val speedBytesPerSec = if (elapsed > 0) (bytesSinceLast * 1000L) / elapsed else 0L
                    if (speedBytesPerSec > peakSpeedBytesPerSec) {
                        peakSpeedBytesPerSec = speedBytesPerSec
                    }
                    val currentSpeedStr = "${TransferItem.formatBytes(speedBytesPerSec)}/s"

                    lastSpeedCalcTime = now
                    lastBytesRead = totalRead

                    val progress = (totalRead.toFloat() / fileSize).coerceIn(0f, 1f)
                    updateTransferItem(transferId) {
                        it.copy(
                            progress = progress,
                            speed = currentSpeedStr,
                            status = TransferStatus.ACTIVE
                        )
                    }

                    if (settingsRepository.settings.value.showNotifications) {
                        val activeCount = _activeTransfers.value.count {
                            it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING
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
        val sessionId = payload["sessionId"] as? String ?: UUID.randomUUID().toString()
        val senderName = payload["senderName"] as? String ?: "Remote Device"
        val senderPlatform = payload["senderPlatform"] as? String ?: "unknown"
        val senderIp = payload["senderIp"] as? String ?: socket.inetAddress?.hostAddress ?: "127.0.0.1"
        val senderPort = (payload["senderPort"] as? Number)?.toInt() ?: 53317
        val totalBytes = (payload["totalBytes"] as? Number)?.toLong() ?: 0L

        val filesArray = payload["files"] as? JSONArray
        val files = FileMetadata.fromJsonList(filesArray)
        val settings = settingsRepository.settings.value

        if (!SecurityValidator.isFileSizeAllowed(totalBytes, settings.maxAcceptedFileSizeMb)) {
            return ProtocolMessage(
                type = ProtocolMessageType.TRANSFER_REJECTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "reason" to "Transfer exceeds maximum accepted file size limit (${settings.maxAcceptedFileSizeMb} MB)"
                )
            )
        }

        if (!storageManager.hasAvailableStorage(totalBytes)) {
            return ProtocolMessage(
                type = ProtocolMessageType.TRANSFER_REJECTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
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

        // Strict policy: autoAcceptTrusted and per-peer autoAccept are disabled by default;
        // only auto-accept if explicitly enabled AND trusted AND no suspicious files
        val allowAutoAccept = (settings.autoAcceptTrusted || (pairedPeer?.autoAccept == true)) &&
            isPaired && !request.hasSuspiciousFiles
        if (allowAutoAccept) {
            prepareIncomingReceiving(request)
            return ProtocolMessage(
                type = ProtocolMessageType.TRANSFER_ACCEPTED,
                senderId = myDeviceIdProvider(),
                receiverId = message.senderId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "accepted" to true
                )
            )
        }

        _incomingRequest.value = request
        if (settings.showNotifications) {
            notificationHelper.showIncomingTransferRequest(request)
        }
        return null
    }

    fun acceptIncomingTransfer(request: IncomingTransferRequest) {
        _incomingRequest.value = null
        notificationHelper.cancelIncomingRequestNotification()
        prepareIncomingReceiving(request)

        scope.launch {
            val acceptMsg = ProtocolMessage(
                type = ProtocolMessageType.TRANSFER_ACCEPTED,
                senderId = myDeviceIdProvider(),
                receiverId = request.senderId,
                payload = mapOf(
                    "sessionId" to request.sessionId,
                    "accepted" to true
                )
            )
            client.sendOneWay(request.senderIp, request.senderPort, acceptMsg)
        }
    }

    fun rejectIncomingTransfer(request: IncomingTransferRequest, reason: String = "Declined by user") {
        _incomingRequest.value = null
        notificationHelper.cancelIncomingRequestNotification()

        scope.launch {
            val rejectMsg = ProtocolMessage(
                type = ProtocolMessageType.TRANSFER_REJECTED,
                senderId = myDeviceIdProvider(),
                receiverId = request.senderId,
                payload = mapOf(
                    "sessionId" to request.sessionId,
                    "reason" to reason
                )
            )
            client.sendOneWay(request.senderIp, request.senderPort, rejectMsg)
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
                relativePath = meta.relativePath
            )
        }

        _activeTransfers.update { items + it }
        recalculateStats()
    }

    suspend fun receiveIncomingFileStream(
        socket: Socket,
        fileMetadata: FileMetadata,
        senderDeviceName: String,
        sessionId: String,
        existingInputStream: InputStream? = null
    ) = withContext(Dispatchers.IO) {
        val receiveStartTime = System.currentTimeMillis()
        val settings = settingsRepository.settings.value
        val peerDevice = Device(
            id = sessionId,
            alias = senderDeviceName,
            deviceType = com.example.domain.model.DeviceType.UNKNOWN,
            ipAddress = socket.inetAddress?.hostAddress ?: ""
        )

        if (!SecurityValidator.isFileSizeAllowed(fileMetadata.fileSize, settings.maxAcceptedFileSizeMb)) {
            val reason = "File exceeds max size limit (${settings.maxAcceptedFileSizeMb} MB)"
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
        val tempFile = storageManager.createTempFile(fileMetadata.fileId)
        val fileOutputStream = FileOutputStream(tempFile, false)
        // Allocate receiver buffer large enough for up to 512 KB chunks regardless of local send buffer setting
        val buffer = ByteArray(FileStreamFraming.DEFAULT_CHUNK_SIZE * 8)
        val digest = MessageDigest.getInstance("SHA-256")

        var receivedBytes = 0L
        val fileSize = fileMetadata.fileSize.coerceAtLeast(1L)
        var lastCalcTime = System.currentTimeMillis()
        var lastBytes = 0L
        var peakSpeedBytesPerSec = 0L

        try {
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

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastCalcTime
                        if (elapsed >= 500L) {
                            val speed = if (elapsed > 0) ((receivedBytes - lastBytes) * 1000L) / elapsed else 0L
                            if (speed > peakSpeedBytesPerSec) {
                                peakSpeedBytesPerSec = speed
                            }
                            val speedStr = "${TransferItem.formatBytes(speed)}/s"
                            lastCalcTime = now
                            lastBytes = receivedBytes

                            val progress = (receivedBytes.toFloat() / fileSize).coerceIn(0f, 1f)
                            updateTransferItem(fileMetadata.fileId) {
                                it.copy(progress = progress, speed = speedStr)
                            }

                            if (settings.showNotifications) {
                                val activeCount = _activeTransfers.value.count {
                                    it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING
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
                            throw IllegalStateException("SHA-256 checksum mismatch! File corrupted during transfer.")
                        }

                        val resolution = storageManager.resolveDestinationWithPolicy(
                            fileName = fileMetadata.fileName,
                            duplicateHandling = settings.duplicateHandling,
                            relativePath = fileMetadata.relativePath,
                            organizeByType = settings.organizeReceivedByType,
                            mimeType = fileMetadata.mimeType
                        )

                        when (resolution) {
                            is StorageManager.DestinationResolution.Skipped -> {
                                storageManager.cleanTempFile(tempFile)
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

                                if (settings.showNotifications) {
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
            storageManager.cleanTempFile(tempFile)
            if (transferCancellationFlags[fileMetadata.fileId] == true) {
                markTransferCancelled(
                    transferId = fileMetadata.fileId,
                    peer = peerDevice,
                    reason = "Cancelled",
                    fileName = fileMetadata.fileName,
                    fileSize = fileMetadata.fileSize,
                    direction = TransferDirection.RECEIVE
                )
            } else {
                val reason = e.localizedMessage ?: "Receive failed"
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
                if (settings.showNotifications) {
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
            try {
                fileOutputStream.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleIncomingTransferCancelled(message: ProtocolMessage): ProtocolMessage? {
        val fileId = message.payload["fileId"] as? String
        if (fileId != null) {
            transferCancellationFlags[fileId] = true
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
            it.copy(status = TransferStatus.PENDING, speed = "Paused")
        }
    }

    fun resumeTransfer(transferId: String) {
        transferPauseFlags[transferId] = false
        updateTransferItem(transferId) {
            it.copy(status = TransferStatus.ACTIVE, speed = "Resuming…")
        }
    }

    fun cancelTransfer(transferId: String) {
        transferCancellationFlags[transferId] = true
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
            _activeTransfers.update { list -> list.filter { it.id != transferId } }
            scope.launch {
                transferDao.deleteById(transferId)
                sendFilesToTarget(listOf(retryEntry.first), retryEntry.second)
            }
        } else {
            resumeTransfer(transferId)
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
                        relativePath = existing?.relativePath
                    )
                )
            }
        }
        connectionManager.recordError(peer.alias, "${peer.ipAddress}:${peer.port}", reason)
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
                timestamp = now
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
        peakSpeedBytesPerSec: Long = 0L
    ) {
        val completionTime = System.currentTimeMillis()
        val durationMs = (completionTime - startTime).coerceAtLeast(1L)
        val avgBytesPerSec = (fileSize * 1000L) / durationMs
        val peak = maxOf(peakSpeedBytesPerSec, avgBytesPerSec)
        val avgSpeed = "${TransferItem.formatBytes(avgBytesPerSec)}/s"
        val myName = settingsRepository.settings.value.deviceName
        val senderDevice = if (direction == TransferDirection.SEND) myName else peer.alias
        val receiverDevice = if (direction == TransferDirection.SEND) peer.alias else myName

        updateTransferItem(id) {
            it.copy(
                status = TransferStatus.COMPLETED,
                progress = 1.0f,
                speed = avgSpeed,
                filePath = filePath ?: it.filePath,
                checksum = checksum ?: it.checksum,
                startTime = startTime,
                completionTime = completionTime,
                durationMs = durationMs,
                relativePath = relativePath ?: it.relativePath,
                avgSpeedBytesPerSec = avgBytesPerSec,
                peakSpeedBytesPerSec = peak
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
            peakSpeedBytesPerSec = peak
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
        startTime: Long = System.currentTimeMillis()
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
                startTime = startTime,
                completionTime = completionTime,
                durationMs = durationMs
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
            timestamp = completionTime
        )
        transferDao.insert(entity)
        connectionManager.recordError(peer.alias, "${peer.ipAddress}:${peer.port}", error)
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
}
