package com.example.data.transfer

import com.example.domain.model.DeviceType
import com.example.domain.model.TransferItem

data class IncomingTransferRequest(
    val sessionId: String,
    val senderId: String,
    val senderName: String,
    val senderPlatform: String,
    val senderIp: String,
    val senderPort: Int,
    val files: List<FileMetadata>,
    val totalBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val deviceType: DeviceType
        get() = DeviceType.fromIdentifier(senderPlatform)

    val formattedTotalBytes: String
        get() = TransferItem.formatBytes(totalBytes)
}
