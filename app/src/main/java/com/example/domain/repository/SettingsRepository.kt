package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun setDeviceName(name: String)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAutoStartReceiving(enabled: Boolean)
    suspend fun setDownloadLocation(path: String)
    suspend fun setPort(port: Int)
    suspend fun setBroadcastEnabled(enabled: Boolean)
}
