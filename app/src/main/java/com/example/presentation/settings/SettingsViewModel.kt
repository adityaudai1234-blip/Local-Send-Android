package com.example.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            settingsRepository.setDeviceName(name)
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun updateAutoStartReceiving(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoStartReceiving(enabled)
        }
    }

    fun updateDownloadLocation(path: String) {
        viewModelScope.launch {
            settingsRepository.setDownloadLocation(path)
        }
    }

    fun updatePort(port: Int) {
        viewModelScope.launch {
            settingsRepository.setPort(port)
        }
    }

    fun updateBroadcastEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBroadcastEnabled(enabled)
        }
    }
}
