package com.example.domain.model

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
    val isSelf: Boolean = false
) {
    val displaySubtitle: String
        get() = if (ipAddress.isNotBlank()) "$ipAddress:$port" else "mDNS Discovered"
}
