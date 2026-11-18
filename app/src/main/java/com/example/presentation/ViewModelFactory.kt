package com.example.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.di.AppContainer
import com.example.presentation.devices.DevicesViewModel
import com.example.presentation.diagnostics.DiagnosticsViewModel
import com.example.presentation.home.HomeViewModel
import com.example.presentation.settings.SettingsViewModel
import com.example.presentation.storage.StorageViewModel
import com.example.presentation.transfers.TransfersViewModel

class ViewModelFactory(
    private val appContainer: AppContainer,
    private val context: Context? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                HomeViewModel(
                    discoveryRepository = appContainer.deviceDiscoveryRepository,
                    transferRepository = appContainer.transferRepository,
                    settingsRepository = appContainer.settingsRepository,
                    pairingManager = appContainer.pairingManager,
                    transferManager = appContainer.transferManager,
                    pairedDeviceRepository = appContainer.pairedDeviceRepository,
                    connectionManager = appContainer.connectionManager
                ) as T
            }
            modelClass.isAssignableFrom(DevicesViewModel::class.java) -> {
                DevicesViewModel(
                    discoveryRepository = appContainer.deviceDiscoveryRepository,
                    pairedDeviceRepository = appContainer.pairedDeviceRepository,
                    connectionManager = appContainer.connectionManager,
                    pairingManager = appContainer.pairingManager
                ) as T
            }
            modelClass.isAssignableFrom(TransfersViewModel::class.java) -> {
                TransfersViewModel(
                    transferRepository = appContainer.transferRepository,
                    transferManager = appContainer.transferManager,
                    storageManager = appContainer.storageManager
                ) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(
                    settingsRepository = appContainer.settingsRepository,
                    pairedDeviceRepository = appContainer.pairedDeviceRepository,
                    storageManager = appContainer.storageManager,
                    nsdDiscoveryManager = appContainer.nsdDiscoveryManager
                ) as T
            }
            modelClass.isAssignableFrom(StorageViewModel::class.java) -> {
                StorageViewModel(
                    storageManager = appContainer.storageManager,
                    settingsRepository = appContainer.settingsRepository,
                    transferDao = appContainer.appDatabase.transferDao()
                ) as T
            }
            modelClass.isAssignableFrom(DiagnosticsViewModel::class.java) -> {
                val ctx = requireNotNull(context) { "Application context required for DiagnosticsViewModel" }
                DiagnosticsViewModel(
                    context = ctx,
                    discoveryRepository = appContainer.deviceDiscoveryRepository,
                    connectionManager = appContainer.connectionManager,
                    pairedDeviceRepository = appContainer.pairedDeviceRepository,
                    tcpServer = appContainer.tcpServer,
                    settingsRepository = appContainer.settingsRepository
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
