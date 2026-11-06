package com.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.di.AppContainer
import com.example.presentation.devices.DevicesViewModel
import com.example.presentation.home.HomeViewModel
import com.example.presentation.settings.SettingsViewModel
import com.example.presentation.transfers.TransfersViewModel

class ViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                HomeViewModel(
                    discoveryRepository = appContainer.deviceDiscoveryRepository,
                    transferRepository = appContainer.transferRepository,
                    settingsRepository = appContainer.settingsRepository
                ) as T
            }
            modelClass.isAssignableFrom(DevicesViewModel::class.java) -> {
                DevicesViewModel(
                    discoveryRepository = appContainer.deviceDiscoveryRepository
                ) as T
            }
            modelClass.isAssignableFrom(TransfersViewModel::class.java) -> {
                TransfersViewModel(
                    transferRepository = appContainer.transferRepository
                ) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(
                    settingsRepository = appContainer.settingsRepository
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
