package com.example.data.db

import androidx.room.ColumnInfo
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
    val model: String? = null,
    val isFavorite: Boolean = false,
    val useCount: Int = 0,
    val lastTransferredAt: Long = 0L,
    val autoAccept: Boolean = false,
    val certFingerprint: String? = null,
    @ColumnInfo(defaultValue = "0")
    val tlsSupported: Boolean = false
)
