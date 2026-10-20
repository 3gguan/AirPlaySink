package com.shilapi.airplaysink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.shilapi.airplaysink.ap2.Ap2Streams
import com.shilapi.airplaysink.dacp.DacpClient
import com.shilapi.airplaysink.net.LocalNetwork
import com.shilapi.airplaysink.raop.RaopEvents
import com.shilapi.airplaysink.raop.RaopReceiver
import com.shilapi.airplaysink.raop.RaopServerConfig
import com.shilapi.airplaysink.util.SinkLog
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the lifetime of the AirPlay audio receiver.
 *
 * It holds a Wi-Fi lock so the head unit keeps the hotspot link alive with the screen off, and a
 * multicast lock so mDNS queries from the iPhone actually reach us.
 */
class SinkService : Service(), RaopEvents {

    companion object {
        const val ACTION_START = "com.shilapi.airplaysink.action.START"
        const val ACTION_STOP = "com.shilapi.airplaysink.action.STOP"

        private const val CHANNEL_ID = "airplay_sink"
        private const val NOTIFICATION_ID = 0x5A17
        private const val WIFI_LOCK_TAG = "airplaysink:wifi"
        private const val MULTICAST_LOCK_TAG = "airplaysink:mdns"

        private val active = AtomicBoolean(false)

        /** The live instance, so the UI can relay a key press without binding to the service. */
        @Volatile
        private var instance: SinkService? = null

        val isActive: Boolean get() = active.get()

        /**
         * Sends a remote control command to the iPhone over DACP. Returns false when the receiver is
         * not running at all, which the UI turns into "not connected yet".
         */
        fun remoteCommand(command: DacpClient.Command): Boolean {
            val service = instance ?: return false
            service.relayRemoteCommand(command)
            return true
        }

        /**
         * Sends a transport command over AirPlay 2's own channel, asynchronously.
         *
         * [onResult] runs on the main thread with exactly what happened, because the write goes to
         * a socket and the UI has to report the outcome rather than a guess.
         */
        fun ap2Remote(command: DacpClient.Command, onResult: (Ap2Streams.Delivery) -> Unit) {
            val service = instance
            if (service == null) {
                onResult(Ap2Streams.Delivery.NO_STREAM)
                return
            }
            service.sendAp2RemoteAsync(command, onResult)
        }

        fun start(context: Context) {
            val intent = Intent(context, SinkService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SinkService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }

    private lateinit var preferences: SinkPreferences

    /** True between a start request and a stop request. The service stays up even without a network. */
    @Volatile
    private var serving = false

    @Volatile
    private var receiver: RaopReceiver? = null
    private var dacp: DacpClient? = null
    private var mediaSession: MediaSession? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** The address the advertisement is currently bound to, so a change can be spotted. */
    @Volatile
    private var boundAddress: InetAddress? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Binding sockets and starting mDNS must never run on the main thread. */
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sink-network").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = SinkPreferences(this)
        createNotificationChannel()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SinkLog.i("Stop requested")
                shutdown()
                return START_NOT_STICKY
            }

            else -> startReceiver()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        networkExecutor.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startReceiver() {
        if (serving) {
            SinkLog.i("Receiver already running; ignoring the start request")
            return
        }
        serving = true
        active.set(true)
        preferences.serviceEnabled = true
        SinkModel.update {
            it.copy(starting = true, waitingForNetwork = false, lastError = null, remoteControl = false)
        }
        startForegroundCompat(buildNotification("Starting…"))

        acquireLocks()
        setupMediaSession()
        registerNetworkMonitor()
        requestNetworkEvaluation()
    }

    /**
     * Brings the advertisement in line with the current network.
     *
     * The receiver deliberately does not care *which* network it is on, nor whether the head unit
     * has managed to join the iPhone hotspot yet: any usable IPv4 will do, Wi-Fi preferred and
     * everything else accepted, because the moment an address exists the advertisement is worth
     * publishing. When there is no address yet the service stays up and waits — a cold boot is
     * usually faster than Wi-Fi — and starts advertising the instant one appears.
     *
     * A later address change is *not* a rebuild. Every listener binds the wildcard address, so a
     * different IP does not invalidate them, and the platform's mDNS responder tracks interface
     * changes on its own. Tearing the receiver down here would only kill a live session to end up
     * in exactly the same place.
     */
    private fun evaluateNetwork() {
        if (!serving) return

        val endpoint = LocalNetwork.find(this)
        if (endpoint == null) {
            if (receiver == null) {
                SinkLog.i("No IPv4 address yet; staying up and waiting for a network")
                SinkModel.update {
                    it.copy(
                        starting = false,
                        running = false,
                        waitingForNetwork = true,
                        lastError = null,
                    )
                }
                updateNotification("Waiting for a network")
            }
            return
        }

        if (receiver != null) {
            if (endpoint.address != boundAddress) {
                SinkLog.i("IPv4 address is now ${endpoint.address.hostAddress}; keeping the listener")
                boundAddress = endpoint.address
                SinkModel.update { it.copy(address = endpoint.address.hostAddress) }
            }
            return
        }

        broadcastOn(endpoint)
    }

    private fun requestNetworkEvaluation() {
        networkExecutor.execute {
            runCatching { evaluateNetwork() }
                .onFailure { SinkLog.e("Network evaluation failed", it) }
        }
    }

    private fun broadcastOn(endpoint: LocalNetwork.Endpoint) {
        LocalNetwork.log(this)

        // DACP reverse control. Browsing for the sender's _dacp._tcp service is what makes the
        // steering wheel keys able to drive the iPhone; without it this is a pure audio sink.
        dacp = DacpClient(this) { available ->
            SinkModel.update { it.copy(remoteControl = available) }
            SinkLog.i(if (available) "Reverse control is ready" else "Reverse control lost")
        }

        val config = RaopServerConfig(
            deviceName = preferences.deviceName,
            instanceName = preferences.instanceName,
            deviceId = preferences.deviceId,
            serverVersion = preferences.serverVersion,
            model = preferences.model,
            statusFlags = preferences.statusFlags,
            firmwareVersion = Build.VERSION.RELEASE ?: "android",
            localAddress = endpoint.address,
            cushionMs = preferences.bufferMs,
            airPlay2 = preferences.airPlay2,
        )
        SinkModel.update { it.copy(bufferMs = config.cushionMs) }

        val sink = RaopReceiver(config, this, this)
        receiver = sink
        try {
            sink.start()
        } catch (error: Exception) {
            SinkLog.e("Could not start the AirPlay receiver", error)
            receiver = null
            releaseDacp()
            SinkModel.update {
                it.copy(starting = false, running = false, lastError = error.message)
            }
            updateNotification("Not running: ${error.message}")
            return
        }

        boundAddress = endpoint.address
        SinkModel.update {
            it.copy(
                starting = false,
                running = true,
                waitingForNetwork = false,
                address = endpoint.address.hostAddress,
                adverisedName = config.instanceName,
                port = sink.boundPort,
                // Read back from the preference the receiver was started with, so a switch flipped
                // without the restart landing cannot make the status card promise something the
                // running receiver does not implement.
                ap2RemoteEnabled = preferences.ap2Remote,
                eventChannelOpen = false,
                remoteCommandResult = null,
                ap2CommandReply = null,
                lastError = null,
            )
        }
        updateNotification("Waiting for the iPhone")
    }

    /** Drops the advertisement but keeps the service (and its media session) alive. */
    private fun stopReceiverOnly() {
        receiver?.let { runCatching { it.close() } }
        receiver = null
        releaseDacp()
        boundAddress = null
    }

    private fun releaseDacp() {
        dacp?.let { runCatching { it.close() } }
        dacp = null
    }

    // ------------------------------------------------------------------ network watching

    private fun registerNetworkMonitor() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SinkLog.i("Network available: $network")
                requestNetworkEvaluation()
            }

            override fun onLost(network: Network) {
                SinkLog.i("Network lost: $network")
                requestNetworkEvaluation()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                requestNetworkEvaluation()
            }
        }

        // No capability requirement at all: whichever network hands us an IPv4 is good enough.
        val request = NetworkRequest.Builder().build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess {
                networkCallback = callback
                SinkLog.i("Watching for network changes")
            }
            .onFailure { SinkLog.w("Could not watch for network changes: ${it.message}") }
    }

    private fun unregisterNetworkMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun shutdown() {
        serving = false
        stopReceiverOnly()
        teardownMediaSession()
        unregisterNetworkMonitor()
        releaseLocks()
        active.set(false)
        preferences.serviceEnabled = false
        SinkModel.update {
            it.copy(
                running = false,
                starting = false,
                waitingForNetwork = false,
                client = null,
                protocol = null,
                streaming = false,
                title = null,
                artist = null,
                album = null,
                port = 0,
                remoteControl = false,
            )
        }
        stopForegroundCompat()
        stopSelf()
    }

    // ------------------------------------------------------------------ locks

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return

        // The two locks are taken independently on purpose. A failure to take one of them must not
        // silently cost us the other: without the multicast lock the Wi-Fi stack filters mDNS away
        // and the head unit never shows up in the sender's AirPlay list at all, which looks exactly
        // like "the app says it is running but nothing is found".
        if (wifiLock == null) {
            runCatching {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                    .also { it.setReferenceCounted(false); it.acquire() }
            }.onFailure {
                wifiLock = null
                SinkLog.w("Could not take the Wi-Fi lock (is WAKE_LOCK declared?): ${it.message}")
            }
        }

        if (multicastLock == null) {
            runCatching {
                multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
                    .also { it.setReferenceCounted(false); it.acquire() }
            }.onFailure {
                multicastLock = null
                SinkLog.w("Could not take the mDNS multicast lock: ${it.message}")
            }
        }

        SinkLog.i(
            "Locks: wifi=${wifiLock?.isHeld == true} multicast=${multicastLock?.isHeld == true}",
        )
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        wifiLock = null
        multicastLock = null
    }

    // ------------------------------------------------------------------ RaopEvents

    override fun onClientConnected(peer: String) {
        SinkModel.update { it.copy(client = peer, lastError = null) }
        updateNotification("Connected: $peer")
    }

    /**
     * What the sender turned out to speak. The switch decides what is advertised; a sender with a
     * cached record can still pick the other one, so the notification and the status card report
     * the session rather than the setting.
     */
    override fun onProtocolNegotiated(protocol: SinkProtocol) {
        val label = if (protocol == SinkProtocol.AP2) "AirPlay 2" else "AirPlay 1"
        SinkModel.update { it.copy(protocol = protocol) }
        updateNotification("Connected: $label")
    }

    override fun onClientDisconnected() {
        // The DACP endpoint is deliberately *not* thrown away. iOS closes the control connection
        // right after its OPTIONS probe and only reconnects when the user actually starts playing,
        // so wiping the credentials here would leave the steering wheel keys dead for the rest of
        // the session.
        dacp?.onSenderDisconnected()
        SinkModel.update {
            it.copy(
                client = null,
                protocol = null,
                streaming = false,
                title = null,
                artist = null,
                album = null,
                progressCurrentSeconds = 0,
                progressTotalSeconds = 0,
                // Both belong to the session that just ended, and a stale "ready" is worse than a
                // blank: it would promise a channel that no longer exists.
                eventChannelOpen = false,
                remoteCommandResult = null,
                ap2CommandReply = null,
            )
        }
        updatePlaybackState()
        updateNotification("Waiting for the iPhone")
    }

    /**
     * The event connection remote commands travel on. Worth surfacing because a phone can hold a
     * perfectly good audio session without ever opening it, and then every command has nowhere to
     * go — which looks identical to a dead button.
     */
    override fun onEventChannelChanged(open: Boolean) {
        SinkModel.update { it.copy(eventChannelOpen = open) }
    }

    /**
     * The phone took our command. Reported separately from the send because the two are different
     * facts: a `SENT` only means the bytes left this device.
     */
    override fun onAp2CommandReply(status: String) {
        SinkLog.i("AP2: command accepted by the sender — $status")
        SinkModel.update { it.copy(ap2CommandReply = status) }
    }

    override fun onStreamingStarted() {
        SinkModel.update { it.copy(streaming = true) }
        updatePlaybackState()
        updateNotification("Playing")
    }

    override fun onStreamingStopped() {
        SinkModel.update { it.copy(streaming = false) }
        updatePlaybackState()
        updateNotification("Waiting for the iPhone")
    }

    override fun onTrackChanged(title: String?, artist: String?, album: String?) {
        SinkModel.update { it.copy(title = title, artist = artist, album = album) }
        val label = listOfNotNull(artist, title).joinToString(" - ").ifEmpty { "AirPlay" }
        updateNotification(label)
    }

    override fun onVolumeChanged(decibels: Double) {
        SinkModel.update { it.copy(volumeDb = decibels) }
    }

    override fun onProgress(current: Int, total: Int) {
        SinkModel.update {
            it.copy(progressCurrentSeconds = current, progressTotalSeconds = total)
        }
    }

    override fun onDacpCredentials(dacpId: String?, activeRemote: String?, peerAddress: InetAddress) {
        dacp?.observe(dacpId, activeRemote, peerAddress)
    }

    override fun onAdvertisementState(name: String?, error: String?) {
        if (error != null) {
            // Nothing is on the network, so the iPhone will never list this head unit. Surface it
            // instead of leaving the UI sitting on "running" forever.
            SinkModel.update { it.copy(lastError = "mDNS: $error") }
            updateNotification("Not advertised: $error")
            return
        }
        if (name == null) return
        // Only the advertised name changes here; the status line is already correct because the
        // advertisement is published before the first sender can possibly appear.
        SinkModel.update { it.copy(adverisedName = name, lastError = null) }
    }

    // ------------------------------------------------------------------ remote control

    /** Handler for the one callback that has to land on the main thread. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Sends a command off the caller's thread and reports the outcome on the main thread.
     *
     * The result is also left in [SinkModel] so the status card keeps showing it after the toast is
     * gone: on a head unit the toast is easy to miss, and "nothing happened" is the complaint this
     * path exists to answer.
     */
    private fun sendAp2RemoteAsync(
        command: DacpClient.Command,
        onResult: (Ap2Streams.Delivery) -> Unit,
    ) {
        val sink = receiver
        SinkLog.i(
            "Remote control \"${command.path}\": sending over the AP2 channel " +
                "(receiver=${if (sink == null) "none" else "up"})",
        )
        // Cleared here, on the caller's thread, and therefore before the write below: a reply can
        // only arrive after the write, so this ordering cannot drop one.
        SinkModel.update { it.copy(ap2CommandReply = null) }
        Thread({
            val delivery = try {
                sink?.sendAp2Remote(command) ?: Ap2Streams.Delivery.NO_STREAM
            } catch (error: Exception) {
                SinkLog.w("Remote control \"${command.path}\" failed to send: ${error.message}")
                Ap2Streams.Delivery.WRITE_FAILED
            }
            SinkLog.i("Remote control \"${command.path}\" over AP2: $delivery")
            SinkModel.update { it.copy(remoteCommandResult = delivery) }
            mainHandler.post { onResult(delivery) }
        }, "ap2-remote").start()
    }

    /** Relays a command coming from the UI buttons or a media key. */
    private fun relayRemoteCommand(command: DacpClient.Command) {
        // AirPlay 2 has its own channel for this — the one shairport-sync drives from MPRIS/D-Bus.
        // It carries `sendMediaRemoteCommand` over the encrypted event connection, so the DACP
        // credentials iOS stops sending under AP2 are not needed at all.
        if (SinkModel.state.protocol == SinkProtocol.AP2) {
            if (preferences.ap2Remote) {
                sendAp2RemoteAsync(command) { }
            } else {
                SinkLog.w(
                    "Remote control \"${command.path}\" under AirPlay 2 needs the " +
                        "\"AP2 remote control\" switch; without it there is no channel to use",
                )
            }
            return
        }
        val client = dacp
        if (client == null || !client.isAvailable) {
            SinkLog.w(
                "Remote control \"${command.path}\" ignored — the iPhone has not offered a DACP " +
                    "endpoint yet",
            )
            return
        }
        SinkLog.i("Remote control: sending \"${command.path}\"")
        client.enqueue(command)
    }

    /**
     * A media session so the head unit knows this app is the active media client and routes its
     * transport keys here.
     *
     * Worth knowing: BYD hands the steering wheel's next/previous keys to whichever window is in
     * front. Play/pause is emitted as a normal media key and arrives in the background, but
     * next/previous only reach us while this app's activity is on screen.
     */
    private fun setupMediaSession() {
        if (mediaSession != null) return
        val session = MediaSession(this, "AirPlaySink")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = relayRemoteCommand(DacpClient.Command.PLAY)
            override fun onPause() = relayRemoteCommand(DacpClient.Command.PAUSE)
            override fun onStop() = relayRemoteCommand(DacpClient.Command.STOP)
            override fun onSkipToNext() = relayRemoteCommand(DacpClient.Command.NEXT)
            override fun onSkipToPrevious() = relayRemoteCommand(DacpClient.Command.PREVIOUS)
        })
        session.isActive = true
        mediaSession = session
        updatePlaybackState()
        SinkLog.i("Media session open; the head unit can route its transport keys here")
    }

    private fun updatePlaybackState() {
        val session = mediaSession ?: return
        val playing = SinkModel.state.streaming
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f,
                SystemClock.elapsedRealtime(),
            )
        runCatching { session.setPlaybackState(builder.build()) }
    }

    private fun teardownMediaSession() {
        val session = mediaSession ?: return
        mediaSession = null
        runCatching { session.isActive = false }
        runCatching { session.release() }
    }

    // ------------------------------------------------------------------ notifications

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_label),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sink)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!active.get() && receiver == null) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }
}
