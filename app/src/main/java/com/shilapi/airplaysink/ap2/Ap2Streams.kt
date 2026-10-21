package com.shilapi.airplaysink.ap2

import android.os.SystemClock
import com.shilapi.airplaysink.audio.AudioCodec
import com.shilapi.airplaysink.audio.Resend
import com.shilapi.airplaysink.audio.ResendTracker
import com.shilapi.airplaysink.net.LocalNetwork
import com.shilapi.airplaysink.rtsp.RtspReader
import com.shilapi.airplaysink.rtsp.RtspResponse
import com.shilapi.airplaysink.rtsp.writeRtsp
import com.shilapi.airplaysink.util.SeqNo
import com.shilapi.airplaysink.util.SinkLog
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The AirPlay 2 audio half: the SETUP exchange, the event channel and the audio stream.
 *
 * AirPlay 2 sets up in two steps on the same control connection. The first `SETUP` carries no
 * `streams` array — it only asks for an event port, and the reply tells the sender where to report
 * playback events. The second one names the streams it wants and expects the ports back, already
 * listening, because the sender connects the instant it reads the reply.
 *
 * Two stream types are carried, because the sender picks between them and this receiver has to
 * accept whichever it is told:
 *
 * - `96` realtime: plain RTP over UDP, arriving at playback pace.
 * - `103` buffered: TCP, framed as
 *   `uint16 length ‖ RTP header ‖ ChaCha20-Poly1305 ciphertext ‖ tag ‖ nonce`, so a slow link
 *   stretches delivery without losing packets.
 *
 * Both are encrypted the same way: the shared secret from pairing, keyed through the SETUP's `shk`,
 * with the 8-byte nonce sent in the packet padded out to the 12 bytes ChaCha20-Poly1305-IETF wants.
 *
 * The event connection can also carry remote commands back to the sender (see [sendCommand]), and
 * that direction works: a transient-paired, audio-only session is allowed to drive the phone, with
 * no MFi identity, HomeKit pairing or HID device anywhere. The one thing that has to be exactly
 * right is the key direction — [ChannelKeys.EVENTS], not the mirror of the control channel. With the
 * wrong pair, iOS fails the Poly1305 check on the first frame out and closes the event connection
 * *and* the control connection within tens of milliseconds, which reads like a refusal but is not.
 */
class Ap2Streams(
    private val localAddress: InetAddress,
    private val sharedSecret: ByteArray,
    /** Fires once, as soon as the SETUP names an audio format, so the caller can build a decoder. */
    private val onFormat: (Format) -> Unit,
    /** One decrypted audio frame: RTP sequence, RTP timestamp and the codec payload. */
    private val onAudio: (sequence: Long, timestamp: Long, payload: ByteArray) -> Unit,
    /**
     * Fires when the sender's event connection comes up and when it goes away.
     *
     * Remote commands travel on that connection, so the UI has to be able to tell "no session" apart
     * from "session, but the phone never opened the channel commands use".
     */
    private val onEventChannel: (open: Boolean) -> Unit = {},
    /**
     * Fires with the status line of the phone's reply to a command we sent.
     *
     * This is the only positive confirmation the protocol offers: `RTSP/1.0 200` on the event
     * connection means the command was taken. Anything else — or a reply that never comes because
     * the connection died — has to be visible on a head unit where the toast is swallowed.
     */
    private val onCommandReply: (status: String) -> Unit = {},
    /**
     * The device description to post as `updateInfo` the moment the event channel comes up.
     *
     * shairport-sync sends this before it does anything else on that connection, and a sender may
     * take it as the receiver announcing itself: Apple Music will sit in "session open" forever
     * without ever asking for an audio stream, which is indistinguishable from a dead receiver.
     * Null means "this session has no description to send" and skips the message.
     */
    private val infoPlist: (() -> Map<String, Any?>?)? = null,
) : Closeable {

    /** Where a `POST /command` ended up. Every value is reported, so none of them is a guess. */
    enum class Delivery {
        /** Written into the encrypted channel. Whether the phone acted on it is a separate matter. */
        SENT,

        /** The phone never opened the event connection, or it has already gone away. */
        NO_EVENT_CHANNEL,

        /** The write itself failed — the socket is broken, not merely idle. */
        WRITE_FAILED,

        /** No AirPlay 2 stream has been set up on this session yet. */
        NO_STREAM,
    }

    companion object {
        const val STREAM_TYPE_REALTIME = 96L
        const val STREAM_TYPE_BUFFERED = 103L

        /**
         * What the reply promises the sender may buffer.
         *
         * The sender takes this literally and keeps this much audio in flight, so it is also the
         * price of a stall: bigger survives more jitter, but seeks and pauses take longer to take
         * effect. 256 KiB is roughly two seconds of 16-bit ALAC stereo.
         */
        const val AUDIO_BUFFER_BYTES = 256 * 1024L

        /** Compression types from the AirPlay spec; ALAC is 2 and AAC-LC is 4. */
        const val COMPRESSION_ALAC = 2L
        const val COMPRESSION_AAC = 4L

        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val RTP_HEADER_BYTES = 12
        private const val TAG_BYTES = 16
        private const val NONCE_BYTES = 8
        private const val OVERHEAD = RTP_HEADER_BYTES + TAG_BYTES + NONCE_BYTES

        /** Apple's retransmission reply, the same type the RAOP control socket carries. */
        private const val TYPE_RESEND_REPLY = 0x56

        /**
         * How often the audio thread wakes while nothing is arriving.
         *
         * A hole at the end of a burst has no following packet to ride on, so without a tick of its
         * own its request would wait for the next packet — which may be the very one that never
         * comes.
         */
        private const val IDLE_TICK_MS = 20
    }

    /** The minimum the decoder needs; anything else in the SETUP is ignored. */
    data class Format(
        val sampleRate: Int,
        val channels: Int,
        val framesPerPacket: Int,
        val compressionType: Long,
        val audioFormat: Long,
        val codec: AudioCodec,
    )

    private val random = SecureRandom()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private var eventServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private var realtimeSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null

    private var audioKey: ByteArray? = null

    /**
     * The sender's event connection, kept so commands can travel back over it.
     *
     * AirPlay 2 has no separate "command" socket: the phone opens this one for playback events and
     * accepts `POST /command` on it, which is how shairport-sync sends `sendMediaRemoteCommand`.
     */
    @Volatile
    private var eventLink: Ap2SecureLink? = null

    /** One command at a time: [Ap2SecureLink.output] frames are stateful, so writes must not meet. */
    private val eventWriteLock = Any()

    /** Commands written on this channel, for the log only — no `CSeq` goes on the wire. */
    private var commandsSent = 0L

    /** Samples per packet, used to turn the realtime timestamp into an ordering counter. */
    @Volatile
    private var audioFramesPerPacket = 352

    /**
     * The sender's `groupUUID`, taken from the initial SETUP.
     *
     * Every `sendMediaRemoteCommand` has to name the group it is addressed to, and this is the only
     * place the phone tells us which group that is. Null until the initial SETUP arrives.
     */
    @Volatile
    private var groupId: String? = null

    /** Set once the buffered stream exists, so a second SETUP does not open a second port pair. */
    private var streamDescriptor: Map<String, Any?>? = null

    /** The codec the current stream announced, for the first-frame cross-check against the SSRC. */
    @Volatile
    private var streamCodec = AudioCodec.ALAC

    /** What the last SETUP named, reported back to `POST /feedback`. */
    @Volatile
    private var streamType = STREAM_TYPE_REALTIME

    @Volatile
    private var streamRate = 44100

    private var audioFrames = 0
    private var realtimePackets = 0

    /**
     * Retransmission, for the realtime stream only.
     *
     * The buffered stream is TCP: the kernel repeats anything lost, so asking again there would
     * only duplicate what is already on its way.
     */
    private val resend = ResendTracker()

    /** Where the sender takes requests. Both come from the stream SETUP and the audio it sends. */
    private var senderControlPort = 0
    private var senderAddress: InetAddress? = null

    /** Per-stream counters, so each line in the log describes one play, not the process. */
    private var resendRequests = 0L
    private var resendReplies = 0L

    /** When the socket may be tried again after a failed send; see [sendResend]. */
    private var resendBlockedUntil = 0L

    /**
     * Answers one `SETUP`. The plist is returned as the reply body, or null when the request names
     * nothing this receiver can carry — in which case the caller should answer 451 rather than
     * pretend a stream exists.
     */
    fun setup(plist: Map<String, Any?>): ByteArray? {
        @Suppress("UNCHECKED_CAST")
        val streams = plist["streams"] as? List<Map<String, Any?>>

        if (streams == null) {
            val protocol = plist["timingProtocol"] as? String
            SinkLog.i(
                "AP2: SETUP (initial) timingProtocol=${protocol ?: "-"} keys=${plist.keys}",
            )
            // The group every remote command has to be addressed to. Phones send it here; a
            // receiver that never reads it cannot build a valid sendMediaRemoteCommand.
            (plist["groupUUID"] as? String)?.let { gid ->
                groupId = gid
                SinkLog.i("AP2: group UUID $gid — remote commands can name it")
            } ?: SinkLog.w("AP2: no groupUUID in the initial SETUP")
            val eventPort = openEventChannel()
            val response = linkedMapOf<String, Any?>(
                "eventPort" to eventPort.toLong(),
                // PTP is the sender's own clock; there is no NTP socket to point it at. shairport-sync
                // answers 0 here as well, and iOS treats it as "nothing extra to sync with".
                "timingPort" to 0L,
            )
            (plist["timingPeerInfo"] as? Map<*, *>)?.let { peer ->
                SinkLog.i("AP2: SETUP asks for timingPeerInfo: ${BPlistReader.describe(peer)}")
                response["timingPeerInfo"] = timingPeerInfoAnswer()
            }
            return BPlist.write(response)
        }

        SinkLog.i("AP2: SETUP (streams) ${streams.size} stream(s): ${BPlistReader.describe(streams)}")
        val descriptors = ArrayList<Map<String, Any?>>(streams.size)
        for (stream in streams) {
            val descriptor = setupStream(stream) ?: continue
            descriptors.add(descriptor)
        }
        if (descriptors.isEmpty()) {
            SinkLog.w("AP2: SETUP named no stream this receiver can carry")
            return null
        }
        return BPlist.write(linkedMapOf<String, Any?>("streams" to descriptors))
    }

    // ------------------------------------------------------------------ event channel

    /**
     * The `timingPeerInfo` half of the SETUP answer: who we are, and the addresses to use for it.
     *
     * The address named is the receiver's own **IPv6 link-local address, with its zone**, and it is
     * the only one named. The reasoning is a measurement, and it took three attempts to get it right
     * because two plausible-looking rules both fit parts of the data and neither fit all of it.
     *
     * What was ruled out, each time by changing one thing and watching the outcome:
     *
     * - "The address must be the one the connection arrived on." Falsified by four handshakes in which
     *   the phone reached us over IPv4, we named that same IPv4, and all four were dropped a
     *   millisecond after the event channel opened.
     * - "The address must be the one the receiver is advertised on" (the IPv4 in the mDNS record).
     *   Falsified the same way, and by the same four handshakes.
     * - "Name several addresses and let the sender pick." Falsified by four handshakes carrying every
     *   interface address: 0 of 4 went on to `RECORD`, against 9 of 9 for a single link-local entry.
     *
     * What survives is narrower and simpler: name one address, and make it the link-local one.
     * Across 30-odd handshakes the sessions that reached `RECORD` are exactly the ones that named
     * link-local — nine for nine on the phone hotspot, and two for two on the phone's own Wi-Fi.
     * Every session that named a global unicast address or an IPv4 address was dropped at the same
     * millisecond, on every network tried.
     *
     * The address family is the load-bearing part. AirPlay 2's timing is PTP, and PTP runs over
     * IPv6 link-local multicast; a sender that has been handed an IPv4 or a global address to be
     * timed on has nothing it can synchronize against, and it abandons the whole session rather
     * than fall back. The link-local address is also the one address that is guaranteed to be
     * reachable on the link the sender is already on, whatever the sender's own address situation
     * is — which matters because the sender picks where to connect, and it may pick an address that
     * is fine for TCP and useless for timing.
     *
     * The zone is part of the address. `fe80::…` without it names no interface, and the sender has no
     * way to guess which one was meant.
     */
    internal fun timingPeerInfoAnswer(): Map<String, Any?> {
        val selfAddress = localAddress.hostAddress.orEmpty()
        val others = LocalNetwork.interfaceAddressStrings()
        val answer = timingPeerInfoAnswer(selfAddress, others)
        val named = answer["ID"]
        // Logged here rather than in the free function: this one is the caller, and the function stays
        // a pure value-for-value transform that a JVM unit test can run (android.util.Log is a stub
        // off-device and throws).
        if (named == selfAddress) {
            SinkLog.i("AP2: timingPeerInfo names $named")
        } else {
            SinkLog.i(
                "AP2: the sender arrived on $selfAddress but timingPeerInfo names $named — " +
                    "the arrival address is not the one we can be timed on. Device has: $others",
            )
        }
        return answer
    }

    /**
     * Builds the `timingPeerInfo` half of the SETUP answer: who we are, and the addresses we answer on.
     *
     * `ID` is the address this connection actually arrived on, and `Addresses` starts with that same
     * address. Beyond it the list is deliberately left empty for now — see the free function of the
     * same name, which is where the reasoning lives and where it is tested.
     *
     * The interface addresses are still collected and logged on every run, because that log line is
     * the only place the device's whole address situation is visible; that is a diagnostic, not
     * something to hand the sender.
     */

    /**
     * Opens the port the sender reports playback events on.
     *
     * It is a plain RTSP connection, encrypted with the pairing secret under the `Events-Salt`
     * keys. Nothing here acts on those messages — volume and metadata come from the control
     * connection — but the port has to exist and answer, because a sender that cannot open it
     * treats the receiver as unable to start a session.
     */
    private fun openEventChannel(): Int {
        val existing = eventServer
        if (existing != null) return existing.localPort

        val server = ServerSocket(0, 4, localAddress)
        eventServer = server
        SinkLog.i("AP2: event port opened at ${localAddress.hostAddress}:${server.localPort}")
        thread(name = "ap2-events", isDaemon = true) {
            while (!closed.get()) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(name = "ap2-event-conn", isDaemon = true) { serveEvents(client) }
            }
        }
        return server.localPort
    }

    private fun serveEvents(client: Socket) {
        SinkLog.i("AP2: sender opened the event channel from ${client.remoteSocketAddress}")
        val link = Ap2SecureLink(
            client.getInputStream(),
            client.getOutputStream(),
            sharedSecret,
            ChannelKeys.EVENTS,
        )
        val reader = RtspReader(link.input)
        eventLink = link
        onEventChannel(true)
        announceSelf()
        try {
            while (!closed.get()) {
                val request = reader.readRequest() ?: break

                // The phone answers our own commands on this same socket. A reply is not a request
                // and must not be replied to, or the two sides bounce responses at each other. Its
                // status is the only evidence a command was accepted: 200 means taken, silence
                // followed by a closed socket means refused.
                if (request.method.startsWith("RTSP/")) {
                    val status = "${request.method} ${request.uri}".trim()
                    SinkLog.i(
                        "AP2: events <- command reply $status " +
                            "cseq=${request.cseq} body=${request.content.size}B",
                    )
                    onCommandReply(status)
                    continue
                }

                SinkLog.i(
                    "AP2: events <- ${request.method} ${request.uri} " +
                        "(${request.content.size}B ${request.contentType})",
                )
                if (request.content.isNotEmpty()) {
                    SinkLog.i("AP2: events   body ${request.content.toString(Charsets.ISO_8859_1).take(200)}")
                }
                link.output.writeRtsp(RtspResponse(200), request.cseq, "AirTunes/366.0")
            }
        } catch (error: Exception) {
            if (!closed.get()) SinkLog.w("AP2: event channel ended: ${error.message}")
        } finally {
            // A phone that reconnects opens a second connection before the first one's close is
            // noticed. Only the current link may report, or the UI would flip to "no channel"
            // while a perfectly good one is open.
            if (eventLink === link) {
                eventLink = null
                onEventChannel(false)
            }
            runCatching { client.close() }
            SinkLog.i("AP2: event channel closed")
        }
    }

    // ------------------------------------------------------------------ remote control

    /**
     * Posts `updateInfo` — the same device description `/info` answers with — as the first message
     * on the event channel.
     *
     * shairport-sync's `ap2_event_receiver` does exactly this, immediately after accepting the
     * connection. Senders differ in whether they wait for it, and a receiver that stays silent
     * gives a waiting sender no way to proceed — with nothing on the control connection to show it.
     */
    private fun announceSelf() {
        val value = infoPlist?.invoke() ?: return
        val delivery = sendCommand(
            linkedMapOf<String, Any?>("type" to "updateInfo", "value" to value),
        )
        SinkLog.i("AP2: updateInfo posted on the event channel -> $delivery")
    }

    /**
     * Sends one transport command as an AirPlay 2 `sendMediaRemoteCommand`.
     *
     * [fallbackGroupId] is only used when the phone's SETUP carried no `groupUUID`. Trying anyway is
     * the point: the reply — 200, or silence and a closed socket — is the measurement.
     */
    fun sendRemoteCommand(number: Int, fallbackGroupId: String): Delivery {
        val gid = groupId ?: fallbackGroupId.also {
            SinkLog.w("AP2: no group UUID from the sender; addressing remote commands to $it")
        }
        return sendCommand(Ap2Remote.command(number, gid))
    }

    /**
     * One `POST /command` on the event connection.
     *
     * No `updateInfo` is sent first, and that is a measurement rather than an omission: the reply to
     * the command itself is the confirmation this receiver relies on, and on 2026-10-20 the phone
     * answered `RTSP/1.0 200` to every bare command without one. shairport-sync does open the
     * channel with `updateInfo`; this receiver never sends it, so nothing has been measured about
     * how iOS reacts to it *with the right keys* — the earlier "it closes the connection" result came
     * from running it with the mirrored key pair, and says nothing about the message.
     */
    fun sendCommand(command: Map<String, Any?>): Delivery = synchronized(eventWriteLock) {
        val link = eventLink
        if (link == null) {
            SinkLog.w("AP2: command ${command["type"]} dropped — the event channel is not open")
            return@synchronized Delivery.NO_EVENT_CHANNEL
        }
        commandsSent++
        val body = BPlist.write(command)
        // No CSeq here, deliberately: shairport-sync's ap2_event_port_post_command sends only
        // Content-Length and Content-Type. The event port is not the RTSP control channel and the
        // phone parses this as an RTSP-flavoured envelope, so an extra header is a real difference
        // and not a harmless one.
        val head = "POST /command RTSP/1.0\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\n\r\n"
        SinkLog.i(
            "AP2: command -> ${command["type"]} #$commandsSent body=${body.size}B " +
                command.filterKeys { it != "params" && it != "value" },
        )
        try {
            // One write so the header and the plist land together; a half-written command is a
            // command the phone cannot parse. [Ap2SecureLink.output] serialises writes itself.
            val bytes = head.toByteArray(Charsets.ISO_8859_1) + body
            link.output.write(bytes)
            Delivery.SENT
        } catch (error: Exception) {
            SinkLog.w("AP2: command ${command["type"]} failed: ${error.message}")
            Delivery.WRITE_FAILED
        }
    }

    // ------------------------------------------------------------------ audio stream

    private fun setupStream(stream: Map<String, Any?>): Map<String, Any?>? {
        val type = stream["type"] as? Long
        if (type != STREAM_TYPE_REALTIME && type != STREAM_TYPE_BUFFERED) {
            SinkLog.w("AP2: stream type $type is not handled; only audio 96/103 is carried")
            return null
        }

        val existing = streamDescriptor
        if (existing != null) {
            SinkLog.i("AP2: reusing the stream already set up on port ${existing["dataPort"]}")
            return existing
        }

        // The SETUP's own fields, not guesses: a buffered AAC stream from Apple Music carries no
        // `sr` at all, so falling back to 44100 would play its 48 kHz audio a semitone and a half
        // flat — and at the wrong pace, which the timeline pacing then spends the session
        // correcting. The mask names the format outright: 0x00800000 is AAC-LC/48000/stereo.
        val compression = stream["ct"] as? Long ?: COMPRESSION_ALAC
        val audioFormat = stream["audioFormat"] as? Long ?: 0L
        val declared = AudioFormatMask.of(audioFormat)
        val sampleRate = declared?.sampleRate ?: (stream["sr"] as? Long)?.toInt() ?: 44100
        val framesPerPacket = (stream["spf"] as? Long)?.toInt() ?: 352
        audioFramesPerPacket = framesPerPacket
        streamType = type
        streamRate = sampleRate
        val channels = declared?.channels ?: channelsFor(audioFormat)
        val codec = when (compression) {
            COMPRESSION_AAC -> AudioCodec.AAC
            COMPRESSION_ALAC -> AudioCodec.ALAC
            // Anything else is a format this receiver has no decoder for. Naming it ALAC keeps the
            // session alive and makes the failure audible rather than silent; the log says which.
            else -> AudioCodec.ALAC
        }
        streamCodec = codec
        val key = stream["shk"] as? ByteArray

        SinkLog.i(
            "AP2: ${if (type == STREAM_TYPE_REALTIME) "realtime" else "buffered"} stream: " +
                "${sampleRate}Hz ${channels}ch spf=$framesPerPacket ct=$compression " +
                "audioFormat=0x%08X key=%d bytes".format(audioFormat, key?.size ?: 0),
        )
        if (declared == null && audioFormat != 0L) {
            SinkLog.w(
                "AP2: audioFormat 0x%08X is not one this sink knows; trusting sr=$sampleRate"
                    .format(audioFormat),
            )
        }
        if (codec == AudioCodec.ALAC && compression != COMPRESSION_ALAC) {
            SinkLog.w(
                "AP2: compression type $compression has no decoder here; " +
                    "the ALAC decoder will reject what arrives",
            )
        }
        if (key != null && key.size != Ap2Crypto.KEY_BYTES) {
            SinkLog.w("AP2: shk is ${key.size} bytes, expected ${Ap2Crypto.KEY_BYTES}")
        }

        audioKey = key

        // Per-stream counters: a resume after a pause is a new stream, and the "first frame" lines
        // only print while these are zero. Without the reset the second stream would look silent.
        audioFrames = 0
        realtimePackets = 0
        decryptFailures = 0

        // Retransmission: where the sender takes requests, and a clean slate for the new stream.
        // `controlPort` here is the *sender's* control port, not the one handed out below.
        senderControlPort = (stream["controlPort"] as? Long)?.toInt() ?: 0
        senderAddress = null
        resend.reset()
        resendRequests = 0
        resendReplies = 0
        resendBlockedUntil = 0

        // Both stream types keep an RTCP control port next to the audio port. Nothing acts on what
        // arrives there — playback is driven by the audio itself — but the port has to exist and be
        // drained, or the socket buffer fills and the sender's RTCP starts backing up.
        val control = DatagramSocket(InetSocketAddress(localAddress, 0))
        controlSocket = control
        thread(name = "ap2-audio-ctl", isDaemon = true) { drainControl(control) }

        onFormat(
            Format(
                sampleRate = sampleRate,
                channels = channels,
                framesPerPacket = framesPerPacket,
                compressionType = compression,
                audioFormat = audioFormat,
                codec = codec,
            ),
        )

        val streamId = random.nextInt().toLong() and 0xFFFF_FFFFL
        val descriptor: Map<String, Any?>
        if (type == STREAM_TYPE_REALTIME) {
            // Realtime audio is plain RTP over UDP: one datagram per packet, no framing on top.
            val data = DatagramSocket(InetSocketAddress(localAddress, 0))
            realtimeSocket = data
            SinkLog.i(
                "AP2: realtime audio port opened at ${localAddress.hostAddress}:${data.localPort}, " +
                    "control at :${control.localPort}",
            )
            thread(name = "ap2-audio-rt", isDaemon = true) { serveRealtime(data) }
            descriptor = linkedMapOf(
                "type" to STREAM_TYPE_REALTIME,
                "controlPort" to control.localPort.toLong(),
                "dataPort" to data.localPort.toLong(),
                "streamID" to streamId,
            )
        } else {
            val server = ServerSocket(0, 4, localAddress)
            audioServer = server
            SinkLog.i(
                "AP2: buffered audio port opened at ${localAddress.hostAddress}:${server.localPort}, " +
                    "control at :${control.localPort}",
            )
            thread(name = "ap2-audio", isDaemon = true) { serveAudio(server) }
            descriptor = linkedMapOf(
                "type" to STREAM_TYPE_BUFFERED,
                "controlPort" to control.localPort.toLong(),
                "dataPort" to server.localPort.toLong(),
                "audioBufferSize" to AUDIO_BUFFER_BYTES,
                "streamID" to streamId,
            )
        }
        streamDescriptor = descriptor
        return descriptor
    }

    /** `audioFormat` packs the channel count in bits 24-31 (0x40000 = 2 channels, 16-bit ALAC). */
    /**
     * Channel count for an `audioFormat` descriptor.
     *
     * The descriptor is a single bitmask, not separate fields, and every value the protocol defines
     * for audio is stereo: bit 18 is `ALAC/44100/16/2`, 19 the 24-bit variant, 20/21 the 48 kHz
     * ones, 22+ AAC. A mono sender does not pick another bit — it stays on two channels and fills
     * one of them.
     *
     * Reading a channel count out of the high byte (which is always zero) used to return 1, and a
     * mono ALAC cookie makes the decoder reject every channel-pair element. The result was a link
     * that connected cleanly and played nothing at all.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun channelsFor(audioFormat: Long): Int = 2

    private fun serveAudio(server: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            SinkLog.i("AP2: sender connected the audio stream from ${client.remoteSocketAddress}")
            try {
                readFrames(client)
                SinkLog.i("AP2: audio stream ended after $audioFrames frames")
            } catch (error: Exception) {
                if (!closed.get()) SinkLog.w("AP2: audio stream ended: ${error.message}")
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun readFrames(client: Socket) {
        val input = DataInputStream(BufferedInputStream(client.getInputStream(), 64 * 1024))
        while (!closed.get()) {
            val length = try {
                input.readUnsignedShort()
            } catch (_: EOFException) {
                return
            }
            if (length <= 2 || length > MAX_FRAME_BYTES) {
                SinkLog.w("AP2: bogus audio frame length $length")
                return
            }
            val packet = ByteArray(length - 2)
            input.readFully(packet)
            handlePacket(packet, realtime = false)
        }
    }

    /**
     * Realtime audio: one RTP packet per datagram, arriving at playback pace.
     *
     * UDP means a lost packet is simply gone, and this sender never repeats one on its own. The
     * only recovery the protocol offers is to name the missing sequence numbers and let the sender
     * answer from what it still holds — which is what [ResendTracker] decides and [sendResend]
     * puts on the wire. The socket is woken on a timer as well as by packets so that a hole with
     * nothing behind it is still asked about.
     */
    private fun serveRealtime(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_FRAME_BYTES)
        runCatching { socket.soTimeout = IDLE_TICK_MS }
        while (!closed.get()) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (_: java.net.SocketTimeoutException) {
                resend.fireDue(SystemClock.elapsedRealtime()) { request -> sendResend(request) }
                continue
            } catch (_: Exception) {
                return
            }
            if (datagram.length <= OVERHEAD) continue
            if (realtimePackets == 0) {
                // The audio itself is the only reliable statement of where the sender is; the
                // control port it named in the SETUP is where the requests have to go.
                senderAddress = datagram.address
                SinkLog.i(
                    "AP2: first realtime packet ${datagram.length}B from ${datagram.socketAddress}" +
                        if (senderControlPort != 0) {
                            " — resends go to ${datagram.address.hostAddress}:$senderControlPort"
                        } else {
                            " — the SETUP named no control port, so losses cannot be re-asked"
                        },
                )
            }
            realtimePackets++
            if (realtimePackets <= 4) {
                // The sender's RTSP `rtp-info` and the sequence this header appears to carry do
                // not agree, so the layout is being read wrong somewhere. These four dumps settle
                // which field is which instead of guessing another offset.
                SinkLog.i(
                    "AP2: packet ${realtimePackets} (${datagram.length}B) " +
                        buffer.dumpHex(datagram.length),
                )
            }
            handlePacket(buffer.copyOf(datagram.length), realtime = true)
            resend.fireDue(SystemClock.elapsedRealtime()) { request -> sendResend(request) }
        }
    }

    /**
     * Puts one resend request on the wire.
     *
     * Returns false when nothing could be sent — no control port named, no address learned yet, or
     * a recent failure still holds the socket back — so the tracker keeps the hole rather than
     * writing it off. The hold-back is shairport-sync's `resend_error_backoff_time`: on a link that
     * is genuinely gone, every idle tick would otherwise be another `sendto` that cannot work.
     */
    private fun sendResend(request: Resend.Request): Boolean {
        val control = controlSocket ?: return false
        val address = senderAddress ?: return false
        if (senderControlPort == 0) return false
        val now = SystemClock.elapsedRealtime()
        if (now < resendBlockedUntil) return false
        val datagram = Resend.encode(request.first, request.count)
        return try {
            control.send(DatagramPacket(datagram, datagram.size, address, senderControlPort))
            resendRequests++
            if (resendRequests <= 3) {
                SinkLog.i(
                    "AP2: asking again for ${request.count} packet(s) from seq ${request.first}",
                )
            }
            true
        } catch (error: Exception) {
            resendBlockedUntil = now + Resend.ERROR_BACKOFF_MS
            SinkLog.w("AP2: resend request failed: ${error.message}")
            false
        }
    }

    private fun handlePacket(packet: ByteArray, realtime: Boolean) {
        if (packet.size <= OVERHEAD) return

        // Realtime packets are ordinary RTP, so the sequence sits where RTP puts it. The buffered
        // framing starts one byte earlier in the sender's implementation, which is why the two
        // offsets differ.
        //
        // Realtime ordering is taken from the timestamp instead of the sequence field, and the
        // reason is a 16-bit wrap: the header's counter runs out every 65536 packets, i.e. every
        // 8.7 minutes at 8 ms a packet, and the pipeline compares sequences as plain numbers, so
        // playback would stop dead at the wrap. The timestamp is 32-bit and every packet is exactly
        // `spf` samples, so dividing it yields the same counter monotonically for 27 hours.
        //
        // (This used to read the field with a 4-byte load starting at offset 2 and mask it to 16
        // bits, which actually returned the high half of the timestamp — a value that drifts far
        // too slowly to be a packet counter. 98% of the stream was then judged "already played"
        // and dropped, which is why the first AP2 sessions connected and stayed silent.)
        val timestamp = beUInt(packet, 4)
        val sequence = if (realtime) {
            timestamp / audioFramesPerPacket.coerceAtLeast(1).toLong()
        } else {
            // The buffered stream's counter is 23 bits wide; [SeqNo] is where that width is
            // defined, so that the flush endpoints the sender names can be brought into the same
            // space instead of being compared against a counter of a different width.
            SeqNo.narrow(beUInt(packet, 0))
        }
        val bodyEnd = packet.size - NONCE_BYTES

        val key = audioKey
        val payload = if (key == null) {
            packet.copyOfRange(RTP_HEADER_BYTES, bodyEnd)
        } else {
            val nonce = ByteArray(Ap2Crypto.NONCE_BYTES)
            System.arraycopy(packet, bodyEnd, nonce, Ap2Crypto.NONCE_BYTES - NONCE_BYTES, NONCE_BYTES)
            try {
                Ap2Crypto.open(
                    key,
                    nonce,
                    packet.copyOfRange(4, RTP_HEADER_BYTES),
                    packet.copyOfRange(RTP_HEADER_BYTES, bodyEnd),
                )
            } catch (error: Exception) {
                // One line per failure would flood the log at 125 frames a second, so the first
                // one explains the problem and the counter says how often it happens.
                if (++decryptFailures <= 3) {
                    SinkLog.w(
                        "AP2: audio frame $sequence failed to decrypt (${error.message}); " +
                            "packet ${packet.size}B",
                    )
                }
                return
            }
        }

        if (audioFrames == 0) {
            val ssrc = beUInt(packet, 8)
            SinkLog.i(
                "AP2: first decrypted frame: seq=$sequence ts=$timestamp " +
                    "headerSeq=${beUShort(packet, 2)} " +
                    "ssrc=0x%08X payload=${payload.size}B".format(ssrc),
            )
            // Two independent statements of the same format, both logged: the first bytes say
            // whether the payload is a raw access unit or carries an ADTS header, and the SSRC is
            // the sender's own encoding table. If they ever disagree with the SETUP, the sound is
            // noise and this is the line that says why.
            SinkLog.i(
                "AP2: payload starts ${payload.dumpHex(8)}; " +
                    "ssrc says ${ssrcDescription(ssrc)}; SETUP said ${streamCodec.label} " +
                    "${streamRate}Hz",
            )
            val fromSsrc = ssrcCodec(ssrc)
            if (fromSsrc != null && fromSsrc != streamCodec) {
                SinkLog.w(
                    "AP2: the RTP SSRC says ${ssrcDescription(ssrc)} but the SETUP said " +
                        "${streamCodec.label}; the decoder will produce noise",
                )
            }
        }
        // Playback ordering is taken from the timestamp above, but a request has to name the
        // header's own 16-bit sequence, so gaps are tracked in that space instead. Done after
        // decryption: a frame that did not decrypt has not really arrived.
        if (realtime) resend.onPacket(beUShort(packet, 2), SystemClock.elapsedRealtime())
        audioFrames++
        onAudio(sequence, timestamp, payload)
    }

    private var decryptFailures = 0

    /**
     * The format the sender encodes in the RTP header's SSRC field.
     *
     * These are shairport-sync's `ssrc_t` values (`player.h`), read from real streams: an AirPlay 2
     * buffered packet does not carry a random synchronisation source, it carries the format. Same
     * information the `SETUP` gave, arrived at a second way, which makes it a free cross-check.
     */
    private fun ssrcDescription(ssrc: Long): String = when (ssrc) {
        0x0000FACEL -> "ALAC 44100Hz 16bit 2ch"
        0x15000000L -> "ALAC 48000Hz 24bit 2ch"
        0x16000000L -> "AAC 44100Hz 2ch"
        0x17000000L -> "AAC 48000Hz 2ch"
        0x27000000L -> "AAC 48000Hz 6ch"
        0x28000000L -> "AAC 48000Hz 8ch"
        0L -> "nothing (unset)"
        else -> "a format this sink does not know"
    }

    /** The codec [ssrc] names, or null when it names none this sink knows. */
    private fun ssrcCodec(ssrc: Long): AudioCodec? = when (ssrc) {
        0x0000FACEL, 0x15000000L -> AudioCodec.ALAC
        0x16000000L, 0x17000000L, 0x27000000L, 0x28000000L -> AudioCodec.AAC
        else -> null
    }

    /** First bytes as hex, grouped per RTP field width so the header can be read at a glance. */
    private fun ByteArray.dumpHex(limit: Int): String {
        val count = minOf(limit, 24)
        val text = StringBuilder(count * 3)
        for (index in 0 until count) {
            if (index > 0) text.append(if (index % 4 == 0) "  " else ' ')
            val value = this[index].toInt() and 0xFF
            text.append(Character.forDigit(value shr 4, 16))
            text.append(Character.forDigit(value and 0xF, 16))
        }
        return text.toString()
    }

    private fun beUInt(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    /** A 16-bit field. [beUInt] always consumes four bytes, so it cannot stand in for this. */
    private fun beUShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /**
     * The RTCP socket, which is also where a retransmission comes back.
     *
     * Ordinary control packets are not acted on; draining the port keeps the kernel from filling.
     * A retransmission, though, is audio the speaker is still waiting for: it arrives with four
     * bytes of prefix in front of the packet, so stripping that and handing the rest to
     * [handlePacket] reuses the whole realtime path — same decrypt, same nonce, same ordering.
     */
    private fun drainControl(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_FRAME_BYTES + 4)
        var packets = 0
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                return
            }
            if (packets++ == 0) {
                SinkLog.i(
                    "AP2: first control packet ${packet.length}B from ${packet.socketAddress}",
                )
            }
            if (packet.length <= 4 + OVERHEAD) continue
            if ((buffer[1].toInt() and 0x7F) == TYPE_RESEND_REPLY) {
                resendReplies++
                if (resendReplies <= 3) {
                    SinkLog.i(
                        "AP2: retransmission ${packet.length - 4}B from ${packet.socketAddress}",
                    )
                }
                handlePacket(buffer.copyOfRange(4, packet.length), realtime = true)
            }
        }
    }

    fun describe(): String = "audio stream, $audioFrames decoded frames"

    /**
     * The stream the sender set up, as `(type, sample rate)`, or null when there is none.
     *
     * `POST /feedback` asks the receiver to state what it is playing, and this is the pair it
     * wants back. Null means the honest answer is "nothing" — after a pause the phone has torn the
     * stream down, and reporting the retired one would claim playback that no longer exists.
     */
    fun currentStream(): Pair<Long, Int>? =
        if (streamDescriptor == null) null else streamType to streamRate

    /**
     * Closes the audio ports and forgets the stream, without touching the event channel.
     *
     * The phone ends the stream on every pause and opens a new one when playback resumes, but it
     * keeps the session — and the event connection that carries remote commands — alive across
     * both. Forgetting `streamDescriptor` here is what lets the next `SETUP` hand out new ports
     * instead of pointing the sender back at sockets that are already closed.
     */
    fun stopAudioStream() {
        if (resendRequests > 0 || resendReplies > 0) {
            // Both numbers, always: requests without replies is a sender ignoring us, which is a
            // different fault from a link that loses packets and gets them back.
            SinkLog.i(
                "AP2: resends this stream: $resendRequests request(s), $resendReplies reply(s)",
            )
        }
        runCatching { audioServer?.close() }
        runCatching { realtimeSocket?.close() }
        runCatching { controlSocket?.close() }
        audioServer = null
        realtimeSocket = null
        controlSocket = null
        streamDescriptor = null
        audioKey = null
        SinkLog.i("AP2: audio ports closed after $audioFrames frames; the event channel stays")
    }

    override fun close() {
        closed.set(true)
        if (resendRequests > 0 || resendReplies > 0) {
            SinkLog.i(
                "AP2: resends this stream: $resendRequests request(s), $resendReplies reply(s)",
            )
        }
        runCatching { eventServer?.close() }
        runCatching { audioServer?.close() }
        runCatching { realtimeSocket?.close() }
        runCatching { controlSocket?.close() }
        eventServer = null
        audioServer = null
        realtimeSocket = null
        controlSocket = null
        streamDescriptor = null
        audioKey = null
    }
}

/**
 * Builds the `timingPeerInfo` answer: our timing identity, and the address to use for it.
 *
 * Kept as a free function of two strings so it can be tested without a network stack: resolving an
 * IPv6 address that carries a zone (`fe80::…%wlan0`) needs a real interface to exist, which a unit
 * test has no way to promise, and the part worth pinning is the choice of address rather than the
 * parsing.
 *
 * **One address is named, and it is the receiver's own IPv6 link-local address.**
 *
 * That is the only shape that worked, out of everything tried:
 *
 * | named in `timingPeerInfo` | handshakes | reached `RECORD` |
 * |---|---|---|
 * | link-local (`fe80::…%wlan0`) | 11 | **11** |
 * | global unicast | 17 | 2 |
 * | IPv4 | 4 | 0 |
 * | every interface address | 4 | 0 |
 *
 * The two global-unicast successes were on the phone's own Wi-Fi, where the address came from the
 * router; the fifteen failures were the same address family handed out by the phone's own hotspot,
 * where the address belongs to the phone's cellular prefix and its route for that prefix goes out
 * of the radio. The IPv4 row is the cleanest of the failures: the phone reached the receiver over
 * IPv4, the answer named that same IPv4, and all four sessions were dropped a millisecond after the
 * event channel opened — so "name the address the connection arrived on" is not enough, and neither
 * is "name the address the receiver is advertised on".
 *
 * The reason is the address family. AirPlay 2's timing is PTP, which runs over IPv6 link-local
 * multicast; a sender handed an IPv4 or a global address has nothing it can synchronize against,
 * and it drops the session instead of falling back. The link-local address is also the one address
 * guaranteed to be reachable on the link the sender is already on, which is what makes it the right
 * answer for a sender that may have connected to us over an address of its own choosing.
 *
 * The zone stays on the address. `fe80::…` without one names no interface, and the sender cannot
 * guess which was meant.
 *
 * @param selfAddress the address the control connection arrived on — the fallback for a host with
 *   no link-local address at all.
 * @param interfaceAddresses every address the device has, with zones intact.
 */
internal fun timingPeerInfoAnswer(
    selfAddress: String,
    interfaceAddresses: List<String>,
): Map<String, Any?> {
    val linkLocal = interfaceAddresses.firstOrNull { it.startsWith("fe80:") }
    val named = linkLocal ?: selfAddress
    return linkedMapOf<String, Any?>(
        "ID" to named,
        "Addresses" to listOf(named),
    )
}
