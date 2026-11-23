package com.example.domain.model

import com.example.BuildConfig

data class ReleaseNoteEntry(
    val version: String,
    val buildNumber: String,
    val releaseDate: String,
    val newFeatures: List<String>,
    val improvements: List<String>,
    val bugFixes: List<String>,
    val securityUpdates: List<String>
)

object ReleaseNotesCatalog {

    val latest: ReleaseNoteEntry
        get() = entries.first().copy(
            version = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString()
        )

    val entries: List<ReleaseNoteEntry> = listOf(
        ReleaseNoteEntry(
            version = "2.2.0",
            buildNumber = "7",
            releaseDate = "2026-11-22",
            newFeatures = listOf(
                "First-time onboarding wizard with Welcome, Privacy, Contextual Permissions, Device Setup & Quick Launch.",
                "Modern AndroidX SplashScreen supporting light and dark system themes without launch delay.",
                "Dedicated Privacy Information & publishable Privacy Policy screen plus full About LocalDrop screen.",
                "Granular Notification controls for incoming requests, live progress, and completion alerts.",
                "Built-in What's New release notes dialog shown once after app updates."
            ),
            improvements = listOf(
                "Contextual permission handling with clear rationales and Android Settings deep link for permanently denied permissions.",
                "Categorized Error Reporting across Discovery, Pairing, Auth, Connection, Transfer, Storage, Permission & Protocol.",
                "Custom Space Grotesk & Plus Jakarta Sans typography paired with cohesive LocalDrop branding.",
                "Local-only crash diagnostic recorder synced into Network Diagnostics without cloud uploads."
            ),
            bugFixes = listOf(
                "Eliminated unsolicited permission prompts at first launch when a feature does not yet require them.",
                "Synchronized Settings version and build numbers directly with Gradle BuildConfig."
            ),
            securityUpdates = listOf(
                "Sanitizes tokens, keys, and credential patterns from error diagnostics and local crash logs.",
                "Preserves default-deny incoming transfer approval and Keystore-backed peer trust across app upgrades."
            )
        ),
        ReleaseNoteEntry(
            version = "2.1.0",
            buildNumber = "6",
            releaseDate = "2026-11-19",
            newFeatures = listOf(
                "Folder hierarchy preservation over LocalDrop v2 protocol, Quick Send rail, and Glance home widgets.",
                "Network Diagnostics with per-peer RTT pings and Storage–Room consistency cleanup."
            ),
            improvements = listOf(
                "Non-destructive Room v1–v4 schema migrations and 48dp accessible touch targets."
            ),
            bugFixes = listOf(
                "Resolved concurrent queue slot starvation, approval timeouts, and >4 GB progress arithmetic."
            ),
            securityUpdates = listOf(
                "TLS ClientHello peeking on TCP listener and canonical relative-path traversal validation."
            )
        )
    )
}
