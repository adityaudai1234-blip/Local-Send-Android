package com.example.data.network

import android.util.Log
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.security.SecurityValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class TcpServer : NetworkServer {

    companion object {
        private const val TAG = "TcpServer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _listeningPort = MutableStateFlow(53317)
    override val listeningPort: StateFlow<Int> = _listeningPort.asStateFlow()

    private var messageHandler: (suspend (ProtocolMessage, Socket) -> ProtocolMessage?)? = null
    private var streamMessageHandler: (suspend (ProtocolMessage, Socket, BufferedInputStream) -> ProtocolMessage?)? = null

    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()

    override fun setMessageHandler(handler: suspend (ProtocolMessage, Socket) -> ProtocolMessage?) {
        this.messageHandler = handler
    }

    override fun setStreamMessageHandler(handler: suspend (ProtocolMessage, Socket, BufferedInputStream) -> ProtocolMessage?) {
        this.streamMessageHandler = handler
    }

    override fun start(port: Int) {
        if (_isRunning.value) return

        serverJob = scope.launch {
            try {
                val safePort = if (SecurityValidator.isValidPort(port)) port else 53317
                val server = ServerSocket(safePort).apply {
                    reuseAddress = true
                }
                serverSocket = server
                _listeningPort.value = server.localPort
                _isRunning.value = true
                Log.d(TAG, "TCP Server started on port ${server.localPort}")

                while (isActive && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        val remoteIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
                        if (!SecurityValidator.allowIncomingConnection(remoteIp)) {
                            Log.w(TAG, "Rate limit exceeded from $remoteIp; closing socket")
                            try {
                                clientSocket.close()
                            } catch (_: Exception) {}
                            continue
                        }
                        activeClientSockets.add(clientSocket)
                        handleClient(clientSocket)
                    } catch (e: Exception) {
                        if (isActive && !server.isClosed) {
                            Log.w(TAG, "Exception accepting socket: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting TCP Server on port $port", e)
            } finally {
                _isRunning.value = false
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                while (isActive && !socket.isClosed && socket.isConnected) {
                    val message = MessageFraming.readFrame(input) ?: break
                    if (message.senderId.isBlank()) {
                        Log.w(TAG, "Rejected malformed protocol frame with blank senderId")
                        break
                    }
                    Log.d(TAG, "Received message type=${message.type} from ${socket.inetAddress?.hostAddress}")

                    val sHandler = streamMessageHandler
                    if (sHandler != null) {
                        val response = sHandler.invoke(message, socket, input)
                        if (response != null) {
                            MessageFraming.writeFrame(output, response)
                        }
                    } else {
                        val handler = messageHandler
                        if (handler != null) {
                            val response = handler.invoke(message, socket)
                            if (response != null) {
                                MessageFraming.writeFrame(output, response)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client socket closed or malformed frame: ${e.message}")
            } finally {
                activeClientSockets.remove(socket)
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }
    }

    override fun stop() {
        _isRunning.value = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        activeClientSockets.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        activeClientSockets.clear()

        serverJob?.cancel()
        serverJob = null
        Log.d(TAG, "TCP Server stopped")
    }
}
