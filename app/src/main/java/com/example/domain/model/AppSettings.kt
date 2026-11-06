package com.example.domain.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class AppSettings(
    val deviceName: String,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoStartReceiving: Boolean = true,
    val downloadLocation: String = "Downloads/LocalDrop",
    val port: Int = 53317,
    val broadcastEnabled: Boolean = true,
    val appVersion: String = "1.0.0 (Phase 1)"
)
