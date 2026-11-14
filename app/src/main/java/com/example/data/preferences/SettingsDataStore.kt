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
        val CUSTOM_DOWNLOAD_TREE_URI = stringPreferencesKey("custom_download_tree_uri")
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
        val TLS_PREFERRED = booleanPreferencesKey("tls_preferred")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
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
            customDownloadTreeUri = prefs[PreferencesKeys.CUSTOM_DOWNLOAD_TREE_URI],
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
            tlsPreferred = prefs[PreferencesKeys.TLS_PREFERRED] ?: true,
            hapticsEnabled = prefs[PreferencesKeys.HAPTICS_ENABLED] ?: true,
            appVersion = "2.0.0",
            buildNumber = "4",
            protocolVersion = "v2"
        )
    }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { it[PreferencesKeys.DEVICE_NAME] = name.trim() }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    suspend fun updateAutoStartReceiving(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_START_RECEIVING] = enabled }
    }

    suspend fun updateAutoAcceptTrusted(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_ACCEPT_TRUSTED] = enabled }
    }

    suspend fun updateAskBeforeReceiving(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ASK_BEFORE_RECEIVING] = enabled }
    }

    suspend fun updateDownloadLocation(path: String, treeUri: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DOWNLOAD_LOCATION] = path
            if (treeUri != null) {
                prefs[PreferencesKeys.CUSTOM_DOWNLOAD_TREE_URI] = treeUri
            } else {
                prefs.remove(PreferencesKeys.CUSTOM_DOWNLOAD_TREE_URI)
            }
        }
    }

    suspend fun updatePort(port: Int) {
        context.dataStore.edit { it[PreferencesKeys.PORT] = port }
    }

    suspend fun updateBroadcastEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BROADCAST_ENABLED] = enabled }
    }

    suspend fun updateShowNotifications(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_NOTIFICATIONS] = enabled }
    }

    suspend fun updateDiscoveryIntervalSeconds(seconds: Int) {
        context.dataStore.edit { it[PreferencesKeys.DISCOVERY_INTERVAL] = seconds.coerceIn(5, 120) }
    }

    suspend fun updateConnectionTimeoutMs(timeoutMs: Int) {
        context.dataStore.edit { it[PreferencesKeys.CONNECTION_TIMEOUT] = timeoutMs.coerceIn(3000, 120_000) }
    }

    suspend fun updateMaxConcurrent(count: Int) {
        context.dataStore.edit { it[PreferencesKeys.MAX_CONCURRENT] = count.coerceIn(1, 5) }
    }

    suspend fun updateDuplicateHandling(handling: String) {
        context.dataStore.edit { it[PreferencesKeys.DUPLICATE_HANDLING] = handling }
    }

    suspend fun updatePreserveOriginalNames(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.PRESERVE_NAMES] = enabled }
    }

    suspend fun updateAutoOpenCompleted(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_OPEN_COMPLETED] = enabled }
    }

    suspend fun updateMaxAcceptedFileSizeMb(sizeMb: Long) {
        context.dataStore.edit { it[PreferencesKeys.MAX_FILE_SIZE_MB] = sizeMb.coerceAtLeast(0L) }
    }

    suspend fun updateRequireApprovalUnknown(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.REQUIRE_APPROVAL_UNKNOWN] = enabled }
    }

    suspend fun updateTlsPreferred(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.TLS_PREFERRED] = enabled }
    }

    suspend fun updateHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.HAPTICS_ENABLED] = enabled }
    }
}
