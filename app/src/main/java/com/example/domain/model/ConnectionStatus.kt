package com.example.domain.model

data class ConnectionStatus(
    val isConnected: Boolean = false,
    val networkName: String = "No Network",
    val ipAddress: String = "127.0.0.1",
    val isWifi: Boolean = false
)
