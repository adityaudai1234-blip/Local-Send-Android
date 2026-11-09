package com.example.data.network

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Discovering : ConnectionState
    data object Connecting : ConnectionState
    data object AwaitingApproval : ConnectionState
    data object Authenticating : ConnectionState
    data object Connected : ConnectionState
    data class ConnectionFailed(val reason: String) : ConnectionState

    val label: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Discovering -> "Discovering"
            is Connecting -> "Connecting…"
            is AwaitingApproval -> "Awaiting Approval"
            is Authenticating -> "Authenticating…"
            is Connected -> "Connected"
            is ConnectionFailed -> "Failed: $reason"
        }
}
