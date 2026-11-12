package com.example.data.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.db.TransferDao
import com.example.data.db.TransferEntity
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.network.NetworkClient
import com.example.data.network.NetworkServer
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.domain.model.Device
import com.example.domain.model.TransferCategory
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
import kotlinx.coroutines.Job
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
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
        private const val MAX_CONCURRENT_TRANSFERS = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrencySemaphore = Semaphore(MAX_CONCURRENT_TRANSFERS)

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
                mimeType = entity.mimeType
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Tracks pause/cancellation tokens for ongoing transfers
    private val transferCancellationFlags = ConcurrentHashMap<String, Boolean>()
    private val transferPauseFlags = ConcurrentHashMap<String, Boolean>()

    init {
        scope.launch {
            transferHistory.collect {
                recalculateStats()
            }
        }
    }

    /**
     * Handles incoming transfer messages dispatched by the TCP server.
     */
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
        val fileName = payload["fileName"] as? String ?: "file_${System.currentTimeMillis()}"
        val fileSize = (payload["fileSize"] as? Number)?.toLong() ?: 0L
        val mimeType = payload["mimeType"] as? String
        val senderName = payload["senderName"] as? String ?: "Remote Device"

        val metadata = FileMetadata(
            fileId = fileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType ?: "application/octet-stream"
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
     * Sends selected files to one or more target devices.
     */
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

        // Prepare file metadata entries
        val metadataList = files.map { file ->
            FileMetadata(
                fileId = UUID.randomUUID().toString(),
                fileName = file.name,
                fileSize = file.size,
                mimeType = file.mimeType
            )
        }

        // Create in-memory transfer items with PENDING state
        val initialItems = files.mapIndexed { index, file ->
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
                timestamp = System.currentTimeMillis()
            )
        }

        _activeTransfers.update { initialItems + it }
        recalculateStats()

        // 1. Send TRANSFER_REQUEST
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

        val result = client.sendMessage(target.ipAddress, target.port, requestMessage, timeoutMs = 25_000)

        result.fold(
            onSuccess = { response ->
                when (response.type) {
                    ProtocolMessageType.TRANSFER_ACCEPTED -> {
                        // Peer accepted! Stream each file
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
                failTransferItems(metadataList.map { it.fileId }, "Connection error: ${error.localizedMessage}", target)
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

        for (i in files.indices) {
            val file = files[i]
            val meta = metadataList[i]
            val transferId = meta.fileId

            if (transferCancellationFlags[transferId] == true) {
                markTransferCancelled(transferId, target)
                continue
            }

            concurrencySemaphore.acquire()
            try {
                updateTransferItem(transferId) {
                    it.copy(status = TransferStatus.ACTIVE, speed = "Connecting…")
                }

                // Connect streaming socket to peer
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(target.ipAddress, target.port), 10_000)
                    socket.soTimeout = 30_000
                    val output = BufferedOutputStream(socket.getOutputStream())

                    // Send TRANSFER_STARTED message
                    val startMsg = ProtocolMessage(
                        type = ProtocolMessageType.TRANSFER_STARTED,
                        senderId = myId,
                        receiverId = target.id,
                        payload = mapOf(
                            "sessionId" to sessionId,
                            "fileId" to meta.fileId,
                            "fileIndex" to i,
                            "fileName" to meta.fileName,
                            "fileSize" to meta.fileSize,
                            "mimeType" to meta.mimeType
                        )
                    )
                    MessageFraming.writeFrame(output, startMsg)

                    // Stream binary chunks
                    streamSingleFile(
                        file = file,
                        fileIndex = i,
                        transferId = transferId,
                        target = target,
                        output = output
                    )

                    // Mark completed in database and UI
                    recordTransferCompleted(
                        id = transferId,
                        sessionId = sessionId,
                        fileName = meta.fileName,
                        fileSize = meta.fileSize,
                        direction = TransferDirection.SEND,
                        peer = target
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Stream failed for ${meta.fileName}", e)
                    recordTransferFailed(
                        id = transferId,
                        sessionId = sessionId,
                        fileName = meta.fileName,
                        fileSize = meta.fileSize,
                        direction = TransferDirection.SEND,
                        peer = target,
                        error = e.localizedMessage ?: "Stream error"
                    )
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                }
            } finally {
                concurrencySemaphore.release()
            }
        }
    }

    private suspend fun streamSingleFile(
        file: SelectedFile,
        fileIndex: Int,
        transferId: String,
        target: Device,
        output: BufferedOutputStream
    ) = withContext(Dispatchers.IO) {
        val inputStream: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Could not open input stream for ${file.name}")

        val buffer = ByteArray(FileStreamFraming.DEFAULT_CHUNK_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L
        val fileSize = file.size.coerceAtLeast(1L)

        var lastSpeedCalcTime = System.currentTimeMillis()
        var lastBytesRead = 0L
        var currentSpeedStr = ""

        try {
            while (isActive) {
                // Check if user paused transfer
                while (transferPauseFlags[transferId] == true && isActive) {
                    updateTransferItem(transferId) {
                        it.copy(status = TransferStatus.PENDING, speed = "Paused")
                    }
                    delay(500)
                }

                if (transferCancellationFlags[transferId] == true) {
                    throw IllegalStateException("Transfer cancelled by user")
                }

                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                digest.update(buffer, 0, bytesRead)
                FileStreamFraming.writeChunk(output, fileIndex, totalRead, buffer, bytesRead)

                totalRead += bytesRead

                // Update speed and progress every ~500ms
                val now = System.currentTimeMillis()
                val elapsed = now - lastSpeedCalcTime
                if (elapsed >= 500L) {
                    val bytesSinceLast = totalRead - lastBytesRead
                    val speedBytesPerSec = if (elapsed > 0) (bytesSinceLast * 1000L) / elapsed else 0L
                    currentSpeedStr = "${TransferItem.formatBytes(speedBytesPerSec)}/s"

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

                    notificationHelper.showTransferProgress(
                        notificationId = transferId.hashCode(),
                        fileName = file.name,
                        progressPercent = (progress * 100).toInt(),
                        speedStr = currentSpeedStr
                    )
                }
            }

            // Write EOF frame with SHA-256 hash
            val sha256Bytes = digest.digest()
            FileStreamFraming.writeEof(output, fileIndex, sha256Bytes)

        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Handles incoming TRANSFER_REQUEST message from a peer.
     */
    private suspend fun handleIncomingTransferRequest(message: ProtocolMessage, socket: Socket): ProtocolMessage? {
        val payload = message.payload
        val sessionId = payload["sessionId"] as? String ?: UUID.randomUUID().toString()
        val senderName = payload["senderName"] as? String ?: "Remote Device"
        val senderPlatform = payload["senderPlatform"] as? String ?: "unknown"
        val senderIp = payload["senderIp"] as? String ?: socket.inetAddress.hostAddress ?: "127.0.0.1"
        val senderPort = (payload["senderPort"] as? Number)?.toInt() ?: 53317
        val totalBytes = (payload["totalBytes"] as? Number)?.toLong() ?: 0L

        val filesArray = payload["files"] as? JSONArray
        val files = FileMetadata.fromJsonList(filesArray)

        // Check storage availability
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

        val request = IncomingTransferRequest(
            sessionId = sessionId,
            senderId = message.senderId,
            senderName = senderName,
            senderPlatform = senderPlatform,
            senderIp = senderIp,
            senderPort = senderPort,
            files = files,
            totalBytes = totalBytes
        )

        val settings = settingsRepository.settings.value
        val isPaired = pairedDeviceRepository.getPairedDevice(message.senderId) != null

        // Auto-accept if configured and device is trusted
        if (settings.autoStartReceiving && isPaired) {
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

        // Otherwise post in-app request
        _incomingRequest.value = request

        // Suspends until user accepts or declines via UI
        return null // Handled asynchronously via acceptIncomingTransfer / rejectIncomingTransfer
    }

    fun acceptIncomingTransfer(request: IncomingTransferRequest) {
        _incomingRequest.value = null
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
                timestamp = System.currentTimeMillis()
            )
        }

        _activeTransfers.update { items + it }
        recalculateStats()
    }

    /**
     * Called by TcpServer when receiving incoming binary chunks.
     */
    suspend fun receiveIncomingFileStream(
        socket: Socket,
        fileMetadata: FileMetadata,
        senderDeviceName: String,
        sessionId: String,
        existingInputStream: InputStream? = null
    ) = withContext(Dispatchers.IO) {
        val tempFile = storageManager.createTempFile(fileMetadata.fileId)
        val fileOutputStream = FileOutputStream(tempFile, false)
        val buffer = ByteArray(FileStreamFraming.DEFAULT_CHUNK_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")

        var receivedBytes = 0L
        val fileSize = fileMetadata.fileSize.coerceAtLeast(1L)
        var lastCalcTime = System.currentTimeMillis()
        var lastBytes = 0L

        try {
            val input = if (existingInputStream is BufferedInputStream) existingInputStream else BufferedInputStream(existingInputStream ?: socket.getInputStream())

            while (isActive && !socket.isClosed) {
                if (transferCancellationFlags[fileMetadata.fileId] == true) {
                    throw IllegalStateException("Transfer cancelled")
                }

                val frameResult = FileStreamFraming.readNextFrame(input, buffer)
                when (frameResult) {
                    is FileStreamFraming.FrameReadResult.Chunk -> {
                        fileOutputStream.write(frameResult.data, 0, frameResult.length)
                        digest.update(frameResult.data, 0, frameResult.length)
                        receivedBytes += frameResult.length

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastCalcTime
                        if (elapsed >= 500L) {
                            val speed = if (elapsed > 0) ((receivedBytes - lastBytes) * 1000L) / elapsed else 0L
                            val speedStr = "${TransferItem.formatBytes(speed)}/s"
                            lastCalcTime = now
                            lastBytes = receivedBytes

                            val progress = (receivedBytes.toFloat() / fileSize).coerceIn(0f, 1f)
                            updateTransferItem(fileMetadata.fileId) {
                                it.copy(progress = progress, speed = speedStr)
                            }

                            notificationHelper.showTransferProgress(
                                notificationId = fileMetadata.fileId.hashCode(),
                                fileName = fileMetadata.fileName,
                                progressPercent = (progress * 100).toInt(),
                                speedStr = speedStr
                            )
                        }
                    }

                    is FileStreamFraming.FrameReadResult.Eof -> {
                        fileOutputStream.flush()
                        fileOutputStream.close()

                        // Verify SHA-256 Checksum
                        val calculatedHashHex = FileStreamFraming.bytesToHex(digest.digest())
                        val expectedHashHex = frameResult.sha256Hex

                        if (expectedHashHex.isNotBlank() && !calculatedHashHex.equals(expectedHashHex, ignoreCase = true)) {
                            throw IllegalStateException("Checksum mismatch! Data corrupted during transfer.")
                        }

                        // Finalize received file
                        val destFile = storageManager.resolveUniqueDestinationFile(fileMetadata.fileName)
                        val moved = storageManager.finalizeReceivedFile(tempFile, destFile)
                        if (!moved) throw IllegalStateException("Could not save to ${destFile.name}")

                        // Record success
                        val peerDevice = Device(
                            id = sessionId,
                            alias = senderDeviceName,
                            deviceType = com.example.domain.model.DeviceType.UNKNOWN,
                            ipAddress = socket.inetAddress.hostAddress ?: ""
                        )
                        recordTransferCompleted(
                            id = fileMetadata.fileId,
                            sessionId = sessionId,
                            fileName = destFile.name,
                            fileSize = fileMetadata.fileSize,
                            direction = TransferDirection.RECEIVE,
                            peer = peerDevice,
                            filePath = destFile.absolutePath
                        )

                        notificationHelper.showTransferComplete(
                            notificationId = fileMetadata.fileId.hashCode(),
                            fileName = destFile.name,
                            totalSize = TransferItem.formatBytes(fileMetadata.fileSize)
                        )
                        return@withContext
                    }

                    is FileStreamFraming.FrameReadResult.ConnectionClosed -> {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            storageManager.cleanTempFile(tempFile)
            val peerDevice = Device(
                id = sessionId,
                alias = senderDeviceName,
                deviceType = com.example.domain.model.DeviceType.UNKNOWN,
                ipAddress = socket.inetAddress.hostAddress ?: ""
            )
            recordTransferFailed(
                id = fileMetadata.fileId,
                sessionId = sessionId,
                fileName = fileMetadata.fileName,
                fileSize = fileMetadata.fileSize,
                direction = TransferDirection.RECEIVE,
                peer = peerDevice,
                error = e.localizedMessage ?: "Receive failed"
            )
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
            updateTransferItem(fileId) {
                it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by peer")
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
        updateTransferItem(transferId) {
            it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by user")
        }
        notificationHelper.cancelNotification(transferId.hashCode())
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
        _activeTransfers.update { list ->
            list.map {
                if (it.id in ids) {
                    it.copy(status = TransferStatus.FAILED, errorMessage = reason, speed = "Failed")
                } else it
            }
        }
        ids.forEach { id ->
            scope.launch {
                transferDao.insert(
                    TransferEntity(
                        id = id,
                        sessionId = "",
                        fileName = "Transfer",
                        fileSize = 0L,
                        direction = TransferDirection.SEND.name,
                        status = TransferStatus.FAILED.name,
                        peerDeviceName = peer.alias,
                        peerAddress = "${peer.ipAddress}:${peer.port}",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        recalculateStats()
    }

    private fun markTransferCancelled(transferId: String, peer: Device) {
        updateTransferItem(transferId) {
            it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by user")
        }
        notificationHelper.cancelNotification(transferId.hashCode())
    }

    private suspend fun recordTransferCompleted(
        id: String,
        sessionId: String,
        fileName: String,
        fileSize: Long,
        direction: TransferDirection,
        peer: Device,
        filePath: String? = null
    ) {
        updateTransferItem(id) {
            it.copy(
                status = TransferStatus.COMPLETED,
                progress = 1.0f,
                speed = "Done"
            )
        }

        val entity = TransferEntity(
            id = id,
            sessionId = sessionId,
            fileName = fileName,
            fileSize = fileSize,
            direction = direction.name,
            status = TransferStatus.COMPLETED.name,
            peerDeviceName = peer.alias,
            peerAddress = "${peer.ipAddress}:${peer.port}",
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            speed = "Completed"
        )
        transferDao.insert(entity)
        recalculateStats()
    }

    private suspend fun recordTransferFailed(
        id: String,
        sessionId: String,
        fileName: String,
        fileSize: Long,
        direction: TransferDirection,
        peer: Device,
        error: String
    ) {
        updateTransferItem(id) {
            it.copy(
                status = TransferStatus.FAILED,
                errorMessage = error,
                speed = "Failed"
            )
        }

        val entity = TransferEntity(
            id = id,
            sessionId = sessionId,
            fileName = fileName,
            fileSize = fileSize,
            direction = direction.name,
            status = TransferStatus.FAILED.name,
            peerDeviceName = peer.alias,
            peerAddress = "${peer.ipAddress}:${peer.port}",
            timestamp = System.currentTimeMillis()
        )
        transferDao.insert(entity)
        recalculateStats()
    }

    private fun recalculateStats() {
        val activeList = _activeTransfers.value
        val historyList = transferHistory.value

        val activeCount = activeList.count { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
        val completedCount = historyList.count { it.status == TransferStatus.COMPLETED } +
                activeList.count { it.status == TransferStatus.COMPLETED }
        val failedCount = historyList.count { it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED } +
                activeList.count { it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED }

        val totalBytes = historyList.filter { it.status == TransferStatus.COMPLETED }.sumOf { it.fileSize } +
                activeList.filter { it.status == TransferStatus.COMPLETED }.sumOf { it.fileSize }

        _stats.value = TransferStats(
            activeCount = activeCount,
            completedCount = completedCount,
            failedCount = failedCount,
            totalFilesTransferred = completedCount,
            totalBytesTransferred = totalBytes
        )
    }
}
