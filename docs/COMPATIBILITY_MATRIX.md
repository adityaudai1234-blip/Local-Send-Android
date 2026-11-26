# LocalDrop Cross-Platform Compatibility Matrix

This matrix summarizes protocol capabilities implemented in the **Android (`com.example`)** application and tracks verification status against the canonical schema (`protocol/localdrop-protocol.schema.json`), the independent **Node.js v2 Reference Peer** (`protocol/tests/protocol_reference_peer.mjs`), and unbuilt external Tauri desktop targets.

> **Workspace Disclosure:** No Tauri/Rust desktop source tree (`src-tauri/`, `Cargo.toml`) or Rust toolchain (`rustc`, `cargo`) exists inside this workspace. All automated cross-platform wire interoperability runs in-container over loopback TCP and TLS 1.2/1.3 against the independent Node.js v2 Reference Peer implementing the exact shared framing and protocol specification.

---

## 1. Feature & Protocol Interoperability Matrix

| Capability | Protocol Detail | Android Implementation | Node.js Reference Peer | Verification Status |
|---|---|---|---|---|
| **Control Envelope Framing** | 4-byte BE length + UTF-8 JSON (2 MB max) | `MessageFraming`, `ProtocolMessage` | `encodeControlFrame`, `SocketFrameReader` | **Verified** (Loopback TCP & TLS) |
| **Version Negotiation** | `v1` & `v2`; `ERR_UNSUPPORTED_VERSION` | `ProtocolConstants.negotiateProtocolVersion` | `negotiateVersion` | **Verified** (`v1`, `v2`, `v99` rejection) |
| **Transport Encryption & Pinning** | TLS 1.2/1.3 (`0x16` ClientHello peek) + EC P-256 SHA-256 fingerprint pinning | `TlsTransport`, `TcpServer`, `TcpClient` | `generateSelfSignedEcIdentity`, `connectPeerSocket` | **Verified** (Pinned pass + bad-fp fail) |
| **mDNS Discovery Schema** | `_lsend._tcp.` TXT (`alias`, `deviceType`, `protocol`, `port`, `fingerprint`, `tlsSupported`, `certFingerprint`) | `NsdDiscoveryManager`, `DeviceType` | Golden fixture `discovery_advertisement.json` | **Contract Verified** (Live Wi-Fi LAN mDNS requires physical hardware) |
| **QR & Deep-Link Pairing** | JSON payload + `localdrop://pair?...` URI, 120s TTL | `PairingPayload`, `PairingManager`, `GenerateQrDialog`, `ScanQrDialog` | `ReferencePeerServer.#handlePairingRequest` | **Verified** (Bidirectional Android ↔ Node) |
| **QR Single-Use Replay Protection** | `consumedSessionIds` + `ERR_PAIRING_REPLAY` | `PairingManager` | `ReferencePeerServer.consumedSessions` | **Verified** (Both directions) |
| **Auth Replay & Lockout** | Per-auth `nonce`, ±5 min timestamp skew (`ERR_REPLAY_DETECTED`), 5 fails / 60s (`ERR_AUTH_LOCKOUT`) | `PairingManager`, `ConnectionManager`, `SecurityValidator` | `ReferencePeerServer.#handleAuthRequest` | **Verified** (Both directions) |
| **Binary Stream Framing** | `0x4C534431` (`"LSD1"`) chunks ≤512 KB + `0x4C53454F` (`"LSEO"`) SHA-256 EOF | `FileStreamFraming`, `TransferManager` | `encodeChunkFrame`, `encodeEofFrame`, `readBinaryFrame` | **Verified** (Multi-file & 10 MB stream) |
| **Completion & Conflict ACK** | `expectCompletionAck` -> `FILE_CONFLICT` + `TRANSFER_COMPLETED` / `TRANSFER_FAILED` | `TransferManager`, `ProtocolMessageType` | `ReferencePeerServer.#handleTransferStarted` | **Verified** (Duplicate rename + corrupt checksum) |
| **Folder Hierarchy & Empty Dirs** | Sanitized POSIX `relativePath` + `isDirectory: true` | `FileMetadata`, `StorageManager`, `TransferManager` | `ReferencePeerServer.#handleTransferStarted` | **Verified** (Nested dirs + empty subdir) |
| **Cross-OS Filename Safety** | Strip control chars, normalize `<>:"/|?*` -> `_`, trim trailing `. `/space, prefix DOS reserved (`CON`, `NUL`, `COM1..9`, `LPT1..9`), preserve UTF-8 | `FileMetadata.sanitizeFileName` & `sanitizeRelativePath` | `sanitizeFileName` & `sanitizeRelativePath` | **Verified** (Parity unit + wire tests) |
| **Case-Insensitive Collision Policy** | `rename`, `overwrite`, `skip` across mixed-case sibling files | `StorageManager.findCaseInsensitiveMatch` | `resolveUniqueFile` + `duplicatePolicy` | **Verified** (`Photo.jpg` vs `photo.jpg`) |
| **O(1) Resume Checkpointing** | Incremental `MessageDigest.clone()` every 256 KB + `TRANSFER_READY` | `TransferManager.snapshotDigestHex` | `TRANSFER_READY` handshake (`supportsResume`) | **Verified** (10 MB TLS stream + Phase 8 resume tests) |
| **>4 GB Transfer Arithmetic** | 64-bit `Long` byte counts, speed, `etaSeconds`, formatted GB strings | `TransferItem` | Standard JSON number / 64-bit offsets | **Verified** (Unit tested with `5_000_000_000L` bytes) |

---

## 2. Target Environment Matrix

| Target Environment | Build & Execution Status | Notes |
|---|---|---|
| **Android JVM & Robolectric (API 36)** | **Verified** (`gradle :app:testDebugUnitTest`) | Full Phase 1–9 suite + live bidirectional Android ↔ Node.js Reference Peer wire tests over loopback TCP and pinned TLS. |
| **Android Release Compile & APK** | **Verified** (`gradle :app:compileReleaseKotlin :app:lintVitalAnalyzeRelease :app:assembleDebug`) | Compiles cleanly with R8/Kotlin release checks. |
| **Node.js `v2` Reference Peer** | **Verified** (`node --test protocol/tests/protocol_reference_peer.mjs`) | Standalone cross-platform wire & schema validator (`node v22.23.4`, `openssl 3.0.22`). |
| **Companion Tauri Desktop App (Windows / macOS / Linux)** | **Not Built / Not Executed Here** | Tauri/Rust repository absent from workspace; wire contract locked via `/protocol/` JSON Schema, fixtures, and Reference Peer. Run physical LAN interop tests on Windows 11, macOS (APFS), and Linux once desktop binary is built. |
| **Physical Multi-Device Wi-Fi / mDNS LAN** | **Not Executed in Container** | No physical Android hardware or LAN multicast switch in execution container; verified loopback sockets and mocked/harness NSD managers instead. |
