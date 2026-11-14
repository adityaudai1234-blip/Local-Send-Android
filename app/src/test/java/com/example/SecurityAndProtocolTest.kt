package com.example

import com.example.data.network.TlsTransport
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.data.security.SecurityValidator
import com.example.data.transfer.FileStreamFraming
import com.example.presentation.navigation.Screen
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SecurityAndProtocolTest {

    @Before
    fun setup() {
        SecurityValidator.clearRateLimiters()
    }

    @After
    fun teardown() {
        SecurityValidator.clearRateLimiters()
    }

    @Test
    fun securityValidator_sanitizesPathTraversalAndChecksSafety() {
        assertEquals("secret.txt", SecurityValidator.sanitizeFileName("../../../../etc/secret.txt"))
        assertEquals("payload.exe", SecurityValidator.sanitizeFileName("C:\\Users\\Admin\\payload.exe"))
        assertTrue(SecurityValidator.isPathSafe("holiday_photo.jpg"))
        assertFalse(SecurityValidator.isPathSafe("../evil.sh"))
        assertFalse(SecurityValidator.isPathSafe("nested/evil.txt"))
        assertFalse(SecurityValidator.isPathSafe("dir\\evil.txt"))
        assertFalse(SecurityValidator.isPathSafe("null\u0000byte.txt"))
        assertFalse(SecurityValidator.isPathSafe("   "))
    }

    @Test
    fun securityValidator_detectsSuspiciousExtensions() {
        assertTrue(SecurityValidator.isSuspiciousExtension("installer.apk"))
        assertTrue(SecurityValidator.isSuspiciousExtension("SETUP.EXE"))
        assertTrue(SecurityValidator.isSuspiciousExtension("run_me.bat"))
        assertTrue(SecurityValidator.isSuspiciousExtension("script.sh"))
        assertTrue(SecurityValidator.isSuspiciousExtension("payload.ps1"))
        assertFalse(SecurityValidator.isSuspiciousExtension("document.pdf"))
        assertFalse(SecurityValidator.isSuspiciousExtension("vacation.png"))
        assertFalse(SecurityValidator.isSuspiciousExtension("archive.zip"))
    }

    @Test
    fun securityValidator_validatesPortsAndFileSizes() {
        assertTrue(SecurityValidator.isValidPort(53317))
        assertTrue(SecurityValidator.isValidPort(1024))
        assertTrue(SecurityValidator.isValidPort(65535))
        assertFalse(SecurityValidator.isValidPort(80))
        assertFalse(SecurityValidator.isValidPort(1023))
        assertFalse(SecurityValidator.isValidPort(65536))

        assertTrue(SecurityValidator.isFileSizeAllowed(500_000_000L, maxAcceptedMb = 0L))
        assertTrue(SecurityValidator.isFileSizeAllowed(10 * 1024 * 1024L, maxAcceptedMb = 10L))
        assertFalse(SecurityValidator.isFileSizeAllowed(11 * 1024 * 1024L, maxAcceptedMb = 10L))
        assertFalse(SecurityValidator.isFileSizeAllowed(-1L, maxAcceptedMb = 0L))
    }

    @Test
    fun securityValidator_rateLimiterBlocksFloodingAndRecoversAfterWindow() {
        val ip = "192.168.1.55"
        val t0 = 1_000_000L
        repeat(30) { idx ->
            assertTrue("Request $idx should be allowed", SecurityValidator.allowIncomingConnection(ip, t0 + idx))
        }
        assertFalse("31st request inside window must be blocked", SecurityValidator.allowIncomingConnection(ip, t0 + 100))
        assertTrue("Requests after 10s window should pass again", SecurityValidator.allowIncomingConnection(ip, t0 + 11_000L))
    }

    @Test
    fun securityValidator_authLockoutAfterRepeatedFailures() {
        val peerId = "peer-abc"
        val t0 = 2_000_000L
        assertFalse(SecurityValidator.isPeerAuthLockedOut(peerId, t0))

        repeat(4) {
            SecurityValidator.recordAuthResult(peerId, success = false, now = t0 + it)
            assertFalse(SecurityValidator.isPeerAuthLockedOut(peerId, t0 + it))
        }
        SecurityValidator.recordAuthResult(peerId, success = false, now = t0 + 10)
        assertTrue("5th failed auth should lock out peer", SecurityValidator.isPeerAuthLockedOut(peerId, t0 + 20))

        // Lockout expires after 60 seconds
        assertFalse(SecurityValidator.isPeerAuthLockedOut(peerId, t0 + 62_000L))

        // Successful auth clears failure counter
        SecurityValidator.recordAuthResult(peerId, success = false, now = t0 + 70_000L)
        SecurityValidator.recordAuthResult(peerId, success = true, now = t0 + 70_100L)
        assertFalse(SecurityValidator.isPeerAuthLockedOut(peerId, t0 + 70_200L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun messageFraming_rejectsOversizedFrames() {
        val oversizedHeader = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MessageFraming.MAX_FRAME_SIZE + 1)
            .array()
        MessageFraming.readFrame(ByteArrayInputStream(oversizedHeader))
    }

    @Test
    fun fileStreamFraming_multiChunkAndSha256RoundTrip() {
        val part1 = "LocalDrop chunk one • ".toByteArray()
        val part2 = "LocalDrop chunk two verified!".toByteArray()
        val fullContent = part1 + part2
        val digest = MessageDigest.getInstance("SHA-256").digest(fullContent)

        val out = ByteArrayOutputStream()
        FileStreamFraming.writeChunk(out, fileIndex = 0, chunkOffset = 0L, data = part1, length = part1.size)
        FileStreamFraming.writeChunk(out, fileIndex = 0, chunkOffset = part1.size.toLong(), data = part2, length = part2.size)
        FileStreamFraming.writeEof(out, fileIndex = 0, sha256Hash = digest)

        val input = ByteArrayInputStream(out.toByteArray())
        val buf = ByteArray(1024)
        val reassembled = ByteArrayOutputStream()

        val f1 = FileStreamFraming.readNextFrame(input, buf) as FileStreamFraming.FrameReadResult.Chunk
        reassembled.write(f1.data, 0, f1.length)

        val f2 = FileStreamFraming.readNextFrame(input, buf) as FileStreamFraming.FrameReadResult.Chunk
        assertEquals(part1.size.toLong(), f2.chunkOffset)
        reassembled.write(f2.data, 0, f2.length)

        val eof = FileStreamFraming.readNextFrame(input, buf) as FileStreamFraming.FrameReadResult.Eof
        assertArrayEquals(fullContent, reassembled.toByteArray())
        assertEquals(FileStreamFraming.bytesToHex(digest), eof.sha256Hex)
    }

    @Test
    fun tlsTransport_detectsClientHelloBytesWithoutConsumingStream() {
        val tlsHeader = BufferedInputStream(ByteArrayInputStream(byteArrayOf(0x16, 0x03, 0x01, 0x00)))
        assertTrue(TlsTransport.isTlsClientHello(tlsHeader))
        assertEquals(0x16, tlsHeader.read())

        val jsonLengthHeader = BufferedInputStream(ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x01, 0x20)))
        assertFalse(TlsTransport.isTlsClientHello(jsonLengthHeader))
        assertEquals(0x00, jsonLengthHeader.read())
    }

    @Test
    fun navigationRoutes_andBottomNavItems_areNonNullAndConsistent() {
        val navItems = Screen.bottomNavItems
        assertEquals(4, navItems.size)
        assertEquals("home", navItems[0].route)
        assertEquals("devices", navItems[1].route)
        assertEquals("transfers", navItems[2].route)
        assertEquals("settings", navItems[3].route)
        assertEquals("transfer_details/tx-42", Screen.transferDetailsRoute("tx-42"))
    }
}
