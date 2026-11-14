package com.example.domain.repository

import com.example.domain.model.PairedDevice
import kotlinx.coroutines.flow.Flow

interface PairedDeviceRepository {
    val pairedDevices: Flow<List<PairedDevice>>

    suspend fun getPairedDevice(id: String): PairedDevice?
    suspend fun savePairedDevice(device: PairedDevice, rawAuthToken: String)
    suspend fun getAuthToken(deviceId: String): String?
    suspend fun removePairedDevice(id: String)
    suspend fun renameDevice(id: String, newAlias: String)
    suspend fun updateLastConnected(id: String)
    suspend fun clearAllDevices()
}
