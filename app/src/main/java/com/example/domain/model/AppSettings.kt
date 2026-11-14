package com.example.domain.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class AppSettings(
    val deviceName: String,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoStartReceiving: Boolean = false,
    val autoAcceptTrusted: Boolean = false, // Mandated: disabled by default
    val askBeforeReceiving: Boolean = true,
    val downloadLocation: String = "Downloads/LocalDrop",
    val port: Int = 53317,
    val broadcastEnabled: Boolean = true,
    val showNotifications: Boolean = true,
    val discoveryIntervalSeconds: Int = 10,
    val connectionTimeoutMs: Int = 15000,
    val maxConcurrentTransfers: Int = 2,
    val duplicateHandling: String = "rename", // "rename", "overwrite", "skip"
    val preserveOriginalNames: Boolean = true,
    val autoOpenCompleted: Boolean = false,
    val maxAcceptedFileSizeMb: Long = 0L, // 0L = unlimited
    val requireApprovalUnknown: Boolean = true,
    val appVersion: String = "2.0.0 (Phase 4)",
    val buildNumber: String = "4",
    val protocolVersion: String = "v2"
)
