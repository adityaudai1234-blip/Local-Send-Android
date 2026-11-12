package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val fileName: String,
    val fileSize: Long,
    val direction: String, // "SEND" or "RECEIVE"
    val status: String,    // "COMPLETED", "FAILED", "CANCELLED"
    val peerDeviceName: String,
    val peerAddress: String,
    val filePath: String? = null,
    val mimeType: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val speed: String? = null
)
