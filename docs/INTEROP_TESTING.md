# LocalDrop Cross-Platform Interoperability & Testing Guide

This guide covers automated wire-compatibility testing, manual cross-peer verification with the Reference Peer, network firewall requirements, and OS filesystem caveats across Android, Windows, macOS, and Linux.

---

## 1. Automated Verification Commands

### 1.1 Independent Node.js Reference Peer Tests
Validates [`protocol/localdrop-protocol.schema.json`](../protocol/localdrop-protocol.schema.json), canonical [`protocol/fixtures/`](../protocol/fixtures/), pinned EC P-256 TLS handshakes, version negotiation (`v1`, `v2`, `ERR_UNSUPPORTED_VERSION`), single-use QR replay rejection (`ERR_PAIRING_REPLAY`), sanitized nested transfers, and `ERR_CHECKSUM_MISMATCH`:

```bash
node --test protocol/tests/protocol_reference_peer.mjs
```

### 1.2 Android Full Test Suite (Phases 1–9 + Live Bidirectional Wire Interop)
`Phase9CrossPlatformInteropTest` spawns `protocol/tests/protocol_reference_peer.mjs --serve` over loopback `127.0.0.1` and executes real TLS-pinned pairing, replay rejection, bidirectional file/folder transfers, corrupted-EOF checksum rejection, Room `v5 -> v6` migration, 100-file batch stress, 10 MB TLS streaming, and zero-socket-leak assertions:

```bash
gradle :app:testDebugUnitTest
```

If `.m2` cache symlink is needed in containerized environments:
```bash
test -L /root/.m2 || ln -sfn /home/appuser/.m2 /root/.m2
gradle :app:testDebugUnitTest
```

---

## 2. Running the Headless Reference Peer Manually (`--serve`)

To inspect live wire exchanges or test an external Tauri desktop client locally against the Reference Peer:

```bash
node protocol/tests/protocol_reference_peer.mjs \
  --serve \
  --port 53318 \
  --tls true \
  --session-id demo-session-1 \
  --pairing-token demo-qr-token \
  --duplicate-policy rename
```

On startup, it prints a JSON `READY` event containing the listening `port`, generated EC P-256 `certFingerprint`, `sessionId`, and `pairingToken`, then accepts newline-delimited JSON commands on `stdin` (`send`, `send_raw_control`, `quit`).

---

## 3. Firewall & Local Network Ports

| Port / Protocol | Direction | Purpose | Notes |
|---|---|---|---|
| **53317 / TCP** | Inbound & Outbound | Control JSON envelopes (`v1`/`v2`), TLS upgrade (`0x16`), and `"LSD1"`/`"LSEO"` binary streams | User-configurable (`1024..65535`). Both Windows Defender Firewall and Linux `ufw`/`firewalld` must allow incoming TCP on Private/LAN profiles. |
| **5353 / UDP** | Multicast (`224.0.0.251`) | mDNS / DNS-SD discovery (`_lsend._tcp.`) | Disable AP/client isolation on Wi-Fi routers; grant `NEARBY_WIFI_DEVICES` on Android 13+. Manual IP + QR pairing still functions if multicast is blocked. |

---

## 4. Cross-OS Filesystem & URI Caveats

1. **Windows Reserved Names & Illegal Characters**:
   * Filenames matching DOS device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1..COM9`, `LPT1..LPT9`, with or without extensions) are automatically prefixed with `_` (e.g., `_CON.txt`) by `FileMetadata.sanitizeFileName`.
   * Illegal characters (`< > : " / | ? *`) and ASCII control bytes are replaced with `_`, and trailing dots/spaces are trimmed.
   * Windows drive prefixes (`C:\...`) on `relativePath` segment 0 are rejected (`null` -> `ERR_PATH_UNSAFE`), while colons in regular filename segments (`12:30_photo.jpg`) are safely normalized to `_`.
2. **Case-Sensitivity Across APFS / NTFS / ext4**:
   * Android Linux filesystems (`ext4`/`f2fs`) and standard Linux desktops are case-sensitive, whereas Windows NTFS and default macOS APFS are case-insensitive.
   * `StorageManager.findCaseInsensitiveMatch` detects case-only sibling matches (e.g., `Photo.jpg` vs `photo.jpg`) so `skip`, `rename` (`photo (1).jpg`), and `overwrite` behave identically across Android and desktop hosts.
3. **Android ContentResolver / SAF Permissions**:
   * Transfers sourced from external document providers or galleries use ContentResolver streams and transient URI permissions; large folders preserve relative subfolder layout without holding broad storage permissions.

---

## 5. Honest Verification Scope

Because the companion Tauri/Rust desktop codebase (`src-tauri`) is not present in this repository workspace, real-host tests on physical Windows, macOS, and Linux desktops and multi-device Wi-Fi LAN multicast tests remain to be executed once desktop binaries are compiled alongside this protocol schema. All wire-level protocol rules are verified today between Android's JVM/Robolectric stack and the independent Node.js v2 Reference Peer over loopback TCP and pinned TLS 1.2/1.3.
