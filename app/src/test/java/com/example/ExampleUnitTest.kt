package com.example

import com.example.data.discovery.DiscoveryProtocol
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessageType
import com.example.data.transfer.FileMetadata
import com.example.data.transfer.FileStreamFraming
import com.example.domain.model.DeviceType
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        assertEquals(ProtocolMessageType.TRANSFER_REQUEST, ProtocolMessageType.fromString("TRANSFER_REQUEST"))
        assertEquals(ProtocolMessageType.TRANSFER_ACCEPTED, ProtocolMessageType.fromString("TRANSFER_ACCEPTED"))
        assertEquals(ProtocolMessageType.TRANSFER_STARTED, ProtocolMessageType.fromString("TRANSFER_STARTED"))
        assertEquals(ProtocolMessageType.TRANSFER_COMPLETED, ProtocolMessageType.fromString("TRANSFER_COMPLETED"))
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
    fun verify_filename_path_traversal_sanitization() {
        assertEquals("report.pdf", FileMetadata.sanitizeFileName("report.pdf"))
        assertEquals("passwd", FileMetadata.sanitizeFileName("../../../etc/passwd"))
        assertEquals("file.txt", FileMetadata.sanitizeFileName("C:\\Windows\\System32\\file.txt"))
        assertEquals("archive.zip", FileMetadata.sanitizeFileName("nested/dir/../archive.zip"))
        assertTrue(FileMetadata.sanitizeFileName("").isNotBlank())
        assertTrue(FileMetadata.sanitizeFileName("   ").isNotBlank())
    }

    @Test
    fun verify_binary_stream_framing_constants() {
        assertEquals(0x4C534431, FileStreamFraming.CHUNK_MAGIC) // "LSD1"
        assertEquals(0x4C53454F, FileStreamFraming.EOF_MAGIC)   // "LSEO"
        assertEquals(65536, FileStreamFraming.DEFAULT_CHUNK_SIZE)
    }

    @Test
    fun verify_binary_stream_chunk_write_and_read() {
        val testData = "Hello LocalDrop Streaming over TCP".toByteArray(Charsets.UTF_8)
        val outputStream = ByteArrayOutputStream()

        FileStreamFraming.writeChunk(
            outputStream = outputStream,
            fileIndex = 0,
            chunkOffset = 0L,
            data = testData,
            length = testData.size
        )

        val frameBytes = outputStream.toByteArray()
        assertTrue(frameBytes.size >= 20 + testData.size)

        val inputStream = ByteArrayInputStream(frameBytes)
        val readBuffer = ByteArray(1024)
        val result = FileStreamFraming.readNextFrame(inputStream, readBuffer)

        assertTrue(result is FileStreamFraming.FrameReadResult.Chunk)
        val chunk = result as FileStreamFraming.FrameReadResult.Chunk
        assertEquals(0, chunk.fileIndex)
        assertEquals(0L, chunk.chunkOffset)
        assertEquals(testData.size, chunk.length)

        val payload = chunk.data.copyOfRange(0, chunk.length)
        assertArrayEquals(testData, payload)
    }

    @Test
    fun verify_binary_stream_eof_write_and_read() {
        val sha256Bytes = ByteArray(32) { (it + 1).toByte() }
        val outputStream = ByteArrayOutputStream()

        FileStreamFraming.writeEof(
            outputStream = outputStream,
            fileIndex = 2,
            sha256Hash = sha256Bytes
        )

        val frameBytes = outputStream.toByteArray()
        assertEquals(40, frameBytes.size) // 4 (magic) + 4 (index) + 32 (hash)

        val inputStream = ByteArrayInputStream(frameBytes)
        val readBuffer = ByteArray(1024)
        val result = FileStreamFraming.readNextFrame(inputStream, readBuffer)

        assertTrue(result is FileStreamFraming.FrameReadResult.Eof)
        val eof = result as FileStreamFraming.FrameReadResult.Eof
        assertEquals(2, eof.fileIndex)
        assertEquals(FileStreamFraming.bytesToHex(sha256Bytes), eof.sha256Hex)
    }

    @Test
    fun verify_transfer_stats_model() {
        val stats = TransferStats(
            activeCount = 2,
            completedCount = 5,
            failedCount = 1,
            totalFilesTransferred = 5,
            totalBytesTransferred = 104857600L // 100 MB
        )

        assertEquals(2, stats.activeCount)
        assertEquals(5, stats.completedCount)
        assertEquals(1, stats.failedCount)
        assertEquals("100.0 MB", stats.formattedTotalBytes)
    }

    @Test
    fun verify_format_bytes_helper() {
        assertEquals("0 B", TransferItem.formatBytes(0))
        assertEquals("1.0 KB", TransferItem.formatBytes(1024))
        assertEquals("1.0 MB", TransferItem.formatBytes(1024 * 1024))
        assertEquals("1.5 GB", TransferItem.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
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
