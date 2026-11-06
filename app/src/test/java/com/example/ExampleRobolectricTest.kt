package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.discovery.DiscoveryProtocol
import com.example.data.repository.TransferRepositoryImpl
import com.example.domain.model.DeviceType
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LocalDrop", appName)
    }

    @Test
    fun `verify discovery protocol constants`() {
        assertEquals("_lsend._tcp", DiscoveryProtocol.NSD_SERVICE_TYPE)
        assertEquals(53317, DiscoveryProtocol.DEFAULT_PORT)
        assertEquals("v1", DiscoveryProtocol.PROTOCOL_VERSION)
    }

    @Test
    fun `verify device type identification`() {
        assertEquals(DeviceType.WINDOWS, DeviceType.fromIdentifier("windows"))
        assertEquals(DeviceType.MACOS, DeviceType.fromIdentifier("darwin"))
        assertEquals(DeviceType.MACOS, DeviceType.fromIdentifier("macos"))
        assertEquals(DeviceType.LINUX, DeviceType.fromIdentifier("linux"))
        assertEquals(DeviceType.ANDROID, DeviceType.fromIdentifier("android"))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromIdentifier("other"))
    }

    @Test
    fun `verify transfer repository stats calculation`() {
        val repository = TransferRepositoryImpl()
        val stats = repository.stats.value

        assertTrue(stats.activeCount >= 0)
        assertTrue(stats.completedCount >= 0)
        assertNotNull(stats.formattedTotalBytes)

        val activeList = repository.getTransfersByCategory(TransferCategory.ACTIVE)
        assertEquals(stats.activeCount, activeList.size)
    }

    @Test
    fun `verify format bytes helper`() {
        assertEquals("0 B", TransferItem.formatBytes(0))
        assertEquals("1.0 KB", TransferItem.formatBytes(1024))
        assertEquals("1.0 MB", TransferItem.formatBytes(1024 * 1024))
    }
}
