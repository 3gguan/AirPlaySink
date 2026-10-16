package com.shilapi.airplaysink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.shilapi.airplaysink.util.SinkLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Chooses which address to *report* for the receiver.
 *
 * None of the sockets bind to this address any more — they all bind to the wildcard address, so the
 * receiver answers on whichever interface the phone happens to reach it through. This only decides
 * what the status card shows and how the "is there a network yet?" question is answered.
 *
 * The Wi-Fi/AP interface wins. That matters because [ConnectivityManager] does not list the
 * interface the head unit runs its own hotspot on: an AP is not a network the device is *connected*
 * to, so a hotspot-only head unit looks like it has nothing but cellular. Preferring the interface
 * scan keeps that from being reported as the listen address.
 */
object LocalNetwork {

    class Endpoint(val address: InetAddress, val description: String)

    fun find(context: Context): Endpoint? {
        val wifiInterface = fromInterfaces(onlyWifi = true)
        if (wifiInterface != null) return wifiInterface
        val fromSystem = fromConnectivityManager(context)
        if (fromSystem != null) return fromSystem
        return fromInterfaces(onlyWifi = false)
    }

    private fun fromConnectivityManager(context: Context): Endpoint? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val networks = runCatching { manager.allNetworks }.getOrNull() ?: return null
        var wifi: Endpoint? = null
        var other: Endpoint? = null
        for (network in networks) {
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
                ?: continue
            val properties = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: continue
            val address = firstIpv4(properties) ?: continue
            val interfaceName = properties.interfaceName ?: "?"
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val endpoint = Endpoint(address, "network=$network if=$interfaceName transport=$transport")
            if (transport == "wifi" && wifi == null) wifi = endpoint else if (other == null) other = endpoint
        }
        return wifi ?: other
    }

    private fun firstIpv4(properties: LinkProperties): InetAddress? =
        properties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    private fun fromInterfaces(onlyWifi: Boolean): Endpoint? {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrNull() ?: return null
        var wifi: Endpoint? = null
        var any: Endpoint? = null
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val name = networkInterface.name.lowercase()
            // Wi-Fi Direct and tethering mirrors duplicate wlan0; they never carry the AirPlay link.
            if (name.startsWith("p2p") || name.startsWith("dummy")) continue
            val address = networkInterface.inetAddresses.toList()
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: continue
            val endpoint = Endpoint(address, "interface=${networkInterface.name}")
            if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                if (wifi == null) wifi = endpoint
            } else if (any == null && !name.startsWith("rmnet") && !name.startsWith("tun")) {
                any = endpoint
            }
        }
        return wifi ?: if (onlyWifi) null else any
    }

    /** Cheap SSID lookup for the status line; returns null when the platform hides it. */
    fun ssid(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        val ssid = info.ssid ?: return null
        if (ssid.isEmpty() || ssid == "<unknown ssid>") return null
        return ssid.trim('"')
    }

    fun describe(context: Context): String {
        val endpoint = find(context)
        if (endpoint == null) return "no usable IPv4 address yet"
        val ssid = ssid(context)
        return "${endpoint.address.hostAddress} (${endpoint.description})" +
            if (ssid != null) " ssid=$ssid" else ""
    }

    fun log(context: Context) {
        SinkLog.i("Local network: ${describe(context)}")
    }
}
