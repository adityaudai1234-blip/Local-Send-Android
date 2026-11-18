package com.example.domain.model

data class PairedDevice(
    val id: String,
    val alias: String,
    val deviceType: DeviceType,
    val ipAddress: String,
    val port: Int = 53317,
    val isTrusted: Boolean = true,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long = System.currentTimeMillis(),
    val model: String? = null,
    val isConnected: Boolean = false,
    val isFavorite: Boolean = false,
    val useCount: Int = 0,
    val lastTransferredAt: Long = 0L,
    val autoAccept: Boolean = false
) {
    val displaySubtitle: String
        get() = "$ipAddress:$port"
}
