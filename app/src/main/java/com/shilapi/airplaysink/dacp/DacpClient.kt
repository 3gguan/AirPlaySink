package com.shilapi.airplaysink.dacp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Apple's DACP reverse control channel.
 *
 * Classic AirPlay is normally one-way: the phone pushes audio and the receiver cannot ask it to do
 * anything. DACP is the exception. On its RTSP requests the phone attaches two headers:
 *
 * ```
 * DACP-ID: CFAE09D9DA7527E9
 * Active-Remote: 3951361179
 * ```
 *
 * `DACP-ID` names an `_dacp._tcp` service the phone publishes on the same link (the instance is
 * usually `iTunes_Ctrl_<DACP-ID>`), and `Active-Remote` is the token that authorises commands. Once
 * the port is known, a plain HTTP GET against the phone is enough:
 *
 * ```
 * GET /ctrl-int/1/nextitem HTTP/1.1
 * Host: <phone-ip>:<port>
 * Active-Remote: <token>
 * ```
 *
 * The phone answers `204 No Content`. Commands: `play`, `pause`, `playpause`, `stop`, `nextitem`,
 * `previtem`, `beginff`, `beginrew`, `volumeup`, `volumedown`.
 *
 * AirPlay 2 replaced this with the (still undocumented) MRP command channel, so DACP only exists on
 * the classic protocol — which is exactly what this receiver speaks, because it advertises
 * `_raop._tcp` and deliberately never `_airplay._tcp`.
 *
 * Browsing goes through [NsdManager] rather than an in-app responder, for the same reason the
 * advertisement does: on Android the system owns UDP 5353, and a hand-rolled browser usually hears
 * nothing at all.
 */
class DacpClient(
    context: Context,
    private val onAvailabilityChanged: (Boolean) -> Unit = {},
) : Closeable {

    /** The subset of `/ctrl-int/1/...` endpoints worth exposing to a car head unit. */
    enum class Command(val path: String) {
        PLAY("play"),
        PAUSE("pause"),
        PLAY_PAUSE("playpause"),
        STOP("stop"),
        NEXT("nextitem"),
        PREVIOUS("previtem"),
    }

    private companion object {
        const val SERVICE_TYPE = "_dacp._tcp"
        const val CONNECT_TIMEOUT_MS = 2500
        const val READ_TIMEOUT_MS = 2500

        /** The instance label iOS uses for its control service, used when the id is not known yet. */
        const val IOS_INSTANCE_PREFIX = "iTunes_Ctrl_"

        /** `discoverServices` refuses to restart while the previous stop is still in flight. */
        const val RESTART_BROWSING_DELAY_MS = 600L
    }

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val main = Handler(Looper.getMainLooper())

    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dacp-command").apply { isDaemon = true }
    }

    // Credentials handed over by the sender in RTSP headers.
    @Volatile
    private var dacpId: String? = null

    @Volatile
    private var activeRemote: String? = null

    @Volatile
    private var peerAddress: InetAddress? = null

    // Resolved endpoint of the sender's control service.
    @Volatile
    private var targetAddress: InetAddress? = null

    @Volatile
    private var port = 0

    @Volatile
    private var resolvedName: String? = null

    @Volatile
    private var lastReportedAvailable = false

    @Volatile
    private var closed = false

    // --- discovery state -------------------------------------------------
    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var discoveryActive = false

    @Volatile
    private var pendingResolve: NsdServiceInfo? = null

    @Volatile
    private var resolving = false

    private val restartBrowsing = Runnable {
        if (!closed) {
            stopBrowsing()
            startBrowsing()
        }
    }

    /** True when a command can actually be delivered right now. */
    val isAvailable: Boolean
        get() = port > 0 && activeRemote != null && (targetAddress ?: peerAddress) != null

    /** Human readable summary for the UI, or null while nothing has been offered. */
    val endpointDescription: String?
        get() {
            val host = targetAddress ?: peerAddress ?: return null
            val token = activeRemote ?: return null
            val resolvedPort = port
            return if (resolvedPort > 0) {
                "${host.hostAddress}:$resolvedPort (id ${dacpId ?: "?"}, token $token)"
            } else {
                "${host.hostAddress} (id ${dacpId ?: "?"}, token $token, port unknown)"
            }
        }

    /**
     * Called for every RTSP request. A `null` field means "this request did not carry it", so the
     * previously learned value is kept rather than cleared.
     */
    fun observe(dacpId: String?, activeRemote: String?, peer: InetAddress?) {
        var targetChanged = false
        synchronized(this) {
            if (peer != null && peer != peerAddress) {
                peerAddress = peer
                targetChanged = true
            }
            if (dacpId != null && !dacpId.equals(this.dacpId, ignoreCase = true)) {
                this.dacpId = dacpId
                targetChanged = true
            }
            if (activeRemote != null && activeRemote != this.activeRemote) {
                this.activeRemote = activeRemote
            }
        }

        if (targetChanged) {
            port = 0
            targetAddress = null
            resolvedName = null
            SinkLog.i(
                "DACP: sender offers id=$dacpId active-remote=$activeRemote; looking for its endpoint",
            )
            startBrowsing()
            // A previous resolve may have matched a stale id; ask again from scratch.
            main.removeCallbacks(restartBrowsing)
            main.postDelayed(restartBrowsing, RESTART_BROWSING_DELAY_MS)
        } else {
            startBrowsing()
        }
        reportAvailability()
    }

    /** Forgets everything; used when the sender disconnects. */
    fun clear() {
        synchronized(this) {
            dacpId = null
            activeRemote = null
            peerAddress = null
        }
        port = 0
        targetAddress = null
        resolvedName = null
        reportAvailability()
    }

    /**
     * The sender dropped its RTSP connection — which iOS does after *every* probe, long before any
     * audio flows. Only the peer address is dropped here: the DACP id, the token and the resolved
     * control endpoint all stay, because the same sender is about to reconnect for ANNOUNCE and the
     * steering wheel keys have to keep working across that gap. Clearing them here is what used to
     * leave reverse control permanently unavailable.
     */
    fun onSenderDisconnected() {
        synchronized(this) { peerAddress = null }
    }

    /** Queues a command. Never blocks the caller; the phone is contacted on a worker thread. */
    fun enqueue(command: Command) {
        if (closed) return
        commandExecutor.execute { send(command) }
    }

    /** Blocking variant, used by the worker thread and by tests. */
    fun send(command: Command): Boolean {
        val targetPort = port
        val host = targetAddress ?: peerAddress
        val token = activeRemote
        if (targetPort <= 0 || host == null || token == null) {
            SinkLog.w(
                "DACP: ${command.path} not sent — the iPhone has not offered a control " +
                    "endpoint yet (port=$targetPort host=$host token=$token)",
            )
            return false
        }

        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                val request = buildString {
                    append("GET /ctrl-int/1/").append(command.path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host.hostAddress).append(':').append(targetPort)
                    append("\r\n")
                    append("Active-Remote: ").append(token).append("\r\n")
                    append("\r\n")
                }

                val output = socket.getOutputStream()
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()

                val status = socket.getInputStream()
                    .bufferedReader(Charsets.ISO_8859_1)
                    .readLine()

                val ok = status == null || status.contains(" 2")
                SinkLog.i("DACP: ${command.path} -> ${status ?: "(no reply)"}")
                ok
            }
        }.getOrElse { error ->
            SinkLog.w("DACP: ${command.path} failed: ${error.message}")
            false
        }
    }

    // ------------------------------------------------------------------ discovery

    private fun startBrowsing() {
        if (closed || discoveryActive) return
        val manager = nsd
        if (manager == null) {
            SinkLog.w("DACP: this ROM has no NSD service, so the sender cannot be found")
            return
        }

        discoveryActive = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                SinkLog.i("DACP: browsing $serviceType for the sender's remote control endpoint")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                consider(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (name == resolvedName) {
                    SinkLog.i("DACP: \"$name\" went away")
                    port = 0
                    targetAddress = null
                    resolvedName = null
                    reportAvailability()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (discoveryListener === this) discoveryActive = false
                SinkLog.i("DACP: stopped browsing $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (discoveryListener === this) discoveryActive = false
                SinkLog.w("DACP: could not start browsing (code $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (discoveryListener === this) discoveryActive = false
                SinkLog.w("DACP: could not stop browsing (code $errorCode)")
            }
        }

        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { error ->
                if (discoveryListener === listener) {
                    discoveryListener = null
                    discoveryActive = false
                }
                SinkLog.w("DACP: discoverServices threw: ${error.message}")
            }
    }

    private fun stopBrowsing() {
        val manager = nsd
        val listener = discoveryListener
        discoveryListener = null
        discoveryActive = false
        if (manager == null || listener == null) return
        runCatching { manager.stopServiceDiscovery(listener) }
            .onFailure { SinkLog.w("DACP: stopServiceDiscovery threw: ${it.message}") }
    }

    /**
     * Resolves one service at a time. The platform allows a single outstanding resolve per
     * `NsdManager`, and asking for a second one while the first is in flight fails outright.
     */
    private fun consider(info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        val wanted = dacpId

        val interesting = if (wanted != null) {
            name.contains(wanted, ignoreCase = true)
        } else {
            name.startsWith(IOS_INSTANCE_PREFIX, ignoreCase = true)
        }
        if (!interesting) {
            SinkLog.i("DACP: ignoring \"$name\" — it is not this sender")
            return
        }

        pendingResolve = info
        pumpResolve()
    }

    private fun pumpResolve() {
        if (resolving || closed) return
        val manager = nsd ?: return
        val next = pendingResolve ?: return
        pendingResolve = null
        resolving = true

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving = false
                SinkLog.w("DACP: could not resolve \"${info.serviceName}\" (code $errorCode)")
                pumpResolve()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving = false
                evaluate(info)
                pumpResolve()
            }
        }

        runCatching { manager.resolveService(next, listener) }
            .onFailure { error ->
                resolving = false
                SinkLog.w("DACP: resolveService threw: ${error.message}")
            }
    }

    private fun evaluate(info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        val address = info.host
        val servicePort = info.port

        if (address == null) {
            SinkLog.w("DACP: \"$name\" resolved without an address")
            return
        }
        if (servicePort <= 0) {
            SinkLog.w("DACP: \"$name\" resolved without a usable port")
            return
        }

        val wanted = dacpId
        val peer = peerAddress
        val matchedById = wanted != null && name.contains(wanted, ignoreCase = true)
        // The credentials can be gone while the service is still the right one: iOS opens a control
        // connection only for the OPTIONS probe, closes it, and reconnects later for ANNOUNCE. The
        // instance prefix is what identifies the sender across that gap.
        val matchedByPrefix =
            wanted == null && name.startsWith(IOS_INSTANCE_PREFIX, ignoreCase = true)
        val matchedByAddress = peer != null && peer == address

        if (!matchedById && !matchedByPrefix && !matchedByAddress) {
            SinkLog.i("DACP: ignoring \"$name\" at ${address.hostAddress} — not this sender")
            return
        }

        val reason = when {
            matchedById -> "matched by DACP id"
            matchedByPrefix -> "matched by instance prefix"
            else -> "matched by address"
        }
        SinkLog.i("DACP: \"$name\" at ${address.hostAddress}:$servicePort ($reason)")
        port = servicePort
        targetAddress = address
        resolvedName = name
        reportAvailability()
    }

    private fun reportAvailability() {
        val now = isAvailable
        if (now == lastReportedAvailable) return
        lastReportedAvailable = now
        runCatching { onAvailabilityChanged(now) }
    }

    override fun close() {
        closed = true
        main.removeCallbacks(restartBrowsing)
        stopBrowsing()
        commandExecutor.shutdownNow()
        clear()
        SinkLog.i("DACP: browser closed")
    }
}
