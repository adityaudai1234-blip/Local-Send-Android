package com.example.data.protocol

/**
 * Shared protocol constants, canonical error codes, and version negotiation
 * helpers synchronized with `/protocol/localdrop-protocol.schema.json`.
 */
object ProtocolConstants {
    const val VERSION_V1 = "v1"
    const val VERSION_V2 = "v2"
    const val PREFERRED_VERSION = VERSION_V2

    val SUPPORTED_VERSIONS: Set<String> = setOf(VERSION_V1, VERSION_V2)
    val SUPPORTED_VERSIONS_LIST: List<String> = listOf(VERSION_V1, VERSION_V2)

    const val DEFAULT_PORT = 53317
    const val SERVICE_TYPE = "_lsend._tcp."
    const val PAIRING_URI_SCHEME = "localdrop"
    const val PAIRING_URI_HOST = "pair"

    /**
     * Negotiates the highest mutually supported protocol version between local and peer.
     * Returns null if [peerVersion] is unsupported.
     */
    fun negotiateProtocolVersion(
        peerVersion: String?,
        localPreferred: String = PREFERRED_VERSION
    ): String? {
        val peer = peerVersion?.trim()?.lowercase() ?: return VERSION_V1
        if (peer.isEmpty()) return VERSION_V1
        if (peer !in SUPPORTED_VERSIONS) return null
        return if (peer == VERSION_V2 && localPreferred == VERSION_V2) VERSION_V2 else VERSION_V1
    }

    object ErrorCode {
        const val ERR_INVALID_FRAME = "ERR_INVALID_FRAME"
        const val ERR_UNSUPPORTED_VERSION = "ERR_UNSUPPORTED_VERSION"
        const val ERR_RATE_LIMITED = "ERR_RATE_LIMITED"
        const val ERR_AUTH_LOCKOUT = "ERR_AUTH_LOCKOUT"
        const val ERR_AUTH_FAILED = "ERR_AUTH_FAILED"
        const val ERR_TOKEN_EXPIRED = "ERR_TOKEN_EXPIRED"
        const val ERR_PAIRING_EXPIRED = "ERR_PAIRING_EXPIRED"
        const val ERR_PAIRING_REPLAY = "ERR_PAIRING_REPLAY"
        const val ERR_REPLAY_DETECTED = "ERR_REPLAY_DETECTED"
        const val ERR_FILE_TOO_LARGE = "ERR_FILE_TOO_LARGE"
        const val ERR_INSUFFICIENT_SPACE = "ERR_INSUFFICIENT_SPACE"
        const val ERR_CHECKSUM_MISMATCH = "ERR_CHECKSUM_MISMATCH"
        const val ERR_PATH_UNSAFE = "ERR_PATH_UNSAFE"
        const val ERR_FILE_CONFLICT = "ERR_FILE_CONFLICT"
        const val ERR_TRANSFER_REJECTED = "ERR_TRANSFER_REJECTED"
        const val ERR_TRANSFER_FAILED = "ERR_TRANSFER_FAILED"
        const val ERR_INTERNAL = "ERR_INTERNAL"
    }
}
