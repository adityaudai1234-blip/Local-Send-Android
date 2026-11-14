# LocalDrop Production & Release Documentation

## 1. Architecture Overview

LocalDrop is structured with clean MVVM & repository layering across Android Jetpack libraries:

* **Presentation Layer (`com.example.presentation`)**:
  * Pure Jetpack Compose Material 3 UI with edge-to-edge layout (`enableEdgeToEdge()`, `WindowInsets.safeDrawing`).
  * Top-level Navigation (`MainAppScaffold`): `Home`, `Devices`, `Transfers`, `Settings`.
  * Detail sub-routes:
    * `TransferDetailsScreen` (metadata, SHA-256 copy, retry/open/share/delete actions).
    * `TrustedDevicesScreen` (local alias rename, Keystore security info, ping, revoke trust).
    * `StorageManagementScreen` (SAF folder selection, temp cache sweep, multi-select received file cleanup).
* **Domain Layer (`com.example.domain`)**:
  * Immutable models (`Device`, `PairedDevice`, `TransferItem`, `AppSettings`, `TransferStats`) and repository contracts (`DeviceDiscoveryRepository`, `PairedDeviceRepository`, `TransferRepository`, `SettingsRepository`).
* **Data Layer (`com.example.data`)**:
  * **Discovery**: `NsdDiscoveryManager` (`_lsend._tcp.`) with lifecycle-aware start/stop and configurable refresh/stale cleanup interval.
  * **Transport & TLS**: `TcpServer`, `TcpClient`, `ConnectionManager`, and `TlsTransport` (`TLSv1.2`/`TLSv1.3`).
  * **Pairing & Security**: `PairingManager`, `SecurityKeyStore` (Android Keystore AES-256-GCM encryption for peer tokens), and `SecurityValidator` (path traversal prevention, rate limiting, auth lockout, port/size checks, suspicious extension flagging).
  * **Persistence**:
    * **Room (`AppDatabase`)**: Stores persistent `TransferEntity` history (including sender/receiver, duration, speed, SHA-256 checksum, destination path/URI, failure reason) and `PairedDeviceEntity`.
    * **DataStore (`SettingsDataStore`)**: Stores all 6 settings categories (`General`, `Network`, `File Transfers`, `Storage`, `Privacy & Security`, `About`) plus persistent local node UUID.
  * **Transfer Engine**: `TransferManager` + `FileTransferService` (foreground notification service with Cancel action & notification grouping), `FileStreamFraming` (64 KB buffered chunked I/O + SHA-256), `StorageManager` (SAF `DocumentsContract` & `FileProvider`), and `TransferNotificationHelper` / `TransferNotificationReceiver`.

---

## 2. Build & Release Signing Instructions

Signing keys and credentials are **never** hardcoded in Gradle files or source code.

1. Set the following environment variables in your CI/CD environment or local shell (or AI Studio Secrets panel where applicable):
   * `KEYSTORE_PATH` — Path to your `.jks` or `.keystore` upload keystore (defaults to `${rootDir}/my-upload-key.jks`).
   * `STORE_PASSWORD` — Upload keystore password.
   * `KEY_PASSWORD` — Key alias (`upload`) password.
2. Build a release APK or Android App Bundle (AAB):
   ```bash
   gradle :app:assembleRelease
   gradle :app:bundleRelease
   ```
3. To enable full R8 shrinking and obfuscation for store distribution, set `isMinifyEnabled = true` and `isShrinkResources = true` in `app/build.gradle.kts` (`app/proguard-rules.pro` already contains keep rules for Room, Protocol models, Manifest components, ZXing, and Coroutines).

---

## 3. Required Permissions Rationale

LocalDrop declares strictly least-privilege permissions in `AndroidManifest.xml`:

| Permission | Purpose | Runtime Prompt? |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Local LAN TCP sockets, Wi-Fi interface detection, and mDNS multicast discovery | No (Install-time) |
| `NEARBY_WIFI_DEVICES` (`usesPermissionFlags="neverForLocation"`, API 33+) | Discover nearby peers over local Wi-Fi without requiring location access | Yes (Android 13+) |
| `CAMERA` | Scan QR codes for local peer pairing | Yes (only when QR scanner opened) |
| `POST_NOTIFICATIONS` (API 33+) | Show incoming transfer requests (Accept/Decline), live progress, and completion alerts | Yes (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep active multi-file transfers reliable when backgrounded | No |
| Storage Selection | Uses Android Photo Picker (`PickVisualMedia`) / SAF (`OpenMultipleDocuments`, `OpenDocumentTree`) — **zero** broad `READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE` permissions requested | No |

---

## 4. Security Checklist

### Implemented Controls
- Encrypted peer token storage via `AndroidKeyStore` (`AES/GCM/NoPadding`, `SecurityKeyStore`).
- Disallowed unguarded HTTP cleartext traffic globally via `res/xml/network_security_config.xml` (`cleartextTrafficPermitted="false"`).
- Filename & canonical path traversal sanitization (`SecurityValidator.sanitizeFileName` + canonical base prefix checks).
- Suspicious executable extension detection (`.apk`, `.exe`, `.bat`, `.sh`, etc.) surfaced prominently in incoming transfer approval UI.
- Per-IP incoming socket rate limiting (30 connections / 10 seconds) and peer authentication lockout (5 failed attempts / 60 seconds).
- Oversized control frame rejection (max 2 MB) and blank `senderId` rejection.
- Default-deny auto-accept (`autoAcceptTrusted = false` by default).
- Incremental SHA-256 verification per file stream; immediate temp file deletion on hash mismatch or cancellation.
- One-tap "Clear Stored Credentials" in Settings -> Privacy & Security (wipes Room `paired_devices` table and `AndroidKeyStore` key material).

### Remaining Limitations & Future Hardening
- Cross-platform Tauri peers using legacy `v1` unencrypted TCP framing are still accepted on local LAN when `v2` TLS handshake bytes (`0x16`) are absent; disabling `v1` fallback strictly requires a matching Tauri desktop `v2` TLS release.
- Certificate pinning relies on QR-exchanged ephemeral trust establishment; enterprise CA or TOFU key fingerprint UI comparison can be layered into `PairingConfirmationDialog` without breaking storage schemas.
- `Downloads/LocalDrop` public directory listing displays files stored in the default folder; custom SAF tree folders (`content://` URIs) delegate deletion/browsing to the Android Files app or transfer history records.
