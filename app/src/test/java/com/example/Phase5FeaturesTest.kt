package com.example

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.PairedDeviceEntity
import com.example.data.network.ConnectionManager
import com.example.data.network.TcpClient
import com.example.data.security.SecurityValidator
import com.example.data.transfer.FileMetadata
import com.example.data.transfer.ReceivedFileCategory
import com.example.data.transfer.StorageManager
import com.example.di.DefaultAppContainer
import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppSettings
import com.example.domain.model.DeviceType
import com.example.domain.model.PairedDevice
import com.example.domain.model.TransferStats
import com.example.util.FilePreviewHelper
import com.example.util.PreviewKind
import com.example.widget.WidgetUpdateHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5FeaturesTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var storageManager: StorageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = StorageManager(context)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun relativePath_sanitizationPreservesSubfoldersAndBlocksTraversal() {
        assertEquals(
            "TripPhotos/Day1/photo.jpg",
            FileMetadata.sanitizeRelativePath("/TripPhotos\\Day1/photo.jpg")
        )
        assertTrue(SecurityValidator.isRelativePathSafe("Projects/Mobile/notes.md"))

        assertNull(FileMetadata.sanitizeRelativePath("../../etc/passwd"))
        assertNull(FileMetadata.sanitizeRelativePath("Folder/../Secret/file.txt"))
        assertNull(FileMetadata.sanitizeRelativePath("C:\\Windows\\System32\\cmd.exe"))
        assertNull(FileMetadata.sanitizeRelativePath("Folder/file\u0000.txt"))
        assertFalse(SecurityValidator.isRelativePathSafe("../escape.txt"))
        assertFalse(SecurityValidator.isRelativePathSafe(""))
    }

    @Test
    fun storageManager_resolveDestinationPreservesRelativePathAndOrganizesByType() {
        val baseDir = storageManager.getDownloadDirectory()

        val relResolution = storageManager.resolveDestinationWithPolicy(
            fileName = "sunset.png",
            duplicateHandling = "rename",
            relativePath = "Vacation/Beach/sunset.png",
            organizeByType = false,
            mimeType = "image/png"
        )
        assertTrue(relResolution is StorageManager.DestinationResolution.Ready)
        val relFile = (relResolution as StorageManager.DestinationResolution.Ready).file
        assertTrue(relFile.canonicalPath.startsWith(baseDir.canonicalPath))
        assertTrue(relFile.canonicalPath.endsWith("Vacation/Beach/sunset.png"))

        val organizedResolution = storageManager.resolveDestinationWithPolicy(
            fileName = "contract.pdf",
            duplicateHandling = "rename",
            relativePath = null,
            organizeByType = true,
            mimeType = "application/pdf"
        )
        assertTrue(organizedResolution is StorageManager.DestinationResolution.Ready)
        val orgFile = (organizedResolution as StorageManager.DestinationResolution.Ready).file
        assertTrue(orgFile.canonicalPath.startsWith(baseDir.canonicalPath))
        assertTrue(orgFile.canonicalPath.endsWith("Documents/contract.pdf"))

        // Traversal attempt in relativePath must be stripped and fall back safely inside baseDir
        val traversalFallback = storageManager.resolveDestinationWithPolicy(
            fileName = "payload.txt",
            duplicateHandling = "rename",
            relativePath = "../../outside/payload.txt",
            organizeByType = false,
            mimeType = "text/plain"
        )
        assertTrue(traversalFallback is StorageManager.DestinationResolution.Ready)
        val safeFile = (traversalFallback as StorageManager.DestinationResolution.Ready).file
        assertEquals(baseDir.canonicalPath, safeFile.parentFile?.canonicalPath)
    }

    @Test
    fun storageManager_categorizeFileCoversAllReceivedCategories() {
        assertEquals(ReceivedFileCategory.IMAGES, StorageManager.categorizeFile("photo.jpg", "image/jpeg"))
        assertEquals(ReceivedFileCategory.VIDEOS, StorageManager.categorizeFile("clip.mp4", "video/mp4"))
        assertEquals(ReceivedFileCategory.AUDIO, StorageManager.categorizeFile("song.flac", "audio/flac"))
        assertEquals(ReceivedFileCategory.DOCUMENTS, StorageManager.categorizeFile("report.pdf", "application/pdf"))
        assertEquals(ReceivedFileCategory.DOCUMENTS, StorageManager.categorizeFile("readme.md", "text/markdown"))
        assertEquals(ReceivedFileCategory.ARCHIVES, StorageManager.categorizeFile("bundle.zip", "application/zip"))
        assertEquals(ReceivedFileCategory.APPLICATIONS, StorageManager.categorizeFile("build.apk", "application/vnd.android.package-archive"))
        assertEquals(ReceivedFileCategory.OTHER, StorageManager.categorizeFile("data.bin", "application/octet-stream"))
    }

    @Test
    fun filePreviewHelper_calculatesSampleSizeAndDetectsKinds() {
        assertEquals(1, FilePreviewHelper.calculateInSampleSize(300, 300, 360, 360))
        assertEquals(4, FilePreviewHelper.calculateInSampleSize(1440, 1440, 360, 360))
        assertEquals(8, FilePreviewHelper.calculateInSampleSize(4000, 3000, 360, 360))

        assertEquals(PreviewKind.IMAGE, FilePreviewHelper.detectKind("hero.webp"))
        assertEquals(PreviewKind.VIDEO, FilePreviewHelper.detectKind("demo.mkv"))
        assertEquals(PreviewKind.AUDIO, FilePreviewHelper.detectKind("podcast.mp3"))
        assertEquals(PreviewKind.PDF, FilePreviewHelper.detectKind("invoice.pdf"))
        assertEquals(PreviewKind.TEXT, FilePreviewHelper.detectKind("MainActivity.kt"))
        assertEquals(PreviewKind.ARCHIVE, FilePreviewHelper.detectKind("release.tar.gz"))
        assertEquals(PreviewKind.APK, FilePreviewHelper.detectKind("app-debug.apk"))
        assertEquals(PreviewKind.OTHER, FilePreviewHelper.detectKind("firmware.img"))
    }

    @Test
    fun pairedDeviceDao_favoritesOrderingUsageEndpointAndAutoAccept() {
        runBlocking {
            val dao = database.pairedDeviceDao()
            val recentRegular = PairedDeviceEntity(
                id = "peer-1",
                alias = "Studio Linux",
                deviceType = "linux",
                ipAddress = "192.168.1.10",
                port = 53317,
                encryptedAuthToken = "tok-1",
                lastConnectedAt = 5000L,
                isFavorite = false
            )
            val olderFavorite = PairedDeviceEntity(
                id = "peer-2",
                alias = "Mac Mini",
                deviceType = "macos",
                ipAddress = "192.168.1.20",
                port = 53317,
                encryptedAuthToken = "tok-2",
                lastConnectedAt = 1000L,
                isFavorite = true
            )
            dao.insertOrUpdate(recentRegular)
            dao.insertOrUpdate(olderFavorite)

            val ordered = dao.getAll().first()
            assertEquals(listOf("peer-2", "peer-1"), ordered.map { it.id })

            dao.setFavorite("peer-1", true)
            dao.incrementUsage("peer-1", 9000L)
            dao.updateNetworkEndpoint("peer-1", "192.168.1.99", 54000, 9500L)
            dao.setAutoAccept("peer-1", true)

            val updated = dao.getById("peer-1")
            assertNotNull(updated)
            assertTrue(updated!!.isFavorite)
            assertEquals(1, updated.useCount)
            assertEquals(9000L, updated.lastTransferredAt)
            assertEquals("192.168.1.99", updated.ipAddress)
            assertEquals(54000, updated.port)
            assertEquals(9500L, updated.lastConnectedAt)
            assertTrue(updated.autoAccept)
        }
    }

    @Test
    fun phase5SettingsAndDeviceDefaults_enforcePrivacyAndSensibleConfig() {
        val settings = AppSettings(deviceName = "Pixel 9 Pro")
        assertFalse("autoAcceptTrusted must default to false", settings.autoAcceptTrusted)
        assertEquals(64, settings.transferBufferSizeKb)
        assertEquals(AccentColorOption.TEAL, settings.accentColor)
        assertTrue(settings.suggestRecentDevices)
        assertTrue(settings.rememberLastDestination)
        assertFalse(settings.organizeReceivedByType)
        assertTrue(settings.autoCleanTempFiles)

        val paired = PairedDevice(
            id = "peer-default",
            alias = "Desktop",
            deviceType = DeviceType.WINDOWS,
            ipAddress = "192.168.1.50"
        )
        assertFalse("Per-device autoAccept must default to false", paired.autoAccept)
        assertFalse(paired.isFavorite)
        assertEquals(0, paired.useCount)
    }

    @Test
    fun shareIntentExtractor_handlesSingleMultipleAndClipDataUris() {
        val uri1 = Uri.parse("content://media/external/images/1")
        val uri2 = Uri.parse("content://media/external/images/2")
        val uri3 = Uri.parse("content://media/external/file/3")

        val singleIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri1)
        }
        assertEquals(listOf(uri1), ShareIntentExtractor.extractSharedUris(singleIntent))

        val multipleIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
        }
        assertEquals(listOf(uri1, uri2), ShareIntentExtractor.extractSharedUris(multipleIntent))

        val clipFallbackIntent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("Shared", uri3)
        }
        assertEquals(listOf(uri3), ShareIntentExtractor.extractSharedUris(clipFallbackIntent))

        assertTrue(ShareIntentExtractor.extractSharedUris(Intent(Intent.ACTION_MAIN)).isEmpty())
    }

    @Test
    fun connectionManager_recordsCapsAndClearsRecentErrors() {
        val cm = ConnectionManager(
            client = TcpClient(),
            myDeviceIdProvider = { "local-node" }
        )
        try {
            repeat(30) { idx ->
                cm.recordError("Peer $idx", "192.168.1.$idx:53317", "Failure #$idx")
            }
            val errors = cm.recentErrors.value
            assertEquals(25, errors.size)
            assertEquals("Peer 29", errors.first().targetLabel)
            assertEquals("Failure #29", errors.first().message)

            cm.clearRecentErrors()
            assertTrue(cm.recentErrors.value.isEmpty())
        } finally {
            cm.cleanup()
        }
    }

    @Test
    fun transferManager_estimateTransferSecondsRequiresMeasuredBaseline() {
        val container = DefaultAppContainer(context)
        val manager = container.transferManager

        // Fresh manager with zero measured historical average speed returns null (never fabricated)
        assertNull(manager.estimateTransferSeconds(10 * 1024 * 1024L))
        assertNull(manager.estimateTransferSeconds(0L))

        // Reflectively verify baseline calculation with a real measured TransferStats value
        val statsField = manager.javaClass.getDeclaredField("_stats").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statsFlow = statsField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<TransferStats>
        statsFlow.value = TransferStats(averageSpeedBytesPerSec = 5 * 1024 * 1024L) // 5 MB/s measured

        val estimated = manager.estimateTransferSeconds(25 * 1024 * 1024L)
        assertEquals(5L, estimated)
    }

    @Test
    fun widgetUpdateHelper_computesSnapshotWithoutCrash() {
        runBlocking {
            val snapshot = WidgetUpdateHelper.computeSnapshot(context)
            assertTrue(snapshot.nearbyCount >= 0)
            assertTrue(snapshot.connectedCount >= 0)
            assertTrue(snapshot.overallProgressPercent in 0..100)
            assertNotNull(snapshot.totalBytesText)
        }
    }
}
