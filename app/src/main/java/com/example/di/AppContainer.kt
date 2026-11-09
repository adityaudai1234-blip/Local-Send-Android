package com.example.di

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.discovery.NsdDiscoveryManager
import com.example.data.network.ConnectionManager
import com.example.data.network.NetworkClient
import com.example.data.network.NetworkServer
import com.example.data.network.TcpClient
import com.example.data.network.TcpServer
import com.example.data.pairing.PairingManager
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.DeviceDiscoveryRepositoryImpl
import com.example.data.repository.PairedDeviceRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.data.repository.TransferRepositoryImpl
import com.example.data.security.SecurityKeyStore
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.runBlocking
import java.util.UUID

interface AppContainer {
    val settingsDataStore: SettingsDataStore
    val settingsRepository: SettingsRepository
    val nsdDiscoveryManager: NsdDiscoveryManager
    val deviceDiscoveryRepository: DeviceDiscoveryRepository
    val transferRepository: TransferRepository
    val securityKeyStore: SecurityKeyStore
    val appDatabase: AppDatabase
    val pairedDeviceRepository: PairedDeviceRepository
    val tcpServer: NetworkServer
    val tcpClient: NetworkClient
    val connectionManager: ConnectionManager
    val pairingManager: PairingManager
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val settingsDataStore: SettingsDataStore by lazy {
        SettingsDataStore(context)
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(settingsDataStore)
    }

    override val nsdDiscoveryManager: NsdDiscoveryManager by lazy {
        NsdDiscoveryManager(context)
    }

    override val deviceDiscoveryRepository: DeviceDiscoveryRepository by lazy {
        DeviceDiscoveryRepositoryImpl(context, nsdDiscoveryManager)
    }

    override val transferRepository: TransferRepository by lazy {
        TransferRepositoryImpl()
    }

    override val securityKeyStore: SecurityKeyStore by lazy {
        SecurityKeyStore()
    }

    override val appDatabase: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    override val pairedDeviceRepository: PairedDeviceRepository by lazy {
        PairedDeviceRepositoryImpl(appDatabase.pairedDeviceDao(), securityKeyStore)
    }

    override val tcpServer: NetworkServer by lazy {
        TcpServer()
    }

    override val tcpClient: NetworkClient by lazy {
        TcpClient()
    }

    private val deviceId: String by lazy {
        try {
            runBlocking { settingsDataStore.getOrCreateDeviceId() }
        } catch (_: Exception) {
            "android-${UUID.randomUUID()}"
        }
    }

    override val connectionManager: ConnectionManager by lazy {
        ConnectionManager(
            client = tcpClient,
            myDeviceIdProvider = { deviceId }
        )
    }

    override val pairingManager: PairingManager by lazy {
        PairingManager(
            server = tcpServer,
            client = tcpClient,
            pairedDeviceRepository = pairedDeviceRepository,
            securityKeyStore = securityKeyStore,
            connectionManager = connectionManager,
            myDeviceId = deviceId,
            settingsProvider = { settingsRepository.settings.value }
        )
    }
}
