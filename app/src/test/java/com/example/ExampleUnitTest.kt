package com.example

import com.example.data.discovery.DiscoveryProtocol
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessageType
import com.example.data.repository.TransferRepositoryImpl
import com.example.domain.model.DeviceType
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExampleUnitTest {

    @Test
    fun verify_discovery_protocol_constants() {
        assertEquals("_lsend._tcp", DiscoveryProtocol.NSD_SERVICE_TYPE)
        assertEquals(53317, DiscoveryProtocol.DEFAULT_PORT)
        assertEquals("v1", DiscoveryProtocol.PROTOCOL_VERSION)
    }

    @Test
    fun verify_protocol_message_types() {
        assertEquals(ProtocolMessageType.PAIRING_REQUEST, ProtocolMessageType.fromString("PAIRING_REQUEST"))
        assertEquals(ProtocolMessageType.PAIRING_ACCEPTED, ProtocolMessageType.fromString("PAIRING_ACCEPTED"))
        assertEquals(ProtocolMessageType.AUTHENTICATION_REQUEST, ProtocolMessageType.fromString("AUTHENTICATION_REQUEST"))
        assertEquals(ProtocolMessageType.HEARTBEAT, ProtocolMessageType.fromString("HEARTBEAT"))
        assertEquals(ProtocolMessageType.ERROR, ProtocolMessageType.fromString("unknown_type"))
    }

    @Test
    fun verify_device_type_identification() {
        assertEquals(DeviceType.WINDOWS, DeviceType.fromIdentifier("windows"))
        assertEquals(DeviceType.MACOS, DeviceType.fromIdentifier("darwin"))
        assertEquals(DeviceType.MACOS, DeviceType.fromIdentifier("macos"))
        assertEquals(DeviceType.LINUX, DeviceType.fromIdentifier("linux"))
        assertEquals(DeviceType.ANDROID, DeviceType.fromIdentifier("android"))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromIdentifier("other"))
    }

    @Test
    fun verify_transfer_repository_stats_calculation() {
        val repository = TransferRepositoryImpl()
        val stats = repository.stats.value

        assertTrue(stats.activeCount >= 0)
        assertTrue(stats.completedCount >= 0)
        assertNotNull(stats.formattedTotalBytes)

        val activeList = repository.getTransfersByCategory(TransferCategory.ACTIVE)
        assertEquals(stats.activeCount, activeList.size)
    }

    @Test
    fun verify_format_bytes_helper() {
        assertEquals("0 B", TransferItem.formatBytes(0))
        assertEquals("1.0 KB", TransferItem.formatBytes(1024))
        assertEquals("1.0 MB", TransferItem.formatBytes(1024 * 1024))
    }

    @Test
    fun verify_message_framing_spec() {
        assertEquals(2 * 1024 * 1024, MessageFraming.MAX_FRAME_SIZE)

        val rawJson = "{\"version\":\"v1\",\"type\":\"HEARTBEAT\"}"
        val jsonBytes = rawJson.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + jsonBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(jsonBytes.size)
        buffer.put(jsonBytes)
        val frame = buffer.array()

        val lengthBuffer = ByteBuffer.wrap(frame.copyOfRange(0, 4)).order(ByteOrder.BIG_ENDIAN)
        val decodedLen = lengthBuffer.int
        assertEquals(jsonBytes.size, decodedLen)

        val decodedJson = String(frame.copyOfRange(4, 4 + decodedLen), Charsets.UTF_8)
        assertEquals(rawJson, decodedJson)
    }
}
