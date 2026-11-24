package com.example.data.network

import android.util.Log
import com.example.data.protocol.MessageFraming
import com.example.data.protocol.ProtocolMessage
import com.example.data.security.SecurityValidator
import com.example.util.PerformanceMonitor
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

class TcpServer(
    private val performanceMonitor: PerformanceMonitor? = null
) : NetworkServer {

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
                        performanceMonitor?.onSocketOpened()
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

    private var serverSslContext: javax.net.ssl.SSLContext? = null

    fun configureTlsServerContext(sslContext: javax.net.ssl.SSLContext?) {
        serverSslContext = sslContext
    }

    private fun handleClient(initialSocket: Socket) {
        scope.launch {
            var activeSocket: Socket = initialSocket
            try {
                activeSocket.soTimeout = 120_000
                var input = BufferedInputStream(activeSocket.getInputStream())
                var output = BufferedOutputStream(activeSocket.getOutputStream())

                if (TlsTransport.isTlsClientHello(input)) {
                    val sslContext = serverSslContext
                    if (sslContext != null) {
                        val host = activeSocket.inetAddress?.hostAddress ?: "localhost"
                        val peerPort = activeSocket.port.coerceAtLeast(1)
                        val wrappedSocket = PeekedInputSocket(activeSocket, input)
                        val sslSocket = sslContext.socketFactory.createSocket(
                            wrappedSocket,
                            host,
                            peerPort,
                            true
                        ) as javax.net.ssl.SSLSocket
                        sslSocket.useClientMode = false
                        sslSocket.enabledProtocols = sslSocket.supportedProtocols.filter {
                            it.startsWith("TLSv1.2") || it.startsWith("TLSv1.3")
                        }.toTypedArray().ifEmpty { sslSocket.enabledProtocols }
                        sslSocket.startHandshake()
                        activeSocket = sslSocket
                        activeClientSockets.add(sslSocket)
                        input = BufferedInputStream(sslSocket.getInputStream())
                        output = BufferedOutputStream(sslSocket.getOutputStream())
                    } else {
                        Log.w(
                            TAG,
                            "TLS ClientHello received from ${activeSocket.inetAddress?.hostAddress} without configured server KeyStore; closing socket cleanly"
                        )
                        return@launch
                    }
                }

                while (isActive && !activeSocket.isClosed && activeSocket.isConnected) {
                    val message = MessageFraming.readFrame(input) ?: break
                    if (message.senderId.isBlank()) {
                        Log.w(TAG, "Rejected malformed protocol frame with blank senderId")
                        break
                    }
                    Log.d(TAG, "Received message type=${message.type} from ${activeSocket.inetAddress?.hostAddress}")

                    val sHandler = streamMessageHandler
                    if (sHandler != null) {
                        val response = sHandler.invoke(message, activeSocket, input)
                        if (response != null) {
                            MessageFraming.writeFrame(output, response)
                        }
                    } else {
                        val handler = messageHandler
                        if (handler != null) {
                            val response = handler.invoke(message, activeSocket)
                            if (response != null) {
                                MessageFraming.writeFrame(output, response)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client socket closed or malformed frame: ${e.message}")
            } finally {
                activeClientSockets.remove(initialSocket)
                activeClientSockets.remove(activeSocket)
                performanceMonitor?.onSocketClosed()
                try {
                    activeSocket.close()
                } catch (_: Exception) {}
                if (activeSocket !== initialSocket) {
                    try {
                        initialSocket.close()
                    } catch (_: Exception) {}
                }
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

private class PeekedInputSocket(
    private val delegate: Socket,
    private val peekedInput: java.io.InputStream
) : Socket() {
    override fun getInputStream(): java.io.InputStream = peekedInput
    override fun getOutputStream(): java.io.OutputStream = delegate.getOutputStream()
    override fun getInetAddress() = delegate.inetAddress
    override fun getLocalAddress() = delegate.localAddress
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
    override fun getLocalSocketAddress() = delegate.localSocketAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun isConnected() = delegate.isConnected
    override fun isClosed() = delegate.isClosed
    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun close() = delegate.close()
}
