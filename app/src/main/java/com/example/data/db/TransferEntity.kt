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
    val direction: String, // "SENT" or "RECEIVED"
    val status: String,    // "COMPLETED", "FAILED", "CANCELLED"
    val startTime: Long = System.currentTimeMillis(),
    val completionTime: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val speed: String? = null,
    val checksum: String? = null,
    val filePath: String? = null,
    val peerDeviceName: String = "",
    val peerAddress: String = "",
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
