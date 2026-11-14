package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun setDeviceName(name: String)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAutoStartReceiving(enabled: Boolean)
    suspend fun setAutoAcceptTrusted(enabled: Boolean)
    suspend fun setAskBeforeReceiving(enabled: Boolean)
    suspend fun setDownloadLocation(path: String)
    suspend fun setPort(port: Int)
    suspend fun setBroadcastEnabled(enabled: Boolean)
    suspend fun setShowNotifications(enabled: Boolean)
    suspend fun setMaxConcurrent(count: Int)
    suspend fun setDuplicateHandling(handling: String)
    suspend fun setAutoOpenCompleted(enabled: Boolean)
    suspend fun setMaxAcceptedFileSizeMb(sizeMb: Long)
    suspend fun setRequireApprovalUnknown(enabled: Boolean)
}
