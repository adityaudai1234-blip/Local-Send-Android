# LocalDrop Shared Communication Protocol (v1)
### Cross-Platform Protocol Specification for Android & Tauri (Desktop)

This specification defines the network framing, message formats, pairing handshake, and authentication flow for peer-to-peer communication between Android and Tauri desktop applications (Windows, macOS, Linux).

---

## 1. Network Transport & Framing

* **Transport**: Direct TCP socket connections.
* **Default Port**: `53317` (configurable).
* **Length-Prefixed Framing**:
  * Every message sent over a TCP stream is preceded by a **4-byte Big-Endian signed 32-bit integer** representing the length $L$ of the payload in bytes.
  * The next $L$ bytes contain the UTF-8 encoded JSON payload.
  * Maximum frame size: `2,097,152` bytes (2 MB).
  * Sockets must read until the full $L$ bytes are received before parsing JSON (handling TCP fragmentation).

```
+-----------------------------------+------------------------------------------+
| 4-byte Int (Payload Length L)     | L bytes of UTF-8 JSON Payload            |
+-----------------------------------+------------------------------------------+
```

---

## 2. Common Message Envelope

All control messages share this JSON structure:

```json
{
  "version": "v1",
  "type": "PAIRING_REQUEST",
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "senderId": "android-node-uuid",
  "receiverId": "tauri-desktop-uuid",
  "timestamp": 1731000000000,
  "payload": {
    /* message-type specific fields */
  }
}
```

### Fields:
| Field | Type | Required | Description |
|---|---|---|---|
| `version` | String | Yes | Protocol version. Must be `"v1"`. |
| `type` | String | Yes | Message type identifier (see Message Types). |
| `messageId` | String | Yes | Unique UUID for request tracking / deduplication. |
| `senderId` | String | Yes | Unique identifier of sending device. |
| `receiverId` | String | No | Target device identifier. |
| `timestamp` | Long | Yes | Unix timestamp in milliseconds. |
| `payload` | Object | Yes | Map of key-value parameters. |

---

## 3. Message Types

### 3.1 `PAIRING_REQUEST`
Initiated by the scanning device after reading the target's QR code.
* **Payload Fields**:
  * `sessionId` (String, Required): Matches the QR code session UUID.
  * `token` (String, Required): Temporary 16-32 byte token from QR code.
  * `deviceName` (String, Required): Friendly alias of the scanning device.
  * `platform` (String, Required): `"android"` | `"windows"` | `"macos"` | `"linux"`.
  * `ipAddress` (String, Required): Scanning device IPv4 address.
  * `port` (Int, Required): Listening TCP port of scanning device.
  * `model` (String, Optional): Hardware/OS description (e.g. `"Pixel 8"`, `"Windows 11"`).

### 3.2 `PAIRING_ACCEPTED`
Returned by the displaying device when the session and token match and have not expired.
* **Payload Fields**:
  * `authToken` (String, Required): Secure permanent mutual authentication token.
  * `deviceName` (String, Required): Friendly alias of the displaying device.
  * `platform` (String, Required): Platform of displaying device.
  * `model` (String, Optional): Hardware/OS description.

### 3.3 `PAIRING_REJECTED`
Returned when the session ID or token does not match, or the QR code has expired.
* **Payload Fields**:
  * `reason` (String, Required): Human-readable rejection reason.

### 3.4 `AUTHENTICATION_REQUEST`
Sent by a paired device when establishing a new connection session.
* **Payload Fields**:
  * `token` (String, Required): Previously established mutual auth token.
  * `timestamp` (Long, Required): Request timestamp.

### 3.5 `AUTHENTICATION_RESPONSE`
Returned upon successful token verification.
* **Payload Fields**:
  * `status` (String, Required): `"authenticated"`.

### 3.6 `HEARTBEAT`
Sent periodically (every 15–20s) to maintain socket health.
* **Payload**: `{"ping": 1731000000000}` or `{"pong": 1731000000000}`.

### 3.7 `CONNECTION_CLOSED`
Sent prior to voluntary graceful socket closure.

### 3.8 `ERROR`
Returned on protocol, parsing, or transport failures.
* **Payload Fields**:
  * `code` (String): e.g. `"AUTH_FAILED"`, `"MALFORMED_FRAME"`, `"TIMEOUT"`.
  * `message` (String): Human-readable error description.

---

## 4. QR Code Pairing Specification

The QR code encodes a single compact JSON string with 120-second validity:

```json
{
  "protocolVersion": "v1",
  "sessionId": "b4cfc134-ff4c-47b2-8c11-137b2586b6a0",
  "pairingToken": "X9k2Pq8...32chars",
  "deviceId": "node-android-1234",
  "deviceName": "Alice's Phone",
  "platform": "android",
  "ipAddress": "192.168.1.50",
  "port": 53317,
  "expiresAt": 1731000120000
}
```

---

## 5. Tauri (Rust) Reference Implementation

In Tauri (Rust), TCP socket framing can be implemented cleanly with `tokio`:

```rust
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub async fn write_frame<W: AsyncWriteExt + Unpin>(writer: &mut W, json_str: &str) -> std::io::Result<()> {
    let bytes = json_str.as_bytes();
    let len = bytes.len() as u32;
    writer.write_all(&len.to_be_bytes()).await?;
    writer.write_all(bytes).await?;
    writer.flush().await?;
    Ok(())
}

pub async fn read_frame<R: AsyncReadExt + Unpin>(reader: &mut R) -> std::io::Result<String> {
    let mut len_buf = [0u8; 4];
    reader.read_exact(&mut len_buf).await?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > 2 * 1024 * 1024 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Frame too large"));
    }
    let mut payload = vec![0u8; len];
    reader.read_exact(&mut payload).await?;
    String::from_utf8(payload).map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
}
```
