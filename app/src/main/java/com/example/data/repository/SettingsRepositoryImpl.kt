package com.example.data.repository

import com.example.data.preferences.SettingsDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsRepositoryImpl(
    private val dataStore: SettingsDataStore
) : SettingsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(
        AppSettings(deviceName = "Android Device")
    )
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            dataStore.settingsFlow.collect { newSettings ->
                _settings.value = newSettings
            }
        }
    }

    override suspend fun setDeviceName(name: String) {
        dataStore.updateDeviceName(name)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.updateThemeMode(mode)
    }

    override suspend fun setAutoStartReceiving(enabled: Boolean) {
        dataStore.updateAutoStartReceiving(enabled)
    }

    override suspend fun setAutoAcceptTrusted(enabled: Boolean) {
        dataStore.updateAutoAcceptTrusted(enabled)
    }

    override suspend fun setAskBeforeReceiving(enabled: Boolean) {
        dataStore.updateAskBeforeReceiving(enabled)
    }

    override suspend fun setDownloadLocation(path: String) {
        dataStore.updateDownloadLocation(path)
    }

    override suspend fun setPort(port: Int) {
        dataStore.updatePort(port)
    }

    override suspend fun setBroadcastEnabled(enabled: Boolean) {
        dataStore.updateBroadcastEnabled(enabled)
    }

    override suspend fun setShowNotifications(enabled: Boolean) {
        dataStore.updateShowNotifications(enabled)
    }

    override suspend fun setMaxConcurrent(count: Int) {
        dataStore.updateMaxConcurrent(count)
    }

    override suspend fun setDuplicateHandling(handling: String) {
        dataStore.updateDuplicateHandling(handling)
    }

    override suspend fun setAutoOpenCompleted(enabled: Boolean) {
        dataStore.updateAutoOpenCompleted(enabled)
    }

    override suspend fun setMaxAcceptedFileSizeMb(sizeMb: Long) {
        dataStore.updateMaxAcceptedFileSizeMb(sizeMb)
    }

    override suspend fun setRequireApprovalUnknown(enabled: Boolean) {
        dataStore.updateRequireApprovalUnknown(enabled)
    }
}
