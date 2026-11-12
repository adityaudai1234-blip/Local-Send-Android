# LocalDrop Shared Communication Protocol (v1 & v2 Extension)
### Cross-Platform Protocol Specification for Android & Tauri Desktop (Windows, macOS, Linux)

This specification defines the network framing, message formats, pairing handshake, authentication flow, and binary chunked streaming file transfer protocol for peer-to-peer communication between Android and Tauri desktop applications.

---

## 1. Network Transport & Framing

* **Transport**: Direct peer-to-peer TCP socket connections over local network / Wi-Fi.
* **Default Port**: `53317` (configurable).
* **Control Channel (Length-Prefixed JSON)**:
  * Control messages are preceded by a **4-byte Big-Endian signed 32-bit integer** representing the payload byte length $L$.
  * The next $L$ bytes contain UTF-8 encoded JSON.
  * Maximum control frame size: `2,097,152` bytes (2 MB).
* **Payload Channel (Binary Chunk Streaming)**:
  * Raw file contents are streamed in binary frames (default 64 KB chunks) with magic byte headers to avoid memory buffering and support large gigabyte files.

```
Length-Prefixed Control Frame:
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

## 3. Message Types

### 3.1 Pairing & Auth Messages (Phase 2)
* `PAIRING_REQUEST`: Sent with temporary QR token and session ID.
* `PAIRING_ACCEPTED`: Returns mutual permanent authentication token.
* `PAIRING_REJECTED`: Returns rejection reason if token/session invalid or expired.
* `AUTHENTICATION_REQUEST`: Proves identity using stored mutual token.
* `AUTHENTICATION_RESPONSE`: Acknowledges successful authentication.
* `HEARTBEAT`: Ping/pong keepalive packet.
* `ERROR`: Returns error code and description.

### 3.2 File Transfer Messages (Phase 3)
* `TRANSFER_REQUEST`: Sender initiates session with list of files and total size.
* `TRANSFER_ACCEPTED`: Receiver approves the transfer session.
* `TRANSFER_REJECTED`: Receiver declines (user declined or insufficient disk space).
* `TRANSFER_STARTED`: Sender announces start of a specific file stream on socket.
* `TRANSFER_PROGRESS`: Optional progress notification.
* `TRANSFER_PAUSED`: Notifies peer that transfer was paused.
* `TRANSFER_RESUMED`: Notifies peer that transfer was resumed.
* `TRANSFER_CANCELLED`: Informs peer that transfer was aborted.
* `TRANSFER_COMPLETED`: Handshake acknowledging successful transfer.

---

## 4. File Transfer Workflow

```
Sender (Android or Tauri)                          Receiver (Android or Tauri)
      |                                                        |
      | 1. TRANSFER_REQUEST (sessionId, file list, totalSize)  |
      |------------------------------------------------------->|
      |                                                        | [Prompt user or auto-accept]
      | 2. TRANSFER_ACCEPTED (sessionId, accepted: true)       |
      |<-------------------------------------------------------|
      |                                                        |
      | 3. Connect dedicated streaming socket                  |
      |=======================================================>|
      |                                                        |
      | 4. TRANSFER_STARTED (fileId, fileName, fileSize)       |
      |------------------------------------------------------->|
      |                                                        | [Create temp file: .tmp_{fileId}]
      | 5. Binary Chunk Frames (CHUNK_MAGIC, offset, data...)   |
      |------------------------------------------------------->| [Write chunk, update SHA-256]
      | 5. Binary Chunk Frames ...                             |
      |------------------------------------------------------->|
      |                                                        |
      | 6. Binary EOF Frame (EOF_MAGIC, SHA-256 checksum)      |
      |------------------------------------------------------->| [Verify SHA-256]
      |                                                        | [Rename temp to destination]
      | 7. Socket close or next file                           |
      |------------------------------------------------------->|
```

---

## 5. Binary Chunk Framing Specification

To stream files with zero memory exhaustion and high throughput, data bytes are streamed in structured frames:

### 5.1 Data Chunk Frame Format
Magic: `0x4C534431` (ASCII `"LSD1"` = LocalSend Data v1)

| Field | Type | Size | Description |
|---|---|---|---|
| Magic Header | UInt32 BE | 4 bytes | `0x4C534431` (`"LSD1"`) |
| File Index | UInt32 BE | 4 bytes | Index of the file within the session (0-based) |
| Chunk Offset | UInt64 BE | 8 bytes | Byte offset within the file |
| Chunk Length $N$ | UInt32 BE | 4 bytes | Byte count of payload (e.g. 65536) |
| Payload Data | Bytes | $N$ bytes | Raw file bytes |

Total frame overhead: 20 bytes per chunk.

### 5.2 EOF Frame Format
Magic: `0x4C53454F` (ASCII `"LSEO"` = LocalSend End Of file)

| Field | Type | Size | Description |
|---|---|---|---|
| Magic Header | UInt32 BE | 4 bytes | `0x4C53454F` (`"LSEO"`) |
| File Index | UInt32 BE | 4 bytes | Index of the file within the session (0-based) |
| SHA-256 Digest | Bytes | 32 bytes | SHA-256 binary digest of the entire file |

Total EOF frame size: 40 bytes.

---

## 6. Security & Integrity Guarantees

1. **Path Traversal Sanitization**:
   Receiving peers MUST sanitize all incoming filenames before creating files on disk:
   * Strip any directory path components (`/`, `\`, `..`, `:`, etc.).
   * Retain only the base name and extension.
   * If a file with the same name exists, rename safely to `filename (1).ext`.
2. **Atomic Writes via Temporary Buffers**:
   Files MUST be written to `.tmp_{fileId}` during transfer. The temporary file is only finalized and moved to `Downloads/LocalDrop` once the EOF frame is received and verified.
3. **SHA-256 Cryptographic Verification**:
   The receiver verifies the computed SHA-256 hash against the EOF frame. If a mismatch is detected, the temporary file is immediately discarded.

---

## 7. Tauri Desktop (Rust) Reference Implementation

Below is the complete Rust reference implementation for sending and receiving files with LocalDrop in a Tauri application:

```rust
use std::io::SeekFrom;
use std::path::{Path, PathBuf};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use sha2::{Digest, Sha256};

pub const CHUNK_MAGIC: u32 = 0x4C534431; // "LSD1"
pub const EOF_MAGIC: u32   = 0x4C53454F; // "LSEO"
pub const DEFAULT_CHUNK_SIZE: usize = 65536; // 64 KB

/// Streams a file from disk to an open TCP socket using chunked framing
pub async fn stream_file_to_socket<W: AsyncWriteExt + Unpin>(
    writer: &mut W,
    file_path: &Path,
    file_index: u32,
    progress_callback: impl Fn(u64, u64),
) -> Result<[u8; 32], Box<dyn std::error::Error>> {
    let mut file = tokio::fs::File::open(file_path).await?;
    let metadata = file.metadata().await?;
    let file_size = metadata.len();

    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; DEFAULT_CHUNK_SIZE];
    let mut offset: u64 = 0;

    loop {
        let bytes_read = file.read(&mut buffer).await?;
        if bytes_read == 0 {
            break;
        }

        hasher.update(&buffer[..bytes_read]);

        // Write Chunk Header: [MAGIC(4)][INDEX(4)][OFFSET(8)][LEN(4)]
        writer.write_u32(CHUNK_MAGIC).await?;
        writer.write_u32(file_index).await?;
        writer.write_u64(offset).await?;
        writer.write_u32(bytes_read as u32).await?;
        writer.write_all(&buffer[..bytes_read]).await?;

        offset += bytes_read as u64;
        progress_callback(offset, file_size);
    }

    // Write EOF Frame: [EOF_MAGIC(4)][INDEX(4)][SHA256(32)]
    let sha256_result: [u8; 32] = hasher.finalize().into();
    writer.write_u32(EOF_MAGIC).await?;
    writer.write_u32(file_index).await?;
    writer.write_all(&sha256_result).await?;
    writer.flush().await?;

    Ok(sha256_result)
}

/// Receives a chunked file stream from a TCP socket and verifies integrity
pub async fn receive_file_from_socket<R: AsyncReadExt + Unpin>(
    reader: &mut R,
    destination_path: &Path,
    expected_file_size: u64,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    let temp_path = destination_path.with_extension("tmp");
    let mut temp_file = tokio::fs::File::create(&temp_path).await?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; DEFAULT_CHUNK_SIZE];

    loop {
        let magic = reader.read_u32().await?;
        match magic {
            CHUNK_MAGIC => {
                let _file_index = reader.read_u32().await?;
                let _offset = reader.read_u64().await?;
                let chunk_len = reader.read_u32().await? as usize;

                if chunk_len > DEFAULT_CHUNK_SIZE * 2 {
                    return Err("Chunk size exceeds buffer limit".into());
                }

                reader.read_exact(&mut buffer[..chunk_len]).await?;
                temp_file.write_all(&buffer[..chunk_len]).await?;
                hasher.update(&buffer[..chunk_len]);
            }
            EOF_MAGIC => {
                let _file_index = reader.read_u32().await?;
                let mut expected_hash = [0u8; 32];
                reader.read_exact(&mut expected_hash).await?;

                let computed_hash: [u8; 32] = hasher.finalize().into();
                if computed_hash != expected_hash {
                    let _ = tokio::fs::remove_file(&temp_path).await;
                    return Err("Checksum mismatch: file corrupted in transit".into());
                }

                temp_file.flush().await?;
                drop(temp_file);

                tokio::fs::rename(&temp_path, destination_path).await?;
                return Ok(destination_path.to_path_buf());
            }
            _ => {
                return Err(format!("Unknown framing magic: 0x{:08X}", magic).into());
            }
        }
    }
}
```
