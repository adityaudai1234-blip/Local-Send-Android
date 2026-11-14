package com.example.data.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localdrop_settings")

class SettingsDataStore(private val context: Context) {

    private object PreferencesKeys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_START_RECEIVING = booleanPreferencesKey("auto_start_receiving")
        val AUTO_ACCEPT_TRUSTED = booleanPreferencesKey("auto_accept_trusted")
        val ASK_BEFORE_RECEIVING = booleanPreferencesKey("ask_before_receiving")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val PORT = intPreferencesKey("port")
        val BROADCAST_ENABLED = booleanPreferencesKey("broadcast_enabled")
        val SHOW_NOTIFICATIONS = booleanPreferencesKey("show_notifications")
        val DISCOVERY_INTERVAL = intPreferencesKey("discovery_interval")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        val DUPLICATE_HANDLING = stringPreferencesKey("duplicate_handling")
        val PRESERVE_NAMES = booleanPreferencesKey("preserve_names")
        val AUTO_OPEN_COMPLETED = booleanPreferencesKey("auto_open_completed")
        val MAX_FILE_SIZE_MB = longPreferencesKey("max_file_size_mb")
        val REQUIRE_APPROVAL_UNKNOWN = booleanPreferencesKey("require_approval_unknown")
    }

    private val defaultDeviceName: String by lazy {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    val deviceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.DEVICE_ID] ?: ""
    }

    suspend fun getOrCreateDeviceId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            val existing = prefs[PreferencesKeys.DEVICE_ID]
            if (existing.isNullOrBlank()) {
                val newId = "android-${java.util.UUID.randomUUID()}"
                prefs[PreferencesKeys.DEVICE_ID] = newId
                id = newId
            } else {
                id = existing
            }
        }
        return id
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
            autoStartReceiving = prefs[PreferencesKeys.AUTO_START_RECEIVING] ?: false,
            autoAcceptTrusted = prefs[PreferencesKeys.AUTO_ACCEPT_TRUSTED] ?: false,
            askBeforeReceiving = prefs[PreferencesKeys.ASK_BEFORE_RECEIVING] ?: true,
            downloadLocation = prefs[PreferencesKeys.DOWNLOAD_LOCATION] ?: "Downloads/LocalDrop",
            port = prefs[PreferencesKeys.PORT] ?: 53317,
            broadcastEnabled = prefs[PreferencesKeys.BROADCAST_ENABLED] ?: true,
            showNotifications = prefs[PreferencesKeys.SHOW_NOTIFICATIONS] ?: true,
            discoveryIntervalSeconds = prefs[PreferencesKeys.DISCOVERY_INTERVAL] ?: 10,
            connectionTimeoutMs = prefs[PreferencesKeys.CONNECTION_TIMEOUT] ?: 15000,
            maxConcurrentTransfers = prefs[PreferencesKeys.MAX_CONCURRENT] ?: 2,
            duplicateHandling = prefs[PreferencesKeys.DUPLICATE_HANDLING] ?: "rename",
            preserveOriginalNames = prefs[PreferencesKeys.PRESERVE_NAMES] ?: true,
            autoOpenCompleted = prefs[PreferencesKeys.AUTO_OPEN_COMPLETED] ?: false,
            maxAcceptedFileSizeMb = prefs[PreferencesKeys.MAX_FILE_SIZE_MB] ?: 0L,
            requireApprovalUnknown = prefs[PreferencesKeys.REQUIRE_APPROVAL_UNKNOWN] ?: true,
            appVersion = "2.0.0 (Phase 4)",
            buildNumber = "4",
            protocolVersion = "v2"
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

    suspend fun updateAutoAcceptTrusted(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.AUTO_ACCEPT_TRUSTED] = enabled
        }
    }

    suspend fun updateAskBeforeReceiving(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.ASK_BEFORE_RECEIVING] = enabled
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

    suspend fun updateShowNotifications(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.SHOW_NOTIFICATIONS] = enabled
        }
    }

    suspend fun updateMaxConcurrent(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.MAX_CONCURRENT] = count
        }
    }

    suspend fun updateDuplicateHandling(handling: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DUPLICATE_HANDLING] = handling
        }
    }

    suspend fun updateAutoOpenCompleted(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.AUTO_OPEN_COMPLETED] = enabled
        }
    }

    suspend fun updateMaxAcceptedFileSizeMb(sizeMb: Long) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.MAX_FILE_SIZE_MB] = sizeMb
        }
    }

    suspend fun updateRequireApprovalUnknown(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.REQUIRE_APPROVAL_UNKNOWN] = enabled
        }
    }
}
