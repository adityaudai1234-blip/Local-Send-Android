package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey
    val id: String,
    val alias: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int = 53317,
    val encryptedAuthToken: String,
    val isTrusted: Boolean = true,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long = System.currentTimeMillis(),
    val model: String? = null
)
