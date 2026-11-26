package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.PairedDeviceEntity
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.network.TcpClient
import com.example.data.network.TcpServer
import com.example.data.network.TlsTransport
import com.example.data.pairing.PairingManager
import com.example.data.pairing.PairingPayload
import com.example.data.pairing.PairingResult
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolConstants
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.security.SecurityKeyStore
import com.example.data.security.SecurityValidator
import com.example.data.transfer.FileMetadata
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.data.transfer.TransferNotificationHelper
import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppSettings
import com.example.domain.model.Device
import com.example.domain.model.DeviceType
import com.example.domain.model.PairedDevice
import com.example.domain.model.ThemeMode
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.presentation.transfer.SelectedFile
import com.example.util.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase9CrossPlatformInteropTest {

    private lateinit var context: Context
    private val tempDirs = mutableListOf<File>()
    private val activeServers = mutableListOf<TcpServer>()
    private val activeDatabases = mutableListOf<AppDatabase>()
    private val activeManagers = mutableListOf<ConnectionManager>()
    private val activeTransferManagers = mutableListOf<TransferManager>()
    private val nodeProcesses = mutableListOf<Process>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SecurityValidator.clearRateLimiters()
    }

    @After
    fun teardown() {
        nodeProcesses.forEach { proc ->
            try {
                if (proc.isAlive) proc.destroyForcibly()
            } catch (_: Exception) {}
        }
        nodeProcesses.clear()
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
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
        SecurityValidator.clearRateLimiters()
    }

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(cwd, cwd.parentFile, File("/app/applet"))
            .filterNotNull()
            .first { File(it, "protocol/localdrop-protocol.schema.json").exists() }
    }

    // -------------------------------------------------------------------------
    // 1. Shared Protocol Schema & Canonical Golden Fixture Tests
    // -------------------------------------------------------------------------

    @Test
    fun canonicalFixtures_deserializeCleanlyIntoAndroidProtocolModels() {
        val root = resolveProjectRoot()
        val fixturesDir = File(root, "protocol/fixtures")
        assertTrue(fixturesDir.isDirectory)

        val pairingFixtures = JSONObject(File(fixturesDir, "pairing_and_auth.json").readText())
        val qrJson = pairingFixtures.getJSONObject("qrPayloadV2").toString()
        val qrPayload = PairingPayload.fromJson(qrJson)
        assertNotNull(qrPayload)
        assertEquals("v2", qrPayload!!.protocolVersion)
        assertEquals("windows", qrPayload.platform)
        assertTrue(qrPayload.tlsSupported)
        assertNotNull(qrPayload.certFingerprint)

        val uriParsed = PairingPayload.fromRawInput(pairingFixtures.getString("pairingUriV2"))
        assertNotNull(uriParsed)
        assertEquals(qrPayload.sessionId, uriParsed!!.sessionId)
        assertEquals(qrPayload.pairingToken, uriParsed.pairingToken)
        assertEquals(qrPayload.certFingerprint, uriParsed.certFingerprint)

        for (key in listOf("pairingRequest", "pairingAccepted", "authenticationRequest", "authenticationResponse")) {
            val msg = ProtocolMessage.fromJsonString(pairingFixtures.getJSONObject(key).toString())
            assertEquals("v2", msg.version)
            assertTrue(msg.senderId.isNotBlank())
        }

        val transferFixtures = JSONObject(File(fixturesDir, "transfer_lifecycle.json").readText())
        for (key in listOf("transferRequest", "transferAccepted", "transferStarted", "transferReady", "fileConflict", "transferCompleted")) {
            val raw = transferFixtures.getJSONObject(key).toString()
            val decoded = ProtocolMessage.fromJsonString(raw)
            assertEquals("v2", decoded.version)
        }
        val reqFiles = FileMetadata.fromJsonList(
            transferFixtures.getJSONObject("transferRequest").getJSONObject("payload").getJSONArray("files")
        )
        assertEquals(2, reqFiles.size)
        assertEquals("ProjectDocs/2026/report.pdf", reqFiles[0].relativePath)
        assertTrue(reqFiles[1].isDirectory)

        val errorFixtures = JSONObject(File(fixturesDir, "error_responses.json").readText())
        val unsupported = ProtocolMessage.fromJsonString(errorFixtures.getJSONObject("unsupportedVersion").toString())
        assertEquals(ProtocolMessageType.ERROR, unsupported.type)
        assertEquals(ProtocolConstants.ErrorCode.ERR_UNSUPPORTED_VERSION, unsupported.payload["code"])

        assertEquals("v2", ProtocolConstants.negotiateProtocolVersion("v2", "v2"))
        assertEquals("v1", ProtocolConstants.negotiateProtocolVersion("v1", "v2"))
        assertNull(ProtocolConstants.negotiateProtocolVersion("v99", "v2"))
    }

    // -------------------------------------------------------------------------
    // 2. Cross-OS Filename Sanitization & Case-Insensitive Collision Safety
    // -------------------------------------------------------------------------

    @Test
    fun fileMetadata_sanitizesWindowsIllegalControlCharsTrailingDotsAndDosReservedNames_preservingUnicode() {
        assertEquals("résumé_🚀 (final).txt", FileMetadata.sanitizeFileName("résumé_🚀 (final).txt"))
        assertEquals("report_2026__final_.pdf", FileMetadata.sanitizeFileName("report<2026>:final*.pdf"))
        assertEquals("notes.txt", FileMetadata.sanitizeFileName("notes.txt..   "))
        assertEquals("_CON.txt", FileMetadata.sanitizeFileName("CON.txt"))
        assertEquals("_nul", FileMetadata.sanitizeFileName("nul"))
        assertEquals("_COM1.log", FileMetadata.sanitizeFileName("COM1.log"))
        assertEquals("Trips/Day_1_/_PRN.txt", FileMetadata.sanitizeRelativePath("/Trips/Day<1>/PRN.txt.."))
    }

    @Test
    fun storageManager_handlesCaseInsensitiveSiblingCollisionsConsistentAcrossPolicies() {
        val storage = StorageManager(context)
        val downloadDir = storage.getDownloadDirectory()
        val initial = File(downloadDir, "Photo.jpg").apply { writeText("original") }
        assertTrue(initial.exists())

        val peek = storage.peekExistingDestination("photo.jpg")
        assertNotNull(peek)
        assertEquals("Photo.jpg", peek!!.name)

        val skipRes = storage.resolveDestinationWithPolicy("photo.jpg", duplicateHandling = "skip")
        assertTrue(skipRes is StorageManager.DestinationResolution.Skipped)

        val renameRes = storage.resolveDestinationWithPolicy("photo.jpg", duplicateHandling = "rename")
        assertTrue(renameRes is StorageManager.DestinationResolution.Ready)
        assertEquals("photo (1).jpg", (renameRes as StorageManager.DestinationResolution.Ready).file.name)

        val overwriteRes = storage.resolveDestinationWithPolicy("photo.jpg", duplicateHandling = "overwrite")
        assertTrue(overwriteRes is StorageManager.DestinationResolution.Ready)
        assertEquals("photo.jpg", (overwriteRes as StorageManager.DestinationResolution.Ready).file.name)
    }

    // -------------------------------------------------------------------------
    // 3. Room v5 -> v6 Non-Destructive Migration
    // -------------------------------------------------------------------------

    @Test
    fun roomMigration_5to6_preservesRowsAndAddsTlsColumns() {
        val dbName = "localdrop_mig_5_6_${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `paired_devices` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `alias` TEXT NOT NULL,
                            `deviceType` TEXT NOT NULL,
                            `ipAddress` TEXT NOT NULL,
                            `port` INTEGER NOT NULL,
                            `encryptedAuthToken` TEXT NOT NULL,
                            `isTrusted` INTEGER NOT NULL,
                            `pairedAt` INTEGER NOT NULL,
                            `lastConnectedAt` INTEGER NOT NULL,
                            `model` TEXT,
                            `isFavorite` INTEGER NOT NULL,
                            `useCount` INTEGER NOT NULL,
                            `lastTransferredAt` INTEGER NOT NULL DEFAULT 0,
                            `autoAccept` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `transfer_history` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `sessionId` TEXT NOT NULL DEFAULT '',
                            `fileName` TEXT NOT NULL,
                            `fileExtension` TEXT NOT NULL DEFAULT '',
                            `mimeType` TEXT,
                            `fileSize` INTEGER NOT NULL DEFAULT 0,
                            `senderDevice` TEXT NOT NULL DEFAULT '',
                            `receiverDevice` TEXT NOT NULL DEFAULT '',
                            `direction` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `startTime` INTEGER NOT NULL DEFAULT 0,
                            `completionTime` INTEGER NOT NULL DEFAULT 0,
                            `durationMs` INTEGER NOT NULL DEFAULT 0,
                            `speed` TEXT,
                            `checksum` TEXT,
                            `filePath` TEXT,
                            `peerDeviceName` TEXT NOT NULL DEFAULT '',
                            `peerAddress` TEXT NOT NULL DEFAULT '',
                            `errorMessage` TEXT,
                            `timestamp` INTEGER NOT NULL DEFAULT 0,
                            `relativePath` TEXT,
                            `avgSpeedBytesPerSec` INTEGER NOT NULL DEFAULT 0,
                            `peakSpeedBytesPerSec` INTEGER NOT NULL DEFAULT 0,
                            `priority` TEXT NOT NULL DEFAULT 'NORMAL',
                            `queueOrder` INTEGER NOT NULL DEFAULT 0,
                            `bytesTransferred` INTEGER NOT NULL DEFAULT 0,
                            `isPaused` INTEGER NOT NULL DEFAULT 0,
                            `retryCount` INTEGER NOT NULL DEFAULT 0,
                            `isDirectory` INTEGER NOT NULL DEFAULT 0,
                            `partialChecksum` TEXT,
                            `sourceUri` TEXT
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `paired_devices` VALUES (
                            'tauri-1', 'Workstation', 'windows', '192.168.1.50', 53317, 'enc_tok',
                            1, 1000, 2000, 'Windows 11', 1, 3, 0, 0
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val legacyDb = helper.writableDatabase
        AppDatabase.MIGRATION_5_6.migrate(legacyDb)
        AppDatabase.MIGRATION_5_6.migrate(legacyDb) // Idempotency check
        legacyDb.close()
        helper.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        activeDatabases.add(roomDb)

        runBlocking {
            val loaded = roomDb.pairedDeviceDao().getById("tauri-1")
            assertNotNull(loaded)
            assertEquals("Workstation", loaded!!.alias)
            assertFalse(loaded.tlsSupported)
            assertNull(loaded.certFingerprint)

            roomDb.pairedDeviceDao().updateTlsInfo("tauri-1", "a".repeat(64), true)
            val updated = roomDb.pairedDeviceDao().getById("tauri-1")
            assertTrue(updated!!.tlsSupported)
            assertEquals("a".repeat(64), updated.certFingerprint)
        }
        roomDb.close()
        dbFile.delete()
    }

    // -------------------------------------------------------------------------
    // 4. Live Android <-> Node.js Reference Peer Wire Interoperability (TCP + TLS)
    // -------------------------------------------------------------------------

    @Test
    fun androidClient_pairsAuthenticatesAndTransfersWithNodeReferencePeerOverPinnedTls() {
        runBlocking {
            val root = resolveProjectRoot()
            val recvDir = File(context.cacheDir, "node_recv_${UUID.randomUUID()}").apply {
                mkdirs()
                tempDirs.add(this)
            }
            val sessionId = "qr-sess-${UUID.randomUUID()}"
            val qrToken = "qr-secret-${UUID.randomUUID()}"
            val nodePeer = spawnNodeReferencePeer(
                root = root,
                receiveDir = recvDir,
                sessionId = sessionId,
                pairingToken = qrToken,
                tls = true
            )
            try {
                val env = createAndroidHarness(
                    nodeId = "android-interop-client",
                    autoAcceptTrusted = false,
                    enableTls = true
                )

                // A. Fingerprint mismatch MUST fail TLS handshake cleanly
                val badFpPayload = PairingPayload(
                    protocolVersion = "v2",
                    sessionId = sessionId,
                    pairingToken = qrToken,
                    deviceId = nodePeer.deviceId,
                    deviceName = nodePeer.deviceName,
                    platform = "linux",
                    ipAddress = "127.0.0.1",
                    port = nodePeer.port,
                    expiresAt = System.currentTimeMillis() + 60_000L,
                    tlsSupported = true,
                    certFingerprint = "0".repeat(64)
                )
                env.pairingManager.pairWithScannedDevice(badFpPayload)
                assertTrue(
                    "Mismatched fingerprint must be rejected",
                    env.pairingManager.pairingResult.value is PairingResult.Failure
                )

                // B. Valid fingerprint pairs over TLS v2 & stores mutual token + fingerprint
                val validPayload = badFpPayload.copy(certFingerprint = nodePeer.certFingerprint)
                env.pairingManager.pairWithScannedDevice(validPayload)
                val pairRes = env.pairingManager.pairingResult.value
                assertTrue("Expected pairing success but got $pairRes", pairRes is PairingResult.Success)
                val paired = (pairRes as PairingResult.Success).device
                assertTrue(paired.tlsSupported)
                assertEquals(nodePeer.certFingerprint, paired.certFingerprint)

                // C. Replaying the exact same single-use QR payload MUST be rejected (ERR_PAIRING_REPLAY)
                env.pairingManager.resetPairingResult()
                env.pairingManager.pairWithScannedDevice(validPayload)
                val replayRes = env.pairingManager.pairingResult.value
                assertTrue("QR replay should fail, got $replayRes", replayRes is PairingResult.Failure)

                // D. ConnectionManager connects & pings with nonce over pinned TLS v2
                val authToken = env.pairedRepo.getAuthToken(paired.id)
                assertTrue(env.connectionManager.connect(paired, authToken))
                assertTrue(env.connectionManager.getConnectionState(paired.id) is ConnectionState.Connected)
                assertTrue(env.connectionManager.ping(paired))

                // E. Send multi-file batch + folder hierarchy + duplicate collision from Android -> Node over TLS
                val file1 = File(context.cacheDir, "android_send_1.bin").apply {
                    writeBytes(ByteArray(180 * 1024) { i -> (i * 17).toByte() })
                    tempDirs.add(this)
                }
                val file2 = File(context.cacheDir, "android_send_dup.txt").apply {
                    writeText("first version")
                    tempDirs.add(this)
                }
                val expectedSha1 = sha256Hex(file1.readBytes())

                val targetDevice = Device(
                    id = nodePeer.deviceId,
                    alias = nodePeer.deviceName,
                    deviceType = DeviceType.LINUX,
                    ipAddress = "127.0.0.1",
                    port = nodePeer.port,
                    protocolVersion = "v2",
                    tlsSupported = true,
                    certFingerprint = nodePeer.certFingerprint
                )

                val sel1 = SelectedFile(
                    uri = Uri.fromFile(file1),
                    name = "android_send_1.bin",
                    size = file1.length(),
                    mimeType = "application/octet-stream",
                    relativePath = "AndroidExport/Sub/android_send_1.bin",
                    isAccessible = true
                )
                val sel2 = SelectedFile(
                    uri = Uri.fromFile(file2),
                    name = "android_send_dup.txt",
                    size = file2.length(),
                    mimeType = "text/plain",
                    isAccessible = true
                )
                val emptyDir = SelectedFile(
                    uri = Uri.EMPTY,
                    name = "EmptySubdir",
                    size = 0L,
                    mimeType = "vnd.android.document/directory",
                    relativePath = "AndroidExport/EmptySubdir",
                    isDirectory = true,
                    isAccessible = true
                )

                env.transferManager.sendFiles(listOf(sel1, sel2, emptyDir), listOf(targetDevice))

                withTimeout(25_000L) {
                    while (env.transferManager.activeTransfers.value.count {
                            it.status == TransferStatus.COMPLETED
                        } < 3
                    ) {
                        delay(50)
                    }
                }

                val savedFile1 = File(recvDir, "AndroidExport/Sub/android_send_1.bin")
                assertTrue(savedFile1.exists())
                assertEquals(expectedSha1, sha256Hex(savedFile1.readBytes()))
                assertTrue(File(recvDir, "AndroidExport/EmptySubdir").isDirectory)
                assertTrue(File(recvDir, "android_send_dup.txt").exists())

                // Send same filename again -> Node resolves collision and emits FILE_CONFLICT + renamed file
                val file2Again = File(context.cacheDir, "android_send_dup.txt").apply {
                    writeText("second version")
                    tempDirs.add(this)
                }
                val sel2Again = SelectedFile(
                    uri = Uri.fromFile(file2Again),
                    name = "android_send_dup.txt",
                    size = file2Again.length(),
                    mimeType = "text/plain",
                    isAccessible = true
                )
                env.transferManager.sendFiles(listOf(sel2Again), listOf(targetDevice))
                withTimeout(15_000L) {
                    while (env.transferManager.activeTransfers.value.count {
                            it.status == TransferStatus.COMPLETED
                        } < 4
                    ) {
                        delay(50)
                    }
                }
                assertTrue(File(recvDir, "android_send_dup (1).txt").exists())
                assertEquals("second version", File(recvDir, "android_send_dup (1).txt").readText())
            } finally {
                nodePeer.close()
            }
        }
    }

    @Test
    fun nodeReferencePeer_pairsAuthenticatesAndStreamsIntoAndroidOverPinnedTlsWithValidation() {
        runBlocking {
            val root = resolveProjectRoot()
            val androidEnv = createAndroidHarness(
                nodeId = "android-server-node",
                autoAcceptTrusted = true,
                enableTls = true
            )
            val androidPort = androidEnv.serverPort
            val androidFp = androidEnv.tlsIdentity!!.certFingerprintSha256

            val nodePeer = spawnNodeReferencePeer(root = root, tls = true)
            try {
                // 1. Node sends unsupported protocol version v99 over TLS -> Android returns ERR_UNSUPPORTED_VERSION
                val badVerCmd = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send_raw_control")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put(
                            "message",
                            JSONObject().apply {
                                put("version", "v99")
                                put("type", "HEARTBEAT")
                                put("messageId", UUID.randomUUID().toString())
                                put("senderId", nodePeer.deviceId)
                                put("timestamp", System.currentTimeMillis())
                                put("payload", JSONObject())
                            }
                        )
                    }
                )
                assertTrue(badVerCmd.getBoolean("ok"))
                val badVerReply = badVerCmd.getJSONObject("reply")
                assertEquals("ERROR", badVerReply.getString("type"))
                assertEquals(
                    ProtocolConstants.ErrorCode.ERR_UNSUPPORTED_VERSION,
                    badVerReply.getJSONObject("payload").getString("code")
                )

                // 2. Android generates QR -> Node authenticates/pairs into Android over TLS, then auth replay rejected
                val session = androidEnv.pairingManager.generateNewPairingSession()
                val pairCmd = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send_raw_control")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put(
                            "message",
                            JSONObject().apply {
                                put("version", "v2")
                                put("type", "PAIRING_REQUEST")
                                put("messageId", UUID.randomUUID().toString())
                                put("senderId", nodePeer.deviceId)
                                put("receiverId", "android-server-node")
                                put("timestamp", System.currentTimeMillis())
                                put(
                                    "payload",
                                    JSONObject().apply {
                                        put("sessionId", session.sessionId)
                                        put("token", session.token)
                                        put("deviceName", nodePeer.deviceName)
                                        put("platform", "linux")
                                        put("tlsSupported", true)
                                        put("certFingerprint", nodePeer.certFingerprint)
                                    }
                                )
                            }
                        )
                    }
                )
                val pairReply = pairCmd.getJSONObject("reply")
                assertEquals("PAIRING_ACCEPTED", pairReply.getString("type"))
                val mutualToken = pairReply.getJSONObject("payload").getString("authToken")

                // Replay QR attempt rejected with ERR_PAIRING_REPLAY
                val qrReplayCmd = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send_raw_control")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put(
                            "message",
                            JSONObject().apply {
                                put("version", "v2")
                                put("type", "PAIRING_REQUEST")
                                put("messageId", UUID.randomUUID().toString())
                                put("senderId", "rogue-peer")
                                put("timestamp", System.currentTimeMillis())
                                put(
                                    "payload",
                                    JSONObject().apply {
                                        put("sessionId", session.sessionId)
                                        put("token", session.token)
                                    }
                                )
                            }
                        )
                    }
                )
                val qrReplayReply = qrReplayCmd.getJSONObject("reply")
                assertEquals("PAIRING_REJECTED", qrReplayReply.getString("type"))
                assertEquals(
                    ProtocolConstants.ErrorCode.ERR_PAIRING_REPLAY,
                    qrReplayReply.getJSONObject("payload").getString("code")
                )

                // Auth with valid nonce succeeds, then exact same nonce replays -> ERR_REPLAY_DETECTED
                val nonce = "auth-nonce-${UUID.randomUUID()}"
                val authPayload = JSONObject().apply {
                    put("version", "v2")
                    put("type", "AUTHENTICATION_REQUEST")
                    put("messageId", UUID.randomUUID().toString())
                    put("senderId", nodePeer.deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put(
                        "payload",
                        JSONObject().apply {
                            put("token", mutualToken)
                            put("timestamp", System.currentTimeMillis())
                            put("nonce", nonce)
                        }
                    )
                }
                val authOk = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send_raw_control")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put("message", authPayload)
                    }
                ).getJSONObject("reply")
                assertEquals("AUTHENTICATION_RESPONSE", authOk.getString("type"))

                val authReplay = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send_raw_control")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put("message", authPayload)
                    }
                ).getJSONObject("reply")
                assertEquals("ERROR", authReplay.getString("type"))
                assertEquals(
                    ProtocolConstants.ErrorCode.ERR_REPLAY_DETECTED,
                    authReplay.getJSONObject("payload").getString("code")
                )

                // 3. Node streams file with Windows illegal characters over TLS into Android (autoAcceptTrusted)
                val rawData = ByteArray(400 * 1024) { idx -> ((idx * 31) xor 0x5A).toByte() }
                val sendRes = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put("senderId", nodePeer.deviceId)
                        put("receiverId", "android-server-node")
                        put("fileName", "win<bad>:file?.bin")
                        put("relativePath", "WinFolder/Sub<1>/win<bad>:file?.bin")
                        put("base64Data", android.util.Base64.encodeToString(rawData, android.util.Base64.NO_WRAP))
                    }
                )
                assertTrue("Send failed: $sendRes", sendRes.getBoolean("ok"))
                val acks = sendRes.getJSONObject("result").getJSONArray("acks")
                val lastAck = acks.getJSONObject(acks.length() - 1)
                assertEquals("TRANSFER_COMPLETED", lastAck.getString("type"))
                assertEquals("win_bad__file_.bin", lastAck.getJSONObject("payload").getString("finalFileName"))
                assertEquals(sha256Hex(rawData), lastAck.getJSONObject("payload").getString("sha256"))

                val savedOnAndroid = File(
                    androidEnv.storageManager.getDownloadDirectory(),
                    "WinFolder/Sub_1_/win_bad__file_.bin"
                )
                assertTrue(savedOnAndroid.exists())
                assertArrayEquals(rawData, savedOnAndroid.readBytes())

                // 4. Node sends corrupted EOF SHA-256 -> Android replies TRANSFER_FAILED ERR_CHECKSUM_MISMATCH
                val corruptRes = nodePeer.sendCommand(
                    JSONObject().apply {
                        put("cmd", "send")
                        put("host", "127.0.0.1")
                        put("port", androidPort)
                        put("useTls", true)
                        put("expectedFp", androidFp)
                        put("senderId", nodePeer.deviceId)
                        put("receiverId", "android-server-node")
                        put("fileName", "corrupt_from_node.bin")
                        put("base64Data", android.util.Base64.encodeToString("abc".toByteArray(), android.util.Base64.NO_WRAP))
                        put("corruptEofChecksum", true)
                    }
                )
                assertTrue(corruptRes.getBoolean("ok"))
                val corruptAcks = corruptRes.getJSONObject("result").getJSONArray("acks")
                val failAck = corruptAcks.getJSONObject(0)
                assertEquals("TRANSFER_FAILED", failAck.getString("type"))
                assertEquals(
                    ProtocolConstants.ErrorCode.ERR_CHECKSUM_MISMATCH,
                    failAck.getJSONObject("payload").getString("code")
                )
                assertFalse(File(androidEnv.storageManager.getDownloadDirectory(), "corrupt_from_node.bin").exists())
            } finally {
                nodePeer.close()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. Stress, Large Stream, >4 GB Math & Socket Leak Verification
    // -------------------------------------------------------------------------

    @Test
    fun stressBatch_100SmallFilesAnd10MbStream_zeroSocketLeakAndExactChecksums() {
        runBlocking {
            val monitor = PerformanceMonitor()
            val senderEnv = createAndroidHarness(
                nodeId = "android-stress-sender",
                autoAcceptTrusted = false,
                enableTls = true,
                performanceMonitor = monitor,
                enableRateLimiting = false
            )
            val receiverEnv = createAndroidHarness(
                nodeId = "android-stress-receiver",
                autoAcceptTrusted = true,
                enableTls = true,
                performanceMonitor = monitor,
                enableRateLimiting = false
            )

            // Pair receiver trust so autoAcceptTrusted handles incoming request smoothly
            receiverEnv.pairedRepo.savePairedDevice(
                PairedDevice(
                    id = "android-stress-sender",
                    alias = "Sender",
                    deviceType = DeviceType.ANDROID,
                    ipAddress = "127.0.0.1",
                    port = senderEnv.serverPort,
                    isTrusted = true,
                    certFingerprint = senderEnv.tlsIdentity!!.certFingerprintSha256,
                    tlsSupported = true
                ),
                "shared-tok"
            )

            val target = Device(
                id = "android-stress-receiver",
                alias = "Receiver",
                deviceType = DeviceType.ANDROID,
                ipAddress = "127.0.0.1",
                port = receiverEnv.serverPort,
                protocolVersion = "v2",
                tlsSupported = true,
                certFingerprint = receiverEnv.tlsIdentity!!.certFingerprintSha256
            )

            // Part A: 100 small files in a single batch
            val batchDir = File(context.cacheDir, "stress_100_${UUID.randomUUID()}").apply {
                mkdirs()
                tempDirs.add(this)
            }
            val batchFiles = (1..100).map { i ->
                val f = File(batchDir, "small_$i.txt").apply {
                    writeText("batch-payload-$i-${UUID.randomUUID()}")
                }
                SelectedFile(
                    uri = Uri.fromFile(f),
                    name = f.name,
                    size = f.length(),
                    mimeType = "text/plain",
                    isAccessible = true
                )
            }

            senderEnv.transferManager.sendFiles(batchFiles, listOf(target))

            withTimeout(45_000L) {
                while (receiverEnv.transferManager.activeTransfers.value.count {
                        it.status == TransferStatus.COMPLETED
                    } < 100 ||
                    senderEnv.transferManager.activeTransfers.value.count {
                        it.status == TransferStatus.COMPLETED
                    } < 100
                ) {
                    delay(100)
                }
            }

            // Part B: 10 MB continuous stream over TLS (exercises multiple 256 KB O(1) checkpoints)
            val tenMbBytes = ByteArray(10 * 1024 * 1024) { i -> ((i * 13) + (i ushr 8)).toByte() }
            val tenMbExpectedSha = sha256Hex(tenMbBytes)
            val largeFile = File(batchDir, "stream_10mb.bin").apply { writeBytes(tenMbBytes) }
            val selectedLarge = SelectedFile(
                uri = Uri.fromFile(largeFile),
                name = largeFile.name,
                size = largeFile.length(),
                mimeType = "application/octet-stream",
                isAccessible = true
            )

            senderEnv.transferManager.sendFiles(listOf(selectedLarge), listOf(target))

            withTimeout(45_000L) {
                while (receiverEnv.transferManager.activeTransfers.value.count {
                        it.fileName == "stream_10mb.bin" && it.status == TransferStatus.COMPLETED
                    } < 1
                ) {
                    delay(100)
                }
            }

            val receivedLarge = receiverEnv.transferManager.activeTransfers.value
                .first { it.fileName == "stream_10mb.bin" && it.status == TransferStatus.COMPLETED }
            assertEquals(tenMbExpectedSha, receivedLarge.checksum)
            assertEquals(10L * 1024L * 1024L, receivedLarge.fileSize)

            // Wait briefly for socket close finally blocks
            delay(300)
            assertEquals(0, receiverEnv.server.activeSocketCount)
            assertEquals(0, senderEnv.server.activeSocketCount)

            // Verify >4 GB (5 GB) TransferItem progress & ETA formatting without 32-bit overflow
            val fiveGb = 5_000_000_000L
            val halfProgress = (2_500_000_000L.toDouble() / fiveGb.toDouble()).toFloat()
            val item5Gb = TransferItem(
                id = "tx-5gb",
                fileName = "archive_5gb.tar",
                fileSize = fiveGb,
                direction = TransferDirection.SEND,
                status = TransferStatus.ACTIVE,
                progress = halfProgress,
                bytesTransferred = 2_500_000_000L,
                speed = "50.0 MB/s",
                peerDeviceName = "Desktop",
                etaSeconds = 50L
            )
            assertEquals(0.5f, item5Gb.progress, 0.001f)
            assertEquals("~50s left", item5Gb.formattedEta)
            assertTrue(item5Gb.formattedSize.endsWith("GB"))
        }
    }

    // -------------------------------------------------------------------------
    // Test Harness & Node Subprocess Driver
    // -------------------------------------------------------------------------

    private data class AndroidTestHarness(
        val db: AppDatabase,
        val pairedRepo: TestPairedRepo,
        val server: TcpServer,
        val serverPort: Int,
        val client: TcpClient,
        val storageManager: StorageManager,
        val connectionManager: ConnectionManager,
        val pairingManager: PairingManager,
        val transferManager: TransferManager,
        val tlsIdentity: com.example.data.network.LocalTlsIdentity?
    )

    private fun allocateFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun createAndroidHarness(
        nodeId: String,
        autoAcceptTrusted: Boolean,
        enableTls: Boolean,
        performanceMonitor: PerformanceMonitor? = null,
        enableRateLimiting: Boolean = true
    ): AndroidTestHarness {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build().also { activeDatabases.add(it) }

        val pairedRepo = TestPairedRepo(db)
        val settingsRepo = TestSettingsRepo(
            AppSettings(
                deviceName = "Pixel Test",
                autoAcceptTrusted = autoAcceptTrusted,
                tlsPreferred = enableTls,
                protocolVersion = ProtocolConstants.VERSION_V2,
                showNotifications = false
            )
        )
        val tlsId = if (enableTls) TlsTransport.createEphemeralTlsIdentity("LocalDrop-$nodeId") else null
        val server = TcpServer(
            performanceMonitor = performanceMonitor,
            localNodeIdProvider = { nodeId },
            enableRateLimiting = enableRateLimiting
        ).also {
            it.configureTlsServerContext(tlsId?.sslContext)
            activeServers.add(it)
        }
        val client = TcpClient(performanceMonitor)
        val storage = StorageManager(context)
        val connMgr = ConnectionManager(
            client = client,
            myDeviceIdProvider = { nodeId },
            context = null,
            pairedDeviceRepository = pairedRepo,
            performanceMonitor = performanceMonitor,
            tlsPreferredProvider = { settingsRepo.settings.value.tlsPreferred }
        ).also { activeManagers.add(it) }

        val pairingMgr = PairingManager(
            server = server,
            client = client,
            pairedDeviceRepository = pairedRepo,
            securityKeyStore = SecurityKeyStore(),
            connectionManager = connMgr,
            myDeviceId = nodeId,
            settingsProvider = { settingsRepo.settings.value },
            localCertFingerprintProvider = { tlsId?.certFingerprintSha256 }
        )

        val xferMgr = TransferManager(
            context = context,
            server = server,
            client = client,
            storageManager = storage,
            transferDao = db.transferDao(),
            pairedDeviceRepository = pairedRepo,
            settingsRepository = settingsRepo,
            connectionManager = connMgr,
            notificationHelper = TransferNotificationHelper(context),
            myDeviceIdProvider = { nodeId },
            performanceMonitor = performanceMonitor
        ).also { activeTransferManagers.add(it) }

        server.setStreamMessageHandler { message, socket, input ->
            xferMgr.handleProtocolMessage(message, socket, input) ?: pairingMgr.handleIncomingMessage(message)
        }
        val targetPort = allocateFreePort()
        server.start(targetPort)
        runBlocking {
            withTimeout(5_000L) {
                while (!server.isRunning.value) delay(20)
            }
        }
        return AndroidTestHarness(
            db = db,
            pairedRepo = pairedRepo,
            server = server,
            serverPort = server.listeningPort.value,
            client = client,
            storageManager = storage,
            connectionManager = connMgr,
            pairingManager = pairingMgr,
            transferManager = xferMgr,
            tlsIdentity = tlsId
        )
    }

    private class NodePeerProcess(
        val process: Process,
        val port: Int,
        val deviceId: String,
        val deviceName: String,
        val certFingerprint: String?,
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
        private val cmdSeq: AtomicInteger = AtomicInteger(1)
    ) : AutoCloseable {
        fun sendCommand(cmd: JSONObject): JSONObject {
            val id = cmdSeq.getAndIncrement()
            cmd.put("id", id)
            synchronized(writer) {
                writer.write(cmd.toString())
                writer.newLine()
                writer.flush()
            }
            val deadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: throw IllegalStateException("Node reference peer exited unexpectedly")
                if (!line.startsWith("{")) continue
                val ev = JSONObject(line)
                if (ev.optString("event") == "CMD_RESULT" && ev.optInt("id") == id) {
                    return ev
                }
            }
            throw IllegalStateException("Timed out waiting for Node reference peer CMD_RESULT id=$id")
        }

        override fun close() {
            try {
                synchronized(writer) {
                    writer.write("""{"cmd":"quit"}""")
                    writer.newLine()
                    writer.flush()
                }
                process.waitFor(2, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            try {
                if (process.isAlive) process.destroyForcibly()
            } catch (_: Exception) {}
        }
    }

    private fun spawnNodeReferencePeer(
        root: File,
        receiveDir: File? = null,
        sessionId: String = "sess-${UUID.randomUUID()}",
        pairingToken: String = "tok-${UUID.randomUUID()}",
        tls: Boolean = true
    ): NodePeerProcess {
        val script = File(root, "protocol/tests/protocol_reference_peer.mjs")
        val cmd = mutableListOf(
            "node",
            script.absolutePath,
            "--serve",
            "--port", "0",
            "--tls", tls.toString(),
            "--session-id", sessionId,
            "--pairing-token", pairingToken
        )
        if (receiveDir != null) {
            cmd.addAll(listOf("--receive-dir", receiveDir.absolutePath))
        }
        val proc = ProcessBuilder(cmd)
            .directory(root)
            .redirectErrorStream(true)
            .start()
            .also { nodeProcesses.add(it) }

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("{")) continue
            val obj = JSONObject(line)
            if (obj.optString("event") == "READY") {
                return NodePeerProcess(
                    process = proc,
                    port = obj.getInt("port"),
                    deviceId = obj.getString("deviceId"),
                    deviceName = obj.getString("deviceName"),
                    certFingerprint = obj.optString("certFingerprint", "").takeIf { it.isNotBlank() },
                    reader = reader,
                    writer = writer
                )
            }
        }
        proc.destroyForcibly()
        throw IllegalStateException("Failed waiting for Node reference peer READY event")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class TestPairedRepo(private val db: AppDatabase) : PairedDeviceRepository {
        private val tokens = mutableMapOf<String, String>()
        private val state = MutableStateFlow<List<PairedDevice>>(emptyList())
        override val pairedDevices: Flow<List<PairedDevice>> = state.asStateFlow()

        override suspend fun getPairedDevice(id: String): PairedDevice? = state.value.find { it.id == id }
        override suspend fun savePairedDevice(device: PairedDevice, authToken: String) {
            tokens[device.id] = authToken
            state.value = state.value.filterNot { it.id == device.id } + device
            db.pairedDeviceDao().insertOrUpdate(
                PairedDeviceEntity(
                    id = device.id,
                    alias = device.alias,
                    deviceType = device.deviceType.identifier,
                    ipAddress = device.ipAddress,
                    port = device.port,
                    encryptedAuthToken = authToken,
                    isTrusted = device.isTrusted,
                    pairedAt = device.pairedAt,
                    lastConnectedAt = device.lastConnectedAt,
                    model = device.model,
                    certFingerprint = device.certFingerprint,
                    tlsSupported = device.tlsSupported
                )
            )
        }

        override suspend fun updateLastConnected(id: String) {}
        override suspend fun updateNetworkEndpoint(id: String, ipAddress: String, port: Int) {
            state.value = state.value.map { if (it.id == id) it.copy(ipAddress = ipAddress, port = port) else it }
        }

        override suspend fun updateTlsInfo(id: String, certFingerprint: String?, tlsSupported: Boolean) {
            state.value = state.value.map {
                if (it.id == id) it.copy(certFingerprint = certFingerprint, tlsSupported = tlsSupported) else it
            }
        }

        override suspend fun renameDevice(id: String, newAlias: String) {}
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setAutoAccept(id: String, autoAccept: Boolean) {}
        override suspend fun incrementUsage(id: String) {}
        override suspend fun removePairedDevice(id: String) {
            tokens.remove(id)
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun getAuthToken(deviceId: String): String? = tokens[deviceId]
        override suspend fun clearAllDevices() {
            tokens.clear()
            state.value = emptyList()
        }
    }

    private class TestSettingsRepo(initial: AppSettings) : SettingsRepository {
        private val flow = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = flow.asStateFlow()
        override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun setDeviceName(name: String) {}
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setAccentColor(option: AccentColorOption) {}
        override suspend fun setAutoStartReceiving(enabled: Boolean) {}
        override suspend fun setAutoAcceptTrusted(enabled: Boolean) {}
        override suspend fun setAskBeforeReceiving(enabled: Boolean) {}
        override suspend fun setDownloadLocation(path: String, treeUri: String?) {}
        override suspend fun setPort(port: Int) {}
        override suspend fun setBroadcastEnabled(enabled: Boolean) {}
        override suspend fun setShowNotifications(enabled: Boolean) {}
        override suspend fun setDiscoveryIntervalSeconds(seconds: Int) {}
        override suspend fun setConnectionTimeoutMs(timeoutMs: Int) {}
        override suspend fun setMaxConcurrent(count: Int) {}
        override suspend fun setTransferBufferSizeKb(sizeKb: Int) {}
        override suspend fun setDuplicateHandling(handling: String) {}
        override suspend fun setPreserveOriginalNames(enabled: Boolean) {}
        override suspend fun setAutoOpenCompleted(enabled: Boolean) {}
        override suspend fun setMaxAcceptedFileSizeMb(sizeMb: Long) {}
        override suspend fun setRequireApprovalUnknown(enabled: Boolean) {}
        override suspend fun setTlsPreferred(enabled: Boolean) {}
        override suspend fun setHapticsEnabled(enabled: Boolean) {}
        override suspend fun setSuggestRecentDevices(enabled: Boolean) {}
        override suspend fun setRememberLastDestination(enabled: Boolean) {}
        override suspend fun setLastDestinationDeviceId(deviceId: String?) {}
        override suspend fun setOrganizeReceivedByType(enabled: Boolean) {}
        override suspend fun setAutoCleanTempFiles(enabled: Boolean) {}
    }
}
