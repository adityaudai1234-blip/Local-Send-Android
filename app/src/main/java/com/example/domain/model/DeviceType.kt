package com.example.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.ui.graphics.vector.ImageVector

enum class DeviceType(val displayName: String, val identifier: String) {
    ANDROID("Android", "android"),
    WINDOWS("Windows", "windows"),
    MACOS("macOS", "macos"),
    LINUX("Linux", "linux"),
    WEB("Web", "web"),
    UNKNOWN("Unknown", "unknown");

    val icon: ImageVector
        get() = when (this) {
            ANDROID -> Icons.Default.PhoneAndroid
            WINDOWS -> Icons.Default.Computer
            MACOS -> Icons.Default.Laptop
            LINUX -> Icons.Default.Computer
            WEB -> Icons.Default.Language
            UNKNOWN -> Icons.Default.Tablet
        }

    companion object {
        fun fromIdentifier(identifier: String?): DeviceType {
            if (identifier == null) return UNKNOWN
            val lower = identifier.lowercase()
            return when {
                lower.contains("android") -> ANDROID
                lower.contains("mac") || lower.contains("darwin") || lower.contains("apple") || lower.contains("osx") -> MACOS
                lower.contains("windows") || lower.startsWith("win") -> WINDOWS
                lower.contains("linux") -> LINUX
                lower.contains("web") -> WEB
                else -> UNKNOWN
            }
        }
    }
}
