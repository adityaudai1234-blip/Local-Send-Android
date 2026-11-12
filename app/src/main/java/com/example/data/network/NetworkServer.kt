package com.example.data.network

import com.example.data.protocol.ProtocolMessage
import kotlinx.coroutines.flow.StateFlow
import java.net.Socket

interface NetworkServer {
    val isRunning: StateFlow<Boolean>
    val listeningPort: StateFlow<Int>

    fun start(port: Int = 53317)
    fun stop()
    fun setMessageHandler(handler: suspend (message: ProtocolMessage, clientSocket: Socket) -> ProtocolMessage?)
    fun setStreamMessageHandler(handler: suspend (message: ProtocolMessage, clientSocket: Socket, inputStream: java.io.BufferedInputStream) -> ProtocolMessage?) {}
}
