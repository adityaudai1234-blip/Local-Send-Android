package com.example.domain.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AccentColorOption(val label: String) {
    TEAL("Ocean Teal"),
    CYAN("Sky Cyan"),
    INDIGO("Electric Indigo"),
    EMERALD("Fresh Emerald"),
    DYNAMIC("Wallpaper Dynamic")
}

data class AppSettings(
    val deviceName: String,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: AccentColorOption = AccentColorOption.TEAL,
    val autoStartReceiving: Boolean = false,
    val autoAcceptTrusted: Boolean = false, // Mandated: disabled by default
    val askBeforeReceiving: Boolean = true,
    val downloadLocation: String = "Downloads/LocalDrop",
    val customDownloadTreeUri: String? = null,
    val port: Int = 53317,
    val broadcastEnabled: Boolean = true,
    val showNotifications: Boolean = true,
    val discoveryIntervalSeconds: Int = 10,
    val connectionTimeoutMs: Int = 15000,
    val maxConcurrentTransfers: Int = 2,
    val transferBufferSizeKb: Int = 64,
    val duplicateHandling: String = "rename", // "rename", "overwrite", "skip"
    val preserveOriginalNames: Boolean = true,
    val autoOpenCompleted: Boolean = false,
    val maxAcceptedFileSizeMb: Long = 0L, // 0L = unlimited
    val requireApprovalUnknown: Boolean = true,
    val tlsPreferred: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val suggestRecentDevices: Boolean = true,
    val rememberLastDestination: Boolean = true,
    val lastDestinationDeviceId: String? = null,
    val organizeReceivedByType: Boolean = false,
    val autoCleanTempFiles: Boolean = true,
    val appVersion: String = "2.1.0",
    val buildNumber: String = "5",
    val protocolVersion: String = "v2"
)
