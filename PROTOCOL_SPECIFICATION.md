# LocalDrop Shared Communication Protocol (v1 & v2)
### Cross-Platform Protocol Specification for Android & Tauri Desktop (Windows, macOS, Linux)

This specification defines the network framing, message formats, pairing handshake, authentication flow, TLS capability negotiation, security controls, and binary chunked streaming file transfer protocol for peer-to-peer communication between Android and Tauri desktop applications.

---

## 1. Discovery & Network Transport

* **mDNS / DNS-SD Service Type**: `_lsend._tcp.`
* **Default Port**: `53317` (configurable within non-privileged range `1024..65535`).
* **mDNS TXT Attributes**:
  * `alias`: User-configured device name.
  * `deviceType`: `android`, `windows`, `macos`, `linux`.
  * `protocol`: `"v2"` (backward compatible with `"v1"` peers).
  * `version`: Application semantic version (e.g., `"2.2.0"`).
  * `deviceModel`: Optional hardware descriptor.
  * `fingerprint`: Ephemeral node identifier used for self-filtering and mDNS duplicate pruning.
  * `port`: Advertised service listening port (`1024..65535`).
* **Control Channel (Length-Prefixed JSON)**:
  * Control messages are preceded by a **4-byte Big-Endian signed 32-bit integer** representing the payload byte length $L$.
  * The next $L$ bytes contain UTF-8 encoded JSON.
  * Maximum control frame size: `2,097,152` bytes (2 MB); frames exceeding bounds, containing empty `senderId`, unsupported protocol `version` (only `"v1"` and `"v2"` accepted), blank `type`, or negative `timestamp` are rejected immediately.
* **Payload Channel (Binary Chunk Streaming)**:
  * Raw file contents are streamed in configurable binary frames (`DEFAULT_CHUNK_SIZE = 65536` bytes / 64 KB default; sender-selectable `32 KB`, `64 KB`, `128 KB`, or `256 KB` buffers; strict receiver frame cap of `512 KB` = `DEFAULT_CHUNK_SIZE * 8` in `FileStreamFraming`) with magic headers so multi-gigabyte files never buffer entirely in RAM.

```
Length-Prefixed Control Frame:
+-----------------------------------+------------------------------------------+
| 4-byte Int (Payload Length L)     | L bytes of UTF-8 JSON Payload            |
+-----------------------------------+------------------------------------------+
```

---

## 2. TLS Capability Negotiation (v2)

* Peers advertising `protocolVersion = "v2"` and `tlsPreferred = true` support TLS 1.2 / TLS 1.3 over the same TCP socket.
* `TcpServer` inspects the initial stream byte (`0x16` = TLS ClientHello record vs length-prefixed JSON) via `TlsTransport.isTlsClientHello(...)` **before** control frame decoding. If an `SSLContext` is configured, it upgrades the socket to `SSLSocket`; if no server certificate KeyStore is configured yet, it closes the socket cleanly with a structured warning rather than misinterpreting TLS ClientHello bytes as a frame length.
* For local self-signed peer certificates exchanged during QR pairing, trust pinning compares the QR-advertised or mutually paired fingerprint before upgrading the socket (`TlsTransport.createClientSslSocketFactory`).
* Fallback to `v1` unencrypted local LAN framing only happens when a peer explicitly lacks TLS v2 capability and user policy permits it.

---

## 3. Common Message Envelope

All control messages share this JSON structure:

```json
{
  "version": "v2",
  "type": "TRANSFER_REQUEST",
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "senderId": "android-node-uuid",
  "receiverId": "tauri-desktop-uuid",
  "timestamp": 1731000000000,
  "payload": {
    /* message-type specific fields */
  }
}
```

---

## 4. Message Types & Error Codes

### 4.1 Pairing & Auth Messages (Phase 2)
* `PAIRING_REQUEST`: Sent with temporary QR token (`120s` default TTL) and session ID. Never contains Keystore permanent keys.
* `PAIRING_ACCEPTED`: Returns mutual permanent authentication token stored encrypted in Android Keystore / Desktop keychain.
* `PAIRING_REJECTED`: Returns rejection reason if token/session invalid or expired.
* `AUTHENTICATION_REQUEST`: Proves identity using stored mutual token.
* `AUTHENTICATION_RESPONSE`: Acknowledges successful authentication.
* `HEARTBEAT`: Ping/pong keepalive packet.
* `ERROR`: Returns structured error code and human-readable description.

### 4.2 File Transfer Messages (Phase 3, 4, 5 & 6)
* `TRANSFER_REQUEST`: Sender initiates session with `sessionId`, `files[]` (`fileId`, `fileName`, `fileSize`, `mimeType`, optional `relativePath`), `totalSize`, and optional `tlsPreferred`.
  * `relativePath` (optional UTF-8 POSIX path, e.g., `"TripPhotos/Day1/photo.jpg"`): Enables folder-hierarchy preservation across Android and Tauri desktop peers while remaining 100% optional for legacy v1/v2 flat-file peers.
* `TRANSFER_ACCEPTED`: Receiver approves the transfer session (returned synchronously on the open request socket within the 60s approval window, or accepted asynchronously over a new socket if dispatched externally).
* `TRANSFER_REJECTED`: Receiver declines (user declined, approval timeout, unknown peer policy, size limit exceeded, or insufficient disk space).
* `TRANSFER_STARTED`: Sender announces start of a specific file stream on socket (`sessionId`, `fileId`, `fileName`, `fileSize`, `mimeType`, optional `relativePath`, `senderName`).
* `TRANSFER_PROGRESS`: Optional progress notification.
* `TRANSFER_PAUSED`: Notifies peer that transfer was paused.
* `TRANSFER_RESUMED`: Notifies peer that transfer was resumed.
* `TRANSFER_CANCELLED`: Informs peer that transfer was aborted (`CANCELLED` status persisted cleanly).
* `TRANSFER_COMPLETED`: Handshake acknowledging successful transfer and SHA-256 verification.

### 4.3 Standard Error Codes
| Code | Meaning | Receiver / Sender Action |
|---|---|---|
| `ERR_INVALID_FRAME` | Malformed header, oversized frame, or blank `senderId` | Close socket immediately |
| `ERR_RATE_LIMITED` | >30 socket requests in 10 seconds from single IP | Drop incoming connection |
| `ERR_AUTH_LOCKOUT` | 5 failed pairing/auth attempts within 60 seconds | Reject auth for lockout window |
| `ERR_TOKEN_EXPIRED` | QR pairing session timestamp expired | Prompt user to regenerate QR |
| `ERR_FILE_TOO_LARGE` | File size exceeds receiver `maxAcceptedFileSizeMb` limit | Reject with `TRANSFER_REJECTED` |
| `ERR_INSUFFICIENT_SPACE` | Free storage below file size + 50 MB safety buffer | Reject with `TRANSFER_REJECTED` |
| `ERR_CHECKSUM_MISMATCH` | SHA-256 digest mismatch at EOF | Delete temp `.tmp` file, mark `FAILED` |
| `ERR_PATH_UNSAFE` | Canonical path escape detected | Abort write, raise security warning |

---

## 5. File Transfer Workflow

```
Sender (Android or Tauri)                          Receiver (Android or Tauri)
      |                                                        |
      | 1. TRANSFER_REQUEST (sessionId, files[], totalSize)    |
      |------------------------------------------------------->|
      |                                                        | [Validate size/policy/suspicious exts]
      |                                                        | [Explicit consent unless trusted auto-accept]
      | 2. TRANSFER_ACCEPTED (sessionId, accepted: true)       |
      |<-------------------------------------------------------|
      |                                                        |
      | 3. Connect dedicated streaming socket (TLS if v2)      |
      |=======================================================>|
      |                                                        |
      | 4. TRANSFER_STARTED (fileId, fileName, relativePath?)  |
      |------------------------------------------------------->|
      |                                                        | [Sanitize relativePath & check canonical prefix]
      |                                                        | [Create temp file: .tmp_{fileId}]
      | 5. Binary Chunk Frames (CHUNK_MAGIC, offset, data...)   |
      |------------------------------------------------------->| [Write chunk <= 512KB, update SHA-256]
      | 6. Binary EOF Frame (EOF_MAGIC, SHA-256 checksum)      |
      |------------------------------------------------------->| [Verify SHA-256]
      |                                                        | [Apply relativePath / organizeByType / duplicate policy]
      |                                                        | [Commit to Downloads/LocalDrop or SAF URI]
      | 7. TRANSFER_COMPLETED / Record Room History            |
      |<------------------------------------------------------>|
```

---

## 6. Binary Chunk Framing Specification

### 6.1 Data Chunk Frame Format
Magic: `0x4C534431` (ASCII `"LSD1"` = LocalSend Data v1)

| Field | Type | Size | Description |
|---|---|---|---|
| Magic Header | UInt32 BE | 4 bytes | `0x4C534431` (`"LSD1"`) |
| File Index | UInt32 BE | 4 bytes | Index of the file within the session (0-based) |
| Chunk Offset | UInt64 BE | 8 bytes | Byte offset within the file |
| Chunk Length $N$ | UInt32 BE | 4 bytes | Byte count of payload (default 65536; allowed `<= 524288` / 512 KB) |
| Payload Data | Bytes | $N$ bytes | Raw file bytes |

### 6.2 EOF Frame Format
Magic: `0x4C53454F` (ASCII `"LSEO"` = LocalSend End Of file)

| Field | Type | Size | Description |
|---|---|---|---|
| Magic Header | UInt32 BE | 4 bytes | `0x4C53454F` (`"LSEO"`) |
| File Index | UInt32 BE | 4 bytes | Index of the file within the session (0-based) |
| SHA-256 Digest | Bytes | 32 bytes | SHA-256 binary digest of the entire file |

---

## 7. Security & Integrity Guarantees

1. **Path Traversal Sanitization & Canonical Verification**:
   Receiving peers sanitize all incoming filenames via `SecurityValidator.sanitizeFileName`, and validate optional `relativePath` hierarchy via `FileMetadata.sanitizeRelativePath` / `SecurityValidator.isRelativePathSafe` (stripping leading slashes/backslashes, rejecting `..`, `:`, and null bytes, and enforcing `canonicalPath.startsWith(canonicalBase)` before creating directories or writing files).
2. **Executable & Dangerous Extension Warnings**:
   Files ending in `.apk`, `.exe`, `.bat`, `.cmd`, `.sh`, `.msi`, `.jar`, `.ps1`, `.vbs`, etc. trigger an explicit security warning banner in `IncomingTransferDialog` before approval.
3. **Atomic Writes & Startup Temp Cleanup**:
   Incoming bytes write exclusively to `cacheDir/transfer_temp/incoming_{fileId}.tmp`. Orphaned `.tmp` files from interrupted transfers or crashes are swept on application startup and never pollute user Downloads.
4. **SHA-256 Cryptographic Verification**:
   Computed incrementally per chunk and verified against the 32-byte EOF digest. Corrupted streams immediately purge the `.tmp` buffer.
5. **Default-Deny Auto-Accept**:
   Global `autoAcceptTrusted` and per-device `PairedDevice.autoAccept` both default to `false`; explicit user approval (via dialog or notification Accept/Decline action) is required unless manually enabled by the user.
6. **Abuse Controls**:
   Per-IP connection rate limiting (30 connections / 10 seconds) and peer authentication lockout (5 consecutive failures / 60 seconds).

---

## 8. Tauri Desktop (Rust) Reference Compatibility

Tauri desktop peers implementing `CHUNK_MAGIC = 0x4C534431`, `EOF_MAGIC = 0x4C53454F`, 4-byte BE length-prefixed UTF-8 JSON control envelopes, and `_lsend._tcp.` mDNS registration on port `53317` interoperate directly with Android v2. Tauri peers should populate `version: "v2"` when supporting TLS, include optional sanitized `relativePath` entries inside `FileMetadata` for folder transfers, respect the 512 KB max binary chunk length, and gracefully handle `version: "v1"` frames for backward compatibility.
