package com.example.data.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MessageFraming {

    const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2 Megabytes safety limit

    /**
     * Encodes a ProtocolMessage into length-prefixed byte array:
     * [4 bytes Big-Endian Int: length][JSON bytes]
     */
    fun encodeFrame(message: ProtocolMessage): ByteArray {
        val jsonBytes = message.toJsonString().toByteArray(Charsets.UTF_8)
        val length = jsonBytes.size

        val buffer = ByteBuffer.allocate(4 + length).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(length)
        buffer.put(jsonBytes)
        return buffer.array()
    }

    /**
     * Writes an encoded frame directly to an OutputStream.
     */
    fun writeFrame(outputStream: OutputStream, message: ProtocolMessage) {
        val dataOut = DataOutputStream(outputStream)
        val jsonBytes = message.toJsonString().toByteArray(Charsets.UTF_8)
        dataOut.writeInt(jsonBytes.size)
        dataOut.write(jsonBytes)
        dataOut.flush()
    }

    /**
     * Reads a complete length-prefixed message frame from an InputStream.
     * Accurately handles TCP stream fragmentation.
     * Returns null if stream is closed (EOF).
     */
    fun readFrame(inputStream: InputStream): ProtocolMessage? {
        val dataIn = DataInputStream(inputStream)
        val length = try {
            dataIn.readInt()
        } catch (_: Exception) {
            return null // Connection closed or EOF
        }

        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IllegalArgumentException("Invalid frame length: $length bytes (max allowed $MAX_FRAME_SIZE)")
        }

        val payloadBytes = ByteArray(length)
        dataIn.readFully(payloadBytes)

        val jsonString = String(payloadBytes, Charsets.UTF_8)
        return ProtocolMessage.fromJsonString(jsonString)
    }
}
