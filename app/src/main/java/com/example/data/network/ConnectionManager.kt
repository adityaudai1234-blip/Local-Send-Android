package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.data.protocol.ProtocolMessage
import com.example.data.protocol.ProtocolMessageType
import com.example.domain.model.Device
import com.example.domain.model.PairedDevice
import com.example.domain.repository.PairedDeviceRepository
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConnectionErrorEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val targetLabel: String,
    val address: String,
    val message: String
)

class ConnectionManager(
    private val client: NetworkClient,
    private val myDeviceIdProvider: () -> String,
    private val context: Context? = null,
    private val pairedDeviceRepository: PairedDeviceRepository? = null
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val MAX_ERROR_LOG_ENTRIES = 25
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    private val _recentErrors = MutableStateFlow<List<ConnectionErrorEvent>>(emptyList())
    val recentErrors: StateFlow<List<ConnectionErrorEvent>> = _recentErrors.asStateFlow()

    private val _reconnectAttemptCount = MutableStateFlow(0)
    val reconnectAttemptCount: StateFlow<Int> = _reconnectAttemptCount.asStateFlow()

    private val _isNetworkMonitorActive = MutableStateFlow(false)
    val isNetworkMonitorActive: StateFlow<Boolean> = _isNetworkMonitorActive.asStateFlow()

    private val activeDevices = ConcurrentHashMap<String, PairedDevice>()
    private val userDisconnectedIds = ConcurrentHashMap.newKeySet<String>()

    var onConnectionStateChanged: (() -> Unit)? = null

    init {
        startHeartbeatWorker()
        registerNetworkCallbackIfAvailable()
    }

    fun getConnectionState(deviceId: String): ConnectionState {
        return _connectionStates.value[deviceId] ?: ConnectionState.Disconnected
    }

    fun setConnectionState(deviceId: String, state: ConnectionState) {
        _connectionStates.update { map ->
            map + (deviceId to state)
        }
        onConnectionStateChanged?.invoke()
    }

    fun recordError(targetLabel: String, address: String, message: String) {
        val event = ConnectionErrorEvent(
            targetLabel = targetLabel,
            address = address,
            message = message
        )
        _recentErrors.update { list ->
            (listOf(event) + list).take(MAX_ERROR_LOG_ENTRIES)
        }
    }

    fun clearRecentErrors() {
        _recentErrors.value = emptyList()
    }

    suspend fun connect(device: PairedDevice, authToken: String?): Boolean {
        userDisconnectedIds.remove(device.id)
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
                    recordError(device.alias, "${device.ipAddress}:${device.port}", reason)
                    setConnectionState(device.id, ConnectionState.ConnectionFailed(reason))
                    false
                }
            },
            onFailure = { error ->
                val msg = error.localizedMessage ?: "Connection timed out"
                recordError(device.alias, "${device.ipAddress}:${device.port}", msg)
                setConnectionState(device.id, ConnectionState.ConnectionFailed(msg))
                false
            }
        )
    }

    fun disconnect(deviceId: String) {
        userDisconnectedIds.add(deviceId)
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

    suspend fun testPeerLatencyMs(device: PairedDevice): Result<Long> {
        val start = System.currentTimeMillis()
        val heartbeat = ProtocolMessage.createHeartbeat(myDeviceIdProvider())
        val result = client.sendMessage(device.ipAddress, device.port, heartbeat, timeoutMs = 4_000)
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        return result.fold(
            onSuccess = { Result.success(elapsed) },
            onFailure = { err ->
                recordError(device.alias, "${device.ipAddress}:${device.port}", err.localizedMessage ?: "Unreachable")
                Result.failure(err)
            }
        )
    }

    /**
     * Updates stored IP/port when mDNS resolves a previously paired peer at a new address
     * and attempts bounded reconnection.
     */
    fun syncDiscoveredPeers(discovered: List<Device>, pairedDevices: List<PairedDevice>) {
        if (discovered.isEmpty() || pairedDevices.isEmpty()) return
        scope.launch {
            for (paired in pairedDevices) {
                val match = discovered.find { d ->
                    d.id == paired.id ||
                        (d.alias.equals(paired.alias, ignoreCase = true) && d.deviceType == paired.deviceType)
                } ?: continue

                val endpointChanged = match.ipAddress.isNotBlank() &&
                    (match.ipAddress != paired.ipAddress || match.port != paired.port)

                if (endpointChanged) {
                    pairedDeviceRepository?.updateNetworkEndpoint(paired.id, match.ipAddress, match.port)
                }

                val currentState = getConnectionState(paired.id)
                if (paired.id !in userDisconnectedIds &&
                    currentState !is ConnectionState.Connected &&
                    currentState !is ConnectionState.Connecting &&
                    currentState !is ConnectionState.Authenticating
                ) {
                    val updatedDevice = paired.copy(
                        ipAddress = match.ipAddress.ifBlank { paired.ipAddress },
                        port = match.port
                    )
                    val token = pairedDeviceRepository?.getAuthToken(paired.id)
                    if (!token.isNullOrBlank()) {
                        connect(updatedDevice, token)
                    }
                }
            }
        }
    }

    /**
     * Triggers bounded reconnection (at most 3 attempts with exponential backoff) when
     * the app returns to foreground or local network connectivity is restored.
     */
    fun triggerBoundedReconnect(
        maxAttempts: Int = MAX_RECONNECT_ATTEMPTS,
        baseDelayMs: Long = 2_000L
    ) {
        val repo = pairedDeviceRepository ?: return
        if (context != null) {
            val status = NetworkUtils.getConnectionStatus(context)
            if (!status.isConnected) return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val pairedList = repo.pairedDevices.firstOrNull().orEmpty()
            val candidates = pairedList.filter { paired ->
                paired.id !in userDisconnectedIds &&
                    getConnectionState(paired.id) !is ConnectionState.Connected
            }
            if (candidates.isEmpty()) return@launch

            var attempt = 0
            while (isActive && attempt < maxAttempts) {
                if (context != null && !NetworkUtils.getConnectionStatus(context).isConnected) {
                    break
                }
                attempt++
                _reconnectAttemptCount.value = attempt

                var allConnected = true
                for (device in candidates) {
                    if (getConnectionState(device.id) is ConnectionState.Connected) continue
                    val token = repo.getAuthToken(device.id)
                    if (token.isNullOrBlank()) continue
                    val ok = connect(device, token)
                    if (!ok) {
                        allConnected = false
                    }
                }
                if (allConnected) break

                if (attempt < maxAttempts) {
                    val backoff = baseDelayMs * (1L shl (attempt - 1))
                    delay(backoff)
                }
            }
        }
    }

    private fun registerNetworkCallbackIfAvailable() {
        val ctx = context ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    triggerBoundedReconnect()
                }

                override fun onLost(network: Network) {
                    activeDevices.keys.toList().forEach { id ->
                        setConnectionState(id, ConnectionState.Disconnected)
                    }
                    activeDevices.clear()
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
            _isNetworkMonitorActive.value = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NetworkCallback: ${e.message}")
            _isNetworkMonitorActive.value = false
        }
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
                        recordError(device.alias, "${device.ipAddress}:${device.port}", "Heartbeat timeout")
                        activeDevices.remove(device.id)
                        setConnectionState(device.id, ConnectionState.Disconnected)
                    }
                }
            }
        }
    }

    fun cleanup() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        val ctx = context
        val cb = networkCallback
        if (ctx != null && cb != null) {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
        _isNetworkMonitorActive.value = false
        activeDevices.clear()
        _connectionStates.value = emptyMap()
    }
}
