# LocalDrop Architecture Overview

**Package:** `com.example` (`applicationId`: `com.aistudio.localdrop.wzkp`)  
**UI Stack:** Kotlin, Jetpack Compose, Material Design 3, Navigation Compose  
**Target SDK:** `36` | **Min SDK:** `24` | **Version:** `2.2.0` (Build `7`)

---

## 1. Layered MVVM Architecture

LocalDrop strictly separates Domain contracts, Data implementations, and Presentation UI through a centralized manual dependency container (`DefaultAppContainer` in `com.example.di.AppContainer`), keeping testability high without heavy runtime code-generation overhead.

```
Presentation (Compose M3 Screens + ViewModels + Glance Widgets)
        │
        ▼
Domain (Immutable Models + Repository Interfaces + AppError/Sanitizer)
        │
        ▼
Data (NSD/mDNS + TCP/TLS Pinned Sockets + Room v6 + DataStore + Keystore + SAF)
```

### Startup & Splash Lifecycle
1. `LocalDropApp.onCreate()` installs `LocalCrashReporter` (on-device uncaught exception snapshot recorder with secret redaction), builds `DefaultAppContainer`, initializes the persistent local EC P-256 TLS identity, cleans stale `.tmp` transfer files, and synchronizes settings observers (SAF URI, mDNS refresh interval, TCP listening port, mDNS-to-paired peer sync, Glance widgets).
2. `MainActivity.onCreate()` calls AndroidX `installSplashScreen()` with a bounded non-blocking condition (`!settingsRepository.isLoaded.value` capped at 1.5 s) so the splash screen adapts automatically to light/dark system themes without blocking startup.
3. Unsolicited startup permission prompts are eliminated; `MainAppScaffold` launches `OnboardingScreen` on first run and coordinates Android Share Sheet, Glance Widget actions, and one-time What's New release notes.

---

## 2. Networking & Protocol Layer

- **mDNS Discovery (`NsdDiscoveryManager`)**: Registers and resolves `_lsend._tcp.` on Android `NsdManager`, advertising `tlsSupported` and `certFingerprint` TXT attributes, filtering self-fingerprints, deduplicating endpoints, pruning stale peers, and holding `WifiManager.MulticastLock` only while actively scanning or advertising. Discovery automatically pauses in `ON_STOP` and resumes in `ON_START`.
- **Transport (`TcpServer`, `TcpClient`, `TlsTransport`)**: Listens on port `53317` (user-configurable `1024..65535`). Persists a self-signed EC P-256 `X.509` keypair in `filesDir/security/` (`TlsTransport.getOrCreateLocalTlsIdentity`), inspects initial client bytes (`0x16` ClientHello vs length-prefixed JSON) to upgrade `v2` TLS sockets seamlessly, and pins peer SHA-256 fingerprints on outgoing TLS upgrades (`TlsTransport.upgradeClientSocketToTls`) while preserving `v1` fallback when peers lack TLS.
- **Connection Resilience (`ConnectionManager`)**: Tracks per-peer `ConnectionState`, runs 20 s nonce-protected heartbeats/auth checks over pinned TLS or LAN TCP, registers `ConnectivityManager.NetworkCallback`, runs bounded 3-attempt exponential backoff reconnects, and feeds sanitized errors to `AppErrorReporter`.
- **Transfer Engine (`TransferManager`, `FileStreamFraming`, `FileTransferService`)**: Coordinates slot-limited concurrent transfers (`1..5`), 60 s approval windows, `"LSD1"` binary chunks, O(1) incremental `MessageDigest.clone()` SHA-256 checkpointing (`snapshotDigestHex` every 256 KB), `"LSEO"` verification + `v2` `expectCompletionAck` handshakes, real-time `etaSeconds` calculation, pause/resume/cancel signaling, foreground `dataSync` service notification, and Room history recording.

---

## 3. Storage & Persistence

- **Room Database (`AppDatabase` v6)**:
  - `transfers` table (`TransferEntity`): Stores filename, extension, MIME type, size, direction, status, speed metrics, duration, `relativePath`, SHA-256 checksum, peer details, and final disk path/URI. Supports non-destructive `MIGRATION_1_2` through `MIGRATION_5_6`.
  - `paired_devices` table (`PairedDeviceEntity`): Stores trusted peers, AES-GCM encrypted auth tokens (`encryptedAuthToken`), `certFingerprint`, `tlsSupported`, favorites, usage counts, and per-peer `autoAccept` flags.
- **Preferences (`SettingsDataStore`)**: Persists user configuration across 7 organized Settings sections (`General`, `Discovery`, `Transfers`, `Notifications`, `Privacy & Security`, `Storage`, `About`) plus `hasCompletedOnboarding` and `lastSeenReleaseVersion`.
- **File Storage (`StorageManager`)**: Writes incoming streams to app-private `cacheDir/transfer_temp/` first, validates SHA-256, checks `canonicalPath.startsWith(canonicalBase)`, enforces cross-OS filename/path sanitization (`FileMetadata`) and case-insensitive sibling collision resolution (`findCaseInsensitiveMatch`), and commits cleanly to `Downloads/LocalDrop` or a user-selected SAF tree URI.

---

## 4. Error Reporting & Diagnostics

- **Categorized Errors (`AppError.kt`)**: All discovery, pairing, authentication, connection, transfer, storage, permission, and protocol failures route through `AppErrorMapper` and `AppErrorSanitizer` (stripping tokens, Bearer headers, and long hex secrets) before surfacing friendly UI explanations and expandable technical details in `NetworkDiagnosticsScreen`.
- **Local-Only Crash Log (`LocalCrashReporter`)**: Records timestamped, redacted stack summaries exclusively to `context.filesDir/localdrop_crash_diagnostics.log`, viewable and clearable inside `NetworkDiagnosticsScreen`.
