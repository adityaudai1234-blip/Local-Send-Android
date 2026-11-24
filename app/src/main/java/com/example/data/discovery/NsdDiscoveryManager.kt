package com.example.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.Device
import com.example.domain.model.DeviceStatus
import com.example.domain.model.DeviceType
import com.example.domain.model.ErrorCategory
import com.example.util.NetworkUtils
import com.example.util.PerformanceMonitor
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
    private val context: Context,
    private val performanceMonitor: PerformanceMonitor? = null
) {
    companion object {
        private const val TAG = "NsdDiscoveryManager"
        private const val MIN_REFRESH_COOLDOWN_MS = 1_200L
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

    @Volatile
    private var discoveryStartedAtMs: Long = 0L

    @Volatile
    private var recordedInitialDiscoveryLatency: Boolean = false

    @Volatile
    private var lastManualRefreshAtMs: Long = 0L

    @Volatile
    private var lastAdvertisedAlias: String? = null

    @Volatile
    private var lastAdvertisedPort: Int = DiscoveryProtocol.DEFAULT_PORT

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
                delay(200)
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
            emitSortedDevices()
        }
        return removedCount
    }

    fun startDiscovery() {
        if (_isScanning.value) return
        if (nsdManager == null) {
            val msg = "NSD service is not available on this device"
            _error.value = msg
            AppErrorReporter.report(ErrorCategory.DISCOVERY, msg, targetLabel = "mDNS Discovery")
            return
        }

        try {
            acquireMulticastLock()
            discoveryStartedAtMs = System.currentTimeMillis()
            recordedInitialDiscoveryLatency = false

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
                    resolvingServices.remove(service.serviceName)
                    val matchingKey = deviceMap.entries.firstOrNull { (key, dev) ->
                        key == service.serviceName || dev.serviceName == service.serviceName
                    }?.key
                    if (matchingKey != null) {
                        val existing = deviceMap[matchingKey]
                        if (existing != null) {
                            deviceMap[matchingKey] = existing.copy(
                                isOnline = false,
                                status = DeviceStatus.OFFLINE
                            )
                            emitSortedDevices()
                        }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    _isScanning.value = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                    _isScanning.value = false
                    val friendly = "Unable to scan for nearby devices on this Wi-Fi network (NSD code $errorCode)."
                    _error.value = friendly
                    AppErrorReporter.report(
                        category = ErrorCategory.DISCOVERY,
                        userMessage = friendly,
                        technicalDetails = "NsdManager.onStartDiscoveryFailed(serviceType=$serviceType, errorCode=$errorCode)",
                        targetLabel = "mDNS Discovery"
                    )
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
            AppErrorReporter.reportThrowable(ErrorCategory.DISCOVERY, e, targetLabel = "mDNS Discovery")
            _error.value = "Unable to start device discovery. Check Wi-Fi connection and permissions."
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
                    val hostAddress = (resolvedInfo.host?.hostAddress ?: "").substringBefore('%')
                    val localIpv4 = NetworkUtils.getLocalIpv4Address() ?: ""
                    val localIpv6 = NetworkUtils.getLocalIpv6Address() ?: ""
                    val attributes = parseAttributes(resolvedInfo)
                    val fingerprint = attributes[DiscoveryProtocol.KEY_FINGERPRINT]?.takeIf { it.isNotBlank() }

                    val isSelfIp = hostAddress.isNotEmpty() &&
                        (hostAddress == localIpv4 || hostAddress == localIpv6)
                    val isSelf = (currentRegistered != null && resolvedInfo.serviceName.contains(currentRegistered)) ||
                        (fingerprint != null && fingerprint == localFingerprint) ||
                        (isSelfIp && resolvedInfo.port == lastAdvertisedPort)

                    if (isSelf) return

                    if (!recordedInitialDiscoveryLatency && discoveryStartedAtMs > 0L) {
                        recordedInitialDiscoveryLatency = true
                        performanceMonitor?.recordDiscoveryDuration(
                            (System.currentTimeMillis() - discoveryStartedAtMs).coerceAtLeast(1L)
                        )
                    }

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
                        isSelf = false,
                        status = DeviceStatus.ONLINE,
                        serviceName = resolvedInfo.serviceName
                    )

                    upsertResolvedDevice(resolvedInfo.serviceName, device)
                }
            }

            manager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during resolve", e)
        }
    }

    /**
     * Deduplicates peers across multiple network interfaces (e.g. IPv4 LAN + IPv6 link-local)
     * while preferring reachable LAN IPv4 addresses over link-local IPv6 addresses.
     */
    fun upsertResolvedDevice(serviceKey: String, device: Device) {
        val cleanedPrimary = device.ipAddress.substringBefore('%')
        val matchingEntries = deviceMap.entries.filter { (key, existing) ->
            key !in simulatedPeerIds &&
                (key == serviceKey ||
                    existing.id == device.id ||
                    (cleanedPrimary.isNotBlank() &&
                        existing.ipAddress == cleanedPrimary &&
                        existing.port == device.port))
        }

        val alternates = linkedSetOf<String>()
        var preferredIp = cleanedPrimary
        for ((_, existing) in matchingEntries) {
            val oldIp = existing.ipAddress.substringBefore('%')
            if (oldIp.isNotBlank() && oldIp != preferredIp) {
                if (addressScore(oldIp) > addressScore(preferredIp)) {
                    if (preferredIp.isNotBlank()) alternates.add(preferredIp)
                    preferredIp = oldIp
                } else {
                    alternates.add(oldIp)
                }
            }
            existing.alternateAddresses.forEach { alt ->
                if (alt.isNotBlank() && alt != preferredIp) alternates.add(alt)
            }
            deviceMap.remove(existing.id)
        }

        matchingEntries.map { it.key }.filter { it != serviceKey }.forEach { deviceMap.remove(it) }

        device.alternateAddresses.forEach { alt ->
            val cleaned = alt.substringBefore('%')
            if (cleaned.isNotBlank() && cleaned != preferredIp) alternates.add(cleaned)
        }

        deviceMap[serviceKey] = device.copy(
            ipAddress = preferredIp,
            isOnline = true,
            status = DeviceStatus.ONLINE,
            serviceName = device.serviceName.ifBlank { serviceKey },
            alternateAddresses = alternates.toList()
        )
        emitSortedDevices()
    }

    private fun addressScore(ip: String): Int {
        if (ip.isBlank()) return 0
        val clean = ip.substringBefore('%').lowercase()
        val isIpv4 = clean.contains('.') && !clean.startsWith("127.")
        return when {
            isIpv4 && !clean.startsWith("169.254.") -> 4 // Private/LAN IPv4
            !clean.startsWith("fe80:") -> 3 // Global/ULA IPv6
            isIpv4 -> 2 // Link-local IPv4
            else -> 1 // Link-local IPv6
        }
    }

    private fun emitSortedDevices() {
        _discoveredDevices.update {
            deviceMap.values.toList().sortedWith(
                compareByDescending<Device> { it.isOnline }.thenBy { it.alias }
            )
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
            resolvingServices.clear()
            _isScanning.value = false
            releaseMulticastLockIfIdle()
        }
    }

    fun refreshDiscovery(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastManualRefreshAtMs < MIN_REFRESH_COOLDOWN_MS) {
            return
        }
        lastManualRefreshAtMs = now
        stopDiscovery()
        scope.launch {
            delay(300)
            startDiscovery()
        }
    }

    fun restartOnNetworkChange() {
        val wasScanning = _isScanning.value
        val wasAdvertising = _isAdvertising.value
        val alias = lastAdvertisedAlias
        val port = lastAdvertisedPort

        stopDiscovery()
        if (wasAdvertising) {
            stopAdvertising()
        }

        scope.launch {
            delay(400)
            if (wasAdvertising && !alias.isNullOrBlank()) {
                startAdvertising(alias, port)
            }
            if (wasScanning) {
                startDiscovery()
            }
        }
    }

    fun startAdvertising(alias: String, port: Int = DiscoveryProtocol.DEFAULT_PORT) {
        lastAdvertisedAlias = alias
        lastAdvertisedPort = port
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
                setAttribute(DiscoveryProtocol.KEY_VERSION, "2.2.0")
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
                    val msg = "Unable to advertise this device on the local network (code $errorCode)."
                    _error.value = msg
                    AppErrorReporter.report(
                        category = ErrorCategory.DISCOVERY,
                        userMessage = msg,
                        technicalDetails = "NsdManager.onRegistrationFailed(errorCode=$errorCode)",
                        targetLabel = "mDNS Broadcast"
                    )
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
            AppErrorReporter.reportThrowable(ErrorCategory.DISCOVERY, e, targetLabel = "mDNS Broadcast")
            _error.value = "Unable to start device broadcast on local Wi-Fi."
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
        emitSortedDevices()
    }

    fun upsertDiscoveredPeerForTest(key: String, device: Device) {
        deviceMap[key] = device
        emitSortedDevices()
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
