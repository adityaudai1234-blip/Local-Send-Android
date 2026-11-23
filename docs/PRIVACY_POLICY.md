# LocalDrop Privacy Policy

**Effective Date:** November 22, 2026  
**Application:** LocalDrop (`com.aistudio.localdrop.wzkp`)

LocalDrop is designed so that your files and metadata remain under your control on your own devices and local network.

## 1. Local-Network Communication
LocalDrop communicates strictly over your local Wi-Fi or wired Local Area Network (LAN). LocalDrop does not operate cloud relay servers, user accounts, advertising SDKs, or remote analytics services.

## 2. File Transfer Behavior
Files and folders selected by you are transmitted directly from sender to receiver over local TCP sockets (with optional TLS encryption on v2-capable peers). Each stream is verified using a SHA-256 checksum before being saved.

## 3. Device Discovery
When Local Network Broadcast is enabled, LocalDrop announces your configured device alias, platform type, and listening port on your local subnet using Android Network Service Discovery (`_lsend._tcp.`). You can disable broadcasting at any time in Settings.

## 4. Pairing and Authentication
QR-code pairing exchanges short-lived session parameters over your local network to establish mutual trust. Long-lived per-device authentication tokens are stored locally encrypted with Android Keystore (`AES/GCM/NoPadding`).

## 5. Transfer History Storage
Metadata about completed, cancelled, and failed transfers (filename, size, timestamp, peer alias, status, and SHA-256 checksum) is saved in an on-device Room database for your convenience. You can delete individual entries or clear all history at any time.

## 6. Received-File Storage
Incoming files are buffered temporarily in the app's private cache during checksum validation, then written to `Downloads/LocalDrop` or a custom folder chosen by you via Android's Storage Access Framework (SAF).

## 7. Permissions Usage
- **Camera:** Requested only if you choose to scan a peer's QR code. Manual pairing works without camera access.
- **Nearby Wi-Fi Devices (Android 13+):** Used to assist local Wi-Fi peer discovery without requesting device location.
- **Notifications (Android 13+):** Used for incoming transfer approval prompts, progress updates, and completion alerts.
- **Files & Folders:** Accessed exclusively through Android's privacy-preserving system pickers and Share Sheet grants—LocalDrop never requests broad external storage access.

## 8. Diagnostics & Error Logs
Error summaries and local crash snapshots (kept in app-private storage) never leave your device and automatically strip sensitive credential or token patterns.

## 9. Data Retention & Deletion
All local preferences, trusted devices, cached temp files, crash logs, and transfer history remain solely on your device and can be deleted anytime using the in-app **Privacy Dashboard**, **Storage Manager**, or **Network Diagnostics** screens, or by uninstalling the app.

## Contact
For questions or feedback regarding this Privacy Policy, contact: `support@localdrop.example`
