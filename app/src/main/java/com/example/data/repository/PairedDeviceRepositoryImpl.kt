package com.example.data.repository

import com.example.data.db.PairedDeviceDao
import com.example.data.db.PairedDeviceEntity
import com.example.data.security.SecurityKeyStore
import com.example.domain.model.DeviceType
import com.example.domain.model.PairedDevice
import com.example.domain.repository.PairedDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PairedDeviceRepositoryImpl(
    private val dao: PairedDeviceDao,
    private val securityKeyStore: SecurityKeyStore
) : PairedDeviceRepository {

    override val pairedDevices: Flow<List<PairedDevice>> = dao.getAll().map { list ->
        list.map { entity ->
            PairedDevice(
                id = entity.id,
                alias = entity.alias,
                deviceType = DeviceType.fromIdentifier(entity.deviceType),
                ipAddress = entity.ipAddress,
                port = entity.port,
                isTrusted = entity.isTrusted,
                pairedAt = entity.pairedAt,
                lastConnectedAt = entity.lastConnectedAt,
                model = entity.model
            )
        }
    }

    override suspend fun getPairedDevice(id: String): PairedDevice? {
        val entity = dao.getById(id) ?: return null
        return PairedDevice(
            id = entity.id,
            alias = entity.alias,
            deviceType = DeviceType.fromIdentifier(entity.deviceType),
            ipAddress = entity.ipAddress,
            port = entity.port,
            isTrusted = entity.isTrusted,
            pairedAt = entity.pairedAt,
            lastConnectedAt = entity.lastConnectedAt,
            model = entity.model
        )
    }

    override suspend fun savePairedDevice(device: PairedDevice, rawAuthToken: String) {
        val encryptedToken = securityKeyStore.encryptToken(rawAuthToken)
        val entity = PairedDeviceEntity(
            id = device.id,
            alias = device.alias,
            deviceType = device.deviceType.identifier,
            ipAddress = device.ipAddress,
            port = device.port,
            encryptedAuthToken = encryptedToken,
            isTrusted = device.isTrusted,
            pairedAt = device.pairedAt,
            lastConnectedAt = System.currentTimeMillis(),
            model = device.model
        )
        dao.insertOrUpdate(entity)
    }

    override suspend fun getAuthToken(deviceId: String): String? {
        val entity = dao.getById(deviceId) ?: return null
        return securityKeyStore.decryptToken(entity.encryptedAuthToken)
    }

    override suspend fun removePairedDevice(id: String) {
        dao.deleteById(id)
    }

    override suspend fun renameDevice(id: String, newAlias: String) {
        val trimmed = newAlias.trim()
        if (trimmed.isNotEmpty()) {
            dao.renameDevice(id, trimmed)
        }
    }

    override suspend fun updateLastConnected(id: String) {
        dao.updateLastConnected(id, System.currentTimeMillis())
    }

    override suspend fun clearAllDevices() {
        dao.deleteAll()
        securityKeyStore.clearAllKeys()
    }
}
