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
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.data.transfer.TransferNotificationHelper
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
    val storageManager: StorageManager
    val transferNotificationHelper: TransferNotificationHelper
    val transferManager: TransferManager
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

    override val storageManager: StorageManager by lazy {
        StorageManager(context)
    }

    override val transferNotificationHelper: TransferNotificationHelper by lazy {
        TransferNotificationHelper(context)
    }

    override val transferManager: TransferManager by lazy {
        TransferManager(
            context = context,
            server = tcpServer,
            client = tcpClient,
            storageManager = storageManager,
            transferDao = appDatabase.transferDao(),
            pairedDeviceRepository = pairedDeviceRepository,
            settingsRepository = settingsRepository,
            connectionManager = connectionManager,
            notificationHelper = transferNotificationHelper,
            myDeviceIdProvider = { deviceId }
        )
    }

    override val transferRepository: TransferRepository by lazy {
        TransferRepositoryImpl(transferManager, appDatabase.transferDao(), storageManager)
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

    init {
        // Configure unified TCP server stream message routing
        tcpServer.setStreamMessageHandler { message, socket, input ->
            val transferResponse = transferManager.handleProtocolMessage(message, socket, input)
            if (transferResponse != null) {
                transferResponse
            } else {
                pairingManager.handleIncomingMessage(message)
            }
        }
    }
}
