package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String = "",
    val fileName: String,
    val fileExtension: String = "",
    val mimeType: String? = null,
    val fileSize: Long,
    val senderDevice: String,
    val receiverDevice: String,
    val direction: String, // "SENT", "RECEIVED", "SEND", "RECEIVE"
    val status: String,    // "PENDING", "ACTIVE", "COMPLETED", "FAILED", "CANCELLED"
    val startTime: Long = System.currentTimeMillis(),
    val completionTime: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val speed: String? = null,
    val checksum: String? = null,
    val filePath: String? = null,
    val peerDeviceName: String = "",
    val peerAddress: String = "",
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val relativePath: String? = null,
    val avgSpeedBytesPerSec: Long = 0L,
    val peakSpeedBytesPerSec: Long = 0L,
    val priority: String = "NORMAL",
    val queueOrder: Int = 0,
    val bytesTransferred: Long = 0L,
    val isPaused: Boolean = false,
    val retryCount: Int = 0,
    val isDirectory: Boolean = false,
    val partialChecksum: String? = null,
    val sourceUri: String? = null
)
