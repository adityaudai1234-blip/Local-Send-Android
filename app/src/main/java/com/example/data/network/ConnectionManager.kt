package com.example.data.network

import android.util.Log
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.domain.model.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager(
    private val client: NetworkClient,
    private val myDeviceIdProvider: () -> String
) {
    companion object {
        private const val TAG = "ConnectionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    private val activeDevices = ConcurrentHashMap<String, PairedDevice>()

    init {
        startHeartbeatWorker()
    }

    fun getConnectionState(deviceId: String): ConnectionState {
        return _connectionStates.value[deviceId] ?: ConnectionState.Disconnected
    }

    fun setConnectionState(deviceId: String, state: ConnectionState) {
        _connectionStates.update { map ->
            map + (deviceId to state)
        }
    }

    suspend fun connect(device: PairedDevice, authToken: String?): Boolean {
        setConnectionState(device.id, ConnectionState.Connecting)
        val myId = myDeviceIdProvider()

        val authMessage = ProtocolMessage(
            type = ProtocolMessageType.AUTHENTICATION_REQUEST,
            senderId = myId,
            receiverId = device.id,
            payload = mapOf(
                "token" to (authToken ?: ""),
                "timestamp" to System.currentTimeMillis()
            )
        )

        setConnectionState(device.id, ConnectionState.Authenticating)
        val result = client.sendMessage(device.ipAddress, device.port, authMessage, timeoutMs = 8_000)

        return result.fold(
            onSuccess = { response ->
                if (response.type == ProtocolMessageType.AUTHENTICATION_RESPONSE ||
                    response.type == ProtocolMessageType.CONNECTION_ESTABLISHED
                ) {
                    activeDevices[device.id] = device
                    setConnectionState(device.id, ConnectionState.Connected)
                    true
                } else {
                    val reason = response.payload["message"] as? String ?: "Authentication failed"
                    setConnectionState(device.id, ConnectionState.ConnectionFailed(reason))
                    false
                }
            },
            onFailure = { error ->
                setConnectionState(device.id, ConnectionState.ConnectionFailed(error.localizedMessage ?: "Timeout"))
                false
            }
        )
    }

    fun disconnect(deviceId: String) {
        val device = activeDevices.remove(deviceId)
        if (device != null) {
            scope.launch {
                val closeMsg = ProtocolMessage(
                    type = ProtocolMessageType.CONNECTION_CLOSED,
                    senderId = myDeviceIdProvider(),
                    receiverId = deviceId
                )
                client.sendOneWay(device.ipAddress, device.port, closeMsg)
            }
        }
        setConnectionState(deviceId, ConnectionState.Disconnected)
    }

    suspend fun ping(device: PairedDevice): Boolean {
        val heartbeat = ProtocolMessage.createHeartbeat(myDeviceIdProvider())
        val result = client.sendMessage(device.ipAddress, device.port, heartbeat, timeoutMs = 4_000)
        return result.isSuccess
    }

    private fun startHeartbeatWorker() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(20_000) // Heartbeat every 20s
                for (device in activeDevices.values) {
                    val ok = ping(device)
                    if (!ok) {
                        Log.w(TAG, "Heartbeat lost for ${device.alias} (${device.ipAddress})")
                        activeDevices.remove(device.id)
                        setConnectionState(device.id, ConnectionState.Disconnected)
                    }
                }
            }
        }
    }

    fun cleanup() {
        heartbeatJob?.cancel()
        activeDevices.clear()
        _connectionStates.value = emptyMap()
    }
}
