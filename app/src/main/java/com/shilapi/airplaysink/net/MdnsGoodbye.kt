package com.shilapi.airplaysink.net

import com.shilapi.airplaysink.util.SinkLog
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.Collections

/**
 * Tells the network to forget a service instance, now rather than at the end of its TTL.
 *
 * `NsdManager.unregisterService()` is not enough: on several ROMs the system responder drops its own
 * bookkeeping without emitting the "goodbye" the DNS-SD specification requires (RFC 6762 §10.1: the
 * same PTR record with TTL 0), so senders keep the instance in their cache for the full TTL — up to
 * 75 minutes for a PTR. That is harmless for a receiver that keeps its identity, and it is exactly
 * the trap when an experimental advertisement (`_airplay._tcp`) is withdrawn: an iPhone that still
 * has the record cached keeps steering the device into a protocol this build does not finish, and
 * reports "unable to connect" without ever asking why.
 *
 * The packet has to originate from port 5353 (RFC 6762 §6), so the socket binds there next to the
 * system responder — allowed because both sides set SO_REUSEADDR, which is what multicast sharing
 * on Linux keys off.
 */
object MdnsGoodbye {

    private const val MDNS_PORT = 5353
    private const val ROUNDS = 3
    private const val ROUND_GAP_MS = 400L

    /** IPv4 and IPv6 mDNS groups; a sender caches whichever family it discovered us on. */
    private val GROUPS = listOf("224.0.0.251", "ff02::fb")

    /** Fire and forget: the caller is starting a receiver and must not wait on the network. */
    fun withdrawAsync(instanceName: String, serviceType: String, localAddress: InetAddress?) {
        Thread({ withdraw(instanceName, serviceType, localAddress) }, "mdns-goodbye").apply {
            isDaemon = true
        }.start()
    }

    private fun withdraw(instanceName: String, serviceType: String, localAddress: InetAddress?) {
        val packet = ptrGoodbye(instanceName, serviceType)
        val nif = interfaceOf(localAddress)
        val groups = GROUPS.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (groups.isEmpty()) return

        var sent = 0
        for (round in 1..ROUNDS) {
            for (group in groups) {
                if (send(packet, group, nif)) sent++
            }
            if (round < ROUNDS) runCatching { Thread.sleep(ROUND_GAP_MS) }
        }
        SinkLog.i(
            "mDNS: sent goodbye for \"$instanceName.$serviceType.local\" " +
                "($sent packets over ${nif?.name ?: "the default interface"})",
        )
    }

    /**
     * A PTR record pointing `_service._tcp.local` at `instance._service._tcp.local` with TTL 0.
     *
     * No cache-flush bit: RFC 6762 §10.1 is explicit that a goodbye is an ordinary record whose
     * only peculiarity is the zero TTL.
     */
    private fun ptrGoodbye(instanceName: String, serviceType: String): ByteArray {
        val service = "$serviceType.local"
        val instance = "$instanceName.$service"
        val name = encodeName(instance)
        val target = encodeName(service)

        val out = ByteArrayOutputStream()
        // Header: id 0, flags 0x8400 (response, authoritative), 0 questions, 1 answer.
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0x00, 0, 0, 0, 1, 0, 0, 0, 0))
        out.write(name)
        // Type PTR (12), class IN (1), TTL 0, then the record data length.
        out.write(
            byteArrayOf(
                0, 12,
                0, 1,
                0, 0, 0, 0,
                (target.size shr 8).toByte(), target.size.toByte(),
            ),
        )
        out.write(target)
        return out.toByteArray()
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size and 0x3F)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun send(packet: ByteArray, group: InetAddress, nif: NetworkInterface?): Boolean {
        val family = if (group is Inet6Address) {
            StandardProtocolFamily.INET6
        } else {
            StandardProtocolFamily.INET
        }
        val wildcard = if (group is Inet6Address) "::" else "0.0.0.0"
        return runCatching {
            DatagramChannel.open(family).use { channel ->
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                nif?.let {
                    runCatching { channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, it) }
                }
                // Binding 5353 is what makes the packet look like it came from an mDNS responder;
                // a sender that validates the source port ignores it otherwise.
                runCatching {
                    channel.bind(InetSocketAddress(InetAddress.getByName(wildcard), MDNS_PORT))
                }.onFailure {
                    SinkLog.w("mDNS: could not bind port $MDNS_PORT (${it.message}); sending anyway")
                }
                channel.send(ByteBuffer.wrap(packet), InetSocketAddress(group, MDNS_PORT)) > 0
            }
        }.getOrElse {
            SinkLog.w("mDNS: goodbye to ${group.hostAddress} failed: ${it.message}")
            false
        }
    }

    /** The interface holding [address]; multicast has to be pinned to it on a multi-homed device. */
    private fun interfaceOf(address: InetAddress?): NetworkInterface? {
        if (address == null) return null
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { nif ->
                nif.isUp && Collections.list(nif.inetAddresses).any { it == address }
            }
        }.getOrNull()
    }
}
