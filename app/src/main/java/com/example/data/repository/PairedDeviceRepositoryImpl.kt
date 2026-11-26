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
        list.map { it.toDomain() }
    }

    override suspend fun getPairedDevice(id: String): PairedDevice? {
        return dao.getById(id)?.toDomain()
    }

    override suspend fun savePairedDevice(device: PairedDevice, rawAuthToken: String) {
        val existing = dao.getById(device.id)
        val encryptedToken = securityKeyStore.encryptToken(rawAuthToken)
        val entity = PairedDeviceEntity(
            id = device.id,
            alias = device.alias,
            deviceType = device.deviceType.identifier,
            ipAddress = device.ipAddress,
            port = device.port,
            encryptedAuthToken = encryptedToken,
            isTrusted = device.isTrusted,
            pairedAt = existing?.pairedAt ?: device.pairedAt,
            lastConnectedAt = System.currentTimeMillis(),
            model = device.model ?: existing?.model,
            isFavorite = existing?.isFavorite ?: device.isFavorite,
            useCount = existing?.useCount ?: device.useCount,
            lastTransferredAt = existing?.lastTransferredAt ?: device.lastTransferredAt,
            autoAccept = existing?.autoAccept ?: device.autoAccept,
            certFingerprint = device.certFingerprint ?: existing?.certFingerprint,
            tlsSupported = device.tlsSupported || (existing?.tlsSupported == true)
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

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        dao.setFavorite(id, isFavorite)
    }

    override suspend fun setAutoAccept(id: String, autoAccept: Boolean) {
        dao.setAutoAccept(id, autoAccept)
    }

    override suspend fun incrementUsage(id: String) {
        dao.incrementUsage(id, System.currentTimeMillis())
    }

    override suspend fun updateNetworkEndpoint(id: String, ipAddress: String, port: Int) {
        dao.updateNetworkEndpoint(id, ipAddress, port, System.currentTimeMillis())
    }

    override suspend fun updateTlsInfo(id: String, certFingerprint: String?, tlsSupported: Boolean) {
        dao.updateTlsInfo(id, certFingerprint, tlsSupported)
    }

    override suspend fun clearAllDevices() {
        dao.deleteAll()
        securityKeyStore.clearAllKeys()
    }

    private fun PairedDeviceEntity.toDomain(): PairedDevice = PairedDevice(
        id = id,
        alias = alias,
        deviceType = DeviceType.fromIdentifier(deviceType),
        ipAddress = ipAddress,
        port = port,
        isTrusted = isTrusted,
        pairedAt = pairedAt,
        lastConnectedAt = lastConnectedAt,
        model = model,
        isFavorite = isFavorite,
        useCount = useCount,
        lastTransferredAt = lastTransferredAt,
        autoAccept = autoAccept,
        certFingerprint = certFingerprint,
        tlsSupported = tlsSupported
    )
}
