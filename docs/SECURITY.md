# LocalDrop Security & Privacy Architecture

**Scope:** Local-network peer-to-peer transfers over trusted LAN/Wi-Fi  
**Version:** `2.2.0`

---

## 1. Zero-Cloud & Zero-Telemetry Guarantee

- LocalDrop opens **zero** outbound internet/cloud connections for telemetry, analytics, crash reporting, user accounts, or file relaying.
- `res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="false"` globally for HTTP connections.
- `LocalCrashReporter` and `AppErrorReporter` operate strictly on-device (`filesDir` and in-memory state) and apply regex redaction (`AppErrorSanitizer`) so auth tokens, pairing tokens, passwords, and long hex keys are never logged in error messages or crash files.

---

## 2. Cryptographic & Access Controls

| Control | Implementation | Details |
|---|---|---|
| **Credential Storage** | `SecurityKeyStore` | Wraps mutual peer authentication tokens using `AndroidKeyStore` `AES/GCM/NoPadding` (`256-bit` hardware/TEE-backed key when available). |
| **QR Session Replay Lock** | `PairingManager` | Ephemeral QR JSON and `localdrop://pair?...` sessions expire automatically after 120 seconds, are bound to single-use `consumedSessionIds` (`ERR_PAIRING_REPLAY`), and never embed long-term Keystore keys. |
| **Auth Replay Window** | `PairingManager` & `ConnectionManager` | `AUTHENTICATION_REQUEST` frames include a random `nonce` and `timestamp`, checked against per-peer seen nonces and a ±5 minute (`300,000 ms`) clock-skew window (`ERR_REPLAY_DETECTED`). |
| **Transport Encryption & Pinning** | `TlsTransport` + `TcpServer` | Persistent EC P-256 X.509 identity (`filesDir/security/`) supports TLS 1.2/1.3 (`v2`) via ClientHello byte peeking (`0x16`) and SHA-256 certificate fingerprint pinning (`upgradeClientSocketToTls`). |
| **Cross-OS Path Safety** | `FileMetadata`, `SecurityValidator` & `StorageManager` | Sanitizes Windows/macOS/Linux illegal characters (`<>:"/|?*`), control bytes, trailing dots/spaces, and DOS reserved names (`CON`, `NUL`, `COM1..9`, `LPT1..9`), rejects drive prefixes & `..`, asserts `canonicalPath.startsWith(canonicalBase)`, and resolves case-insensitive sibling collisions. |
| **Default-Deny Auto-Accept** | `AppSettings` & `PairedDevice` | Global `autoAcceptTrusted` and per-device `autoAccept` both default to `false`. Executable extensions (`.apk`, `.exe`, `.bat`, `.sh`, `.msi`, etc.) trigger explicit warnings. |
| **Abuse & DoS Controls** | `SecurityValidator` & `ProtocolConstants` | Per-IP incoming socket rate limit (`30` connections / `10s`), auth lockout (`5` failures / `60s`), strict protocol version check (`v1`/`v2` or `ERR_UNSUPPORTED_VERSION`), control frame cap (`2 MB`), binary chunk ceiling (`512 KB`). |
| **Stream Integrity** | `FileStreamFraming` & `TransferManager` | Computes incremental SHA-256 (with O(1) `MessageDigest.clone()` resume checkpoints) over streamed chunks and deletes `.tmp` buffers immediately on hash mismatch or cancellation. |

---

## 3. Permissions Posture

- **Zero Broad Storage Permissions**: Uses zero-permission Android system pickers (`OpenMultipleDocuments`, `OpenDocumentTree`, `PickVisualMedia`) and temporary Share Sheet URI grants. Never requests `READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, or `MANAGE_EXTERNAL_STORAGE`.
- **Least-Privilege Runtime Prompts**:
  - `NEARBY_WIFI_DEVICES` (`neverForLocation`, Android 13+): Requested contextually with clear rationale.
  - `CAMERA`: Requested only when user enables QR camera scanning; manual JSON pairing works without Camera.
  - `POST_NOTIFICATIONS` (Android 13+): Requested contextually with granular notification toggles in Settings.

---

## 4. Known Scope & Honest Security Boundaries

1. **Legacy `v1` Interoperability**: Unencrypted `v1` TCP connections remain accepted when interoperating with older peers that do not advertise/negotiate `v2` TLS. Disable legacy `v1` only once all companion Tauri desktop builds enforce `v2` TLS.
2. **LAN Threat Model**: LocalDrop assumes a user-trusted local Wi-Fi/LAN segment for initial discovery and explicit QR pairing. Users should avoid auto-accepting transfers on public/untrusted hotspots.
3. **Device Keystore Wipe**: Removing a trusted device or choosing *Clear Stored Credentials* in Settings / Privacy Dashboard wipes both the Room database record and the associated Android Keystore key material.
