package com.example.data.network

import com.example.data.protocol.ProtocolMessage

interface NetworkClient {
    suspend fun sendMessage(
        host: String,
        port: Int,
        message: ProtocolMessage,
        timeoutMs: Int = 10_000
    ): Result<ProtocolMessage>

    suspend fun sendOneWay(
        host: String,
        port: Int,
        message: ProtocolMessage,
        timeoutMs: Int = 5_000
    ): Result<Unit>
}
