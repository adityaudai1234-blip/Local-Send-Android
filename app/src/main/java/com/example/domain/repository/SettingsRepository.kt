package com.example.domain.repository

import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    val isLoaded: StateFlow<Boolean>
        get() = MutableStateFlow(true)

    suspend fun setDeviceName(name: String)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAccentColor(option: AccentColorOption)
    suspend fun setAppLanguage(language: String) {}
    suspend fun setHasCompletedOnboarding(completed: Boolean) {}
    suspend fun setLastSeenReleaseVersion(version: String) {}
    suspend fun setAutoStartReceiving(enabled: Boolean)
    suspend fun setAutoAcceptTrusted(enabled: Boolean)
    suspend fun setAskBeforeReceiving(enabled: Boolean)
    suspend fun setDownloadLocation(path: String, treeUri: String? = null)
    suspend fun setPort(port: Int)
    suspend fun setBroadcastEnabled(enabled: Boolean)
    suspend fun setShowNotifications(enabled: Boolean)
    suspend fun setNotifyIncomingRequests(enabled: Boolean) {}
    suspend fun setNotifyTransferProgress(enabled: Boolean) {}
    suspend fun setNotifyTransferComplete(enabled: Boolean) {}
    suspend fun setDiscoveryIntervalSeconds(seconds: Int)
    suspend fun setConnectionTimeoutMs(timeoutMs: Int)
    suspend fun setMaxConcurrent(count: Int)
    suspend fun setTransferBufferSizeKb(sizeKb: Int)
    suspend fun setDuplicateHandling(handling: String)
    suspend fun setPreserveOriginalNames(enabled: Boolean)
    suspend fun setAutoOpenCompleted(enabled: Boolean)
    suspend fun setMaxAcceptedFileSizeMb(sizeMb: Long)
    suspend fun setRequireApprovalUnknown(enabled: Boolean)
    suspend fun setTlsPreferred(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setSuggestRecentDevices(enabled: Boolean)
    suspend fun setRememberLastDestination(enabled: Boolean)
    suspend fun setLastDestinationDeviceId(deviceId: String?)
    suspend fun setOrganizeReceivedByType(enabled: Boolean)
    suspend fun setAutoCleanTempFiles(enabled: Boolean)
}
