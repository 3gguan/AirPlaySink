package com.shilapi.airplaysink.raop

import com.shilapi.airplaysink.SinkProtocol
import com.shilapi.airplaysink.ap2.Ap2Remote
import com.shilapi.airplaysink.ap2.Ap2SecureLink
import com.shilapi.airplaysink.ap2.Ap2Session
import com.shilapi.airplaysink.ap2.Ap2Streams
import com.shilapi.airplaysink.ap2.BPlist
import com.shilapi.airplaysink.ap2.BPlistReader
import com.shilapi.airplaysink.ap2.MrCommands
import com.shilapi.airplaysink.audio.AudioPipeline
import com.shilapi.airplaysink.audio.DmapReader
import com.shilapi.airplaysink.audio.RaopAudioTransport
import com.shilapi.airplaysink.crypto.AppleChallenge
import com.shilapi.airplaysink.crypto.RaopCrypto
import com.shilapi.airplaysink.dacp.DacpClient
import com.shilapi.airplaysink.rtsp.AnnounceInfo
import com.shilapi.airplaysink.rtsp.RaopCodec
import com.shilapi.airplaysink.rtsp.RtspReader
import com.shilapi.airplaysink.rtsp.RtspRequest
import com.shilapi.airplaysink.rtsp.RtspResponse
import com.shilapi.airplaysink.rtsp.SdpParser
import com.shilapi.airplaysink.rtsp.textBody
import com.shilapi.airplaysink.rtsp.writeRtsp
import com.shilapi.airplaysink.util.Codec
import com.shilapi.airplaysink.util.SeqNo
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

    /**
     * The generation this connection turned out to speak.
     *
     * Reported once per connection at the first request that can only belong to one of them: an
     * ANNOUNCE means RAOP, `/info` or `/pair-setup` means AirPlay 2.
     */
    fun onProtocolNegotiated(protocol: SinkProtocol)
    fun onClientDisconnected()
    fun onStreamingStarted()
    fun onStreamingStopped()
    fun onTrackChanged(title: String?, artist: String?, album: String?)
    fun onVolumeChanged(decibels: Double)
    fun onProgress(current: Int, total: Int)

    /**
     * The playing app's own command list, as decoded from `updateMRSupportedCommands`.
     *
     * [summary] is already human-readable and goes straight into the log; [scrubbing] is the one
     * bit the scan buttons care about. Default is a no-op so an implementation that only tracks the
     * session does not have to care.
     */
    fun onSenderMediaCommands(summary: String, scrubbing: Boolean) {}

    /**
     * The sender offered its DACP reverse control credentials. A null field means "this request did
     * not carry it"; the receiver keeps whatever it learned before.
     */
    fun onDacpCredentials(dacpId: String?, activeRemote: String?, peerAddress: InetAddress)

    /**
     * The sender opened or closed the AirPlay 2 event connection.
     *
     * Separate from [onClientConnected]: the phone can hold a working audio session without ever
     * opening this one, and in that case a remote command has nowhere to go. Without this the UI
     * could only show "AirPlay 2" and leave the user guessing why the buttons do nothing.
     */
    fun onEventChannelChanged(open: Boolean)

    /**
     * The phone's status line for a remote command sent over the AirPlay 2 event connection.
     *
     * `RTSP/1.0 200` is the protocol's way of saying the command was taken; the button press itself
     * only proves the bytes left this device. Both facts are reported because on a head unit they
     * are the difference between "the phone accepted it" and "the phone closed the session".
     */
    fun onAp2CommandReply(status: String)

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
    /**
     * Whether this run answers the AirPlay 2 message set as well as RAOP.
     *
     * Fixed when the receiver starts, because it also decides which mDNS records are published.
     */
    val airPlay2: Boolean = true,
    /**
     * How much audio the receiver holds in front of the speaker, i.e. its own latency.
     *
     * The sender is told this number twice — once as the `Audio-Latency` header of the RECORD
     * reply, and once implicitly by how far ahead it should be streaming — so it has to be the
     * figure playback actually runs at, not the protocol's stock 250 ms.
     */
    val cushionMs: Int = 250,
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

    /**
     * Whether the AirPlay 2 message set is served on this connection.
     *
     * When false, `/info` and `/pair-setup` fall through to the RAOP handlers and the sender stays
     * on classic RAOP. Answering them commits a sender to a protocol whose streaming half may not
     * be what it wants, and the failure mode is "unable to connect" with no fallback — so the
     * choice has to be explicit, not a guess.
     */
    private val ap2Enabled: Boolean get() = config.airPlay2

    private companion object {
        /** What the AirPort Express reports after RECORD: 250 ms at 44.1 kHz. The floor for ours. */
        const val CONTROL_LATENCY_FRAMES = 11025
        const val SILENT_DB = -144.0

        /**
         * Kernel receive buffer for the audio port, about six seconds of stream at 44.1 kHz stereo.
         *
         * The reader drains it as fast as datagrams arrive, so in normal running it sits near empty
         * and costs nothing. It only fills up when this process is descheduled — and that is exactly
         * the moment dropping packets at the socket, rather than holding them for a few
         * milliseconds, is the difference between a hiccup and a hole in the audio.
         */
        const val AUDIO_RECEIVE_BUFFER_BYTES = 1 shl 20

        /**
         * The AirPlay device volume, remembered across control connections.
         *
         * iOS opens a fresh control connection whenever it re-negotiates and only pushes a
         * `volume:` line on *some* of them, so a session that starts at unity gain plays at full
         * blast until the sender happens to send the value. That is heard as "it suddenly got loud
         * after switching tracks", and re-selecting the output cures it. A real receiver has no such
         * hole: shairport-sync keeps this in `config.airplay_volume` and seeds every new session
         * from it (player.c: "set the default volume to whatever it was before, as stored in the
         * config airplay_volume"). Its `GET_PARAMETER volume` answer reads the same value, so it is
         * also what this session must report before the sender says anything.
         */
        @Volatile
        private var rememberedVolumeDb = 0.0

        /** The gain the sender's dB value asks for; -144 dB is mute, not "very quiet". */
        private fun linearGain(decibels: Double): Float =
            if (decibels <= SILENT_DB) {
                0f
            } else {
                10.0.pow(decibels / 20.0).toFloat().coerceIn(0f, 1f)
            }
    }

    /**
     * The socket's own streams, and the pair the rest of this class actually talks to.
     *
     * AirPlay 2 encrypts the control connection in place once pairing has produced a key, so the
     * reader keeps a handle to a switchable wrapper rather than to the socket: swapping what is
     * behind it turns every subsequent byte — including the ones this class writes — into HAP
     * frames, without the RTSP layer knowing.
     */
    private val rawInput: InputStream = socket.getInputStream()
    private val rawOutput: OutputStream = socket.getOutputStream()
    private val input = SwitchableInput(rawInput)
    private val output = SwitchableOutput(rawOutput)
    private val reader = RtspReader(input)

    /** Pairing state for this connection; harmless and unused while the sender speaks RAOP. */
    private val ap2 = Ap2Session(config) { peerDescription }

    /**
     * The AirPlay 2 audio path, built on the first `SETUP` that names streams.
     *
     * Null until then, and unused entirely when [ap2Enabled] is false — the RAOP stream has its own
     * UDP transport further down this file.
     */
    private var ap2Streams: Ap2Streams? = null

    /** The pairing secret, kept because the event channel derives its own keys from it. */
    private var ap2Secret: ByteArray? = null

    @Volatile
    private var encrypted = false

    /** What the UI has already been told about this connection; null until the first proof. */
    @Volatile
    private var reportedProtocol: SinkProtocol? = null

    /** Same string the TXT record advertises as `vs`; every AirTunes-compatible box answers this. */
    private val serverName: String get() = "AirTunes/${config.serverVersion}"

    /** Inside the Apple-Challenge signature; see [AppleChallenge.hardwareAddressFor]. */
    private val hardwareAddress: ByteArray by lazy {
        AppleChallenge.hardwareAddressFor(config.instanceName)
    }

    private var announce: AnnounceInfo? = null

    /**
     * The receiver's own latency, in frames, as the sender is told it.
     *
     * RAOP has exactly one field for this: the `Audio-Latency` header of the RECORD reply
     * (shairport-sync answers it too, and an AirPort Express says 11025 — 250 ms at 44.1 kHz).
     * It describes how far behind the speaker the receiver plays, so it has to follow the cushion
     * actually in use; reporting 250 ms while holding two seconds is a number the sender may
     * believe when it lines this sink up against another room or against video.
     */
    private val audioLatencyFrames: Int
        get() {
            val rate = announce?.sampleRate?.coerceIn(8000, 96000) ?: 44100
            val frames = rate.toLong() * config.cushionMs / 1000
            return frames.coerceIn(CONTROL_LATENCY_FRAMES.toLong(), 96000L * 30).toInt()
        }
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

    /** This session's volume; it starts where the previous session left off, never at full gain. */
    private var volumeDb = rememberedVolumeDb
    private var currentLinearVolume = linearGain(rememberedVolumeDb)

    private var progressCurrent = 0
    private var progressTotal = 0

    /** Last DACP credentials forwarded upstream, so each change is reported exactly once. */
    private var lastDacpId: String? = null
    private var lastActiveRemote: String? = null

    /** Last state reported to the sender's `POST /feedback` poll, to log each change once. */
    private var lastFeedbackReport: String? = null

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

        // AirPlay 2 uses the same connection as RAOP but a different message set: `/info` and the
        // HAP pairing endpoints are paths, not RTSP URIs, so they cannot collide with anything the
        // RAOP side handles.
        if (ap2Enabled && ap2.handles(request.uri)) {
            reportProtocol(SinkProtocol.AP2)
            val response = ap2.handle(request)
            respond(request, response)
            // The reply itself is still plaintext; the encryption starts with whatever the sender
            // sends next. shairport-sync sequences it exactly this way.
            ap2.takePendingSharedSecret()?.let { upgradeToEncrypted(it) }
            return
        }

        // Past pairing the sender is committed to AirPlay 2: its SETUP carries a plist instead of a
        // Transport header, so the RAOP handlers below would reject every one of them.
        if (ap2Enabled && encrypted && handleAp2(request)) return

        when (request.method.uppercase()) {
            "OPTIONS" -> respond(request, optionsResponse())
            "ANNOUNCE" -> respond(request, announceResponse(request))
            "SETUP" -> respond(request, setupResponse(request))
            "RECORD" -> respond(request, recordResponse(request))
            "SET_PARAMETER" -> respond(request, setParameterResponse(request))
            "GET_PARAMETER" -> respond(request, getParameterResponse(request))
            "FLUSH" -> respond(request, flushResponse())
            "SETRATEANCHORTIME" -> respond(request, setRateAnchorTime(request))
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
                // AirPlay 2 handshakes arrive here as `GET /info` and `POST /pair-setup`. Until the
                // pairing code exists these are answered with an empty body, so the bytes are the
                // only record of what the sender actually asked for.
                if (request.content.isNotEmpty()) {
                    // `/command` is a 1.2 kB binary plist that repeats every two seconds. Dumping
                    // it as hex cost a megabyte of log per quarter of an hour and said nothing a
                    // decoded summary cannot, so plists are described and only anything else is
                    // shown as hex, capped.
                    val described = safePlist(request.content)
                    if (described != null) {
                        SinkLog.i(
                            "  ${request.method} ${request.uri} plist: " +
                                BPlistReader.describe(described),
                        )
                        // `updateMRSupportedCommands` is the phone's own statement of what the
                        // playing app will accept. It is the only way this receiver can tell a scan
                        // that was ignored from one that was never going to work.
                        MrCommands.read(described)?.let { report ->
                            SinkLog.i("AP2: MR ${report.describe()}")
                            events.onSenderMediaCommands(report.describe(), report.scrubbing)
                        }
                    } else {
                        SinkLog.i(
                            "  ${request.method} ${request.uri} body: " +
                                Codec.toHex(request.content).take(400),
                        )
                    }
                }
                respond(request, RtspResponse(200))
            }
        }
    }

    /**
     * Reports the generation this connection speaks, once.
     *
     * Several requests prove it — `/info` and the pairing endpoints for AirPlay 2, `ANNOUNCE` for
     * RAOP, then every encrypted request after pairing — but a connection can only ever be one of
     * them, so the UI is woken at the first proof and not again.
     */
    private fun reportProtocol(protocol: SinkProtocol) {
        if (reportedProtocol == protocol) return
        reportedProtocol = protocol
        SinkLog.i(
            "Protocol: ${if (protocol == SinkProtocol.AP2) "AirPlay 2" else "AirPlay 1 (RAOP)"}",
        )
        events.onProtocolNegotiated(protocol)
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

    // ------------------------------------------------------------------ AirPlay 2

    /**
     * Serves the AirPlay 2 methods that differ from their RAOP namesakes.
     *
     * Returns false for everything else, which then goes through the RAOP handlers — `OPTIONS`,
     * `SET_PARAMETER` (volume and metadata travel as text and DMAP in both protocols) and
     * `GET_PARAMETER` are genuinely the same on the wire.
     */
    private fun handleAp2(request: RtspRequest): Boolean {
        // Reaching here means pairing produced a key, which only AirPlay 2 does.
        reportProtocol(SinkProtocol.AP2)
        when (routeOf(request)) {
            "SETUP" -> {
                if (request.content.isEmpty()) return false // a RAOP SETUP; let it through
                respond(request, ap2Setup(request))
            }
            "RECORD" -> respond(request, ap2Record(request))
            // AirPlay 2 states the device volume as a bplist key instead of the `volume: -12.50`
            // text line RAOP uses. Anything else in this method (DMAP artwork, text parameters)
            // falls through to the shared handler, which is why a miss returns false.
            "SET_PARAMETER" -> {
                val level = ap2Volume(request) ?: return false
                applyVolume(level)
                respond(request, RtspResponse(200))
            }
            "FLUSH" -> {
                pipeline?.flush()
                SinkLog.i("AP2: FLUSH — dropped buffered audio")
                respond(request, RtspResponse(200))
            }
            // The buffered stream's way of saying "throw away what you have". The phone sends this
            // when it discards its own backlog — after a seek, or when the audio it has queued is
            // too old to be worth sending — and it names the sequence number the flush runs to.
            //
            // Answering it matters more than it looks. The phone carries on from the new position,
            // so every packet after the flush has a sequence number thousands ahead of the
            // playhead; a receiver that ignores the request reads that as thousands of lost
            // packets, waits for a hole that can never be filled, and keeps playing the old
            // position until it catches up. Measured: `skipped 20028` and an audio track that
            // slowed to a crawl after a single seek.
            "FLUSHBUFFERED" -> {
                flushBufferedAudio(request)
                respond(request, RtspResponse(200))
            }
            // `POST /feedback` is the sender's health probe: "are you actually playing this, and
            // at what rate?". The answer is a `streams` array, and an *empty* answer means "not
            // playing" — which is how a receiver that ignores the question gets its stream torn
            // down by a sender that builds a playback timeline. Every poll measured so far came
            // over AirPlay 2; the classic RAOP sessions here never asked.
            "FEEDBACK" -> respond(request, ap2Feedback())
            // Two different requests wear this name. With a `streams` array it ends the *audio
            // stream* and nothing else, which is what the phone sends when playback pauses; without
            // one it ends the session. Treating the first as the second is what made pause-then-play
            // impossible: the receiver dropped the control connection, so the play command that
            // followed had no channel left to travel on.
            "TEARDOWN" -> {
                if (endsOnlyTheStream(request)) {
                    stopAirPlayStream()
                    respond(request, RtspResponse(200))
                } else {
                    respond(request, RtspResponse(200).addHeader("Connection", "close"))
                    SinkLog.i("AP2: TEARDOWN — the session ends here")
                    closed = true
                }
            }
            // Playback timing. This receiver plays from its own clock and does not align with other
            // AirPlay 2 devices, so the anchor is acknowledged rather than applied.
            "SETRATEANCHORI", "SETRATEANCHOR", "SETPEERS", "SETPEER", "REMOVEPEER" -> {
                if (request.content.isNotEmpty()) {
                    SinkLog.i(
                        "AP2: ${request.method} ${request.content.size}B " +
                            BPlistReader.describe(safePlist(request.content)),
                    )
                }
                respond(request, RtspResponse(200))
            }
            else -> return false
        }
        return true
    }

    /**
     * Answers the sender's `FLUSHBUFFERED`: the audio it names is stale and must not be played.
     *
     * The request carries the sequence number the flush runs to, and — when it is a *deferred*
     * flush — the one it starts at. Without a starting point the whole backlog up to the endpoint
     * is stale, which is the immediate form and the one a seek produces.
     *
     * The endpoint arrives in a wider field than the audio blocks' counter, so it is narrowed
     * before anything compares it — see [SeqNo] for why that matters so much.
     */
    private fun flushBufferedAudio(request: RtspRequest) {
        val plist = safePlist(request.content) as? Map<*, *>
        val rawUntil = (plist?.get("flushUntilSeq") as? Number)?.toLong() ?: -1L
        val rawFrom = (plist?.get("flushFromSeq") as? Number)?.toLong() ?: -1L
        val untilTs = (plist?.get("flushUntilTS") as? Number)?.toLong()
        val untilSeq = rawUntil.takeIf { it >= 0L }?.let { SeqNo.narrow(it) }
        val fromSeq = rawFrom.takeIf { it >= 0L }?.let { SeqNo.narrow(it) }
        if (untilSeq != null) {
            pipeline?.flushStale(fromSeq ?: -1L, untilSeq)
        } else {
            SinkLog.w("AP2: FLUSHBUFFERED without a sequence number; flushing everything")
            pipeline?.flush()
        }
        SinkLog.i(
            "AP2: FLUSHBUFFERED — the sender discarded its backlog" +
                (if (untilTs != null) " up to timestamp $untilTs" else "") +
                "; the playhead moves to the new position" +
                (if (rawUntil != untilSeq) " (endpoint $rawUntil narrowed to $untilSeq)" else ""),
        )
    }

    /**
     * The sender's play/pause clock, and the anchor that goes with it.
     *
     * shairport-sync's `handle_setrateanchortime` reads one bit out of this: `rate & 1` set means
     * the clock is running, and only then does it set `conn->ap2_play_enabled = 1` and send PTP its
     * "B"eginning message. Clear means the clock is stopped and it resets the anchor.
     *
     * Answering 200 and throwing the body away — which is what this did, because the verb reached
     * the catch-all handler — leaves the pipeline playing through the whole of every pause. An
     * AirPlay 2 sender emits no sync packets at all, so this message is the *only* statement any
     * receiver gets about whether the sender thinks it is playing. Each pause is followed about
     * 150 ms later by a `FLUSHBUFFERED`, which empties the queue and the speaker; the loop then
     * writes into that empty cushion until it has nothing left, so the cushion collapses from
     * 500 ms to 1 ms and every pause becomes an audible dropout (measured: 14 underruns in a
     * two-minute session, `Cushion down to 21ms; refilling` twice).
     *
     * The `rate:1` form also carries the only timing anchor an AirPlay 2 session ever supplies —
     * `rtpTime` is the frame sounding at the given network time — so it is worth reading, though
     * without PTP discipline the anchor can only be taken at face value.
     */
    private fun setRateAnchorTime(request: RtspRequest): RtspResponse {
        val plist = safePlist(request.content) as? Map<*, *>
        val rate = (plist?.get("rate") as? Number)?.toLong()
        if (rate == null) {
            SinkLog.w("AP2: SETRATEANCHORTIME without a rate; leaving playback as it was")
            return RtspResponse(200)
        }
        // Bit 0 is the clock's run state. The rest of `rate` is a timeline identifier we have no
        // use for: shairport keeps it, but only this bit changes what the receiver does.
        val running = setRateAnchorRunsClock(rate)
        val rtpTime = (plist?.get("rtpTime") as? Number)?.toLong()
        if (running) {
            // The frame named here is the one sounding at the sender's `networkTime*` instant.
            // Taking that instant as "now" is the best available reading of the anchor; PTP
            // discipline would be needed to do better, and the sender's own latency compensation
            // is not available either, so this only has to be close enough to pace against.
            if (rtpTime != null) {
                pipeline?.setTimingAnchor(rtpTime, System.nanoTime())
            }
        }
        pipeline?.setPlayEnabled(running)
        SinkLog.i(
            "AP2: SETRATEANCHORTIME rate=$rate — the sender's clock is " +
                (if (running) "running" else "stopped") +
                (if (running && rtpTime != null) ", anchored at rtp time $rtpTime" else ""),
        )
        return RtspResponse(200)
    }

    /**
     * The name a request is dispatched under.
     *
     * Most of the protocol is RTSP-shaped: the verb is the method. A handful of AirPlay 2 requests
     * are not — `POST /feedback`, `POST /audioMode`, `POST /command` — and carry the meaning in the
     * path instead. Routing those on the verb alone sent every one of them to the catch-all handler,
     * which answers 200 with an empty body: the sender's health poll never got its `streams` array
     * back, so it was told "this receiver is not playing" twice a second, forever.
     */
    private fun routeOf(request: RtspRequest): String = ap2Route(request.method, request.uri)

    /** A plist the caller is about to describe in the log; null rather than an exception. */
    private fun safePlist(bytes: ByteArray): Any? = try {
        BPlistReader.parse(bytes)
    } catch (_: Exception) {
        null
    }

    /**
     * Whether an AirPlay 2 `TEARDOWN` is aimed at the audio stream or at the whole session.
     *
     * shairport-sync reads it the same way: a `streams` array means "stop the player, keep the
     * connection open", an empty or streams-less plist means "terminate the connection". The
     * distinction is not cosmetic. The phone sends the stream form every time playback stops, so a
     * receiver that closes the connection on it is unreachable for the rest of the pause — and the
     * play command that follows has nowhere to go, which is exactly how "pause works, play does
     * nothing" happens.
     */
    private fun endsOnlyTheStream(request: RtspRequest): Boolean {
        if (request.content.isEmpty()) return false
        val plist = safePlist(request.content) as? Map<*, *>
        if (plist == null) {
            SinkLog.w("AP2: TEARDOWN body is not a readable bplist; treating it as the session end")
            return false
        }
        SinkLog.i("AP2: TEARDOWN plist: ${BPlistReader.describe(plist)}")
        return teardownEndsOnlyTheStream(plist)
    }

    /**
     * Drops the audio side of an AirPlay 2 session and leaves the session itself standing.
     *
     * Everything a remote command needs — the control connection, the pairing secret and the event
     * channel — belongs to the session, not to the stream, so the buttons keep working while the
     * phone is paused. On resume the phone sends another `SETUP`, which opens fresh audio ports
     * because [Ap2Streams.stopAudioStream] forgets the old descriptor.
     */
    private fun stopAirPlayStream() {
        val wasStreaming = streaming
        streaming = false
        pipeline?.let { runCatching { it.close() } }
        pipeline = null
        ap2Streams?.stopAudioStream()
        if (wasStreaming) events.onStreamingStopped()
        SinkLog.i("AP2: audio stream stopped; the session and its event channel stay up")
    }

    /**
     * The volume carried by an AirPlay 2 `SET_PARAMETER`, or null when this request is not one.
     *
     * The value is the same AirPlay device volume RAOP sends as text — 0 dB at the top of the
     * slider, -144 dB for mute — so it goes straight into [applyVolume]. Without this the phone's
     * volume keys do nothing under AP2 while the status card goes on showing the last value RAOP
     * left behind.
     */
    private fun ap2Volume(request: RtspRequest): Double? {
        if (!request.contentType.lowercase().contains("plist")) return null
        val plist = safePlist(request.content) as? Map<*, *> ?: return null
        val level = when (val value = plist["volume"]) {
            is Double -> value
            is Float -> value.toDouble()
            is Long -> value.toDouble()
            is Int -> value.toDouble()
            else -> return null
        }
        // 0 dB is the top of the scale and mute is -144 dB, so a positive number means the sender
        // used some other unit. Refusing it leaves the current volume alone — clamping it to 0 dB
        // would turn an unrecognised value into full blast, which is the one failure nobody wants.
        if (level > 0.0) {
            SinkLog.w("AP2: volume $level is above 0 dB, so it is not a dB value; ignoring it")
            return null
        }
        return level
    }

    private fun ap2Setup(request: RtspRequest): RtspResponse {
        val plist = try {
            BPlistReader.parseDictionary(request.content)
        } catch (error: Exception) {
            SinkLog.e("AP2: SETUP body is not a readable bplist: ${error.message}")
            null
        }
        if (plist == null) {
            SinkLog.w("AP2: SETUP without a usable plist; falling back to the RAOP handler")
            return setupResponse(request)
        }

        val streams = ap2Streams ?: createAp2Streams().also { ap2Streams = it }
        val body = streams.setup(plist) ?: return RtspResponse(451)
        SinkLog.i("AP2: SETUP answered with ${body.size}B bplist")
        return RtspResponse(200)
            .addHeader("Content-Type", "application/x-apple-binary-plist")
            .also { it.content = body }
    }

    /**
     * `RECORD` opens the session; the audio itself arrives on the TCP port from the second SETUP.
     *
     * The order is the sender's, not ours: it records before it names the streams, so a RECORD
     * with no stream yet is the normal case and must be answered, not refused. Refusing it here is
     * what made the sender treat the whole session as unsupported and hang up.
     *
     * `Audio-Latency: 0` is what the working implementations return; AirPlay 2 states the real
     * latency in the timing anchor instead.
     */
    /**
     * Answers the sender's playback poll.
     *
     * shairport-sync's `handle_feedback` is the reference and it is blunt: when the player is
     * running it returns `{streams:[{type,sr}]}` with the real stream type and sample rate, and
     * when it is not it returns nothing at all. An empty body is therefore not "OK, no news" — it
     * is the receiver saying *it is not playing*. A sender that builds a timeline from this
     * (Apple Music does; QQ Music evidently does not) stops its own playback on that answer, which
     * looks exactly like a receiver that died: the stream is set up, a second of audio arrives,
     * and the phone sends a stream-level TEARDOWN.
     *
     * The rate is a plist real, not an integer — that is what shairport writes.
     */
    private fun ap2Feedback(): RtspResponse {
        val stream = ap2Streams?.currentStream()
        if (!streaming || stream == null) {
            if (lastFeedbackReport != "idle") {
                lastFeedbackReport = "idle"
                SinkLog.i("AP2: /feedback — nothing is playing; answering with an empty body")
            }
            return RtspResponse(200)
        }
        val (type, rate) = stream
        val report = "$type/$rate"
        if (lastFeedbackReport != report) {
            lastFeedbackReport = report
            SinkLog.i("AP2: /feedback — playing stream type $type at $rate Hz; saying so")
        }
        return RtspResponse(200)
            .addHeader("Content-Type", "application/x-apple-binary-plist")
            .also { it.content = ap2FeedbackBody(type, rate) }
    }

    private fun ap2Record(request: RtspRequest): RtspResponse {
        if (request.content.isNotEmpty()) {
            SinkLog.i(
                "AP2: RECORD ${request.content.size}B ${BPlistReader.describe(safePlist(request.content))}",
            )
        }
        val audioPipeline = pipeline
        if (audioPipeline == null) {
            SinkLog.i("AP2: RECORD — session open, waiting for the stream SETUP")
        } else if (!streaming) {
            audioPipeline.start()
            audioPipeline.setVolume(currentLinearVolume)
            streaming = true
            events.onStreamingStarted()
            SinkLog.i(
                String.format(
                    java.util.Locale.US,
                    "AP2: RECORD — playback started at gain %.4f (%.2f dB)",
                    currentLinearVolume,
                    volumeDb,
                ),
            )
        }
        return RtspResponse(200).addHeader("Audio-Latency", "0")
    }

    private fun createAp2Streams(): Ap2Streams {
        val secret = ap2Secret
        if (secret == null) {
            SinkLog.w("AP2: a stream was set up without a pairing secret; audio stays unencrypted")
        }
        return Ap2Streams(
            localAddress = socket.localAddress,
            sharedSecret = secret ?: ByteArray(0),
            onFormat = { format ->
                SinkLog.i(
                    "AP2: audio format ${format.codec.label} ${format.sampleRate}Hz " +
                        "${format.channels}ch spf=${format.framesPerPacket} " +
                        "ct=${format.compressionType} " +
                        "audioFormat=0x%08X".format(format.audioFormat),
                )
                // A second SETUP means a new stream, and the old pipeline owns an AudioTrack and,
                // for AAC, a platform codec. Hand both back before taking new ones.
                pipeline?.let { runCatching { it.close() } }
                pipeline = AudioPipeline(
                    sampleRate = format.sampleRate.coerceIn(8000, 96000),
                    channels = format.channels.coerceIn(1, 2),
                    framesPerPacket = format.framesPerPacket.coerceIn(16, 4096),
                    cushionMs = config.cushionMs,
                    codec = format.codec,
                )
            },
            onAudio = { sequence, timestamp, payload ->
                val audioPipeline = pipeline
                if (audioPipeline != null) {
                    // A sender may open the data port before it sends RECORD. Starting here keeps
                    // the first second of audio from being dropped while waiting for it.
                    if (!streaming) {
                        audioPipeline.start()
                        audioPipeline.setVolume(currentLinearVolume)
                        streaming = true
                        events.onStreamingStarted()
                        SinkLog.i("AP2: playback started on the first audio frame")
                    }
                    audioPipeline.submit(sequence, timestamp, payload)
                }
            },
            onEventChannel = { open ->
                SinkLog.i(
                    if (open) {
                        "AP2: event channel up — remote commands have somewhere to go"
                    } else {
                        "AP2: event channel down — remote commands cannot be sent until it returns"
                    },
                )
                events.onEventChannelChanged(open)
            },
            onCommandReply = { status -> events.onAp2CommandReply(status) },
            // shairport-sync opens the event channel by posting `updateInfo` (ap2_event_receiver.c
            // does it the instant the connection is accepted), and this receiver used to stay
            // silent. The earlier attempt at sending it here measured nothing: it ran with the
            // mirrored event key pair, so the disconnect it caused was the encryption, not the
            // message. With the keys right, the worst case is the phone ignoring it.
            infoPlist = { ap2?.infoPlist() },
        )
    }

    /**
     * Sends a transport command to the sender over AirPlay 2's own remote control channel.
     *
     * The group UUID is normally taken from the initial SETUP; the fallback is the pairing id so a
     * command still has a well-formed destination when the sender sent no `groupUUID`.
     */
    fun sendAp2Remote(command: DacpClient.Command): Ap2Streams.Delivery {
        val number = Ap2Remote.number(command)
        if (number == null) {
            SinkLog.w("AP2: ${command.path} has no media remote command number")
            return Ap2Streams.Delivery.NO_STREAM
        }
        val streams = ap2Streams
        if (streams == null) {
            SinkLog.w("AP2: remote command ${command.path} needs a stream; none is set up yet")
            return Ap2Streams.Delivery.NO_STREAM
        }
        val delivery = streams.sendRemoteCommand(
            number,
            Ap2Session.pairingId(config.deviceId),
        )
        SinkLog.i("AP2: remote command ${command.path} (#$number) result=$delivery")
        return delivery
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

        // A readable ANNOUNCE is the point where this is provably RAOP: AirPlay 2 never sends one,
        // it describes its streams in a plist SETUP instead.
        reportProtocol(SinkProtocol.AP1)
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
        val senderTiming = InetSocketAddress(socket.inetAddress, timingPort)
        releaseAudioResources()

        // Wildcard bindings, in the sender's own address family — see openDatagramSocket.
        val data = openDatagramSocket(socket.inetAddress, AUDIO_RECEIVE_BUFFER_BYTES)
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
            cushionMs = config.cushionMs,
        )
        val audioTransport = RaopAudioTransport(
            audioSocket = data,
            controlSocket = control,
            timingSocket = timing,
            senderControl = senderControl,
            senderTiming = senderTiming,
            pipeline = audioPipeline,
            sessionKey = sessionKey,
            sessionIv = info.aesIv,
            // The retransmission window is what is left of the cushion once the speaker's share is
            // taken out — shairport-sync's `conn->latency = stream latency - backend buffer`
            // (`rtp.c:1656`). A hole can be chased for that long, because that is how long the
            // audio sits in the queue before the playhead reaches it.
            queueWindowMs = (config.cushionMs - audioPipeline.backendBufferMs).coerceAtLeast(0L),
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
        SinkLog.i(
            "SETUP: cushion ${config.cushionMs}ms, retransmission window " +
                "${audioTransport.queueWindowMs}ms, Audio-Latency will be $audioLatencyFrames frames",
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
            return RtspResponse(200).addHeader("Audio-Latency", audioLatencyFrames.toString())
        }

        audioPipeline.start()
        audioTransport.start()
        audioPipeline.setVolume(currentLinearVolume)
        streaming = true
        events.onStreamingStarted()
        // The gain printed here is the one the session actually plays at: when the sender never
        // sends a `volume:` line this stays 1.0000, which is the difference between "loud" and
        // "quiet after the reconnect" in a single line of the log.
        SinkLog.i(
            String.format(
                java.util.Locale.US,
                "RECORD: playback started at gain %.4f (%.2f dB); " +
                    "reporting Audio-Latency %d frames (%d ms cushion)",
                currentLinearVolume,
                volumeDb,
                audioLatencyFrames,
                config.cushionMs,
            ),
        )

        return RtspResponse(200)
            .addHeader("Audio-Latency", audioLatencyFrames.toString())
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
        rememberedVolumeDb = volumeDb
        currentLinearVolume = linearGain(volumeDb)
        // The value the sender sends is the *AirPlay device* volume: 0.00 dB at the top of the
        // slider down to -30.00 dB at the bottom, with -144.00 meaning mute (shairport-sync's
        // `pvol` note). Log it verbatim together with the gain we end up applying — "it got quieter
        // after X" is otherwise impossible to attribute to either end of the link.
        SinkLog.i(
            String.format(
                java.util.Locale.US,
                "Volume: sender asked for %.2f dB -> gain %.4f (%s)",
                decibels,
                currentLinearVolume,
                if (pipeline == null) "no audio output yet, carried to RECORD" else "applied now",
            ),
        )
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

    /**
     * Switches the control connection to HAP framing.
     *
     * Called once, right after the pairing reply has been written. Anything still buffered in the
     * reader would be plaintext by definition — the sender cannot have sent encrypted bytes before
     * it saw our reply — so the swap is safe as long as it happens between two requests.
     */
    private fun upgradeToEncrypted(sharedSecret: ByteArray) {
        if (encrypted) return
        ap2Secret = sharedSecret
        val link = Ap2SecureLink(rawInput, rawOutput, sharedSecret)
        input.delegate = link.input
        output.delegate = link.output
        encrypted = true
        SinkLog.i("AP2: control channel switched to encrypted frames")
    }

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
     *
     * [receiveBufferBytes] has to be set *before* the bind to size the real buffer; the kernel
     * doubles it at bind time and clamps it to `net.core.rmem_max`.
     */
    private fun openDatagramSocket(peer: InetAddress, receiveBufferBytes: Int = 0): DatagramSocket {
        val wildcard = InetAddress.getByName(if (peer is Inet6Address) "::" else "0.0.0.0")
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        if (receiveBufferBytes > 0) {
            runCatching { socket.receiveBufferSize = receiveBufferBytes }
                .onFailure { SinkLog.w("Could not size the receive buffer: ${it.message}") }
        }
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
        ap2Streams?.let { runCatching { it.close() } }
        ap2Streams = null
        releaseAudioResources()
        if (wasStreaming) events.onStreamingStopped()
        announce = null
        sessionKey = null
    }
}

/**
 * Whether an AirPlay 2 `SETRATEANCHORTIME` says the sender's clock is running.
 *
 * shairport-sync's `handle_setrateanchortime` reads exactly one bit: `rate & 1` set means the clock
 * runs, and only then does it set `conn->ap2_play_enabled = 1` and tell PTP the clock is "B"eginning.
 * Clear means stopped, and it resets the anchor.
 *
 * A free function so a unit test can pin it without a socket. Getting this backwards is silent and
 * expensive: a receiver that treats a pause as a play keeps writing into the cushion the paired
 * `FLUSHBUFFERED` just emptied, which is audible as a dropout on every seek.
 */
internal fun setRateAnchorRunsClock(rate: Long): Boolean = (rate and 1L) != 0L

/**
 * Whether an AirPlay 2 `TEARDOWN` body is aimed at the audio stream rather than the session.
 *
 * The rule is shairport-sync's (`handle_teardown_2`): a dictionary carrying a `streams` array stops
 * the player and leaves the connection alone; anything else — an empty body, an unreadable plist,
 * or a plist without that key — ends the connection. Kept as a free function so a unit test can
 * pin it without a socket: getting it backwards is silent, and it breaks resume rather than
 * anything that shows up in a build.
 */
internal fun teardownEndsOnlyTheStream(plist: Any?): Boolean =
    (plist as? Map<*, *>)?.get("streams") is List<*>

/**
 * The body of a `POST /feedback` answer: `{streams:[{type, sr}]}`.
 *
 * Kept as a pure function because two details in it are silent when wrong. `sr` has to be a plist
 * *real* (shairport-sync writes `plist_new_real`), and `type` has to be the stream type the SETUP
 * named — 96 realtime, 103 buffered — not a boolean "yes, playing". Either mistake produces a plist
 * that parses fine and says nothing useful.
 */
internal fun ap2FeedbackBody(type: Long, rate: Int): ByteArray = BPlist.write(
    linkedMapOf<String, Any?>(
        "streams" to listOf(
            linkedMapOf<String, Any?>("type" to type, "sr" to rate.toDouble()),
        ),
    ),
)

/**
 * The dispatch name for a request: the verb for RTSP-shaped requests, the last path segment for the
 * handful of AirPlay 2 requests that are addressed by path (`POST /feedback`, `POST /audioMode`,
 * `POST /command`).
 *
 * Kept out of the session so it can be tested: getting it wrong is silent. The poll still gets a
 * 200, the log still looks healthy, and the only symptom is that the sender believes the receiver
 * is not playing.
 */
internal fun ap2Route(method: String, uri: String): String {
    val verb = method.uppercase()
    if (verb != "POST") return verb
    return uri.trimEnd('/').substringAfterLast('/').uppercase()
}

private fun String.intValueAfter(marker: String): Int? {
    val index = indexOf(marker)
    if (index < 0) return null
    val rest = substring(index + marker.length)
    val digits = rest.takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}

/**
 * An [InputStream] whose source can be replaced while readers keep their reference.
 *
 * AirPlay 2 needs this because the control connection is upgraded in place: [RtspReader] is built
 * once with the plaintext socket stream, and after pairing the same object must return decrypted
 * bytes from the HAP frame reader.
 */
private class SwitchableInput(var delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

/** The write side of [SwitchableInput]. */
private class SwitchableOutput(var delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
