package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.discovery.NsdDiscoveryManager
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.network.TcpClient
import com.example.data.network.TcpServer
import com.example.data.network.TlsTransport
import com.example.data.pairing.PairingPayload
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.transfer.FileMetadata
import com.example.data.transfer.FileStreamFraming
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.data.transfer.TransferNotificationHelper
import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppSettings
import com.example.domain.model.Device
import com.example.domain.model.DeviceType
import com.example.domain.model.PairedDevice
import com.example.domain.model.ThemeMode
import com.example.domain.model.TransferStatus
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase6ProtocolAndStressTest {

    private lateinit var context: Context
    private val tempCreatedFiles = mutableListOf<File>()
    private val activeServers = mutableListOf<TcpServer>()
    private val activeDatabases = mutableListOf<AppDatabase>()
    private val activeManagers = mutableListOf<ConnectionManager>()
    private val activeTransferManagers = mutableListOf<TransferManager>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        activeTransferManagers.forEach { it.cleanup() }
        activeTransferManagers.clear()
        activeServers.forEach { it.stop() }
        activeServers.clear()
        activeManagers.forEach { it.cleanup() }
        activeManagers.clear()
        activeDatabases.forEach { it.close() }
        activeDatabases.clear()
        StorageManager(context).deleteAllReceivedFiles()
        StorageManager(context).cleanAllTempFiles()
        tempCreatedFiles.forEach { it.deleteRecursively() }
        tempCreatedFiles.clear()
    }

    // -------------------------------------------------------------------------
    // 1. Protocol & Pairing Payload Hardening Tests
    // -------------------------------------------------------------------------

    @Test
    fun protocolMessage_validatesVersionsTimestampsNullsAndMissingTypes() {
        // v1 and v2 round-trip cleanly and map JSON null payload fields to Kotlin null
        val v2 = ProtocolMessage(
            version = "v2",
            type = ProtocolMessageType.TRANSFER_REQUEST,
            senderId = "tauri-win-node",
            receiverId = "android-node",
            timestamp = 1731000000000L,
            payload = mapOf("sessionId" to "sess-1", "relativePath" to null)
        )
        val decoded = ProtocolMessage.fromJsonString(v2.toJsonString())
        assertEquals("v2", decoded.version)
        assertEquals(ProtocolMessageType.TRANSFER_REQUEST, decoded.type)
        assertEquals("sess-1", decoded.payload["sessionId"])
        assertNull(decoded.payload["relativePath"])

        // Explicit JSON null payload value decodes to Kotlin null (never literal "null")
        val explicitNullJson = """{"version":"v2","type":"TRANSFER_REQUEST","senderId":"peer","timestamp":100,"payload":{"relativePath":null}}"""
        val decodedExplicitNull = ProtocolMessage.fromJsonString(explicitNullJson)
        assertTrue(decodedExplicitNull.payload.containsKey("relativePath"))
        assertNull(decodedExplicitNull.payload["relativePath"])

        // Reject unsupported version
        val badVersion = """{"version":"v99","type":"HEARTBEAT","senderId":"peer","timestamp":100}"""
        val badVerEx = runCatching { ProtocolMessage.fromJsonString(badVersion) }.exceptionOrNull()
        assertTrue(badVerEx is IllegalArgumentException)

        // Reject negative timestamp
        val badTimestamp = """{"version":"v2","type":"HEARTBEAT","senderId":"peer","timestamp":-5}"""
        val badTsEx = runCatching { ProtocolMessage.fromJsonString(badTimestamp) }.exceptionOrNull()
        assertTrue(badTsEx is IllegalArgumentException)

        // Reject missing / null type
        val nullType = """{"version":"v2","type":null,"senderId":"peer","timestamp":100}"""
        val nullTypeEx = runCatching { ProtocolMessage.fromJsonString(nullType) }.exceptionOrNull()
        assertTrue(nullTypeEx is IllegalArgumentException)
    }

    @Test
    fun pairingPayload_rejectsInvalidVersionsPortsNullsAndZeroExpiry() {
        val validV2 = PairingPayload(
            protocolVersion = "v2",
            sessionId = "sess-123",
            pairingToken = "tok-abc",
            deviceId = "tauri-mac-1",
            deviceName = "Mac Studio",
            platform = "macos",
            ipAddress = "192.168.1.42",
            port = 53317,
            expiresAt = System.currentTimeMillis() + 60_000L
        )
        val parsed = PairingPayload.fromJson(validV2.toJson())
        assertNotNull(parsed)
        assertEquals("v2", parsed!!.protocolVersion)
        assertFalse(parsed.isExpired)

        // Explicit JSON "null" field must be rejected rather than parsed as literal "null" string
        val explicitNullJson = """{"protocolVersion":"v2","sessionId":null,"pairingToken":"t","deviceId":"d","deviceName":"n","ipAddress":"127.0.0.1","port":53317,"expiresAt":9999999999999}"""
        assertNull(PairingPayload.fromJson(explicitNullJson))

        // Privileged or out-of-range ports rejected
        val lowPortJson = """{"protocolVersion":"v2","sessionId":"s","pairingToken":"t","deviceId":"d","deviceName":"n","ipAddress":"127.0.0.1","port":80,"expiresAt":9999999999999}"""
        assertNull(PairingPayload.fromJson(lowPortJson))
        val highPortJson = """{"protocolVersion":"v2","sessionId":"s","pairingToken":"t","deviceId":"d","deviceName":"n","ipAddress":"127.0.0.1","port":70000,"expiresAt":9999999999999}"""
        assertNull(PairingPayload.fromJson(highPortJson))

        // Unsupported version rejected
        val badVersionJson = """{"protocolVersion":"v9","sessionId":"s","pairingToken":"t","deviceId":"d","deviceName":"n","ipAddress":"127.0.0.1","port":53317,"expiresAt":9999999999999}"""
        assertNull(PairingPayload.fromJson(badVersionJson))

        // Zero/negative expiry rejected
        val zeroExpiryJson = """{"protocolVersion":"v2","sessionId":"s","pairingToken":"t","deviceId":"d","deviceName":"n","ipAddress":"127.0.0.1","port":53317,"expiresAt":0}"""
        assertNull(PairingPayload.fromJson(zeroExpiryJson))
    }

    @Test
    fun messageFraming_rejectsNegativeLengthAndMalformedJson() {
        val negativeHeader = java.nio.ByteBuffer.allocate(4).putInt(-10).array()
        val negEx = runCatching { MessageFraming.readFrame(ByteArrayInputStream(negativeHeader)) }.exceptionOrNull()
        assertTrue(negEx is IllegalArgumentException)

        val truncatedPayload = ByteArrayOutputStream().apply {
            write(java.nio.ByteBuffer.allocate(4).putInt(100).array())
            write("{\"partial\":true}".toByteArray())
        }.toByteArray()
        val eofEx = runCatching { MessageFraming.readFrame(ByteArrayInputStream(truncatedPayload)) }.exceptionOrNull()
        assertTrue(eofEx is java.io.EOFException)
        assertNull(MessageFraming.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    // -------------------------------------------------------------------------
    // 2. End-to-End Loopback TCP Transfer & Concurrency Stress Tests
    // -------------------------------------------------------------------------

    @Test
    fun endToEndTcpTransfer_interactiveApprovalZeroByteUnicodeAndMultiMbVerifiedBySha256() {
        runBlocking {
            val receiverEnv = createIsolatedTransferEnv("ReceiverPixel", "receiver-id")
            val senderEnv = createIsolatedTransferEnv("SenderAndroid", "sender-id")

            val port = findFreePort()
            receiverEnv.server.start(port)
            waitUntilTrue { receiverEnv.server.isRunning.value }

            val sourceDir = File(context.cacheDir, "p6_src_${UUID.randomUUID()}").apply {
                mkdirs()
                tempCreatedFiles.add(this)
            }

            // 1) Zero-byte file
            val zeroFile = File(sourceDir, "empty.txt").apply { writeBytes(ByteArray(0)) }
            // 2) Unicode & special characters filename
            val unicodeName = "résumé_🚀 (final).txt"
            val unicodeContent = "Cross-platform Tauri & Android UTF-8 payload: αβγδ ✓".toByteArray()
            val unicodeFile = File(sourceDir, unicodeName).apply { writeBytes(unicodeContent) }
            // 3) Multi-MB binary stream (> 2.5 MB across multiple 64KB chunks) + relative folder path
            val largeSize = (2 * 1024 * 1024) + 321_987
            val largeBytes = ByteArray(largeSize) { i -> ((i * 31 + 7) and 0xFF).toByte() }
            val largeFile = File(sourceDir, "payload_bundle.bin").apply { writeBytes(largeBytes) }

            val selectedFiles = listOf(
                com.example.presentation.transfer.SelectedFile(
                    uri = Uri.fromFile(zeroFile),
                    name = zeroFile.name,
                    size = zeroFile.length(),
                    mimeType = "text/plain"
                ),
                com.example.presentation.transfer.SelectedFile(
                    uri = Uri.fromFile(unicodeFile),
                    name = unicodeFile.name,
                    size = unicodeFile.length(),
                    mimeType = "text/plain",
                    relativePath = "Docs/2026/${unicodeFile.name}"
                ),
                com.example.presentation.transfer.SelectedFile(
                    uri = Uri.fromFile(largeFile),
                    name = largeFile.name,
                    size = largeFile.length(),
                    mimeType = "application/octet-stream",
                    relativePath = "Bundles/Binary/${largeFile.name}"
                )
            )

            val receiverTarget = Device(
                id = "receiver-id",
                alias = "ReceiverPixel",
                deviceType = DeviceType.ANDROID,
                ipAddress = "127.0.0.1",
                port = receiverEnv.server.listeningPort.value,
                isOnline = true
            )

            senderEnv.manager.sendFiles(selectedFiles, listOf(receiverTarget))

            // Verify interactive approval gate stops unknown/non-auto-accept transfers until approved
            val incomingReq = withTimeout(10_000L) {
                receiverEnv.manager.incomingRequest.first { it != null }!!
            }
            assertEquals(3, incomingReq.files.size)
            receiverEnv.manager.acceptIncomingTransfer(incomingReq)

            // Wait for all 3 transfers to persist as COMPLETED in receiver's Room history
            val receiverCompleted = withTimeout(30_000L) {
                receiverEnv.manager.transferHistory.first { list ->
                    list.count { it.status == TransferStatus.COMPLETED } == 3
                }
            }
            val senderCompleted = withTimeout(15_000L) {
                senderEnv.manager.transferHistory.first { list ->
                    list.count { it.status == TransferStatus.COMPLETED } == 3
                }
            }
            assertEquals(3, senderCompleted.size)

            // Byte-for-byte + SHA-256 verification on received files
            val receivedZero = File(receiverCompleted.first { it.fileName == "empty.txt" }.filePath!!)
            assertTrue(receivedZero.exists())
            assertEquals(0L, receivedZero.length())

            val receivedUnicode = File(receiverCompleted.first { it.fileName == unicodeName }.filePath!!)
            assertTrue(receivedUnicode.exists())
            assertTrue(receivedUnicode.absolutePath.contains("Docs/2026"))
            assertArrayEquals(unicodeContent, receivedUnicode.readBytes())

            val largeRecord = receiverCompleted.first { it.fileName == "payload_bundle.bin" }
            val receivedLarge = File(largeRecord.filePath!!)
            assertTrue(receivedLarge.exists())
            assertTrue(receivedLarge.absolutePath.contains("Bundles/Binary"))
            assertArrayEquals(largeBytes, receivedLarge.readBytes())
            val expectedLargeSha = FileStreamFraming.bytesToHex(
                MessageDigest.getInstance("SHA-256").digest(largeBytes)
            )
            assertEquals(expectedLargeSha, largeRecord.checksum)
        }
    }

    @Test
    fun endToEndTcpTransfer_checksumMismatchDeletesTempFileAndRecordsFailure() {
        runBlocking {
            val receiverEnv = createIsolatedTransferEnv("ChecksumReceiver", "rcvr-chk")
            val port = findFreePort()
            receiverEnv.server.start(port)
            waitUntilTrue { receiverEnv.server.isRunning.value }

            val badPayload = "Corrupted network bytes".toByteArray()
            val wrongDigest = ByteArray(32) { 0x42.toByte() } // deliberately wrong hash

            val socket = Socket("127.0.0.1", receiverEnv.server.listeningPort.value)
            socket.use { s ->
                val out = BufferedOutputStream(s.getOutputStream())
                val startMsg = ProtocolMessage(
                    type = ProtocolMessageType.TRANSFER_STARTED,
                    senderId = "rogue-sender",
                    receiverId = "rcvr-chk",
                    payload = mapOf(
                        "sessionId" to "sess-bad-hash",
                        "fileId" to "bad-hash-file-1",
                        "fileName" to "tampered.bin",
                        "fileSize" to badPayload.size.toLong(),
                        "mimeType" to "application/octet-stream",
                        "senderName" to "Tauri Peer"
                    )
                )
                MessageFraming.writeFrame(out, startMsg)
                FileStreamFraming.writeChunk(out, 0, 0L, badPayload, badPayload.size)
                FileStreamFraming.writeEof(out, 0, wrongDigest)
            }

            val failedList = withTimeout(10_000L) {
                receiverEnv.manager.transferHistory.first { list ->
                    list.any { it.id == "bad-hash-file-1" && it.status == TransferStatus.FAILED }
                }
            }
            val failed = failedList.first { it.id == "bad-hash-file-1" }
            assertTrue(failed.errorMessage?.contains("SHA-256", ignoreCase = true) == true)
            assertFalse(
                "Corrupted temp file must be deleted on SHA-256 mismatch",
                StorageManager(context).createTempFile("bad-hash-file-1").exists()
            )
            assertFalse(
                "Final destination must NOT be written for corrupted stream",
                File(StorageManager(context).getDownloadDirectory(), "tampered.bin").exists()
            )
        }
    }

    @Test
    fun tcpTransfer_concurrencyStressAndInteractiveRejectionDoNotDeadlock() {
        runBlocking {
            val receiverEnv = createIsolatedTransferEnv("StressReceiver", "rcvr-stress")
            val senderEnv = createIsolatedTransferEnv(
                alias = "StressSender",
                nodeId = "sndr-stress",
                initialSettings = AppSettings(deviceName = "StressSender", maxConcurrentTransfers = 2)
            )
            val port = findFreePort()
            receiverEnv.server.start(port)
            waitUntilTrue { receiverEnv.server.isRunning.value }

            val target = Device(
                id = "rcvr-stress",
                alias = "StressReceiver",
                deviceType = DeviceType.ANDROID,
                ipAddress = "127.0.0.1",
                port = receiverEnv.server.listeningPort.value,
                isOnline = true
            )

            // Phase A: Send a session and verify explicit rejection works synchronously & marks FAILED
            val rejectDir = File(context.cacheDir, "p6_reject_${UUID.randomUUID()}").apply {
                mkdirs()
                tempCreatedFiles.add(this)
            }
            val rejectFile = File(rejectDir, "declined.txt").apply { writeText("Decline me") }
            senderEnv.manager.sendFiles(
                listOf(
                    com.example.presentation.transfer.SelectedFile(
                        uri = Uri.fromFile(rejectFile),
                        name = rejectFile.name,
                        size = rejectFile.length(),
                        mimeType = "text/plain"
                    )
                ),
                listOf(target)
            )
            val req1 = withTimeout(10_000L) {
                receiverEnv.manager.incomingRequest.first { it != null }!!
            }
            receiverEnv.manager.rejectIncomingTransfer(req1, reason = "User clicked decline")
            val rejectedHistory = withTimeout(10_000L) {
                senderEnv.manager.transferHistory.first { list ->
                    list.any { it.fileName == "declined.txt" && it.status == TransferStatus.FAILED }
                }
            }
            assertEquals("User clicked decline", rejectedHistory.first { it.fileName == "declined.txt" }.errorMessage)

            // Phase B: Batch 6 concurrent files under maxConcurrentTransfers = 2 to prove slot queue never deadlocks
            val stressDir = File(context.cacheDir, "p6_stress_${UUID.randomUUID()}").apply {
                mkdirs()
                tempCreatedFiles.add(this)
            }
            val batchFiles = (1..6).map { idx ->
                val f = File(stressDir, "batch_$idx.dat").apply {
                    writeBytes(ByteArray(40 * 1024) { b -> ((b + idx) and 0xFF).toByte() })
                }
                com.example.presentation.transfer.SelectedFile(
                    uri = Uri.fromFile(f),
                    name = f.name,
                    size = f.length(),
                    mimeType = "application/octet-stream"
                )
            }

            senderEnv.manager.sendFiles(batchFiles, listOf(target))
            val req2 = withTimeout(10_000L) {
                receiverEnv.manager.incomingRequest.first { it != null && it.files.size == 6 }!!
            }
            receiverEnv.manager.acceptIncomingTransfer(req2)

            val receivedAll6 = withTimeout(30_000L) {
                receiverEnv.manager.transferHistory.first { list ->
                    list.count { it.status == TransferStatus.COMPLETED } == 6
                }
            }
            assertEquals(6, receivedAll6.count { it.status == TransferStatus.COMPLETED })
            assertEquals(0, senderEnv.manager.stats.value.activeCount)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Discovery Pruning, TLS ClientHello Resilience & Resume Safety Tests
    // -------------------------------------------------------------------------

    @Test
    fun nsdDiscoveryManager_prunesOfflineRealTauriPeersButPreservesSimulatedPeers() {
        val manager = NsdDiscoveryManager(context)
        try {
            val now = 1_000_000L
            manager.updateDiscoveryInterval(10) // stale window = 10 * 6000 = 60_000ms

            // Real Tauri peer whose lastSeen is stale (120s old)
            manager.upsertDiscoveredPeerForTest(
                key = "LocalDrop-TauriWin",
                device = Device(
                    id = "tauri-win-real-01",
                    alias = "Desktop Tauri",
                    deviceType = DeviceType.WINDOWS,
                    ipAddress = "192.168.1.33",
                    lastSeen = now - 120_000L
                )
            )
            // Fresh online peer (5s old)
            manager.upsertDiscoveredPeerForTest(
                key = "LocalDrop-MacStudio",
                device = Device(
                    id = "tauri-mac-fresh",
                    alias = "Mac Studio",
                    deviceType = DeviceType.MACOS,
                    ipAddress = "192.168.1.34",
                    lastSeen = now - 5_000L
                )
            )
            // Simulated demo peer (old timestamp, explicitly tracked in simulatedPeerIds)
            manager.addSimulatedPeer(
                Device(
                    id = "sim-peer-1",
                    alias = "Demo Tauri",
                    deviceType = DeviceType.LINUX,
                    ipAddress = "127.0.0.1",
                    lastSeen = 0L
                )
            )

            val removed = manager.pruneStalePeers(now = now)
            assertEquals(1, removed)
            val remainingIds = manager.discoveredDevices.value.map { it.id }.toSet()
            assertFalse("Stale real tauri-* peer must be pruned", "tauri-win-real-01" in remainingIds)
            assertTrue("Fresh Tauri peer must stay", "tauri-mac-fresh" in remainingIds)
            assertTrue("Explicit simulated peer must stay", "sim-peer-1" in remainingIds)
        } finally {
            manager.cleanup()
        }
    }

    @Test
    fun transferManager_validatePartialTempFileDeletesTamperedArtifactsAndPassesValid() {
        val env = createIsolatedTransferEnv("VerifyNode", "verify-id")
        val storage = StorageManager(context)
        val tmp = storage.createTempFile("partial-check")
        val bytes = "Deterministic partial content".toByteArray()
        tmp.writeBytes(bytes)
        val expectedHex = FileStreamFraming.bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        assertTrue(env.manager.validatePartialTempFile(tmp, expectedHex))
        assertTrue(tmp.exists())

        // Wrong expected checksum must invalidate & delete temp artifact
        assertFalse(env.manager.validatePartialTempFile(tmp, "0000000000000000"))
        assertFalse(tmp.exists())
    }

    @Test
    fun tcpServer_handlesTlsClientHelloWithoutFrameLengthCrashWhenNoSslConfigured() {
        runBlocking {
            val server = TcpServer().also { activeServers.add(it) }
            val port = findFreePort()
            server.start(port)
            waitUntilTrue { server.isRunning.value }

            // Send TLSv1.2 ClientHello prefix bytes (0x16 0x03 0x01 ...):
            // Previously readFrame interpreted 0x16030100 (369,295,616 bytes) as frame length.
            // Server must close cleanly and stay alive for subsequent JSON control connections!
            Socket("127.0.0.1", server.listeningPort.value).use { s ->
                s.soTimeout = 3_000
                s.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05))
                s.getOutputStream().flush()
                assertEquals(-1, s.getInputStream().read())
            }

            server.setStreamMessageHandler { msg, _, _ ->
                if (msg.type == ProtocolMessageType.HEARTBEAT) {
                    ProtocolMessage.createHeartbeat("server-alive")
                } else null
            }

            val client = TcpClient()
            val heartbeatResp = client.sendMessage(
                "127.0.0.1",
                server.listeningPort.value,
                ProtocolMessage.createHeartbeat("probe-client"),
                timeoutMs = 3_000
            )
            assertTrue("TcpServer must remain healthy after rejecting unconfigured TLS ClientHello", heartbeatResp.isSuccess)
            assertEquals("server-alive", heartbeatResp.getOrNull()?.senderId)
        }
    }

    @Test
    fun duplicateHandling_skipAndOverwriteWorkAsConfigured() {
        val storage = StorageManager(context)
        val dir = storage.getDownloadDirectory()
        val existing = File(dir, "dup_check.txt").apply { writeText("old") }
        assertTrue(existing.exists())

        val skipped = storage.resolveDestinationWithPolicy("dup_check.txt", "skip")
        assertTrue(skipped is StorageManager.DestinationResolution.Skipped)

        val overwrite = storage.resolveDestinationWithPolicy("dup_check.txt", "overwrite")
        assertTrue(overwrite is StorageManager.DestinationResolution.Ready)
        assertEquals(existing.canonicalPath, (overwrite as StorageManager.DestinationResolution.Ready).file.canonicalPath)

        val renamed = storage.resolveDestinationWithPolicy("dup_check.txt", "rename")
        assertTrue(renamed is StorageManager.DestinationResolution.Ready)
        assertTrue((renamed as StorageManager.DestinationResolution.Ready).file.name.startsWith("dup_check (1)"))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private data class IsolatedTransferEnv(
        val server: TcpServer,
        val db: AppDatabase,
        val manager: TransferManager,
        val connectionManager: ConnectionManager
    )

    private fun createIsolatedTransferEnv(
        alias: String,
        nodeId: String,
        initialSettings: AppSettings = AppSettings(deviceName = alias)
    ): IsolatedTransferEnv {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { activeDatabases.add(it) }
        val server = TcpServer().also { activeServers.add(it) }
        val client = TcpClient()
        val settingsRepo = InMemorySettingsRepository(initialSettings)
        val pairedRepo = InMemoryPairedDeviceRepository()
        val connMgr = ConnectionManager(
            client = client,
            myDeviceIdProvider = { nodeId }
        ).also { activeManagers.add(it) }
        val storage = StorageManager(context)
        val notif = TransferNotificationHelper(context)

        val transferManager = TransferManager(
            context = context,
            server = server,
            client = client,
            storageManager = storage,
            transferDao = db.transferDao(),
            pairedDeviceRepository = pairedRepo,
            settingsRepository = settingsRepo,
            connectionManager = connMgr,
            notificationHelper = notif,
            myDeviceIdProvider = { nodeId }
        ).also { activeTransferManagers.add(it) }
        server.setStreamMessageHandler { msg, socket, input ->
            transferManager.handleProtocolMessage(msg, socket, input)
        }
        return IsolatedTransferEnv(server, db, transferManager, connMgr)
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitUntilTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Timed out waiting for condition")
            }
            kotlinx.coroutines.delay(25L)
        }
    }

    private class InMemorySettingsRepository(initial: AppSettings) : SettingsRepository {
        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

        override suspend fun setDeviceName(name: String) { _settings.value = _settings.value.copy(deviceName = name) }
        override suspend fun setThemeMode(mode: ThemeMode) { _settings.value = _settings.value.copy(themeMode = mode) }
        override suspend fun setAccentColor(option: AccentColorOption) { _settings.value = _settings.value.copy(accentColor = option) }
        override suspend fun setAutoStartReceiving(enabled: Boolean) { _settings.value = _settings.value.copy(autoStartReceiving = enabled) }
        override suspend fun setAutoAcceptTrusted(enabled: Boolean) { _settings.value = _settings.value.copy(autoAcceptTrusted = enabled) }
        override suspend fun setAskBeforeReceiving(enabled: Boolean) { _settings.value = _settings.value.copy(askBeforeReceiving = enabled) }
        override suspend fun setDownloadLocation(path: String, treeUri: String?) {
            _settings.value = _settings.value.copy(downloadLocation = path, customDownloadTreeUri = treeUri)
        }
        override suspend fun setPort(port: Int) { _settings.value = _settings.value.copy(port = port) }
        override suspend fun setBroadcastEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(broadcastEnabled = enabled) }
        override suspend fun setShowNotifications(enabled: Boolean) { _settings.value = _settings.value.copy(showNotifications = enabled) }
        override suspend fun setDiscoveryIntervalSeconds(seconds: Int) { _settings.value = _settings.value.copy(discoveryIntervalSeconds = seconds) }
        override suspend fun setConnectionTimeoutMs(timeoutMs: Int) { _settings.value = _settings.value.copy(connectionTimeoutMs = timeoutMs) }
        override suspend fun setMaxConcurrent(count: Int) { _settings.value = _settings.value.copy(maxConcurrentTransfers = count) }
        override suspend fun setTransferBufferSizeKb(sizeKb: Int) { _settings.value = _settings.value.copy(transferBufferSizeKb = sizeKb) }
        override suspend fun setDuplicateHandling(handling: String) { _settings.value = _settings.value.copy(duplicateHandling = handling) }
        override suspend fun setPreserveOriginalNames(enabled: Boolean) { _settings.value = _settings.value.copy(preserveOriginalNames = enabled) }
        override suspend fun setAutoOpenCompleted(enabled: Boolean) { _settings.value = _settings.value.copy(autoOpenCompleted = enabled) }
        override suspend fun setMaxAcceptedFileSizeMb(sizeMb: Long) { _settings.value = _settings.value.copy(maxAcceptedFileSizeMb = sizeMb) }
        override suspend fun setRequireApprovalUnknown(enabled: Boolean) { _settings.value = _settings.value.copy(requireApprovalUnknown = enabled) }
        override suspend fun setTlsPreferred(enabled: Boolean) { _settings.value = _settings.value.copy(tlsPreferred = enabled) }
        override suspend fun setHapticsEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(hapticsEnabled = enabled) }
        override suspend fun setSuggestRecentDevices(enabled: Boolean) { _settings.value = _settings.value.copy(suggestRecentDevices = enabled) }
        override suspend fun setRememberLastDestination(enabled: Boolean) { _settings.value = _settings.value.copy(rememberLastDestination = enabled) }
        override suspend fun setLastDestinationDeviceId(deviceId: String?) { _settings.value = _settings.value.copy(lastDestinationDeviceId = deviceId) }
        override suspend fun setOrganizeReceivedByType(enabled: Boolean) { _settings.value = _settings.value.copy(organizeReceivedByType = enabled) }
        override suspend fun setAutoCleanTempFiles(enabled: Boolean) { _settings.value = _settings.value.copy(autoCleanTempFiles = enabled) }
    }

    private class InMemoryPairedDeviceRepository : PairedDeviceRepository {
        private val devices = MutableStateFlow<List<PairedDevice>>(emptyList())
        private val tokens = mutableMapOf<String, String>()
        override val pairedDevices: Flow<List<PairedDevice>> = devices.asStateFlow()

        override suspend fun getPairedDevice(id: String) = devices.value.find { it.id == id }
        override suspend fun savePairedDevice(device: PairedDevice, rawAuthToken: String) {
            tokens[device.id] = rawAuthToken
            devices.value = devices.value.filterNot { it.id == device.id } + device
        }
        override suspend fun getAuthToken(deviceId: String) = tokens[deviceId]
        override suspend fun removePairedDevice(id: String) {
            tokens.remove(id)
            devices.value = devices.value.filterNot { it.id == id }
        }
        override suspend fun renameDevice(id: String, newAlias: String) {}
        override suspend fun updateLastConnected(id: String) {}
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setAutoAccept(id: String, autoAccept: Boolean) {}
        override suspend fun incrementUsage(id: String) {}
        override suspend fun updateNetworkEndpoint(id: String, ipAddress: String, port: Int) {}
        override suspend fun clearAllDevices() {
            tokens.clear()
            devices.value = emptyList()
        }
    }
}
