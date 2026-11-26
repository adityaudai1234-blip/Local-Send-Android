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
    suspend fun setFavorite(id: String, isFavorite: Boolean)
    suspend fun setAutoAccept(id: String, autoAccept: Boolean)
    suspend fun incrementUsage(id: String)
    suspend fun updateNetworkEndpoint(id: String, ipAddress: String, port: Int)
    suspend fun updateTlsInfo(id: String, certFingerprint: String?, tlsSupported: Boolean) {}
    suspend fun clearAllDevices()
}
