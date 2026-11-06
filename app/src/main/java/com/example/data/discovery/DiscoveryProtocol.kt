package com.example.data.discovery

/**
 * ============================================================================
 * LocalDrop / LocalSend Cross-Platform Discovery Protocol Specification
 * ============================================================================
 *
 * This protocol definition is designed for multi-platform interoperability
 * between Android, desktop (Tauri / Rust), macOS, and Linux clients on the
 * same local network using Multicast DNS (mDNS) / DNS-SD.
 *
 * 1. SERVICE REGISTRATION & DISCOVERY
 *    - Service Type: "_lsend._tcp"
 *    - Domain: "local."
 *    - Default Port: 53317 (configurable in settings)
 *    - Transport: UDP multicast on 224.0.0.251:5353 (mDNS)
 *
 * 2. TXT RECORD ATTRIBUTES (Key-Value map encoded in DNS-SD TXT record):
 *    - "alias": User-assigned device alias (e.g., "Alice's MacBook", "Pixel 8 Pro")
 *    - "version": Application semver version (e.g., "1.0.0")
 *    - "deviceModel": Hardware model or OS build string
 *    - "deviceType": Target platform ("android", "windows", "macos", "linux", "web")
 *    - "protocol": Protocol version identifier ("v1")
 *    - "fingerprint": Unique ephemeral or persistent device UUID
 *    - "port": TCP port listening for incoming connections
 *
 * 3. TAURI / RUST INTEROP SPECIFICATION (Phase 2 & 3):
 *    In Tauri (Rust), use mdns-sd crate or astro-dnssd:
 *    ```rust
 *    let service_type = "_lsend._tcp.local.";
 *    let mut txt = std::collections::HashMap::new();
 *    txt.insert("alias", "Desktop Workstation");
 *    txt.insert("deviceType", "windows"); // or "macos" / "linux"
 *    txt.insert("version", "1.0.0");
 *    txt.insert("protocol", "v1");
 *    ```
 * ============================================================================
 */
object DiscoveryProtocol {
    const val SERVICE_TYPE = "_lsend._tcp."
    const val NSD_SERVICE_TYPE = "_lsend._tcp"
    const val DEFAULT_PORT = 53317
    const val PROTOCOL_VERSION = "v1"

    // TXT record keys
    const val KEY_ALIAS = "alias"
    const val KEY_VERSION = "version"
    const val KEY_DEVICE_MODEL = "deviceModel"
    const val KEY_DEVICE_TYPE = "deviceType"
    const val KEY_PROTOCOL = "protocol"
    const val KEY_FINGERPRINT = "fingerprint"
    const val KEY_PORT = "port"

    val KNOWN_SERVICE_TYPES = listOf(
        "_lsend._tcp",
        "_localsend._tcp",
        "_beamshare._tcp"
    )
}
