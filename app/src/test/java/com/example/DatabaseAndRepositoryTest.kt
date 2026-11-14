package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.PairedDeviceEntity
import com.example.data.db.TransferEntity
import com.example.data.security.SecurityKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseAndRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun transferDao_insertSearchAndDeleteHistoryPreservesPhysicalFile() {
        runBlocking {
            val tempPhysicalFile = File(context.cacheDir, "kept_document.pdf").apply {
                writeText("Important local content")
            }

            val dao = database.transferDao()
            val entry1 = TransferEntity(
                id = "tx-1",
                sessionId = "sess-1",
                fileName = "quarterly_report.pdf",
                fileExtension = "pdf",
                mimeType = "application/pdf",
                fileSize = 2048L,
                senderDevice = "Studio MacBook",
                receiverDevice = "Pixel Phone",
                direction = "RECEIVED",
                status = "COMPLETED",
                durationMs = 1200L,
                speed = "1.7 KB/s",
                checksum = "abcd1234",
                filePath = tempPhysicalFile.absolutePath,
                peerDeviceName = "Studio MacBook",
                timestamp = 2000L
            )
            val entry2 = TransferEntity(
                id = "tx-2",
                sessionId = "sess-2",
                fileName = "archive.zip",
                fileExtension = "zip",
                mimeType = "application/zip",
                fileSize = 1048576L,
                senderDevice = "Pixel Phone",
                receiverDevice = "Ubuntu Workstation",
                direction = "SENT",
                status = "FAILED",
                errorMessage = "Socket reset by peer",
                peerDeviceName = "Ubuntu Workstation",
                timestamp = 3000L
            )

            dao.insert(entry1)
            dao.insert(entry2)

            val all = dao.getAll().first()
            assertEquals(2, all.size)
            assertEquals("tx-2", all.first().id)

            val searchByDevice = dao.search("MacBook").first()
            assertEquals(1, searchByDevice.size)
            assertEquals("tx-1", searchByDevice.first().id)

            val searchByExt = dao.search("zip").first()
            assertEquals(1, searchByExt.size)
            assertEquals("tx-2", searchByExt.first().id)

            // Deleting history entry MUST NOT delete physical file on disk
            dao.deleteById("tx-1")
            assertNull(dao.getById("tx-1"))
            assertTrue("Physical file must remain untouched when deleting history record", tempPhysicalFile.exists())

            dao.deleteAll()
            assertTrue(dao.getAll().first().isEmpty())
            tempPhysicalFile.delete()
        }
    }

    @Test
    fun pairedDeviceDao_insertRenameAndDelete() {
        runBlocking {
            val dao = database.pairedDeviceDao()
            val device = PairedDeviceEntity(
                id = "tauri-win-1",
                alias = "Workstation PC",
                deviceType = "windows",
                ipAddress = "192.168.1.45",
                port = 53317,
                encryptedAuthToken = "encrypted_blob",
                isTrusted = true
            )
            dao.insertOrUpdate(device)
            assertEquals("Workstation PC", dao.getById("tauri-win-1")?.alias)

            dao.renameDevice("tauri-win-1", "Main Windows Rig")
            assertEquals("Main Windows Rig", dao.getById("tauri-win-1")?.alias)

            dao.deleteById("tauri-win-1")
            assertNull(dao.getById("tauri-win-1"))
        }
    }

    @Test
    fun securityKeyStore_encryptsDecryptsAndWipesKeys() {
        val ks = SecurityKeyStore()
        val rawSecret = ks.generateSecureToken()
        val encrypted = ks.encryptToken(rawSecret)
        val decrypted = ks.decryptToken(encrypted)
        assertNotNull(decrypted)
        assertEquals(rawSecret, decrypted)

        ks.clearAllKeys()
        // Re-encryption after wipe succeeds with fresh key
        val nextEncrypted = ks.encryptToken("fresh-secret")
        assertEquals("fresh-secret", ks.decryptToken(nextEncrypted))
    }
}
