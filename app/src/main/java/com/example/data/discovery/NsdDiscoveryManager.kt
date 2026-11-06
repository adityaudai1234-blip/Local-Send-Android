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
import kotlinx.coroutines.launch
import java.net.InetAddress
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
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    // Sequential resolve queue to prevent NsdManager "FAILURE_ALREADY_ACTIVE" error
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolveWorkerJob: Job? = null

    init {
        startResolveWorker()
    }

    private fun startResolveWorker() {
        resolveWorkerJob?.cancel()
        resolveWorkerJob = scope.launch {
            for (serviceInfo in resolveQueue) {
                resolveSingleService(serviceInfo)
                delay(250) // gentle throttle between resolutions
            }
        }
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
                    Log.d(TAG, "Service found: ${service.serviceName}, type: ${service.serviceType}")
                    val registeredName = _registeredServiceName.value
                    if (registeredName != null && service.serviceName.contains(registeredName)) {
                        // Skip self
                        return
                    }
                    resolveQueue.trySend(service)
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.d(TAG, "Service lost: ${service.serviceName}")
                    val key = service.serviceName
                    deviceMap.remove(key)
                    _discoveredDevices.update { deviceMap.values.toList() }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Discovery stopped: $serviceType")
                    _isScanning.value = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                    _isScanning.value = false
                    _error.value = "Discovery start failed (code $errorCode)"
                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: $errorCode")
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
        }
    }

    private suspend fun resolveSingleService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        val currentRegistered = _registeredServiceName.value

        try {
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${service.serviceName}, code $errorCode")
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")
                    val hostAddress = resolvedInfo.host?.hostAddress ?: ""
                    val localIp = NetworkUtils.getLocalIpv4Address() ?: ""

                    val isSelf = (currentRegistered != null && resolvedInfo.serviceName.contains(currentRegistered)) ||
                            (hostAddress.isNotEmpty() && hostAddress == localIp)

                    if (isSelf) {
                        return
                    }

                    val attributes = parseAttributes(resolvedInfo)
                    val alias = attributes[DiscoveryProtocol.KEY_ALIAS]?.takeIf { it.isNotBlank() }
                        ?: resolvedInfo.serviceName.replace("\\.", ".")
                    val deviceTypeStr = attributes[DiscoveryProtocol.KEY_DEVICE_TYPE]
                    val deviceType = DeviceType.fromIdentifier(deviceTypeStr)
                    val protocol = attributes[DiscoveryProtocol.KEY_PROTOCOL] ?: "v1"
                    val model = attributes[DiscoveryProtocol.KEY_DEVICE_MODEL]

                    val device = Device(
                        id = resolvedInfo.serviceName,
                        alias = alias,
                        deviceType = deviceType,
                        ipAddress = hostAddress,
                        port = resolvedInfo.port,
                        isOnline = true,
                        lastSeen = System.currentTimeMillis(),
                        protocolVersion = protocol,
                        model = model,
                        isSelf = false
                    )

                    deviceMap[resolvedInfo.serviceName] = device
                    _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
                }
            }

            manager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during resolve", e)
        }
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
            _isScanning.value = false
            releaseMulticastLock()
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
                setAttribute(DiscoveryProtocol.KEY_VERSION, "1.0.0")
                setAttribute(DiscoveryProtocol.KEY_DEVICE_MODEL, "${Build.MANUFACTURER} ${Build.MODEL}")
                setAttribute(DiscoveryProtocol.KEY_PROTOCOL, DiscoveryProtocol.PROTOCOL_VERSION)
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service registered: ${registeredInfo.serviceName}")
                    _registeredServiceName.value = registeredInfo.serviceName
                    _isAdvertising.value = true
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Registration failed: $errorCode")
                    _isAdvertising.value = false
                    _error.value = "Failed to broadcast device: code $errorCode"
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.d(TAG, "Service unregistered")
                    _isAdvertising.value = false
                    _registeredServiceName.value = null
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
            releaseMulticastLock()
        }
    }

    /**
     * Injects a local test peer for quick developer testing when in isolated emulator networks.
     */
    fun addSimulatedPeer(device: Device) {
        deviceMap[device.id] = device
        _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.alias } }
    }

    fun clearDevices() {
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

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release multicast lock", e)
        }
    }

    fun cleanup() {
        stopDiscovery()
        stopAdvertising()
        resolveWorkerJob?.cancel()
    }
}
