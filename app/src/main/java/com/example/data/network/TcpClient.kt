package com.example.data.network

import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.util.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TcpClient(
    private val performanceMonitor: PerformanceMonitor? = null
) : NetworkClient {

    override suspend fun sendMessage(
        host: String,
        port: Int,
        message: ProtocolMessage,
        timeoutMs: Int
    ): Result<ProtocolMessage> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var tracked = false
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            performanceMonitor?.onSocketOpened()
            tracked = true

            val output = BufferedOutputStream(socket.getOutputStream())
            MessageFraming.writeFrame(output, message)

            val input = BufferedInputStream(socket.getInputStream())
            val response = MessageFraming.readFrame(input)
                ?: return@withContext Result.failure(Exception("Peer closed connection unexpectedly"))

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
            if (tracked) {
                performanceMonitor?.onSocketClosed()
            }
        }
    }

    override suspend fun sendOneWay(
        host: String,
        port: Int,
        message: ProtocolMessage,
        timeoutMs: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var tracked = false
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            performanceMonitor?.onSocketOpened()
            tracked = true

            val output = BufferedOutputStream(socket.getOutputStream())
            MessageFraming.writeFrame(output, message)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
            if (tracked) {
                performanceMonitor?.onSocketClosed()
            }
        }
    }
}
