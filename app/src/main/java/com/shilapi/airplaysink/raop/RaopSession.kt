package com.shilapi.airplaysink.raop

import com.shilapi.airplaysink.audio.AudioPipeline
import com.shilapi.airplaysink.audio.DmapReader
import com.shilapi.airplaysink.audio.RaopAudioTransport
import com.shilapi.airplaysink.crypto.AppleChallenge
import com.shilapi.airplaysink.crypto.RaopCrypto
import com.shilapi.airplaysink.rtsp.AnnounceInfo
import com.shilapi.airplaysink.rtsp.RaopCodec
import com.shilapi.airplaysink.rtsp.RtspReader
import com.shilapi.airplaysink.rtsp.RtspRequest
import com.shilapi.airplaysink.rtsp.RtspResponse
import com.shilapi.airplaysink.rtsp.SdpParser
import com.shilapi.airplaysink.rtsp.textBody
import com.shilapi.airplaysink.rtsp.writeRtsp
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.pow

/** What the UI and the service want to know about a session. */
interface RaopEvents {
    fun onClientConnected(peer: String)
    fun onClientDisconnected()
    fun onStreamingStarted()
    fun onStreamingStopped()
    fun onTrackChanged(title: String?, artist: String?, album: String?)
    fun onVolumeChanged(decibels: Double)
    fun onProgress(current: Int, total: Int)

    /**
     * The sender offered its DACP reverse control credentials. A null field means "this request did
     * not carry it"; the receiver keeps whatever it learned before.
     */
    fun onDacpCredentials(dacpId: String?, activeRemote: String?, peerAddress: InetAddress)

    /**
     * The mDNS advertisement changed state.
     *
     * [name] is the instance name that is actually on the network — the platform renames it when
     * another device already claims the same one — and [error] is non-null when it could not be
     * published at all, which is the difference between "the iPhone has not looked yet" and "the
     * iPhone will never see us".
     */
    fun onAdvertisementState(name: String?, error: String?)
}

/** Immutable per-run description of the receiver, as seen by the sender. */
data class RaopServerConfig(
    val deviceName: String,
    val instanceName: String,
    val deviceId: String,
    val serverVersion: String,
    val model: String,
    val statusFlags: Int,
    val firmwareVersion: String,
    val localAddress: InetAddress,
)

/**
 * One RTSP control connection from a sender.
 *
 * Handles exactly the classic AirPlay (RAOP) method set. Video, mirroring, iAP and every other
 * negotiation a CarPlay receiver would need are deliberately absent: this is an audio sink.
 */
class RaopSession(
    private val socket: Socket,
    private val config: RaopServerConfig,
    private val events: RaopEvents,
    private val onFinished: (RaopSession) -> Unit,
) : Closeable {

    private companion object {
        const val CONTROL_LATENCY_FRAMES = 11025 // what the AirPort Express reports after RECORD
        const val SILENT_DB = -144.0
    }

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val reader = RtspReader(input)

    /** Same string the TXT record advertises as `vs`; every AirTunes-compatible box answers this. */
    private val serverName: String get() = "AirTunes/${config.serverVersion}"

    /** Inside the Apple-Challenge signature; see [AppleChallenge.hardwareAddressFor]. */
    private val hardwareAddress: ByteArray by lazy {
        AppleChallenge.hardwareAddressFor(config.instanceName)
    }

    private var announce: AnnounceInfo? = null
    private var sessionKey: ByteArray? = null
    private var pipeline: AudioPipeline? = null
    private var transport: RaopAudioTransport? = null

    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    private var senderControl = InetSocketAddress(0)

    @Volatile
    private var streaming = false

    @Volatile
    private var closed = false

    private var volumeDb = 0.0
    private var currentLinearVolume = 1.0f

    private var progressCurrent = 0
    private var progressTotal = 0

    /** Last DACP credentials forwarded upstream, so each change is reported exactly once. */
    private var lastDacpId: String? = null
    private var lastActiveRemote: String? = null

    private val peerDescription: String get() = "${socket.inetAddress.hostAddress}:${socket.port}"

    /** Blocking read loop; runs on the session thread. */
    fun run() {
        SinkLog.i("Control connection from $peerDescription")
        events.onClientConnected(peerDescription)
        try {
            while (!closed) {
                val request = reader.readRequest()
                if (request == null) {
                    SinkLog.i("The sender closed the control stream ($peerDescription)")
                    break
                }
                // Log every request, including OPTIONS. Without this an "it connects and then
                // immediately drops" session shows nothing at all between "connection from" and
                // "connection closed", which makes it impossible to tell whether the sender gave up
                // on our answer or never asked anything.
                SinkLog.i(
                    "RTSP <- ${request.method} ${request.uri} cseq=${request.cseq} " +
                        "body=${request.contentLength}B ct=${request.contentType.ifEmpty { "-" }}",
                )
                SinkLog.i("         ${request.headerSummary()}")
                handle(request)
            }
        } catch (error: Exception) {
            if (!closed) {
                SinkLog.w(
                    "Control connection ended: ${error.javaClass.simpleName} — ${error.message}",
                )
            }
        } finally {
            teardownSession(sendResponse = false)
            events.onClientDisconnected()
            SinkLog.i("Control connection closed ($peerDescription)")
            onFinished(this)
        }
    }

    override fun close() {
        closed = true
        teardownSession(sendResponse = false)
        runCatching { socket.close() }
    }

    // ------------------------------------------------------------------ dispatch

    private fun handle(request: RtspRequest) {
        observeDacpHeaders(request)
        when (request.method.uppercase()) {
            "OPTIONS" -> respond(request, optionsResponse())
            "ANNOUNCE" -> respond(request, announceResponse(request))
            "SETUP" -> respond(request, setupResponse(request))
            "RECORD" -> respond(request, recordResponse(request))
            "SET_PARAMETER" -> respond(request, setParameterResponse(request))
            "GET_PARAMETER" -> respond(request, getParameterResponse(request))
            "FLUSH" -> respond(request, flushResponse())
            "TEARDOWN" -> {
                respond(request, RtspResponse(200).addHeader("Connection", "close"))
                SinkLog.i("TEARDOWN received; closing the session")
                closed = true
            }
            "PAUSE" -> {
                pipeline?.flush()
                events.onStreamingStopped()
                streaming = false
                respond(request, RtspResponse(200))
            }
            else -> {
                SinkLog.i("${request.method} from $peerDescription answered with 200")
                respond(request, RtspResponse(200))
            }
        }
    }

    /**
     * iOS attaches the DACP reverse-control credentials to most RTSP requests. They rarely change,
     * so report them only when something is new — otherwise every SET_PARAMETER would spam the log.
     */
    private fun observeDacpHeaders(request: RtspRequest) {
        val dacpId = request.header("dacp-id")
        val activeRemote = request.header("active-remote")
        if (dacpId == null && activeRemote == null) return
        if (dacpId == lastDacpId && activeRemote == lastActiveRemote) return
        lastDacpId = dacpId
        lastActiveRemote = activeRemote
        SinkLog.i(
            "Remote control offered: DACP-ID=${dacpId ?: "-"} Active-Remote=${activeRemote ?: "-"}",
        )
        events.onDacpCredentials(dacpId, activeRemote, socket.inetAddress)
    }

    private fun optionsResponse(): RtspResponse = RtspResponse(200)
        .addHeader(
            "Public",
            "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER",
        )

    private fun announceResponse(request: RtspRequest): RtspResponse {
        val info = SdpParser.parse(request.contentText())
        if (info == null) {
            SinkLog.e("ANNOUNCE did not describe a usable audio stream")
            return RtspResponse(456)
        }

        releaseAudioResources()
        announce = info
        sessionKey = null

        if (info.encrypted) {
            val rsaKey = info.rsaAesKey
            val iv = info.aesIv
            if (rsaKey != null && iv != null) {
                val key = RaopCrypto.decryptSessionKey(rsaKey)
                if (key == null) {
                    SinkLog.e("Could not unwrap the AES session key; audio would be unusable")
                    return RtspResponse(456)
                }
                if (iv.size != 16) {
                    SinkLog.e("a=aesiv has ${iv.size} bytes, expected 16")
                    return RtspResponse(456)
                }
                sessionKey = key
                SinkLog.i("Session key unwrapped; stream is AES-128-CBC protected")
            }
        } else {
            SinkLog.i("ANNOUNCE describes an unencrypted stream")
        }

        SinkLog.i(
            "ANNOUNCE: codec=${info.codec} ${info.sampleRate}Hz ${info.bitDepth}bit " +
                "${info.channels}ch frame=${info.frameLength} " +
                "rice(pb=${info.riceHistoryMult} mb=${info.riceInitialHistory} kb=${info.riceKModifier}) " +
                "latency(min=${info.minLatency} max=${info.maxLatency})",
        )
        if (info.codec == RaopCodec.AAC) {
            SinkLog.e("The sender chose AAC, which this audio sink does not decode; audio will be silent")
        }

        return RtspResponse(200)
            .addHeader("Audio-Jack-Status", "connected; type=analog")
    }

    private fun setupResponse(request: RtspRequest): RtspResponse {
        if (announce == null) {
            SinkLog.w("SETUP arrived before ANNOUNCE")
            return RtspResponse(451)
        }

        val transportHeader = request.header("transport")
            ?: return RtspResponse(451).also { SinkLog.w("SETUP has no Transport header") }

        val controlPort = transportHeader.intValueAfter("control_port=")
        val timingPort = transportHeader.intValueAfter("timing_port=")
        if (controlPort == null || timingPort == null) {
            SinkLog.w("SETUP Transport is not a UDP record pair: $transportHeader")
            return RtspResponse(451)
        }

        senderControl = InetSocketAddress(socket.inetAddress, controlPort)
        releaseAudioResources()

        // Wildcard bindings, in the sender's own address family — see openDatagramSocket.
        val data = openDatagramSocket(socket.inetAddress)
        val control = openDatagramSocket(socket.inetAddress)
        val timing = openDatagramSocket(socket.inetAddress)
        audioSocket = data
        controlSocket = control
        timingSocket = timing

        val info = announce!!
        // The announced values end up sizing buffers and configuring AudioTrack, so clamp them to
        // something sane rather than trusting the sender.
        val channels = info.channels.coerceIn(1, 2)
        val sampleRate = info.sampleRate.coerceIn(8000, 96000)
        val framesPerPacket = info.frameLength.coerceIn(16, 4096)
        val audioPipeline = AudioPipeline(
            sampleRate = sampleRate,
            channels = channels,
            framesPerPacket = framesPerPacket,
        )
        val audioTransport = RaopAudioTransport(
            audioSocket = data,
            controlSocket = control,
            timingSocket = timing,
            senderControl = senderControl,
            pipeline = audioPipeline,
            sessionKey = sessionKey,
            sessionIv = info.aesIv,
        )
        pipeline = audioPipeline
        transport = audioTransport

        SinkLog.i(
            "SETUP: sender ${socket.inetAddress.hostAddress} control/timing ports " +
                "$controlPort/$timingPort; our sockets " +
                "data=${data.localAddress.hostAddress}:${data.localPort} " +
                "control=${control.localAddress.hostAddress}:${control.localPort} " +
                "timing=${timing.localAddress.hostAddress}:${timing.localPort}",
        )

        return RtspResponse(200)
            .addHeader(
                "Transport",
                "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${control.localPort};" +
                    "timing_port=${timing.localPort};" +
                    "server_port=${data.localPort}",
            )
            .addHeader("Session", "1")
    }

    private fun recordResponse(request: RtspRequest): RtspResponse {
        val audioPipeline = pipeline
        val audioTransport = transport
        if (audioPipeline == null || audioTransport == null) {
            SinkLog.w("RECORD arrived before SETUP")
            return RtspResponse(451)
        }
        if (streaming) {
            SinkLog.w("Duplicate RECORD ignored")
            return RtspResponse(200).addHeader("Audio-Latency", CONTROL_LATENCY_FRAMES.toString())
        }

        audioPipeline.start()
        audioTransport.start()
        audioPipeline.setVolume(currentLinearVolume)
        streaming = true
        events.onStreamingStarted()
        SinkLog.i("RECORD: playback started")

        return RtspResponse(200)
            .addHeader("Audio-Latency", CONTROL_LATENCY_FRAMES.toString())
    }

    private fun setParameterResponse(request: RtspRequest): RtspResponse {
        val contentType = request.contentType.lowercase()
        when {
            contentType.startsWith("text/parameters") -> handleTextParameters(request.contentText())
            contentType.startsWith("application/x-dmap-tagged") -> handleMetadata(request.content)
            contentType.startsWith("image") -> Unit // Artwork is not advertised; ignore politely.
            else -> if (request.content.isNotEmpty()) {
                SinkLog.i("SET_PARAMETER with Content-Type \"$contentType\" ignored")
            }
        }
        return RtspResponse(200)
    }

    private fun handleTextParameters(body: String) {
        for (rawLine in body.split("\r\n", "\n")) {
            val line = rawLine.trim()
            when {
                line.startsWith("volume:") -> {
                    val value = line.substringAfter(':').trim().toDoubleOrNull() ?: continue
                    applyVolume(value)
                }

                line.startsWith("progress:") -> {
                    val parts = line.substringAfter(':').trim().split('/')
                    if (parts.size >= 2) {
                        progressCurrent = parts[0].toIntOrNull() ?: 0
                        progressTotal = parts[1].toIntOrNull() ?: 0
                        events.onProgress(progressCurrent, progressTotal)
                    }
                }

                line.startsWith("daap.songalbum") -> Unit
            }
        }
    }

    private fun applyVolume(decibels: Double) {
        volumeDb = decibels.coerceIn(SILENT_DB, 0.0)
        val linear = if (volumeDb <= SILENT_DB) 0.0 else 10.0.pow(volumeDb / 20.0)
        currentLinearVolume = linear.toFloat().coerceIn(0f, 1f)
        pipeline?.setVolume(currentLinearVolume)
        events.onVolumeChanged(volumeDb)
    }

    private fun handleMetadata(payload: ByteArray) {
        val info = DmapReader.parse(payload) ?: return
        SinkLog.i(
            "Now playing: " +
                listOfNotNull(
                    info.title?.let { "title=\"$it\"" },
                    info.artist?.let { "artist=\"$it\"" },
                    info.album?.let { "album=\"$it\"" },
                ).joinToString(" "),
        )
        events.onTrackChanged(info.title, info.artist, info.album)
    }

    private fun getParameterResponse(request: RtspRequest): RtspResponse {
        val body = request.contentText()
        val response = RtspResponse(200)
        if (body.contains("volume")) {
            response.content = textBody(String.format(java.util.Locale.US, "volume: %.6f", volumeDb))
        }
        return response
    }

    private fun flushResponse(): RtspResponse {
        pipeline?.flush()
        SinkLog.i("FLUSH: dropped buffered audio")
        return RtspResponse(200)
    }

    // ------------------------------------------------------------------ plumbing

    private fun respond(request: RtspRequest, response: RtspResponse) {
        // Answer the challenge on the way out, for every method. shairport-sync does exactly this
        // and senders are known to hang up silently when the response is missing.
        request.header("apple-challenge")?.let { challenge ->
            val signature = AppleChallenge.respond(challenge, socket.localAddress, hardwareAddress)
            if (signature != null) {
                response.addHeader("Apple-Response", signature)
            } else {
                SinkLog.w(
                    "Answering ${request.method} without an Apple-Response; the sender may hang up",
                )
            }
        }
        try {
            output.writeRtsp(response, request.cseq, serverName)
            SinkLog.i("RTSP -> ${request.method} ${response.status}")
        } catch (error: Exception) {
            SinkLog.w("Could not answer ${request.method}: ${error.message}")
            closed = true
        }
    }

    /**
     * Opens a wildcard UDP socket in [peer]'s address family.
     *
     * The family has to match the control connection: iOS talks to us over IPv6 the moment the
     * hotspot hands out a prefix, and an IPv4 socket never sees those datagrams — which presents
     * exactly as "the session came up but no audio ever arrives". The address itself stays the
     * wildcard, for the same reason the RTSP listener uses one: a head unit hosting the hotspot is
     * not "connected" to it, so no address-picking heuristic can find the right interface.
     */
    private fun openDatagramSocket(peer: InetAddress): DatagramSocket {
        val wildcard = InetAddress.getByName(if (peer is Inet6Address) "::" else "0.0.0.0")
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        return try {
            socket.bind(InetSocketAddress(wildcard, 0))
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            SinkLog.w(
                "Could not bind ${wildcard.hostAddress} (${error.message}); " +
                    "falling back to the platform default",
            )
            DatagramSocket(0)
        }
    }

    private fun releaseAudioResources() {
        transport?.let { runCatching { it.close() } }
        transport = null
        pipeline?.let { runCatching { it.close() } }
        pipeline = null
        runCatching { audioSocket?.close() }
        runCatching { controlSocket?.close() }
        runCatching { timingSocket?.close() }
        audioSocket = null
        controlSocket = null
        timingSocket = null
    }

    private fun teardownSession(@Suppress("UNUSED_PARAMETER") sendResponse: Boolean) {
        val wasStreaming = streaming
        streaming = false
        releaseAudioResources()
        if (wasStreaming) events.onStreamingStopped()
        announce = null
        sessionKey = null
    }
}

private fun String.intValueAfter(marker: String): Int? {
    val index = indexOf(marker)
    if (index < 0) return null
    val rest = substring(index + marker.length)
    val digits = rest.takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}
