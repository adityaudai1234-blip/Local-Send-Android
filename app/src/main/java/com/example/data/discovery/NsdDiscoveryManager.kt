package com.example.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.domain.model.Device
import com.example.domain.model.DeviceType
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NsdDiscoveryManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "NsdDiscoveryManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _registeredServiceName = MutableStateFlow<String?>(null)
    val registeredServiceName: StateFlow<String?> = _registeredServiceName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, Device>()
    private val simulatedPeerIds = ConcurrentHashMap.newKeySet<String>()
    private val resolvingServices = ConcurrentHashMap.newKeySet<String>()
    private val localFingerprint = UUID.randomUUID().toString()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolveWorkerJob: Job? = null
    private var periodicCleanupJob: Job? = null

    @Volatile
    private var discoveryIntervalSeconds: Int = 10

    init {
        startResolveWorker()
        startStaleDeviceCleanupWorker()
    }

    fun updateDiscoveryInterval(seconds: Int) {
        discoveryIntervalSeconds = seconds.coerceIn(5, 120)
    }

    private fun startResolveWorker() {
        resolveWorkerJob?.cancel()
        resolveWorkerJob = scope.launch {
            for (serviceInfo in resolveQueue) {
                try {
                    resolveSingleService(serviceInfo)
                } finally {
                    resolvingServices.remove(serviceInfo.serviceName)
                }
                delay(250)
            }
        }
    }

    private fun startStaleDeviceCleanupWorker() {
        periodicCleanupJob?.cancel()
        periodicCleanupJob = scope.launch {
            while (isActive) {
                delay((discoveryIntervalSeconds.coerceAtLeast(10)) * 1000L)
                pruneStalePeers()
            }
        }
    }

    fun pruneStalePeers(now: Long = System.currentTimeMillis()): Int {
        val staleThreshold = now - (discoveryIntervalSeconds * 6_000L)
        var removedCount = 0
        val iter = deviceMap.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in simulatedPeerIds && entry.value.lastSeen < staleThreshold) {
                iter.remove()
                removedCount++
            }
        }
        if (removedCount > 0) {
            _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
        }
        return removedCount
    }

    fun startDiscovery() {
        if (_isScanning.value) return
        if (nsdManager == null) {
            _error.value = "NSD service is not available on this device"
            return
        }

        try {
            acquireMulticastLock()

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "Discovery started for $regType")
                    _isScanning.value = true
                    _error.value = null
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    val registeredName = _registeredServiceName.value
                    if (registeredName != null && service.serviceName.contains(registeredName)) {
                        return
                    }
                    if (resolvingServices.add(service.serviceName)) {
                        resolveQueue.trySend(service)
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    val removed = deviceMap.remove(service.serviceName) != null
                    resolvingServices.remove(service.serviceName)
                    if (removed) {
                        _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    _isScanning.value = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                    _isScanning.value = false
                    _error.value = "Discovery start failed (code $errorCode)"
                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    _isScanning.value = false
                }
            }

            discoveryListener = listener
            nsdManager.discoverServices(
                DiscoveryProtocol.NSD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            _error.value = "Failed to start discovery: ${e.localizedMessage}"
            _isScanning.value = false
            releaseMulticastLock()
        }
    }

    private fun resolveSingleService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        val currentRegistered = _registeredServiceName.value

        try {
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${service.serviceName}, code $errorCode")
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val hostAddress = resolvedInfo.host?.hostAddress ?: ""
                    val localIp = NetworkUtils.getLocalIpv4Address() ?: ""
                    val attributes = parseAttributes(resolvedInfo)
                    val fingerprint = attributes[DiscoveryProtocol.KEY_FINGERPRINT]?.takeIf { it.isNotBlank() }

                    val isSelf = (currentRegistered != null && resolvedInfo.serviceName.contains(currentRegistered)) ||
                        (fingerprint != null && fingerprint == localFingerprint) ||
                        (hostAddress.isNotEmpty() && hostAddress == localIp && resolvedInfo.port == DiscoveryProtocol.DEFAULT_PORT)

                    if (isSelf) return

                    val alias = attributes[DiscoveryProtocol.KEY_ALIAS]?.takeIf { it.isNotBlank() }
                        ?: resolvedInfo.serviceName.replace("\\.", ".")
                    val deviceTypeStr = attributes[DiscoveryProtocol.KEY_DEVICE_TYPE]
                    val deviceType = DeviceType.fromIdentifier(deviceTypeStr)
                    val protocol = attributes[DiscoveryProtocol.KEY_PROTOCOL] ?: DiscoveryProtocol.PROTOCOL_VERSION
                    val model = attributes[DiscoveryProtocol.KEY_DEVICE_MODEL]
                    val txtPort = attributes[DiscoveryProtocol.KEY_PORT]?.toIntOrNull()
                    val resolvedPort = if (txtPort != null && txtPort in 1024..65535) txtPort else resolvedInfo.port
                    val stableId = fingerprint ?: resolvedInfo.serviceName

                    val device = Device(
                        id = stableId,
                        alias = alias,
                        deviceType = deviceType,
                        ipAddress = hostAddress,
                        port = resolvedPort,
                        isOnline = true,
                        lastSeen = System.currentTimeMillis(),
                        protocolVersion = protocol,
                        model = model,
                        isSelf = false
                    )

                    upsertResolvedDevice(resolvedInfo.serviceName, device)
                }
            }

            manager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during resolve", e)
        }
    }

    private fun upsertResolvedDevice(serviceKey: String, device: Device) {
        val duplicateKeys = deviceMap.entries.filter { (key, existing) ->
            key !in simulatedPeerIds &&
                key != serviceKey &&
                (existing.id == device.id ||
                    (device.ipAddress.isNotBlank() &&
                        existing.ipAddress == device.ipAddress &&
                        existing.port == device.port))
        }.map { it.key }
        duplicateKeys.forEach { deviceMap.remove(it) }

        deviceMap[serviceKey] = device
        _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
    }

    private fun parseAttributes(serviceInfo: NsdServiceInfo): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val rawAttributes = serviceInfo.attributes
            for ((key, valueBytes) in rawAttributes) {
                if (valueBytes != null) {
                    result[key] = String(valueBytes, StandardCharsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading attributes", e)
        }
        return result
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        } finally {
            discoveryListener = null
            resolvingServices.clear()
            _isScanning.value = false
            releaseMulticastLockIfIdle()
        }
    }

    fun refreshDiscovery() {
        stopDiscovery()
        scope.launch {
            delay(300)
            startDiscovery()
        }
    }

    fun startAdvertising(alias: String, port: Int = DiscoveryProtocol.DEFAULT_PORT) {
        if (_isAdvertising.value) return
        if (nsdManager == null) return

        try {
            acquireMulticastLock()

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "LocalDrop-$alias-${UUID.randomUUID().toString().take(4)}"
                serviceType = DiscoveryProtocol.NSD_SERVICE_TYPE
                setPort(port)
                setAttribute(DiscoveryProtocol.KEY_ALIAS, alias)
                setAttribute(DiscoveryProtocol.KEY_DEVICE_TYPE, "android")
                setAttribute(DiscoveryProtocol.KEY_VERSION, "2.1.0")
                setAttribute(DiscoveryProtocol.KEY_DEVICE_MODEL, "${Build.MANUFACTURER} ${Build.MODEL}")
                setAttribute(DiscoveryProtocol.KEY_PROTOCOL, DiscoveryProtocol.PROTOCOL_VERSION_V2)
                setAttribute(DiscoveryProtocol.KEY_FINGERPRINT, localFingerprint)
                setAttribute(DiscoveryProtocol.KEY_PORT, port.toString())
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                    _registeredServiceName.value = registeredInfo.serviceName
                    _isAdvertising.value = true
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    _isAdvertising.value = false
                    _error.value = "Failed to broadcast device: code $errorCode"
                    releaseMulticastLockIfIdle()
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    _isAdvertising.value = false
                    _registeredServiceName.value = null
                    releaseMulticastLockIfIdle()
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Unregistration failed: $errorCode")
                }
            }

            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            _error.value = "Broadcast error: ${e.localizedMessage}"
            _isAdvertising.value = false
            releaseMulticastLockIfIdle()
        }
    }

    fun stopAdvertising() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering service", e)
        } finally {
            registrationListener = null
            _isAdvertising.value = false
            _registeredServiceName.value = null
            releaseMulticastLockIfIdle()
        }
    }

    fun addSimulatedPeer(device: Device) {
        simulatedPeerIds.add(device.id)
        deviceMap[device.id] = device
        _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
    }

    fun upsertDiscoveredPeerForTest(key: String, device: Device) {
        deviceMap[key] = device
        _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
    }

    fun clearDevices() {
        simulatedPeerIds.clear()
        deviceMap.clear()
        _discoveredDevices.value = emptyList()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = NetworkUtils.createMulticastLock(context)
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLockIfIdle() {
        if (!_isScanning.value && !_isAdvertising.value) {
            releaseMulticastLock()
        }
    }

    private fun releaseMulticastLock() {
        try {
            while (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release multicast lock", e)
        }
    }

    fun cleanup() {
        stopDiscovery()
        stopAdvertising()
        releaseMulticastLock()
        resolveWorkerJob?.cancel()
        periodicCleanupJob?.cancel()
    }
}
