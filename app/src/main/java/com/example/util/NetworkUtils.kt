package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.NetworkTransportType
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

data class NetworkInterfaceAddress(
    val interfaceName: String,
    val address: String,
    val isIpv6: Boolean,
    val isLinkLocal: Boolean,
    val isHotspotInterface: Boolean
)

object NetworkUtils {

    private val HOTSPOT_INTERFACE_PREFIXES = listOf("ap", "swlan", "wlan1", "softap")

    fun isHotspotInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return HOTSPOT_INTERFACE_PREFIXES.any { lower.startsWith(it) }
    }

    fun getConnectionStatus(context: Context): ConnectionStatus {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val ipv4 = getLocalIpv4Address()
        val ipv6 = getLocalIpv6Address()
        val interfaces = getAvailableNetworkAddresses()
        val primaryIntf = interfaces.firstOrNull { !it.isIpv6 && it.address == ipv4 }
            ?: interfaces.firstOrNull { !it.isIpv6 }
            ?: interfaces.firstOrNull()

        val hotspotActive = interfaces.any { it.isHotspotInterface }

        if (connectivityManager == null) {
            return ConnectionStatus(
                isConnected = ipv4 != null,
                networkName = ipv4?.let { "Local Network ($it)" } ?: "Unavailable",
                ipAddress = ipv4 ?: "127.0.0.1",
                ipv6Address = ipv6,
                interfaceName = primaryIntf?.interfaceName.orEmpty(),
                transportType = if (hotspotActive) NetworkTransportType.HOTSPOT else NetworkTransportType.OTHER,
                isHotspot = hotspotActive
            )
        }

        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val hasCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val isConnected = (caps != null && (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                hasWifi ||
                hasEthernet
            )) || (hotspotActive && ipv4 != null)

        val ip = ipv4 ?: "127.0.0.1"
        val transportType = when {
            !isConnected -> NetworkTransportType.NONE
            hotspotActive && !hasWifi -> NetworkTransportType.HOTSPOT
            hasWifi -> NetworkTransportType.WIFI
            hasEthernet -> NetworkTransportType.ETHERNET
            hasCellular -> NetworkTransportType.CELLULAR
            else -> NetworkTransportType.OTHER
        }

        val networkName = when {
            !isConnected -> "Disconnected"
            transportType == NetworkTransportType.HOTSPOT -> "Hotspot ($ip)"
            hasWifi && hasVpn -> "Wi-Fi + VPN ($ip)"
            hasWifi -> "Wi-Fi ($ip)"
            hasEthernet -> "Ethernet ($ip)"
            hasCellular -> "Cellular ($ip)"
            else -> "Local Network ($ip)"
        }

        return ConnectionStatus(
            isConnected = isConnected,
            networkName = networkName,
            ipAddress = ip,
            ipv6Address = ipv6,
            interfaceName = primaryIntf?.interfaceName.orEmpty(),
            transportType = transportType,
            isWifi = hasWifi,
            isHotspot = hotspotActive
        )
    }

    fun getAvailableNetworkAddresses(): List<NetworkInterfaceAddress> {
        val collected = mutableListOf<NetworkInterfaceAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val intfName = intf.displayName ?: intf.name ?: continue
                val hotspot = isHotspotInterfaceName(intfName)
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr.isLoopbackAddress) continue
                    when (addr) {
                        is Inet4Address -> {
                            val host = addr.hostAddress ?: continue
                            if (!host.startsWith("127.")) {
                                collected.add(
                                    NetworkInterfaceAddress(
                                        interfaceName = intfName,
                                        address = host,
                                        isIpv6 = false,
                                        isLinkLocal = addr.isLinkLocalAddress,
                                        isHotspotInterface = hotspot
                                    )
                                )
                            }
                        }
                        is Inet6Address -> {
                            val raw = addr.hostAddress?.substringBefore('%') ?: continue
                            collected.add(
                                NetworkInterfaceAddress(
                                    interfaceName = intfName,
                                    address = raw,
                                    isIpv6 = true,
                                    isLinkLocal = addr.isLinkLocalAddress,
                                    isHotspotInterface = hotspot
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore network interface enumeration error
        }
        return collected.sortedWith(
            compareBy<NetworkInterfaceAddress> { it.isIpv6 }
                .thenBy { it.isLinkLocal }
                .thenByDescending { it.interfaceName.startsWith("wlan") || it.interfaceName.startsWith("eth") }
        )
    }

    fun getLocalIpv4Address(): String? {
        return getAvailableNetworkAddresses()
            .firstOrNull { !it.isIpv6 && !it.isLinkLocal }
            ?.address
            ?: getAvailableNetworkAddresses().firstOrNull { !it.isIpv6 }?.address
    }

    fun getLocalIpv6Address(): String? {
        return getAvailableNetworkAddresses()
            .firstOrNull { it.isIpv6 && !it.isLinkLocal }
            ?.address
            ?: getAvailableNetworkAddresses().firstOrNull { it.isIpv6 }?.address
    }

    fun isLocalPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return try {
            ServerSocket(port).use { socket ->
                socket.reuseAddress = true
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isLanIpv4Address(address: String): Boolean {
        return try {
            val cleaned = address.substringBefore('%')
            cleaned.contains('.') &&
                !cleaned.startsWith("127.") &&
                !cleaned.startsWith("169.254.") &&
                InetAddress.getByName(cleaned) is Inet4Address
        } catch (_: Exception) {
            false
        }
    }

    fun createMulticastLock(context: Context, tag: String = "LocalDropMulticastLock"): WifiManager.MulticastLock? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock(tag)?.apply {
                setReferenceCounted(true)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getActiveInterfaceSummary(): String {
        val addrs = getAvailableNetworkAddresses()
        val ipv4 = addrs.firstOrNull { !it.isIpv6 }
        if (ipv4 != null) {
            return "${ipv4.interfaceName} • ${ipv4.address}"
        }
        val ipv6 = addrs.firstOrNull { it.isIpv6 }
        if (ipv6 != null) {
            return "${ipv6.interfaceName} • ${ipv6.address}"
        }
        return "No active LAN interface"
    }
}
