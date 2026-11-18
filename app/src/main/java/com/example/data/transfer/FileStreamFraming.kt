package com.example.data.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object FileStreamFraming {

    const val CHUNK_MAGIC = 0x4C534431 // "LSD1" (LocalSendDrop1)
    const val EOF_MAGIC = 0x4C53454F   // "LSEO" (LocalSendEOf)
    const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 Kilobytes

    /**
     * Writes a binary chunk frame to the output stream.
     * Header format:
     * [4-byte Int: CHUNK_MAGIC]
     * [4-byte Int: fileIndex]
     * [8-byte Long: chunkOffset]
     * [4-byte Int: chunkLength]
     * [chunkLength bytes: data]
     */
    fun writeChunk(
        outputStream: OutputStream,
        fileIndex: Int,
        chunkOffset: Long,
        data: ByteArray,
        length: Int
    ) {
        val dataOut = DataOutputStream(outputStream)
        dataOut.writeInt(CHUNK_MAGIC)
        dataOut.writeInt(fileIndex)
        dataOut.writeLong(chunkOffset)
        dataOut.writeInt(length)
        dataOut.write(data, 0, length)
        dataOut.flush()
    }

    /**
     * Writes an EOF frame containing the calculated SHA-256 checksum.
     * [4-byte Int: EOF_MAGIC]
     * [4-byte Int: fileIndex]
     * [32-byte SHA-256 hash bytes]
     */
    fun writeEof(
        outputStream: OutputStream,
        fileIndex: Int,
        sha256Hash: ByteArray
    ) {
        val dataOut = DataOutputStream(outputStream)
        dataOut.writeInt(EOF_MAGIC)
        dataOut.writeInt(fileIndex)
        val hash32 = if (sha256Hash.size == 32) sha256Hash else ByteArray(32)
        dataOut.write(hash32)
        dataOut.flush()
    }

    sealed interface FrameReadResult {
        data class Chunk(val fileIndex: Int, val chunkOffset: Long, val data: ByteArray, val length: Int) : FrameReadResult
        data class Eof(val fileIndex: Int, val sha256Hex: String) : FrameReadResult
        data object ConnectionClosed : FrameReadResult
    }

    /**
     * Reads the next frame (Chunk or EOF) from the incoming stream.
     */
    fun readNextFrame(inputStream: InputStream, reusableBuffer: ByteArray): FrameReadResult {
        val dataIn = DataInputStream(inputStream)
        val magic = try {
            dataIn.readInt()
        } catch (_: Exception) {
            return FrameReadResult.ConnectionClosed
        }

        return when (magic) {
            CHUNK_MAGIC -> {
                val fileIndex = dataIn.readInt()
                val chunkOffset = dataIn.readLong()
                val length = dataIn.readInt()
                if (length < 0 || length > DEFAULT_CHUNK_SIZE * 8) {
                    throw IllegalArgumentException("Invalid chunk length: $length")
                }
                val buffer = if (reusableBuffer.size >= length) reusableBuffer else ByteArray(length)
                dataIn.readFully(buffer, 0, length)
                FrameReadResult.Chunk(fileIndex, chunkOffset, buffer, length)
            }
            EOF_MAGIC -> {
                val fileIndex = dataIn.readInt()
                val hashBytes = ByteArray(32)
                dataIn.readFully(hashBytes)
                val hexString = hashBytes.joinToString("") { "%02x".format(it) }
                FrameReadResult.Eof(fileIndex, hexString)
            }
            else -> {
                throw IllegalStateException("Unexpected stream magic byte: 0x${Integer.toHexString(magic)}")
            }
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
