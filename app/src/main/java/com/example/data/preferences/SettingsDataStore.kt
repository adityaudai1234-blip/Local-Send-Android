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
import com.example.BuildConfig
import com.example.domain.model.AccentColorOption
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
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val LAST_SEEN_RELEASE_VERSION = stringPreferencesKey("last_seen_release_version")
        val AUTO_START_RECEIVING = booleanPreferencesKey("auto_start_receiving")
        val AUTO_ACCEPT_TRUSTED = booleanPreferencesKey("auto_accept_trusted")
        val ASK_BEFORE_RECEIVING = booleanPreferencesKey("ask_before_receiving")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val CUSTOM_DOWNLOAD_TREE_URI = stringPreferencesKey("custom_download_tree_uri")
        val PORT = intPreferencesKey("port")
        val BROADCAST_ENABLED = booleanPreferencesKey("broadcast_enabled")
        val SHOW_NOTIFICATIONS = booleanPreferencesKey("show_notifications")
        val NOTIFY_INCOMING_REQUESTS = booleanPreferencesKey("notify_incoming_requests")
        val NOTIFY_TRANSFER_PROGRESS = booleanPreferencesKey("notify_transfer_progress")
        val NOTIFY_TRANSFER_COMPLETE = booleanPreferencesKey("notify_transfer_complete")
        val DISCOVERY_INTERVAL = intPreferencesKey("discovery_interval")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        val TRANSFER_BUFFER_SIZE_KB = intPreferencesKey("transfer_buffer_size_kb")
        val DUPLICATE_HANDLING = stringPreferencesKey("duplicate_handling")
        val ASK_ON_CONFLICT_IN_UI = booleanPreferencesKey("ask_on_conflict_in_ui")
        val PRESERVE_NAMES = booleanPreferencesKey("preserve_names")
        val AUTO_OPEN_COMPLETED = booleanPreferencesKey("auto_open_completed")
        val MAX_FILE_SIZE_MB = longPreferencesKey("max_file_size_mb")
        val REQUIRE_APPROVAL_UNKNOWN = booleanPreferencesKey("require_approval_unknown")
        val TLS_PREFERRED = booleanPreferencesKey("tls_preferred")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val SUGGEST_RECENT_DEVICES = booleanPreferencesKey("suggest_recent_devices")
        val REMEMBER_LAST_DESTINATION = booleanPreferencesKey("remember_last_destination")
        val LAST_DESTINATION_DEVICE_ID = stringPreferencesKey("last_destination_device_id")
        val ORGANIZE_RECEIVED_BY_TYPE = booleanPreferencesKey("organize_received_by_type")
        val AUTO_CLEAN_TEMP_FILES = booleanPreferencesKey("auto_clean_temp_files")
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

        val accentStr = prefs[PreferencesKeys.ACCENT_COLOR] ?: AccentColorOption.TEAL.name
        val accentColor = try {
            AccentColorOption.valueOf(accentStr)
        } catch (_: Exception) {
            AccentColorOption.TEAL
        }

        AppSettings(
            deviceName = prefs[PreferencesKeys.DEVICE_NAME] ?: defaultDeviceName,
            themeMode = themeMode,
            accentColor = accentColor,
            appLanguage = prefs[PreferencesKeys.APP_LANGUAGE] ?: "System Default",
            hasCompletedOnboarding = prefs[PreferencesKeys.HAS_COMPLETED_ONBOARDING] ?: false,
            lastSeenReleaseVersion = prefs[PreferencesKeys.LAST_SEEN_RELEASE_VERSION] ?: "",
            autoStartReceiving = prefs[PreferencesKeys.AUTO_START_RECEIVING] ?: false,
            autoAcceptTrusted = prefs[PreferencesKeys.AUTO_ACCEPT_TRUSTED] ?: false,
            askBeforeReceiving = prefs[PreferencesKeys.ASK_BEFORE_RECEIVING] ?: true,
            downloadLocation = prefs[PreferencesKeys.DOWNLOAD_LOCATION] ?: "Downloads/LocalDrop",
            customDownloadTreeUri = prefs[PreferencesKeys.CUSTOM_DOWNLOAD_TREE_URI],
            port = prefs[PreferencesKeys.PORT] ?: 53317,
            broadcastEnabled = prefs[PreferencesKeys.BROADCAST_ENABLED] ?: true,
            showNotifications = prefs[PreferencesKeys.SHOW_NOTIFICATIONS] ?: true,
            notifyIncomingRequests = prefs[PreferencesKeys.NOTIFY_INCOMING_REQUESTS] ?: true,
            notifyTransferProgress = prefs[PreferencesKeys.NOTIFY_TRANSFER_PROGRESS] ?: true,
            notifyTransferComplete = prefs[PreferencesKeys.NOTIFY_TRANSFER_COMPLETE] ?: true,
            discoveryIntervalSeconds = prefs[PreferencesKeys.DISCOVERY_INTERVAL] ?: 10,
            connectionTimeoutMs = prefs[PreferencesKeys.CONNECTION_TIMEOUT] ?: 15000,
            maxConcurrentTransfers = prefs[PreferencesKeys.MAX_CONCURRENT] ?: 2,
            transferBufferSizeKb = prefs[PreferencesKeys.TRANSFER_BUFFER_SIZE_KB] ?: 64,
            duplicateHandling = prefs[PreferencesKeys.DUPLICATE_HANDLING] ?: "rename",
            askOnConflictInUi = prefs[PreferencesKeys.ASK_ON_CONFLICT_IN_UI] ?: true,
            preserveOriginalNames = prefs[PreferencesKeys.PRESERVE_NAMES] ?: true,
            autoOpenCompleted = prefs[PreferencesKeys.AUTO_OPEN_COMPLETED] ?: false,
            maxAcceptedFileSizeMb = prefs[PreferencesKeys.MAX_FILE_SIZE_MB] ?: 0L,
            requireApprovalUnknown = prefs[PreferencesKeys.REQUIRE_APPROVAL_UNKNOWN] ?: true,
            tlsPreferred = prefs[PreferencesKeys.TLS_PREFERRED] ?: true,
            hapticsEnabled = prefs[PreferencesKeys.HAPTICS_ENABLED] ?: true,
            suggestRecentDevices = prefs[PreferencesKeys.SUGGEST_RECENT_DEVICES] ?: true,
            rememberLastDestination = prefs[PreferencesKeys.REMEMBER_LAST_DESTINATION] ?: true,
            lastDestinationDeviceId = prefs[PreferencesKeys.LAST_DESTINATION_DEVICE_ID],
            organizeReceivedByType = prefs[PreferencesKeys.ORGANIZE_RECEIVED_BY_TYPE] ?: false,
            autoCleanTempFiles = prefs[PreferencesKeys.AUTO_CLEAN_TEMP_FILES] ?: true,
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString(),
            protocolVersion = "v2"
        )
    }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { it[PreferencesKeys.DEVICE_NAME] = name.trim() }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    suspend fun updateAccentColor(option: AccentColorOption) {
        context.dataStore.edit { it[PreferencesKeys.ACCENT_COLOR] = option.name }
    }

    suspend fun updateAppLanguage(language: String) {
        context.dataStore.edit { it[PreferencesKeys.APP_LANGUAGE] = language }
    }

    suspend fun updateHasCompletedOnboarding(completed: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.HAS_COMPLETED_ONBOARDING] = completed }
    }

    suspend fun updateLastSeenReleaseVersion(version: String) {
        context.dataStore.edit { it[PreferencesKeys.LAST_SEEN_RELEASE_VERSION] = version }
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

    suspend fun updateNotifyIncomingRequests(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NOTIFY_INCOMING_REQUESTS] = enabled }
    }

    suspend fun updateNotifyTransferProgress(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NOTIFY_TRANSFER_PROGRESS] = enabled }
    }

    suspend fun updateNotifyTransferComplete(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NOTIFY_TRANSFER_COMPLETE] = enabled }
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

    suspend fun updateTransferBufferSizeKb(sizeKb: Int) {
        val allowed = listOf(32, 64, 128, 256)
        val safe = if (sizeKb in allowed) sizeKb else 64
        context.dataStore.edit { it[PreferencesKeys.TRANSFER_BUFFER_SIZE_KB] = safe }
    }

    suspend fun updateDuplicateHandling(handling: String) {
        context.dataStore.edit { it[PreferencesKeys.DUPLICATE_HANDLING] = handling }
    }

    suspend fun updateAskOnConflictInUi(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ASK_ON_CONFLICT_IN_UI] = enabled }
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

    suspend fun updateSuggestRecentDevices(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SUGGEST_RECENT_DEVICES] = enabled }
    }

    suspend fun updateRememberLastDestination(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.REMEMBER_LAST_DESTINATION] = enabled }
    }

    suspend fun updateLastDestinationDeviceId(deviceId: String?) {
        context.dataStore.edit { prefs ->
            if (deviceId.isNullOrBlank()) {
                prefs.remove(PreferencesKeys.LAST_DESTINATION_DEVICE_ID)
            } else {
                prefs[PreferencesKeys.LAST_DESTINATION_DEVICE_ID] = deviceId
            }
        }
    }

    suspend fun updateOrganizeReceivedByType(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ORGANIZE_RECEIVED_BY_TYPE] = enabled }
    }

    suspend fun updateAutoCleanTempFiles(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_CLEAN_TEMP_FILES] = enabled }
    }
}
