package com.example.domain.model

data class TransferStats(
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val totalFilesTransferred: Int = 0,
    val totalBytesTransferred: Long = 0L
) {
    val formattedTotalBytes: String
        get() = TransferItem.formatBytes(totalBytesTransferred)
}
