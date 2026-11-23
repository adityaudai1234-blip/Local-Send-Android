# LocalDrop — Private Local-Network File Sharing for Android

**Version:** `2.2.0` (Build `7`)  
**Protocol Compatibility:** LocalDrop `v1` & `v2` (Android ↔ Tauri Desktop on Windows, macOS, Linux)

LocalDrop is a privacy-focused, zero-cloud Android file-sharing application built with Kotlin, Jetpack Compose, and Material Design 3. Share files and entire folders directly across local Wi-Fi or wired LAN with nearby Android devices and companion Tauri desktop applications—without accounts, remote relays, or cloud uploads.

---

## Core Features

- **Zero-Cloud Local Transfers**: Peer-to-peer mDNS discovery (`_lsend._tcp.`) and direct TCP / optional TLS v2 socket streaming.
- **Tauri Desktop Interoperability**: Length-prefixed JSON control frames + `"LSD1"` / `"LSEO"` binary chunk framing compatible with companion Windows, macOS, and Linux Tauri desktop apps.
- **QR Code Device Pairing & Mutual Trust**: CameraX + ZXing QR scanning, manual payload fallback, short-lived QR sessions, and Android Keystore AES-256-GCM encrypted peer tokens.
- **Rich File & Folder Transfers**: Multi-file batches, multi-recipient sending, SAF folder hierarchy preservation (`relativePath`), >4 GB progress tracking, pause/resume/cancel, SHA-256 checksum verification, and configurable duplicate handling.
- **Persistent History & Received Files Browser**: Room v4 local database with non-destructive migrations, sortable/filterable Received Files browser, previews (images, video, audio, PDF, text), and Storage Management.
- **Android System Integration**: Share Sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), Foreground Sync Service (`dataSync`) with notification actions, and Jetpack Glance Home Screen Widgets.
- **Polished First-Time Onboarding & Diagnostics**: 5-step guided setup, contextual runtime permissions with Android Settings deep links, dedicated Privacy Policy & About screens, What's New release notes modal, categorized error reporting, and local-only crash diagnostic logs.

---

## Architecture & Module Map

- `app/src/main/java/com/example/domain/` — Immutable domain models (`Device`, `PairedDevice`, `TransferItem`, `AppSettings`, `AppError`, `ReleaseNotes`) and repository interfaces.
- `app/src/main/java/com/example/data/` — Room database (`AppDatabase`), DataStore preferences (`SettingsDataStore`), NSD discovery (`NsdDiscoveryManager`), TCP/TLS sockets (`TcpServer`, `TcpClient`, `ConnectionManager`, `TlsTransport`), Keystore security (`SecurityKeyStore`, `SecurityValidator`), QR pairing (`PairingManager`), and streaming engine (`TransferManager`, `StorageManager`, `FileTransferService`).
- `app/src/main/java/com/example/presentation/` — Jetpack Compose Material 3 screens (`OnboardingScreen`, `HomeScreen`, `DevicesScreen`, `TransfersScreen`, `SettingsScreen`, `NetworkDiagnosticsScreen`, `PrivacyDashboardScreen`, `PrivacyPolicyScreen`, `AboutScreen`, `ReceivedFilesScreen`, `StorageManagementScreen`, `TrustedDevicesScreen`).
- `app/src/main/java/com/example/widget/` — Jetpack Glance widgets (`DeviceStatusWidget`, `QuickSendWidget`, `TransferStatusWidget`).
- `app/src/main/java/com/example/util/` — `NetworkUtils`, `PermissionHelper`, `LocalCrashReporter`.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`PROTOCOL_SPECIFICATION.md`](PROTOCOL_SPECIFICATION.md), and [`docs/SECURITY.md`](docs/SECURITY.md) for deep technical details.

---

## Building & Testing

```bash
# Run JVM & Robolectric test suite
gradle :app:testDebugUnitTest

# Build debug APK
gradle :app:assembleDebug

# Compile Release Kotlin & run Release Vital Lint
gradle :app:compileReleaseKotlin :app:lintVitalAnalyzeRelease

# Build signed Release APK & AAB (requires KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD)
gradle :app:assembleRelease :app:bundleRelease
```

See [`docs/RELEASE_GUIDE.md`](docs/RELEASE_GUIDE.md) for signing keystore configuration and release verification steps.
