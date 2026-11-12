package com.example.data.protocol

enum class ProtocolMessageType {
    DISCOVERY,
    PAIRING_REQUEST,
    PAIRING_RESPONSE,
    PAIRING_ACCEPTED,
    PAIRING_REJECTED,
    AUTHENTICATION_REQUEST,
    AUTHENTICATION_RESPONSE,
    CONNECTION_ESTABLISHED,
    CONNECTION_CLOSED,
    HEARTBEAT,
    TRANSFER_REQUEST,
    TRANSFER_ACCEPTED,
    TRANSFER_REJECTED,
    TRANSFER_METADATA,
    TRANSFER_READY,
    TRANSFER_STARTED,
    TRANSFER_PROGRESS,
    TRANSFER_PAUSED,
    TRANSFER_RESUMED,
    TRANSFER_COMPLETED,
    TRANSFER_CANCELLED,
    TRANSFER_FAILED,
    ERROR;

    companion object {
        fun fromString(name: String): ProtocolMessageType {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ERROR
        }
    }
}
