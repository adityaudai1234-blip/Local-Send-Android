package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.TransferEntity
import com.example.data.discovery.NsdDiscoveryManager
import com.example.data.network.ConnectionManager
import com.example.data.network.TcpClient
import com.example.data.network.TcpServer
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.repository.TransferRepositoryImpl
import com.example.data.security.SecurityValidator
import com.example.data.transfer.ConflictAction
import com.example.data.transfer.ConflictDecision
import com.example.data.transfer.ConflictPrompt
import com.example.data.transfer.FileMetadata
import com.example.data.transfer.FileStreamFraming
import com.example.data.transfer.ResumeMetadata
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.data.transfer.TransferNotificationHelper
import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import com.example.domain.model.DeviceStatus
import com.example.domain.model.DeviceType
import com.example.domain.model.PairedDevice
import com.example.domain.model.ThemeMode
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferPriority
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.TransferRepository
import com.example.presentation.diagnostics.DiagnosticsViewModel
import com.example.presentation.diagnostics.NetworkDiagnosticsScreen
import com.example.presentation.transfer.FileConflictDialog
import com.example.presentation.transfer.SelectedFile
import com.example.presentation.transfers.TransfersScreen
import com.example.presentation.transfers.TransfersViewModel
import com.example.ui.theme.LocalDropTheme
import com.example.util.NetworkUtils
import com.example.util.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8EcosystemAndResilienceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val cleanupFiles = mutableListOf<File>()
    private val activeServers = mutableListOf<TcpServer>()
    private val activeDatabases = mutableListOf<AppDatabase>()
    private val activeManagers = mutableListOf<ConnectionManager>()
    private val activeTransferManagers = mutableListOf<TransferManager>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
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
        cleanupFiles.forEach { it.deleteRecursively() }
        cleanupFiles.clear()
    }

    // -------------------------------------------------------------------------
    // 1. Verified-Offset Transfer Resume & Tamper Fallback
    // -------------------------------------------------------------------------

    @Test
    fun transferManager_verifiedOffsetResumeCompletesRemainingBytesWithoutCorruption() {
        runBlocking {
            val receiverEnv = createIsolatedTransferEnv("ResumeReceiver", "recv-node")
            val port = findFreePort()
            receiverEnv.server.start(port)
            waitUntilTrue { receiverEnv.server.isRunning.value }

            val srcDir = File(context.cacheDir, "p8_resume_${UUID.randomUUID()}").apply {
                mkdirs()
                cleanupFiles.add(this)
            }
            val totalSize = 768 * 1024
            val fullPayload = ByteArray(totalSize) { i -> ((i * 17 + 11) and 0xFF).toByte() }
            val sourceFile = File(srcDir, "resume_bundle.bin").apply { writeBytes(fullPayload) }

            val partialOffset = 256 * 1024
            val partialBytes = fullPayload.copyOfRange(0, partialOffset)

            val fixedTransferId = "tx-resume-valid-${UUID.randomUUID()}"
            val receiverStorage = StorageManager(context)
            val receiverTmp = receiverStorage.createTempFile(fixedTransferId).apply {
                writeBytes(partialBytes)
            }
            val prefixHash = receiverStorage.computeFilePrefixSha256(receiverTmp, partialOffset.toLong())!!
            receiverStorage.writeResumeMetadata(
                ResumeMetadata(
                    fileId = fixedTransferId,
                    senderId = "send-node",
                    fileName = sourceFile.name,
                    fileSize = totalSize.toLong(),
                    bytesWritten = partialOffset.toLong(),
                    prefixSha256 = prefixHash
                )
            )

            val listenPort = receiverEnv.server.listeningPort.value
            val sessionId = "sess-resume-${UUID.randomUUID()}"
            val approver = launch(Dispatchers.IO) {
                waitUntilTrue { receiverEnv.manager.incomingRequest.value != null }
                receiverEnv.manager.acceptIncomingTransfer(receiverEnv.manager.incomingRequest.value!!)
            }

            val client = TcpClient()
            val req = ProtocolMessage(
                version = "v2",
                type = ProtocolMessageType.TRANSFER_REQUEST,
                senderId = "send-node",
                receiverId = "recv-node",
                payload = mapOf(
                    "sessionId" to sessionId,
                    "senderName" to "ResumeSender",
                    "senderType" to "ANDROID",
                    "totalSize" to totalSize.toLong(),
                    "fileCount" to 1,
                    "files" to FileMetadata.toJsonArray(
                        listOf(
                            FileMetadata(
                                fileId = fixedTransferId,
                                fileName = sourceFile.name,
                                fileSize = totalSize.toLong(),
                                mimeType = "application/octet-stream"
                            )
                        )
                    )
                )
            )
            val ack = client.sendMessage("127.0.0.1", listenPort, req, timeoutMs = 5_000).getOrThrow()
            approver.join()
            assertEquals(ProtocolMessageType.TRANSFER_ACCEPTED, ack.type)

            val streamJob = launch(Dispatchers.IO) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", listenPort), 5_000)
                    socket.soTimeout = 5_000
                    val output = BufferedOutputStream(socket.getOutputStream())
                    val input = BufferedInputStream(socket.getInputStream())

                    val startedMsg = ProtocolMessage(
                        version = "v2",
                        type = ProtocolMessageType.TRANSFER_STARTED,
                        senderId = "send-node",
                        receiverId = "recv-node",
                        payload = mapOf(
                            "sessionId" to sessionId,
                            "fileId" to fixedTransferId,
                            "fileName" to sourceFile.name,
                            "fileSize" to totalSize.toLong(),
                            "supportsResume" to true,
                            "expectReady" to true
                        )
                    )
                    MessageFraming.writeFrame(output, startedMsg)
                    val readyMsg = MessageFraming.readFrame(input)!!
                    assertEquals(ProtocolMessageType.TRANSFER_READY, readyMsg.type)
                    val acceptedOffset = (readyMsg.payload["acceptedOffset"] as Number).toLong()
                    val remotePrefixSha = readyMsg.payload["prefixSha256"] as String
                    assertEquals(partialOffset.toLong(), acceptedOffset)
                    assertEquals(prefixHash, remotePrefixSha)

                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(fullPayload, 0, acceptedOffset.toInt())
                    var pos = acceptedOffset.toInt()
                    val chunkSize = 64 * 1024
                    while (pos < fullPayload.size) {
                        val len = minOf(chunkSize, fullPayload.size - pos)
                        FileStreamFraming.writeChunk(output, 0, pos.toLong(), fullPayload.copyOfRange(pos, pos + len), len)
                        digest.update(fullPayload, pos, len)
                        pos += len
                    }
                    FileStreamFraming.writeEof(output, 0, digest.digest())
                }
            }
            streamJob.join()

            waitUntilTrue(timeoutMs = 5_000L) {
                receiverStorage.listReceivedFiles().any { it.name == sourceFile.name }
            }
            val received = receiverStorage.listReceivedFiles().first { it.name == sourceFile.name }
            assertEquals(totalSize.toLong(), received.length())
            assertArrayEquals(fullPayload, received.readBytes())
        }
    }

    @Test
    fun transferManager_tamperedPartialTempFallsBackToOffsetZero() {
        runBlocking {
            val receiverEnv = createIsolatedTransferEnv("TamperReceiver", "recv-tamper")
            val port = findFreePort()
            receiverEnv.server.start(port)
            waitUntilTrue { receiverEnv.server.isRunning.value }

            val fixedTransferId = "tx-tamper-${UUID.randomUUID()}"
            val receiverStorage = StorageManager(context)
            val tmpFile = receiverStorage.createTempFile(fixedTransferId).apply {
                writeBytes(ByteArray(4096) { 0x55 })
            }
            receiverStorage.writeResumeMetadata(
                ResumeMetadata(
                    fileId = fixedTransferId,
                    senderId = "send-node",
                    fileName = "tamper.bin",
                    fileSize = 8192L,
                    bytesWritten = 4096L,
                    prefixSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                )
            )
            assertTrue(tmpFile.exists())

            val listenPort = receiverEnv.server.listeningPort.value
            val sessionId = "sess-tamper-${UUID.randomUUID()}"
            val approver = launch(Dispatchers.IO) {
                waitUntilTrue { receiverEnv.manager.incomingRequest.value != null }
                receiverEnv.manager.acceptIncomingTransfer(receiverEnv.manager.incomingRequest.value!!)
            }

            val client = TcpClient()
            val req = ProtocolMessage(
                version = "v2",
                type = ProtocolMessageType.TRANSFER_REQUEST,
                senderId = "send-node",
                receiverId = "recv-tamper",
                payload = mapOf(
                    "sessionId" to sessionId,
                    "senderName" to "Sender",
                    "senderType" to "ANDROID",
                    "totalSize" to 8192L,
                    "fileCount" to 1,
                    "files" to FileMetadata.toJsonArray(
                        listOf(
                            FileMetadata(
                                fileId = fixedTransferId,
                                fileName = "tamper.bin",
                                fileSize = 8192L,
                                mimeType = "application/octet-stream"
                            )
                        )
                    )
                )
            )
            val ack = client.sendMessage("127.0.0.1", listenPort, req, timeoutMs = 5_000).getOrThrow()
            approver.join()
            assertEquals(ProtocolMessageType.TRANSFER_ACCEPTED, ack.type)

            val fullPayload = ByteArray(8192) { i -> (i and 0xFF).toByte() }
            val job = launch(Dispatchers.IO) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", listenPort), 5_000)
                    socket.soTimeout = 5_000
                    val output = BufferedOutputStream(socket.getOutputStream())
                    val input = BufferedInputStream(socket.getInputStream())

                    val startedMsg = ProtocolMessage(
                        version = "v2",
                        type = ProtocolMessageType.TRANSFER_STARTED,
                        senderId = "send-node",
                        receiverId = "recv-tamper",
                        payload = mapOf(
                            "sessionId" to sessionId,
                            "fileId" to fixedTransferId,
                            "fileName" to "tamper.bin",
                            "fileSize" to 8192L,
                            "supportsResume" to true,
                            "expectReady" to true
                        )
                    )
                    MessageFraming.writeFrame(output, startedMsg)
                    val readyMsg = MessageFraming.readFrame(input)!!
                    assertEquals(ProtocolMessageType.TRANSFER_READY, readyMsg.type)
                    assertEquals(0L, (readyMsg.payload["acceptedOffset"] as Number).toLong())

                    FileStreamFraming.writeChunk(output, 0, 0L, fullPayload, fullPayload.size)
                    val digest = MessageDigest.getInstance("SHA-256").digest(fullPayload)
                    FileStreamFraming.writeEof(output, 0, digest)
                }
            }
            job.join()

            waitUntilTrue {
                receiverStorage.listReceivedFiles().any { it.name == "tamper.bin" }
            }
            val received = receiverStorage.listReceivedFiles().first { it.name == "tamper.bin" }
            assertArrayEquals(fullPayload, received.readBytes())
        }
    }

    // -------------------------------------------------------------------------
    // 2. Security Hardening, Folder Empty Dirs, Share Sheet & Conflict Safety
    // -------------------------------------------------------------------------

    @Test
    fun securityValidator_blocksSiblingPrefixTraversalAndComparesTokensInConstantTime() {
        val base = File(context.cacheDir, "LocalDrop").apply { mkdirs(); cleanupFiles.add(this) }
        val safeChild = File(base, "Docs/Sub/report.pdf")
        val siblingAttack = File(context.cacheDir, "LocalDropEvil/secret.txt")
        val parentAttack = File(context.cacheDir, "outside.txt")

        assertTrue(SecurityValidator.isCanonicalSubPath(safeChild, base))
        assertFalse(SecurityValidator.isCanonicalSubPath(siblingAttack, base))
        assertFalse(SecurityValidator.isCanonicalSubPath(parentAttack, base))

        assertTrue(SecurityValidator.constantTimeEquals("abc-token-123", "abc-token-123"))
        assertFalse(SecurityValidator.constantTimeEquals("abc-token-123", "abc-token-124"))
        assertFalse(SecurityValidator.constantTimeEquals("abc", "abcd"))
        assertFalse(SecurityValidator.constantTimeEquals(null, "abc"))
    }

    @Test
    fun storageAndSelection_emptyFoldersKeepBothConflictAndAtomicReplaceWork() {
        val storage = StorageManager(context)
        assertTrue(storage.createDirectoryFromRelativePath("Work/EmptyFolder"))
        val emptyDir = File(storage.getDownloadDirectory(), "Work/EmptyFolder")
        assertTrue(emptyDir.exists() && emptyDir.isDirectory)

        val existing = File(storage.getDownloadDirectory(), "proposal.txt").apply {
            writeText("original-content")
        }
        assertTrue(existing.exists())

        assertNotNull(storage.peekExistingDestination("proposal.txt"))
        val keepBoth = storage.resolveDestinationWithPolicy("proposal.txt", "keep_both")
        assertTrue(keepBoth is StorageManager.DestinationResolution.Ready)
        assertTrue((keepBoth as StorageManager.DestinationResolution.Ready).file.name.contains("(1)"))

        val stagedTmp = storage.createTempFile("atomic-replace-id").apply {
            writeText("replacement-content")
        }
        val replacedPath = storage.finalizeReceivedFile(stagedTmp, existing, "text/plain")
        assertEquals(existing.canonicalPath, File(replacedPath!!).canonicalPath)
        assertEquals("replacement-content", existing.readText())

        val sampleFile = File(context.cacheDir, "p8_sample_${UUID.randomUUID()}.txt").apply {
            writeText("hello")
            cleanupFiles.add(this)
        }
        val summary = SelectedFile.summarize(
            listOf(
                SelectedFile(
                    uri = Uri.fromFile(sampleFile),
                    name = sampleFile.name,
                    size = 5L,
                    mimeType = "text/plain",
                    relativePath = "Root/Sub/${sampleFile.name}",
                    isAccessible = true
                ),
                SelectedFile(
                    uri = Uri.parse("content://empty"),
                    name = "Empty",
                    size = 0L,
                    mimeType = "vnd.android.document/directory",
                    relativePath = "Root/Empty",
                    isDirectory = true
                ),
                SelectedFile(
                    uri = Uri.parse("content://missing"),
                    name = "broken.bin",
                    size = 0L,
                    mimeType = "application/octet-stream",
                    isAccessible = false,
                    accessError = "Permission expired"
                )
            )
        )
        assertEquals(2, summary.fileCount)
        assertEquals(2, summary.folderCount)
        assertEquals(5L, summary.totalBytes)
        assertEquals(1, summary.inaccessibleCount)
    }

    @Test
    fun shareIntentExtractor_handlesSharedTextMultipleStreamsAndInvalidUris() {
        val textIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Meeting Notes")
            putExtra(Intent.EXTRA_TEXT, "LocalDrop test note content")
        }
        val extractedText = ShareIntentExtractor.extractSharedText(textIntent)
        assertNotNull(extractedText)
        assertEquals("LocalDrop test note content", extractedText!!.first)
        assertEquals("Meeting Notes", extractedText.second)

        val noteFile = SelectedFile.createTextNoteFile(context, extractedText.first, extractedText.second)
        assertNotNull(noteFile)
        assertTrue(noteFile!!.name.endsWith(".txt"))
        assertTrue(noteFile.isAccessible)
        assertEquals(extractedText.first.length.toLong(), noteFile.size)

        val uri1 = Uri.parse("content://example.provider/doc/1")
        val uri2 = Uri.parse("content://example.provider/doc/2")
        val multiIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
        }
        assertEquals(listOf(uri1, uri2), ShareIntentExtractor.extractSharedUris(multiIntent))

        val broken = SelectedFile.fromUri(context, Uri.parse("content://nonexistent.authority/missing/99"))
        assertFalse(broken.isAccessible)
        assertNotNull(broken.accessError)
    }

    // -------------------------------------------------------------------------
    // 3. Queue Priority, Reordering, Orphan Cleanup Protection & Room v4 -> v5
    // -------------------------------------------------------------------------

    @Test
    fun roomMigration_v4ToV5PreservesHistoryAndAddsQueueResumeColumns() {
        val dbFile = File(context.cacheDir, "mig_v4_${UUID.randomUUID()}.db").also { cleanupFiles.add(it) }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `paired_devices` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `alias` TEXT NOT NULL,
                            `deviceType` TEXT NOT NULL,
                            `model` TEXT,
                            `ipAddress` TEXT NOT NULL,
                            `port` INTEGER NOT NULL,
                            `encryptedAuthToken` TEXT NOT NULL,
                            `pairedAt` INTEGER NOT NULL,
                            `lastConnectedAt` INTEGER NOT NULL,
                            `isTrusted` INTEGER NOT NULL,
                            `isFavorite` INTEGER NOT NULL DEFAULT 0,
                            `autoAccept` INTEGER NOT NULL DEFAULT 0,
                            `lastTransferredAt` INTEGER NOT NULL DEFAULT 0,
                            `useCount` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `transfer_history` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `sessionId` TEXT NOT NULL,
                            `fileName` TEXT NOT NULL,
                            `fileExtension` TEXT NOT NULL,
                            `mimeType` TEXT,
                            `fileSize` INTEGER NOT NULL,
                            `senderDevice` TEXT NOT NULL,
                            `receiverDevice` TEXT NOT NULL,
                            `direction` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `startTime` INTEGER NOT NULL,
                            `completionTime` INTEGER NOT NULL,
                            `durationMs` INTEGER NOT NULL,
                            `speed` TEXT,
                            `checksum` TEXT,
                            `filePath` TEXT,
                            `peerDeviceName` TEXT NOT NULL,
                            `peerAddress` TEXT NOT NULL,
                            `errorMessage` TEXT,
                            `timestamp` INTEGER NOT NULL,
                            `relativePath` TEXT,
                            `avgSpeedBytesPerSec` INTEGER NOT NULL DEFAULT 0,
                            `peakSpeedBytesPerSec` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `transfer_history`
                        (`id`, `sessionId`, `fileName`, `fileExtension`, `mimeType`, `fileSize`, `senderDevice`, `receiverDevice`, `direction`, `status`, `startTime`, `completionTime`, `durationMs`, `speed`, `checksum`, `filePath`, `peerDeviceName`, `peerAddress`, `errorMessage`, `timestamp`, `relativePath`, `avgSpeedBytesPerSec`, `peakSpeedBytesPerSec`)
                        VALUES ('v4-tx-1', 's4', 'archive.zip', 'zip', 'application/zip', 12000, 'Pixel', 'Desktop', 'SEND', 'ACTIVE', 100, 0, 0, '', NULL, NULL, 'Desktop', '192.168.1.9:53317', NULL, 500, NULL, 0, 0)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase.close() }

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        activeDatabases.add(migratedDb)

        runBlocking {
            val row = migratedDb.transferDao().getById("v4-tx-1")
            assertNotNull(row)
            assertEquals("archive.zip", row!!.fileName)
            assertEquals(TransferPriority.NORMAL.name, row.priority)
            assertEquals(0L, row.bytesTransferred)
            assertEquals(0, row.retryCount)

            val recoverable = migratedDb.transferDao().getRecoverableTransfers()
            assertTrue(recoverable.any { it.id == "v4-tx-1" })
        }
    }

    @Test
    fun storageManager_cleanOrphanTempFilesPreservesRecoverableResumeFiles() {
        val storage = StorageManager(context)
        val keepTmp = storage.createTempFile("keep-id").apply { writeText("partial") }
        val keepMeta = storage.getTempMetaFile("keep-id").apply { writeText("{}") }
        val orphanTmp = storage.createTempFile("orphan-id").apply { writeText("junk") }
        val orphanMeta = storage.getTempMetaFile("orphan-id").apply { writeText("{}") }

        val deleted = storage.cleanOrphanTempFiles(setOf("keep-id"))
        assertEquals(2, deleted)
        assertTrue(keepTmp.exists())
        assertTrue(keepMeta.exists())
        assertFalse(orphanTmp.exists())
        assertFalse(orphanMeta.exists())
    }

    @Test
    fun transferManager_queuePriorityMoveAndBulkControlsWorkAndRecoverOnStartup() {
        runBlocking {
            val env = createIsolatedTransferEnv("QueueNode", "q-node")
            val storage = StorageManager(context)
            val tmpFile = storage.createTempFile("interrupted-tx-1").apply {
                writeBytes(ByteArray(4_000) { 0x42 })
            }
            val prefixSha = storage.computeFilePrefixSha256(tmpFile, 4_000L)!!
            storage.writeResumeMetadata(
                ResumeMetadata(
                    fileId = "interrupted-tx-1",
                    senderId = "desktop-1",
                    fileName = "big_video.mp4",
                    fileSize = 10_000L,
                    bytesWritten = 4_000L,
                    prefixSha256 = prefixSha
                )
            )
            env.db.transferDao().insert(
                TransferEntity(
                    id = "interrupted-tx-1",
                    fileName = "big_video.mp4",
                    fileSize = 10_000L,
                    bytesTransferred = 4_000L,
                    senderDevice = "Desktop",
                    receiverDevice = "QueueNode",
                    direction = "RECEIVE",
                    status = "ACTIVE",
                    peerDeviceName = "Desktop",
                    partialChecksum = prefixSha,
                    sourceUri = null
                )
            )
            env.db.transferDao().insert(
                TransferEntity(
                    id = "low-1",
                    fileName = "low.bin",
                    fileSize = 100L,
                    senderDevice = "QueueNode",
                    receiverDevice = "Peer",
                    direction = "SEND",
                    status = "PENDING",
                    peerDeviceName = "Peer",
                    priority = TransferPriority.LOW.name,
                    queueOrder = 1
                )
            )
            env.db.transferDao().insert(
                TransferEntity(
                    id = "norm-1",
                    fileName = "norm.bin",
                    fileSize = 100L,
                    senderDevice = "QueueNode",
                    receiverDevice = "Peer",
                    direction = "SEND",
                    status = "PENDING",
                    peerDeviceName = "Peer",
                    priority = TransferPriority.NORMAL.name,
                    queueOrder = 2
                )
            )

            val recoveredIds = env.manager.recoverInterruptedTransfers()
            assertTrue(recoveredIds.contains("interrupted-tx-1"))
            val recoveredItem = env.manager.activeTransfers.value.find { it.id == "interrupted-tx-1" }
            assertNotNull(recoveredItem)
            assertEquals(TransferStatus.PENDING, recoveredItem!!.status)
            assertEquals(4_000L, recoveredItem.bytesTransferred)
            assertTrue(recoveredItem.isPaused)

            val repo = TransferRepositoryImpl(env.manager, env.db.transferDao(), storage)
            repo.setTransferPriority("low-1", TransferPriority.HIGH)
            repo.moveTransferUp("norm-1")
            env.manager.pauseAllTransfers()
            assertTrue(env.manager.activeTransfers.value.find { it.id == "low-1" }?.isPaused == true)
            env.manager.resumeAllTransfers()
            val updated = env.manager.activeTransfers.value.find { it.id == "low-1" }
            assertEquals(TransferPriority.HIGH, updated?.priority)
            assertFalse(updated?.isPaused ?: true)
        }
    }

    // -------------------------------------------------------------------------
    // 4. NSD Multi-Interface Deduplication, NetworkUtils & PerformanceMonitor
    // -------------------------------------------------------------------------

    @Test
    fun nsdDiscoveryManager_deduplicatesMultiInterfacePreferringLanIpv4AndTracksOffline() {
        val perf = PerformanceMonitor()
        val nsd = NsdDiscoveryManager(context, perf)
        try {
            nsd.upsertResolvedDevice(
                "LocalDrop-MacStudio",
                Device(
                    id = "mac-fp-1",
                    alias = "Mac Studio",
                    deviceType = DeviceType.MACOS,
                    ipAddress = "fe80::1ff:fe23:4567:890a%wlan0",
                    port = 53317,
                    isOnline = true
                )
            )
            nsd.upsertResolvedDevice(
                "LocalDrop-MacStudio-v4",
                Device(
                    id = "mac-fp-1",
                    alias = "Mac Studio",
                    deviceType = DeviceType.MACOS,
                    ipAddress = "192.168.1.77",
                    port = 53317,
                    isOnline = true
                )
            )
            val list = nsd.discoveredDevices.value
            assertEquals(1, list.size)
            assertEquals("192.168.1.77", list.first().ipAddress)
            assertTrue(list.first().alternateAddresses.contains("fe80::1ff:fe23:4567:890a"))
            assertEquals(DeviceStatus.ONLINE, list.first().status)
        } finally {
            nsd.cleanup()
        }
    }

    @Test
    fun networkUtilsAndPerformanceMonitor_reportRealStateAndMetricsWithoutFabrication() {
        assertTrue(NetworkUtils.isLanIpv4Address("192.168.1.15"))
        assertTrue(NetworkUtils.isLanIpv4Address("10.0.0.5"))
        assertFalse(NetworkUtils.isLanIpv4Address("127.0.0.1"))
        assertFalse(NetworkUtils.isLanIpv4Address("169.254.1.10"))

        assertTrue(NetworkUtils.isHotspotInterfaceName("ap0"))
        assertTrue(NetworkUtils.isHotspotInterfaceName("swlan0"))
        assertFalse(NetworkUtils.isHotspotInterfaceName("eth0"))

        val freePort = findFreePort()
        assertTrue(NetworkUtils.isLocalPortAvailable(freePort))

        val perf = PerformanceMonitor()
        assertEquals(0L, perf.snapshot.value.discoveryDurationMs)
        perf.recordDiscoveryDuration(420L)
        perf.recordConnectionDuration(35L)
        perf.recordMeasuredSendSpeed(12_000_000L)
        perf.recordMeasuredReceiveSpeed(8_000_000L)
        perf.onSocketOpened()
        perf.recordRetry()
        perf.recordRecoveryAttempt(true)
        perf.refreshMemoryUsage()

        val snap = perf.snapshot.value
        assertEquals(420L, snap.discoveryDurationMs)
        assertEquals(35L, snap.lastConnectionDurationMs)
        assertEquals(12_000_000L, snap.measuredSendBytesPerSec)
        assertEquals(8_000_000L, snap.measuredReceiveBytesPerSec)
        assertEquals(12_000_000L, snap.peakMeasuredSpeedBytesPerSec)
        assertEquals(1, snap.activeSocketCount)
        assertEquals(1L, snap.totalRetries)
        assertEquals(1L, snap.recoveryAttempts)
        assertEquals(1L, snap.recoverySuccesses)
        assertTrue(snap.maxMemoryBytes > 0L)
        perf.onSocketClosed()
        assertEquals(0, perf.snapshot.value.activeSocketCount)
    }

    // -------------------------------------------------------------------------
    // 5. Compose UI Tests: FileConflictDialog, Diagnostics, Queue Controls
    // -------------------------------------------------------------------------

    @Test
    fun fileConflictDialog_rendersDetailsAndDispatchesActionWithApplyToAll() {
        var resolved: ConflictDecision? = null
        val prompt = ConflictPrompt(
            fileId = "f-1",
            fileName = "quarterly_report.pdf",
            incomingSize = 2_048_000L,
            existingSize = 1_024_000L,
            existingLastModified = 1_730_000_000_000L,
            existingPath = "/Downloads/LocalDrop/quarterly_report.pdf",
            remainingConflictCount = 3
        )

        composeTestRule.setContent {
            LocalDropTheme {
                FileConflictDialog(
                    prompt = prompt,
                    onResolve = { resolved = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("file_conflict_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("quarterly_report.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithTag("conflict_apply_all_checkbox").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("conflict_action_keep_both").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNotNull(resolved)
        assertEquals(ConflictAction.KEEP_BOTH, resolved!!.action)
        assertTrue(resolved!!.applyToAll)
    }

    @Test
    fun networkDiagnosticsScreen_displaysTelemetryAndCopiesNetworkInfoToClipboard() {
        val perf = PerformanceMonitor().apply {
            recordDiscoveryDuration(180L)
            recordConnectionDuration(22L)
            recordMeasuredSendSpeed(5_242_880L)
        }
        val tcpClient = TcpClient(perf)
        val tcpServer = TcpServer(performanceMonitor = perf).also { activeServers.add(it) }
        val settingsRepo = InMemorySettingsRepository(AppSettings(deviceName = "PixelTest"))
        val pairedRepo = InMemoryPairedDeviceRepository()
        val connMgr = ConnectionManager(tcpClient, { "pixel-test" }, performanceMonitor = perf)
            .also { activeManagers.add(it) }
        val fakeDiscovery = object : DeviceDiscoveryRepository {
            override val discoveredDevices = MutableStateFlow(emptyList<Device>())
            override val isScanning = MutableStateFlow(false)
            override val isAdvertising = MutableStateFlow(false)
            override val connectionStatus = MutableStateFlow(ConnectionStatus())
            override val error = MutableStateFlow<String?>(null)
            override fun startDiscovery() {}
            override fun stopDiscovery() {}
            override fun refreshDiscovery() {}
            override fun startAdvertising(alias: String, port: Int) {}
            override fun stopAdvertising() {}
            override fun updateNetworkStatus() {}
            override fun addSimulatedPeer(device: Device) {}
            override fun addPredefinedTestPeers() {}
        }

        val vm = DiagnosticsViewModel(
            context = context,
            discoveryRepository = fakeDiscovery,
            connectionManager = connMgr,
            pairedDeviceRepository = pairedRepo,
            tcpServer = tcpServer,
            settingsRepository = settingsRepo,
            performanceMonitor = perf
        )

        composeTestRule.setContent {
            LocalDropTheme {
                NetworkDiagnosticsScreen(viewModel = vm, onBack = {})
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("network_diagnostics_screen").assertIsDisplayed()
        val scrollableList = composeTestRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        )
        scrollableList.performScrollToNode(hasTestTag("performance_metrics_card"))
        composeTestRule.onNodeWithTag("performance_metrics_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("180 ms").assertIsDisplayed()

        scrollableList.performScrollToNode(hasTestTag("copy_network_info_button"))
        composeTestRule.onNodeWithTag("copy_network_info_button").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        assertTrue(copied.contains("LocalDrop Network Diagnostics"))
        assertTrue(copied.contains("Local IPv4"))
        assertTrue(copied.contains("Discovery Latency: 180 ms"))
    }

    @Test
    fun transfersScreen_displaysBulkQueueControlsAndPriorityBadge() {
        val transferEnv = createIsolatedTransferEnv("UiQueueNode", "ui-q-node")
        val item = TransferItem(
            id = "q-ui-1",
            fileName = "priority_deck.pdf",
            fileSize = 4096L,
            peerDeviceName = "Linux Laptop",
            direction = TransferDirection.SEND,
            status = TransferStatus.PENDING,
            priority = TransferPriority.HIGH
        )
        val transfersFlow = MutableStateFlow(listOf(item))
        val fakeRepo = object : TransferRepository {
            override val transfers: StateFlow<List<TransferItem>> = transfersFlow
            override val transferHistory: StateFlow<List<TransferItem>> = MutableStateFlow(emptyList())
            override val stats: StateFlow<TransferStats> = MutableStateFlow(
                TransferStats(activeCount = 1)
            )
            override fun getTransfersByCategory(category: TransferCategory) = listOf(item)
            override fun addTransfer(item: TransferItem) {}
            override fun cancelTransfer(id: String) {}
            override fun retryTransfer(id: String) {}
            override fun clearCompleted() {}
            override fun clearAll() {}
            override fun deleteHistoryEntry(id: String) {}
            override fun deleteHistoryEntryWithFile(item: TransferItem) {}
        }

        val vm = TransfersViewModel(
            transferRepository = fakeRepo,
            transferManager = transferEnv.manager,
            storageManager = StorageManager(context)
        )

        composeTestRule.setContent {
            LocalDropTheme {
                TransfersScreen(viewModel = vm)
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("queue_pause_all_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("queue_resume_all_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("queue_cancel_all_button").assertIsDisplayed()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag("priority_chip_q-ui-1"))
        composeTestRule.onNodeWithTag("priority_chip_q-ui-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("High").assertIsDisplayed()
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
        override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun setDeviceName(name: String) { _settings.value = _settings.value.copy(deviceName = name) }
        override suspend fun setThemeMode(mode: ThemeMode) { _settings.value = _settings.value.copy(themeMode = mode) }
        override suspend fun setAccentColor(option: AccentColorOption) { _settings.value = _settings.value.copy(accentColor = option) }
        override suspend fun setAppLanguage(language: String) { _settings.value = _settings.value.copy(appLanguage = language) }
        override suspend fun setHasCompletedOnboarding(completed: Boolean) { _settings.value = _settings.value.copy(hasCompletedOnboarding = completed) }
        override suspend fun setLastSeenReleaseVersion(version: String) { _settings.value = _settings.value.copy(lastSeenReleaseVersion = version) }
        override suspend fun setAutoStartReceiving(enabled: Boolean) { _settings.value = _settings.value.copy(autoStartReceiving = enabled) }
        override suspend fun setAutoAcceptTrusted(enabled: Boolean) { _settings.value = _settings.value.copy(autoAcceptTrusted = enabled) }
        override suspend fun setAskBeforeReceiving(enabled: Boolean) { _settings.value = _settings.value.copy(askBeforeReceiving = enabled) }
        override suspend fun setDownloadLocation(path: String, treeUri: String?) {
            _settings.value = _settings.value.copy(downloadLocation = path, customDownloadTreeUri = treeUri)
        }
        override suspend fun setPort(port: Int) { _settings.value = _settings.value.copy(port = port) }
        override suspend fun setBroadcastEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(broadcastEnabled = enabled) }
        override suspend fun setShowNotifications(enabled: Boolean) { _settings.value = _settings.value.copy(showNotifications = enabled) }
        override suspend fun setNotifyIncomingRequests(enabled: Boolean) { _settings.value = _settings.value.copy(notifyIncomingRequests = enabled) }
        override suspend fun setNotifyTransferProgress(enabled: Boolean) { _settings.value = _settings.value.copy(notifyTransferProgress = enabled) }
        override suspend fun setNotifyTransferComplete(enabled: Boolean) { _settings.value = _settings.value.copy(notifyTransferComplete = enabled) }
        override suspend fun setDiscoveryIntervalSeconds(seconds: Int) { _settings.value = _settings.value.copy(discoveryIntervalSeconds = seconds) }
        override suspend fun setConnectionTimeoutMs(timeoutMs: Int) { _settings.value = _settings.value.copy(connectionTimeoutMs = timeoutMs) }
        override suspend fun setMaxConcurrent(count: Int) { _settings.value = _settings.value.copy(maxConcurrentTransfers = count) }
        override suspend fun setTransferBufferSizeKb(sizeKb: Int) { _settings.value = _settings.value.copy(transferBufferSizeKb = sizeKb) }
        override suspend fun setDuplicateHandling(handling: String) { _settings.value = _settings.value.copy(duplicateHandling = handling) }
        override suspend fun setAskOnConflictInUi(enabled: Boolean) { _settings.value = _settings.value.copy(askOnConflictInUi = enabled) }
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
