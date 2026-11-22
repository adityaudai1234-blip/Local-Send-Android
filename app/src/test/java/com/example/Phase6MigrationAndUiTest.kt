package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.TransferEntity
import com.example.data.transfer.StorageManager
import com.example.di.DefaultAppContainer
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.TransferRepository
import com.example.presentation.diagnostics.DiagnosticsViewModel
import com.example.presentation.diagnostics.NetworkDiagnosticsScreen
import com.example.presentation.history.TransferDetailsScreen
import com.example.presentation.privacy.PrivacyDashboardScreen
import com.example.presentation.storage.StorageViewModel
import com.example.presentation.transfer.SelectedFile
import com.example.presentation.transfers.TransfersScreen
import com.example.presentation.transfers.TransfersViewModel
import com.example.ui.theme.LocalDropTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase6MigrationAndUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val cleanupFiles = mutableListOf<File>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        StorageManager(context).deleteAllReceivedFiles()
        cleanupFiles.forEach { it.deleteRecursively() }
        cleanupFiles.clear()
    }

    // -------------------------------------------------------------------------
    // 1. Non-Destructive Room Database Migrations (v1 -> v4, v3 -> v4)
    // -------------------------------------------------------------------------

    @Test
    fun roomMigration_v1ToV4PreservesPairedDevicesAndAddsNewColumns() {
        val dbFile = File(context.cacheDir, "mig_v1_${UUID.randomUUID()}.db").also { cleanupFiles.add(it) }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
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
                            `pairedAt` INTEGER NOT NULL,
                            `lastConnectedAt` INTEGER NOT NULL,
                            `isTrusted` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `paired_devices`
                        (`id`, `alias`, `deviceType`, `ipAddress`, `port`, `encryptedAuthToken`, `pairedAt`, `lastConnectedAt`, `isTrusted`)
                        VALUES ('legacy-mac-1', 'Legacy MacBook', 'macos', '192.168.1.50', 53317, 'enc-tok', 1000, 2000, 1)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            helper.writableDatabase.close()
        }

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                val device = migratedDb.pairedDeviceDao().getById("legacy-mac-1")
                assertNotNull("Legacy paired_devices row must survive v1->v4 migration", device)
                assertEquals("Legacy MacBook", device!!.alias)
                assertFalse("autoAccept must default to false after migration", device.autoAccept)
                assertFalse(device.isFavorite)
                assertEquals(0, device.useCount)

                // Also verify transfer_history table works post-migration
                migratedDb.transferDao().insert(
                    TransferEntity(
                        id = "post-mig-tx",
                        fileName = "ok.txt",
                        fileSize = 2L,
                        senderDevice = "Pixel 9",
                        receiverDevice = "Legacy MacBook",
                        direction = "SEND",
                        status = "COMPLETED",
                        peerDeviceName = "Legacy MacBook"
                    )
                )
                assertNotNull(migratedDb.transferDao().getById("post-mig-tx"))
            }
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun roomMigration_v3ToV4PreservesTransferHistoryAndAddsPhase5Columns() {
        val dbFile = File(context.cacheDir, "mig_v3_${UUID.randomUUID()}.db").also { cleanupFiles.add(it) }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
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
                            `pairedAt` INTEGER NOT NULL,
                            `lastConnectedAt` INTEGER NOT NULL,
                            `isTrusted` INTEGER NOT NULL
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
                            `timestamp` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `transfer_history`
                        (`id`, `sessionId`, `fileName`, `fileExtension`, `mimeType`, `fileSize`, `senderDevice`, `receiverDevice`, `direction`, `status`, `startTime`, `completionTime`, `durationMs`, `speed`, `checksum`, `filePath`, `peerDeviceName`, `peerAddress`, `errorMessage`, `timestamp`)
                        VALUES ('legacy-tx-3', 'sess-3', 'budget.xlsx', 'xlsx', 'application/vnd.ms-excel', 4096, 'Desktop', 'Pixel', 'RECEIVE', 'COMPLETED', 100, 200, 100, '40 KB/s', 'deadbeef', '/tmp/budget.xlsx', 'Desktop', '192.168.1.2:53317', NULL, 200)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            helper.writableDatabase.close()
        }

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                val tx = migratedDb.transferDao().getById("legacy-tx-3")
                assertNotNull("Legacy transfer_history row must survive v3->v4 migration", tx)
                assertEquals("budget.xlsx", tx!!.fileName)
                assertEquals("deadbeef", tx.checksum)
                assertNull(tx.relativePath)
                assertEquals(0L, tx.avgSpeedBytesPerSec)
                assertEquals(0L, tx.peakSpeedBytesPerSec)
            }
        } finally {
            migratedDb.close()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Storage-DB Consistency & Share Sheet Size Fallback
    // -------------------------------------------------------------------------

    @Test
    fun storageViewModel_deletesPhysicalFileAndClearsRoomFilePathReference() {
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val container = DefaultAppContainer(context)
            val storage = StorageManager(context)
            val receivedFile = File(storage.getDownloadDirectory(), "to_clean_up.txt").apply {
                writeText("Delete me physically")
            }

            try {
                db.transferDao().insert(
                    TransferEntity(
                        id = "sync-tx-1",
                        fileName = receivedFile.name,
                        fileSize = receivedFile.length(),
                        senderDevice = "Tauri Box",
                        receiverDevice = "Pixel 9",
                        direction = "RECEIVE",
                        status = "COMPLETED",
                        filePath = receivedFile.absolutePath,
                        peerDeviceName = "Tauri Box"
                    )
                )

                val storageVm = StorageViewModel(storage, container.settingsRepository, db.transferDao())
                var deletedCount = -1
                storageVm.deleteSelectedFiles(setOf(receivedFile.absolutePath)) { count ->
                    deletedCount = count
                }
                testDispatcher.scheduler.advanceUntilIdle()

                val deadline = System.currentTimeMillis() + 5_000L
                while (deletedCount == -1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25L)
                    testDispatcher.scheduler.advanceUntilIdle()
                }

                assertEquals(1, deletedCount)
                assertFalse("Physical file should be removed", receivedFile.exists())
                val updatedRow = db.transferDao().getById("sync-tx-1")
                assertNotNull("History metadata row should stay intact", updatedRow)
                assertNull("Stale filePath in Room must be nulled out after file delete", updatedRow?.filePath)
            } finally {
                db.close()
                container.nsdDiscoveryManager.cleanup()
                container.connectionManager.cleanup()
                container.tcpServer.stop()
            }
        }
    }

    @Test
    fun selectedFileFromUri_usesAssetFileDescriptorLengthFallbackForLocalFiles() {
        val sampleFile = File(context.cacheDir, "afd_fallback_${UUID.randomUUID()}.bin").apply {
            writeBytes(ByteArray(1234) { 0x5A })
            cleanupFiles.add(this)
        }
        val selected = SelectedFile.fromUri(context, Uri.fromFile(sampleFile), relativePath = "Folder/${sampleFile.name}")
        assertEquals(1234L, selected.size)
        assertEquals("Folder/${sampleFile.name}", selected.relativePath)
    }

    // -------------------------------------------------------------------------
    // 3. Jetpack Compose UI & Accessibility Tests
    // -------------------------------------------------------------------------

    @Test
    fun transfersScreen_rendersFiltersAndHistoryWithoutOverflow() {
        val container = DefaultAppContainer(context)
        val sample = TransferItem(
            id = "ui-tx-1",
            fileName = "architecture_diagram.png",
            fileExtension = "png",
            fileSize = 204_800L,
            direction = TransferDirection.SEND,
            status = TransferStatus.COMPLETED,
            peerDeviceName = "MacBook Pro",
            senderDevice = "Pixel 9",
            receiverDevice = "MacBook Pro",
            timestamp = System.currentTimeMillis()
        )
        val fakeRepo = object : TransferRepository {
            override val transfers: StateFlow<List<TransferItem>> = MutableStateFlow(listOf(sample))
            override val transferHistory: StateFlow<List<TransferItem>> = MutableStateFlow(listOf(sample))
            override val stats: StateFlow<TransferStats> = MutableStateFlow(TransferStats(completedCount = 1))
            override fun getTransfersByCategory(category: TransferCategory) = listOf(sample)
            override fun addTransfer(item: TransferItem) {}
            override fun cancelTransfer(id: String) {}
            override fun retryTransfer(id: String) {}
            override fun clearCompleted() {}
            override fun clearAll() {}
            override fun deleteHistoryEntry(id: String) {}
            override fun deleteHistoryEntryWithFile(item: TransferItem) {}
        }
        val vm = TransfersViewModel(fakeRepo, container.transferManager, container.storageManager)

        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    TransfersScreen(viewModel = vm)
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("transfers_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("chip_active").assertIsDisplayed()
            composeTestRule.onNodeWithTag("chip_completed").assertIsDisplayed()
            composeTestRule.onNodeWithTag("chip_failed").assertIsDisplayed()
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }

    @Test
    fun transferDetailsScreen_showsMissingNoticeWhenLocalFileRemoved() {
        val container = DefaultAppContainer(context)
        val missingPath = File(context.cacheDir, "non_existent_${UUID.randomUUID()}.pdf").absolutePath
        val sample = TransferItem(
            id = "missing-tx-1",
            fileName = "missing_report.pdf",
            fileExtension = "pdf",
            fileSize = 1024L,
            direction = TransferDirection.RECEIVE,
            status = TransferStatus.COMPLETED,
            filePath = missingPath,
            peerDeviceName = "Tauri Desktop",
            timestamp = System.currentTimeMillis()
        )
        val fakeRepo = object : TransferRepository {
            override val transfers: StateFlow<List<TransferItem>> = MutableStateFlow(listOf(sample))
            override val transferHistory: StateFlow<List<TransferItem>> = MutableStateFlow(listOf(sample))
            override val stats: StateFlow<TransferStats> = MutableStateFlow(TransferStats(completedCount = 1))
            override fun getTransfersByCategory(category: TransferCategory) = listOf(sample)
            override fun addTransfer(item: TransferItem) {}
            override fun cancelTransfer(id: String) {}
            override fun retryTransfer(id: String) {}
            override fun clearCompleted() {}
            override fun clearAll() {}
            override fun deleteHistoryEntry(id: String) {}
            override fun deleteHistoryEntryWithFile(item: TransferItem) {}
        }
        val vm = TransfersViewModel(fakeRepo, container.transferManager, container.storageManager)

        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    TransferDetailsScreen(
                        transferId = "missing-tx-1",
                        viewModel = vm,
                        onBack = {},
                        onViewTrustedDevices = {}
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("transfer_details_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_file_missing_notice")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_open_file_button").assertDoesNotExist()
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }

    @Test
    fun diagnosticsAndPrivacyScreens_renderWithoutErrors() {
        val container = DefaultAppContainer(context)
        val diagVm = DiagnosticsViewModel(
            context = context,
            discoveryRepository = container.deviceDiscoveryRepository,
            connectionManager = container.connectionManager,
            pairedDeviceRepository = container.pairedDeviceRepository,
            tcpServer = container.tcpServer,
            settingsRepository = container.settingsRepository
        )
        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    NetworkDiagnosticsScreen(viewModel = diagVm, onBack = {})
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("network_diagnostics_screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("Network Diagnostics").assertIsDisplayed()
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }

    @Test
    fun privacyDashboardScreen_rendersZeroCloudStatusAndControls() {
        val container = DefaultAppContainer(context)
        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    PrivacyDashboardScreen(
                        settingsRepository = container.settingsRepository,
                        pairedDeviceRepository = container.pairedDeviceRepository,
                        connectionManager = container.connectionManager,
                        transferManager = container.transferManager,
                        storageManager = container.storageManager,
                        nsdDiscoveryManager = container.nsdDiscoveryManager,
                        onBack = {}
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("privacy_dashboard_screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("Privacy Dashboard").assertIsDisplayed()
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }
}
