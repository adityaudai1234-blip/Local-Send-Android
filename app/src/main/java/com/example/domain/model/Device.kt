package com.example.domain.model

enum class DeviceStatus(val label: String) {
    ONLINE("Online"),
    BUSY("Busy"),
    OFFLINE("Offline"),
    UNREACHABLE("Unreachable")
}

data class Device(
    val id: String,
    val alias: String,
    val deviceType: DeviceType,
    val ipAddress: String,
    val port: Int = 53317,
    val isOnline: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis(),
    val protocolVersion: String = "v1",
    val model: String? = null,
    val isSelf: Boolean = false,
    val status: DeviceStatus = if (isOnline) DeviceStatus.ONLINE else DeviceStatus.OFFLINE,
    val serviceName: String = "",
    val alternateAddresses: List<String> = emptyList()
) {
    val displaySubtitle: String
        get() = if (ipAddress.isNotBlank()) "$ipAddress:$port" else "mDNS Discovered"
}
