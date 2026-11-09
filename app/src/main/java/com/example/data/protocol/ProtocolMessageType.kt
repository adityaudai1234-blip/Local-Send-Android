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
    ERROR;

    companion object {
        fun fromString(name: String): ProtocolMessageType {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ERROR
        }
    }
}
