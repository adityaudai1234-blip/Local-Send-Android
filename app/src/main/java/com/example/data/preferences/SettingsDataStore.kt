package com.example.data.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localdrop_settings")

class SettingsDataStore(private val context: Context) {

    private object PreferencesKeys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_START_RECEIVING = booleanPreferencesKey("auto_start_receiving")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val PORT = intPreferencesKey("port")
        val BROADCAST_ENABLED = booleanPreferencesKey("broadcast_enabled")
    }

    private val defaultDeviceName: String by lazy {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val themeModeStr = prefs[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }

        AppSettings(
            deviceName = prefs[PreferencesKeys.DEVICE_NAME] ?: defaultDeviceName,
            themeMode = themeMode,
            autoStartReceiving = prefs[PreferencesKeys.AUTO_START_RECEIVING] ?: true,
            downloadLocation = prefs[PreferencesKeys.DOWNLOAD_LOCATION] ?: "Downloads/LocalDrop",
            port = prefs[PreferencesKeys.PORT] ?: 53317,
            broadcastEnabled = prefs[PreferencesKeys.BROADCAST_ENABLED] ?: true,
            appVersion = "1.0.0 (Phase 1)"
        )
    }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DEVICE_NAME] = name.trim()
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun updateAutoStartReceiving(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.AUTO_START_RECEIVING] = enabled
        }
    }

    suspend fun updateDownloadLocation(path: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DOWNLOAD_LOCATION] = path
        }
    }

    suspend fun updatePort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.PORT] = port
        }
    }

    suspend fun updateBroadcastEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.BROADCAST_ENABLED] = enabled
        }
    }
}
