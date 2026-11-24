package com.example.domain.model

enum class NetworkTransportType(val label: String) {
    WIFI("Wi-Fi"),
    ETHERNET("Ethernet"),
    HOTSPOT("Wi-Fi Hotspot"),
    CELLULAR("Cellular"),
    OTHER("Local Network"),
    NONE("Disconnected")
}

data class ConnectionStatus(
    val isConnected: Boolean = false,
    val networkName: String = "No Network",
    val ipAddress: String = "127.0.0.1",
    val ipv6Address: String? = null,
    val interfaceName: String = "",
    val transportType: NetworkTransportType = NetworkTransportType.NONE,
    val isWifi: Boolean = false,
    val isHotspot: Boolean = false
)
