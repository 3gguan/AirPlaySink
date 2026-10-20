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

    /**
     * Every address on every interface that is up and not loopback, IPv4 and IPv6 alike.
     *
     * This is the list an AirPlay 2 `timingPeerInfo` answer has to carry. shairport-sync builds it
     * with `getifaddrs()` and puts all of it in the reply (`rtsp.c`, the initial SETUP), and that
     * list is not cosmetic: the sender picks the entry matching the address it reached us on and
     * uses it for PTP. Naming only the one address our control socket was accepted on was measured
     * to be the difference between connecting and being dropped — see [com.shilapi.airplaysink.ap2.Ap2Streams].
     *
     * shairport runs on hosts with one NIC, so its `getifaddrs()` walk only ever sees the one
     * interface the sender is on. A phone is not that: it is usually *also* carrying a SIM, and
     * `rmnet_ims*` hands out a global address on a different network entirely. Put one of those in
     * the list and the sender tries to run timing over cellular, never gets an answer, and drops
     * the session. So cellular — and every other interface that is not the link the receiver is
     * actually reachable on — is left out here.
     *
     * The connection's own address is not filtered by construction: the caller puts that one first,
     * because it is the one the sender certainly wants, and it may be a scoped link-local address
     * this scan reports with its zone attached.
     */
    fun allInterfaceAddresses(): List<InetAddress> {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrNull() ?: return emptyList()
        val out = LinkedHashSet<InetAddress>()
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            if (!carriesAirPlay(networkInterface.name)) continue
            for (address in networkInterface.inetAddresses.toList()) {
                if (address.isLoopbackAddress || address.isAnyLocalAddress) continue
                out.add(address)
            }
        }
        return out.toList()
    }

    /**
     * Whether this interface is one the sender could plausibly reach us on.
     *
     * Wi-Fi (including the hotspot the head unit runs itself), Ethernet and USB networking are in;
     * cellular, VPN/tunnel and virtual bridges are out. A phone is multi-homed and the AirPlay link
     * is only ever the Wi-Fi one — naming an address from another network is worse than naming
     * none, because the sender will try it.
     */
    internal fun carriesAirPlay(interfaceName: String): Boolean {
        val name = interfaceName.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni")) return false
        if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec")) return false
        // Tethering mirrors and the dummy bridge repeat wlan0's addresses under other names.
        if (name.startsWith("p2p") || name.startsWith("dummy")) return false
        return true
    }

    /**
     * The string form of [allInterfaceAddresses], for the places that put addresses on the wire.
     *
     * A link-local address is only usable with its zone (`fe80::…%wlan0`), so the zone travels
     * with it — dropping it produces an address the sender cannot connect to at all.
     */
    fun interfaceAddressStrings(): List<String> =
        allInterfaceAddresses().mapNotNull { it.hostAddress }

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
