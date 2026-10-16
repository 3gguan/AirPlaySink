package com.shilapi.airplaysink.raop

import android.content.Context
import com.shilapi.airplaysink.net.MdnsAdvertiser
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Owns the RTSP control listener and the mDNS advertisement.
 *
 * AirTunes semantics: exactly one sender at a time. When a second control connection arrives the
 * first session is closed, which is what iOS relies on when you pick the device again.
 *
 * There is no connection management of any kind beyond this: no hotspot creation, no Wi-Fi Direct,
 * no USB, no tunnelling, no Bluetooth negotiation. The head unit is expected to already be on the
 * iPhone's personal hotspot.
 */
class RaopReceiver(
    private val config: RaopServerConfig,
    private val events: RaopEvents,
    context: Context,
) : Closeable {

    private val appContext = context.applicationContext

    private companion object {
        val PREFERRED_PORTS = intArrayOf(5000, 5001, 5002, 5003, 5004, 5005)
        const val ACCEPT_TIMEOUT_MS = 1500
    }

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var advertiser: MdnsAdvertiser? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var currentSession: RaopSession? = null

    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /** Binds the control port and starts advertising. Throws when nothing can be bound. */
    fun start() {
        if (running) return

        val socket = bindControlSocket()
        serverSocket = socket
        running = true

        val mdns = MdnsAdvertiser(appContext, config) { name, error ->
            events.onAdvertisementState(name, error)
        }
        advertiser = mdns
        mdns.start(socket.localPort)

        acceptThread = Thread({ acceptLoop(socket) }, "raop-accept").also { it.start() }
        SinkLog.i(
            "AirPlay receiver listening on port ${socket.localPort} " +
                "as \"${config.deviceName}\" (all interfaces)",
        )
    }

    private fun bindControlSocket(): ServerSocket {
        var lastError: Exception? = null
        for (port in PREFERRED_PORTS) {
            val attempt = runCatching { openControlSocket(port) }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull() as? Exception
        }
        // Last resort: let the OS pick a free port; the mDNS record carries it to the sender.
        val fallback = runCatching { openControlSocket(0) }
        return fallback.getOrElse {
            throw IllegalStateException(
                "Could not open the AirPlay control port (${lastError?.message ?: it.message})",
            )
        }
    }

    /**
     * Binds the wildcard address, never a single interface address.
     *
     * This is deliberate and load-bearing. The head unit is usually *hosting* the hotspot the phone
     * joins, and when a device is an access point its `wlan0` is not among the networks
     * `ConnectivityManager` reports — an AP is not a network you are connected to. Picking "the"
     * address therefore tends to land on cellular, and binding there means the phone gets a reset
     * on the very address mDNS advertised. The wildcard has no such failure mode: it answers on
     * every interface, so it does not matter which network the phone arrives on, nor whether the
     * address changed underneath us.
     */
    private fun openControlSocket(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), 4)
        }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (running) SinkLog.w("Accept loop stopped: ${error.message}")
                return
            }

            runCatching {
                client.tcpNoDelay = true
                client.soTimeout = 0
            }

            // Only one sender is served at a time; a new connection replaces the old one.
            currentSession?.let {
                SinkLog.i("Replacing the previous control connection")
                runCatching { it.close() }
            }

            val session = RaopSession(
                socket = client,
                config = config,
                events = events,
                onFinished = { finished -> if (currentSession === finished) currentSession = null },
            )
            currentSession = session
            Thread({ session.run() }, "raop-session").start()
        }
        SinkLog.i("Accept loop finished")
    }

    /** True while a sender holds the control connection. */
    val hasClient: Boolean get() = currentSession != null

    /** Keeps the socket alive without touching the session. */
    fun isServing(): Boolean = running && serverSocket?.isClosed == false

    override fun close() {
        running = false
        runCatching { currentSession?.close() }
        currentSession = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { advertiser?.close() }
        advertiser = null
        runCatching { acceptThread?.join(2000) }
        acceptThread = null
        SinkLog.i("AirPlay receiver stopped")
    }
}

/** Convenience for callers that only have a [Socket]. */
internal fun Socket.describePeer(): String = "$inetAddress:$port"
