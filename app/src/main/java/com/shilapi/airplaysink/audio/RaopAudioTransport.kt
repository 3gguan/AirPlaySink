package com.shilapi.airplaysink.audio

import com.shilapi.airplaysink.crypto.RaopCrypto
import com.shilapi.airplaysink.util.Codec
import com.shilapi.airplaysink.util.SinkLog
import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Receives the RAOP audio stream.
 *
 * Wire format, taken from shairport-sync's `rtp.c`/`player.c`:
 *
 *  * 12 byte RTP header. Byte 1 with the marker bit masked off is the packet type: 0x60 regular
 *    audio, 0x56 a retransmission (which carries a 4 byte prefix), 0x54 a timing/sync packet sent
 *    to the *timing* port rather than the audio port.
 *  * The 16 bit sequence number and 32 bit RTP timestamp follow in the header.
 *  * The payload is AES-128-CBC encrypted with the session IV from ANNOUNCE. Only whole 16 byte
 *    blocks are encrypted; the trailing remainder is passed through unchanged.
 *  * A missing sequence is re-requested with an 8 byte "resend" datagram sent to the sender's
 *    control port, and the replacement is answered to the socket that asked — the control one.
 */
class RaopAudioTransport(
    private val audioSocket: DatagramSocket,
    private val controlSocket: DatagramSocket,
    private val timingSocket: DatagramSocket,
    private val senderControl: InetSocketAddress,
    /** The sender's *timing* port, where the 0xD2 clock-sync request must go. */
    private val senderTiming: InetSocketAddress,
    private val pipeline: AudioPipeline,
    private val sessionKey: ByteArray?,
    private val sessionIv: ByteArray?,
    /**
     * How many times a gap is asked for before it is left to the receiver to play out.
     *
     * Sized by the caller from the pipeline's gap window, because asking again is only worth
     * anything while playback is still waiting: past that the answer arrives to find the hole
     * already concealed. A deep cushion therefore gets a longer schedule, and each ask is one
     * 8 byte datagram. Public so the session can log what it actually chose.
     */
    val maxResendAttempts: Int = 4,
) : Closeable {

    companion object {
        const val TYPE_AUDIO = 0x60
        const val TYPE_RESEND_REPLY = 0x56
        const val TYPE_SYNC = 0x54
        // The NTP clock-sync request/reply pair. 0xD2 is the request the *receiver* sends to ask the
        // sender for its timestamps; 0xD3 is the reply. The marker bit (0x80) is masked off on read.
        const val TYPE_TIMING_REQUEST = 0x52
        const val TYPE_TIMING_REPLY = 0x53
        const val MAX_DATAGRAM = 4096
        const val MAX_RESEND_SPAN = 128

        /** Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01). */
        const val NTP_UNIX_OFFSET_S = 2_208_988_800L

        /**
         * Throttle between resend requests. A gap is only ever asked for once by
         * [unwrapSequence] — it moves `expectedSequence` past the hole as soon as the request is
         * queued — so this only spaces out *different* gaps.
         *
         * This used to be 250 ms, which answered a burst of loss with a single request, and then
         * 40 ms, which is still coarse: packets are 8 ms apart, so a burst leaves several gaps
         * queued at once and those were asked for one per 40 ms — the fourth gap in a burst went
         * out 160 ms after the first, well past the pipeline's own wait. 12 ms fits a burst of ten
         * inside a 150 ms window and still caps the wire at ~80 requests a second, which the link
         * carries without noticing: measured over fifteen minutes the sender is asked about once
         * every two seconds outside a burst.
         */
        const val RESEND_BACKOFF_MS = 12L
        const val SOCKET_TIMEOUT_MS = 400

        /**
         * How many times a gap is asked for before it is left to the receiver to play out.
         *
         * One request per gap is what the code did, and over nineteen minutes of playback 68 holes
         * still reached the pipeline as skips against 134 that were filled — i.e. a third of the
         * losses were never recovered. Some of those requests were dropped on the floor by
         * [RESEND_BACKOFF_MS] alone, which is pure loss: the gap had been noticed, the sender was
         * never told. A second ask costs one 8 byte datagram and lands 45 ms after the first, still
         * inside the pipeline's own wait for the gap.
         *
         * The default is the floor; the caller sizes the real figure from the gap window. See
         * [maxResendAttempts].
         */
        const val MAX_RESEND_ATTEMPTS = 4
        const val RESEND_ATTEMPT_CEILING = 12

        /**
         * The retry schedule that fits inside a gap window of [gapWaitMs].
         *
         * The first ask goes out one [NACK_DELAY_MS] after the hole is noticed and each further
         * one [RESEND_RETRY_MS] later, so the last has to land before playback stops waiting;
         * asking after that buys a packet that will be dropped on arrival and only adds traffic to
         * a link that is already losing packets.
         */
        fun attemptsFor(gapWaitMs: Long): Int =
            ((gapWaitMs - NACK_DELAY_MS) / RESEND_RETRY_MS).toInt()
                .coerceIn(MAX_RESEND_ATTEMPTS, RESEND_ATTEMPT_CEILING)
        const val RESEND_RETRY_MS = 45L

        /**
         * How long a gap has to stay a gap before it is worth asking about.
         *
         * Most holes in the sequence are the sender's packets arriving out of order, not lost, and
         * over a hotspot they sort themselves out in a few milliseconds. Asking immediately turned
         * an entirely self-healing reordering into a retransmission: measured on the head unit,
         * roughly 18 requests a second went out while only about 0.3 gaps a second ever reached
         * the pipeline as an actual hole — the rest had already arrived by the time playback got
         * there. Crying wolf that often is not just noise on the wire, it buries the one number
         * worth watching. 35 ms filters the reordering and still leaves the reply plenty of time
         * to land inside the pipeline's own wait for the gap.
         */
        const val NACK_DELAY_MS = 35L

        /** Ceiling on the queue above, so a sender that stops streaming cannot grow it forever. */
        const val MAX_RESEND_QUEUE = 64

        /**
         * How often the clock-sync request is sent, mirroring shairport-sync's `rtp_timing_sender`:
         * the first three go out 300 ms apart to lock on quickly, then the schedule relaxes to one
         * every 3 s to track clock drift without adding traffic.
         */
        const val TIMING_EARLY_INTERVAL_MS = 300L
        const val TIMING_STEADY_INTERVAL_MS = 3000L
        const val TIMING_EARLY_COUNT = 3

        /** A reply whose round trip exceeds this is discarded as unusable (shairport uses 200 ms). */
        const val TIMING_MAX_RTT_NS = 200_000_000L

        /**
         * How many packets in a row may arrive behind the expected one before that is read as the
         * sender having restarted its sequence. Reordering never runs this deep.
         */
        const val RESYNC_AFTER_LATE_PACKETS = 96

        /**
         * How long a session may stay silent after RECORD before we say so out loud. A phone that
         * accepted SETUP and then streams nothing is indistinguishable, in the rest of the log,
         * from a phone that never tried — and those two need completely different fixes.
         */
        const val SILENT_WARN_MS = 4000L
    }

    /** Audio packets carry the RTP header inline; resent packets have an extra 4 byte prefix. */
    private class Layout(val payloadOffset: Int, val sequenceOffset: Int, val timestampOffset: Int)

    private val liveLayout = Layout(payloadOffset = 12, sequenceOffset = 2, timestampOffset = 4)
    private val resendLayout = Layout(payloadOffset = 16, sequenceOffset = 6, timestampOffset = 8)

    @Volatile
    private var running = false

    private val decryptBuffer = ByteArray(MAX_DATAGRAM)
    private var audioThread: Thread? = null
    private var controlThread: Thread? = null
    private var timingThread: Thread? = null
    private var timingSenderThread: Thread? = null
    private var watchdogThread: Thread? = null

    @Volatile
    private var startedAt = 0L

    private var expectedSequence = -1
    private var lastResendAt = 0L
    private var latePackets = 0

    // Sequence numbers are 16 bit; they are unwrapped into a monotonic counter for the pipeline.
    private var highestRaw = -1
    private var highestAbsolute = 0L

    /**
     * Guards everything the audio and the control thread can now both touch.
     *
     * Retransmissions come back to the control port (see [readControl]), so [handleAudio] runs on
     * either thread and the sequence bookkeeping below it is no longer single threaded.
     */
    private val stateLock = Any()

    /** A gap noticed but not yet asked for, with the moment it becomes worth asking about. */
    private class ResendWait(
        val first: Int,
        val count: Int,
        val dueAt: Long,
        val attempts: Int = 0,
    )

    private val resendQueue = java.util.ArrayDeque<ResendWait>()

    private var audioPackets = 0L
    private var resendReplies = 0L
    private var resendRequests = 0L
    private var syncPackets = 0L
    private var unknownControlPackets = 0L
    private var lastStatsAt = 0L

    // --- NTP clock sync against the sender ---------------------------------------------
    //
    // The sender stamps every audio frame with an RTP timestamp in *its* clock, but playback must
    // happen against *our* clock. `Audio-Latency` only declares the intended delay; making the
    // progress bar actually line up with the sound needs the two clocks reconciled, which is what
    // the 0xD2/0xD3 exchange does. The offset computed here is handed to the pipeline so it can
    // relate "when this frame must be heard" to `System.nanoTime()`.
    //
    // Time bases (the subtlety that makes or breaks this):
    //  * NTP timestamps on the wire are absolute, counted in nanoseconds from 1900-01-01 — ~4e18.
    //  * `System.nanoTime()` is a *monotonic* clock with an arbitrary, small origin (~1e14).
    //  * `System.currentTimeMillis()` is the Unix epoch (1970-01-01), NTP epoch minus 2208988800 s.
    //
    // The offset between the two *clocks* lives in the NTP domain (remote NTP minus local NTP), but
    // the anchor handed to the pipeline must be in the `System.nanoTime()` domain, because that is
    // what `paceToTimeline` compares against. So a sync packet's NTP instant is converted in two
    // hops: remote NTP -> local NTP (subtract the clock offset) -> local monotonic (relate the two
    // via a simultaneously-sampled NTP/monotonic pair). Sampling that pair freshly on every sync
    // keeps `currentTimeMillis`'s millisecond quantisation from building up into a drift.

    /** The number of times a timing request has been sent. */
    private var timingRequestCount = 0

    /** Monotonic clock captured just before the last request left, via [System.nanoTime]. */
    private var lastRequestDepartureNs = 0L

    /**
     * remote-minus-local clock offset in the NTP domain: remote NTP = local NTP + offset. Positive
     * means the sender's clock is ahead of ours. Updated on every usable reply. Both terms are NTP
     * epoch nanoseconds — this must *not* be mixed with `System.nanoTime()`, whose origin is
     * arbitrary and unrelated to the NTP epoch.
     */
    @Volatile
    private var clockOffsetNs = 0L

    /** True once at least one usable reply has arrived and [clockOffsetNs] is meaningful. */
    @Volatile
    private var clockSynced = false

    /** The local clock now, in NTP epoch nanoseconds (millisecond precision is plenty here). */
    private fun localNtpNs(): Long =
        (System.currentTimeMillis() + NTP_UNIX_OFFSET_S * 1000L) * 1_000_000L

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        SinkLog.i(
            "Audio transport on data :${audioSocket.localPort}, control :${controlSocket.localPort}, " +
                "timing :${timingSocket.localPort}; sender control $senderControl; " +
                "encryption ${if (sessionKey != null && sessionIv != null) "on" else "off"}",
        )
        audioThread = Thread({ audioLoop() }, "raop-audio-rx").also { it.start() }
        controlThread = Thread({ receiveLoop(controlSocket, "control") }, "raop-control-rx")
            .also { it.start() }
        timingThread = Thread({ receiveLoop(timingSocket, "timing") }, "raop-timing-rx")
            .also { it.start() }
        timingSenderThread = Thread({ timingSenderLoop() }, "raop-timing-tx").also { it.start() }
        watchdogThread = Thread({ watchdog() }, "raop-silence-watchdog").also { it.start() }
    }

    /**
     * Sends the NTP clock-sync request (0xD2) on a relaxed schedule and records the departure time
     * of the last one, which the reply handler pairs with its own arrival to compute round-trip.
     *
     * The first few requests go out quickly so the clocks lock on as soon as playback starts, then
     * the pace drops to one every few seconds — enough to track drift without chattering the link.
     */
    private fun timingSenderLoop() {
        while (running) {
            val count = timingRequestCount
            val interval = if (count < TIMING_EARLY_COUNT) {
                TIMING_EARLY_INTERVAL_MS
            } else {
                TIMING_STEADY_INTERVAL_MS
            }
            try {
                Thread.sleep(interval)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            sendTimingRequest()
        }
    }

    /**
     * Builds and sends the 32 byte timing request. Everything but the leader/type/sequence is zero;
     * the sender fills in its receive/transmit timestamps in the reply, and we measure the rest on
     * our side.
     */
    private fun sendTimingRequest() {
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte() // timing request
        Codec.putBe16(request, 2, 7)
        // bytes 4..31 stay zero: filler, origin, receive, transmit.
        lastRequestDepartureNs = System.nanoTime()
        runCatching {
            timingSocket.send(DatagramPacket(request, request.size, senderTiming))
            timingRequestCount++
            if (timingRequestCount <= 3) {
                SinkLog.i(
                    "Timing request #$timingRequestCount sent to ${senderTiming.address?.hostAddress}:" +
                        "${senderTiming.port}",
                )
            }
        }.onFailure {
            SinkLog.w("Timing request failed: ${it.message}")
        }
    }

    /**
     * Shouts once when a fully negotiated session stays silent. Everything else in the log looks
     * identical whether the sender is streaming or not, so without this the only clue is the
     * absence of a message.
     */
    private fun watchdog() {
        while (running) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            if (audioPackets + resendReplies > 0) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < SILENT_WARN_MS) continue
            SinkLog.e(
                "RECORD was acknowledged but no audio has arrived after ${elapsed} ms. The sender " +
                    "is not streaming to this output — check that it is actually playing, and " +
                    "that it reaches us on ${senderControl.address?.hostAddress}.",
            )
            return
        }
    }

    override fun close() {
        running = false
        runCatching { audioSocket.close() }
        runCatching { controlSocket.close() }
        runCatching { timingSocket.close() }
        runCatching { audioThread?.join(500) }
        runCatching { controlThread?.join(500) }
        runCatching { timingThread?.join(500) }
        timingSenderThread?.interrupt()
        runCatching { timingSenderThread?.join(500) }
        watchdogThread?.interrupt()
        runCatching { watchdogThread?.join(500) }
        audioThread = null
        controlThread = null
        timingThread = null
        timingSenderThread = null
        watchdogThread = null
        SinkLog.i(
            "Audio transport closed: $audioPackets audio packets, $resendReplies retransmissions, " +
                "$resendRequests resend requests, $syncPackets sync packets",
        )
    }

    private fun audioLoop() {
        // Below the decode thread on purpose — it is the one holding the speaker up — but still
        // above default: a packet left sitting in the kernel is a packet the pipeline cannot wait
        // for. Raising it needs MODIFY_AUDIO_SETTINGS, so a refusal is survivable rather than fatal.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            .onFailure { SinkLog.w("Could not raise the receive thread priority: ${it.message}") }

        val datagram = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        while (running) {
            try {
                audioSocket.receive(datagram)
            } catch (error: Exception) {
                if (running) SinkLog.w("Audio socket receive failed: ${error.message}")
                return
            }
            val type = if (datagram.length >= 2) datagram.data[1].toInt() and 0x7F else -1
            when (type) {
                TYPE_AUDIO -> handleAudio(datagram, liveLayout, isResend = false)
                TYPE_RESEND_REPLY -> handleAudio(datagram, resendLayout, isResend = true)
                else -> SinkLog.w(
                    "Unexpected RTP type 0x${Integer.toHexString(type)} on the audio port (${datagram.length}B)",
                )
            }
        }
    }

    private fun handleAudio(datagram: DatagramPacket, layout: Layout, isResend: Boolean) {
        val data = datagram.data
        val length = datagram.length
        if (length < layout.payloadOffset + 16) {
            SinkLog.w("Ignoring short audio packet: ${length}B")
            return
        }

        val rawSequence = Codec.be16(data, layout.sequenceOffset)
        val rtpTime = Codec.be32(data, layout.timestampOffset)
        val payloadLength = length - layout.payloadOffset

        val key = sessionKey
        val iv = sessionIv
        val payload: ByteArray
        val sequence: Long
        synchronized(stateLock) {
            sequence = unwrapSequence(rawSequence)
            if (key != null && iv != null) {
                RaopCrypto.decryptAudioPayload(
                    data, layout.payloadOffset, payloadLength, key, iv, decryptBuffer,
                )
                payload = ByteArray(payloadLength)
                System.arraycopy(decryptBuffer, 0, payload, 0, payloadLength)
            } else {
                payload = ByteArray(payloadLength)
                System.arraycopy(data, layout.payloadOffset, payload, 0, payloadLength)
            }

            if (isResend) {
                resendReplies++
                if (resendReplies <= 3) {
                    SinkLog.i(
                        "Retransmission received: seq=$rawSequence ${payloadLength}B " +
                            "on ${datagram.address?.hostAddress}:${datagram.port}",
                    )
                }
            } else {
                audioPackets++
            }
            val total = audioPackets + resendReplies
            if (total <= 8) {
                SinkLog.i(
                    "Audio ${if (isResend) "retransmission" else "packet"} seq=$rawSequence rtpTime=$rtpTime " +
                        "${payloadLength}B encrypted=${key != null} " +
                        "from ${datagram.address?.hostAddress}:${datagram.port}",
                )
            }
            pipeline.submit(sequence, rtpTime.toLong() and 0xFFFFFFFFL, payload)

            // Whatever this packet was, it is no longer missing — and with it out of the way the
            // queue may hold gaps that are now old enough to be worth asking about.
            cancelResendLocked(rawSequence)
            fireDueResendsLocked()
        }
    }

    /** Turns the 16 bit RTP sequence into a monotonic counter rooted at the first packet seen. */
    private fun unwrapSequence(raw: Int): Long {
        if (highestRaw < 0) {
            highestRaw = raw
            highestAbsolute = 0L
        } else {
            val forward = (raw - highestRaw) and 0xFFFF
            if (forward in 1..0x7FFF) {
                highestRaw = raw
                highestAbsolute += forward.toLong()
            } else if (forward > 0x7FFF) {
                return highestAbsolute - (((highestRaw - raw) and 0xFFFF).toLong())
            }
        }

        if (expectedSequence >= 0) {
            // Unsigned 16 bit arithmetic, so the three cases are: 0 = the packet we were waiting
            // for, a small positive distance = a gap, and anything from 0x8000 up = a packet that
            // is *behind* what we are waiting for, i.e. one that merely arrived late.
            val delta = (raw - expectedSequence) and 0xFFFF
            when {
                delta == 0 -> {
                    latePackets = 0
                    expectedSequence = (raw + 1) and 0xFFFF
                }
                delta <= MAX_RESEND_SPAN -> {
                    latePackets = 0
                    queueResendLocked(expectedSequence, delta)
                    expectedSequence = (raw + 1) and 0xFFFF
                }
                // A late packet. This is the case that used to be wrong: it lands in the same
                // bucket as a real discontinuity because the unsigned subtraction wraps, so
                // `expectedSequence` was dragged back to just after it. Every packet after that
                // then looked like a one packet gap and was asked for again — including packets
                // that had already arrived. That is where the 18 requests a second came from on a
                // link losing a third of a packet a second, and it is why the retransmissions that
                // did come back were for the wrong packets. A late packet tells us nothing about
                // what is still missing, so the expectation must not move.
                delta >= 0x8000 -> {
                    // Unless nothing else is arriving at all, which means the sender restarted its
                    // sequence rather than that these are all late. Resync then, or the expectation
                    // would stay ahead of a stream that has moved on and nothing would ever be
                    // asked for again.
                    if (++latePackets > RESYNC_AFTER_LATE_PACKETS) {
                        latePackets = 0
                        expectedSequence = (raw + 1) and 0xFFFF
                    }
                }
                else -> {
                    // A jump too far forward to be a gap: resynchronise without asking.
                    latePackets = 0
                    expectedSequence = (raw + 1) and 0xFFFF
                }
            }
        } else {
            expectedSequence = (raw + 1) and 0xFFFF
        }
        return highestAbsolute
    }

    /** Must be called while holding [stateLock]. */
    private fun queueResendLocked(first: Int, count: Int) {
        if (resendQueue.size >= MAX_RESEND_QUEUE) resendQueue.removeFirst()
        resendQueue.addLast(ResendWait(first, count, SystemClock.elapsedRealtime() + NACK_DELAY_MS))
    }

    /**
     * Must be called while holding [stateLock]. A packet that turned up on its own — however late
     * — is not a loss, and asking for it again would only make the sender resend something we
     * already have.
     */
    private fun cancelResendLocked(raw: Int) {
        if (resendQueue.isEmpty()) return
        val iterator = resendQueue.iterator()
        while (iterator.hasNext()) {
            val wait = iterator.next()
            if (((raw - wait.first) and 0xFFFF) < wait.count) iterator.remove()
        }
    }

    /** Must be called while holding [stateLock]. */
    private fun fireDueResendsLocked() {
        if (resendQueue.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        while (resendQueue.isNotEmpty() && resendQueue.first().dueAt <= now) {
            val wait = resendQueue.removeFirst()
            val sentAt = requestResend(wait.first, wait.count)
            if (sentAt == 0L) {
                // Throttled, not answered: the gap is still a gap, so it goes back in the queue
                // instead of being dropped. Dropping it meant the sender was never asked at all,
                // and that hole was then certain to be played out as a skip.
                resendQueue.addFirst(
                    ResendWait(wait.first, wait.count, now + RESEND_BACKOFF_MS, wait.attempts),
                )
                return
            }
            if (wait.attempts + 1 < maxResendAttempts) {
                // Not confirmed yet — a reply cancels this entry, silence brings it back round.
                resendQueue.addLast(
                    ResendWait(wait.first, wait.count, sentAt + RESEND_RETRY_MS, wait.attempts + 1),
                )
            }
        }
    }

    /** Sends one resend request. Returns when it went out, or 0 if the throttle held it back. */
    private fun requestResend(first: Int, count: Int): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - lastResendAt < RESEND_BACKOFF_MS) return 0L
        lastResendAt = now

        val request = ByteArray(8)
        request[0] = 0x80.toByte()
        request[1] = 0xD5.toByte() // Apple "resend"
        Codec.putBe16(request, 2, 1)
        Codec.putBe16(request, 4, first)
        Codec.putBe16(request, 6, count)
        resendRequests++
        runCatching {
            controlSocket.send(DatagramPacket(request, request.size, senderControl))
            if (resendRequests <= 3) SinkLog.i("Requesting resend of $count packet(s) from seq $first")
        }.onFailure { SinkLog.w("Resend request failed: ${it.message}") }
        return now
    }

    /**
     * One thread per socket, deliberately.
     *
     * Reading both from a single loop meant a retransmission could sit unread in the control socket
     * while the loop was parked on the timing socket — for up to [SOCKET_TIMEOUT_MS], 400 ms. The
     * pipeline writes a gap off after 120 ms, so replies that had already arrived were still being
     * counted as losses: over one measured minute, 33 retransmissions came back and exactly one of
     * them filled the hole it had been asked for. Resends are the one thing on this socket that is
     * racing the speaker, so nothing may block in front of them.
     */
    private fun receiveLoop(socket: DatagramSocket, name: String) {
        val datagram = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        socket.soTimeout = SOCKET_TIMEOUT_MS
        while (running) {
            readControl(socket, datagram, name)
            reportCounters()
        }
    }

    /**
     * The socket timeouts make this loop tick at least every [SOCKET_TIMEOUT_MS], so it doubles as
     * the place to say how the link is actually doing.
     *
     * `resend requests` climbing while `retransmissions` does not means the sender is ignoring us
     * and every gap is going to be a skipped packet — a completely different fault from a link that
     * drops packets and recovers them, and one that is invisible in the counters at close time.
     */
    @Synchronized
    private fun reportCounters() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAt < 5000) return
        lastStatsAt = now
        val audio: Long
        val replies: Long
        val requests: Long
        val sync: Long
        synchronized(stateLock) {
            audio = audioPackets
            replies = resendReplies
            requests = resendRequests
            sync = syncPackets
        }
        SinkLog.i(
            "RTP: $audio audio packets, $replies retransmissions, " +
                "$requests resend requests, $sync sync packets",
        )
    }

    private fun readControl(socket: DatagramSocket, datagram: DatagramPacket, name: String) {
        try {
            datagram.length = MAX_DATAGRAM
            socket.receive(datagram)
        } catch (_: java.net.SocketTimeoutException) {
            return
        } catch (error: Exception) {
            if (running) SinkLog.w("$name socket receive failed: ${error.message}")
            return
        }
        if (datagram.length < 2) return
        val data = datagram.data
        val type = data[1].toInt() and 0x7F
        when (type) {
            TYPE_SYNC -> {
                if (datagram.length < 20) return
                syncPackets++
                // Sync packet layout (shairport-sync's rtp_control_receiver):
                //  * offset 2  — flags short (AirPlay 2 sets 4, older iTunes 7)
                //  * offset 4  — the RTP timestamp the sender expects to be playing *now*
                //                (already latency-adjusted, i.e. "raw less latency")
                //  * offset 8/12 — the sender's NTP time of that instant
                //  * offset 16 — the raw RTP timestamp (offset 4 plus the latency)
                //
                // The anchor is offset 4: that frame is the one sounding *at* the NTP time in the
                // packet. Using offset 16 here was the bug — the raw frame is still `latency` in the
                // future, so anchoring on it made every frame play a latency too early and the
                // progress bar sat ahead of the sound by exactly that amount.
                val nowRtpTime = Codec.be32(data, 4).toLong() and 0xFFFFFFFFL
                val rawRtpTime = Codec.be32(data, 16).toLong() and 0xFFFFFFFFL
                val senderNtpNs = ntpToNanos(data, 8)
                if (syncPackets <= 2) {
                    SinkLog.i(
                        "Timing sync #$syncPackets now=${nowRtpTime} raw=$rawRtpTime " +
                            "ntp=${Codec.be32(data, 8)}.${Codec.be32(data, 12)}",
                    )
                }
                // The sender's latency is `raw - now`: how far the raw stream runs ahead of the
                // frame it expects to be sounding. Tell the pipeline so its pre-buffer can target
                // exactly this much and the progress bar lines up with the sound, instead of the
                // receiver parking its own (larger) cushion in front of the speaker.
                val latencyFrames = (rawRtpTime - nowRtpTime) and 0xFFFFFFFFL
                if (latencyFrames > 0L) {
                    pipeline.setSenderLatencyFrames(latencyFrames)
                    if (syncPackets <= 2) {
                        SinkLog.i(
                            "Sender latency $latencyFrames frames (${latencyFrames * 1000 / pipeline.sampleRateHz}ms)",
                        )
                    }
                }
                // Relate "the frame sounding now" to our local monotonic clock. The frame
                // `nowRtpTime` sounds at the sender's NTP instant `senderNtpNs`; subtracting the
                // clock offset lands it in our NTP domain, and subtracting that from a freshly
                // sampled NTP/monotonic pair converts it to `System.nanoTime()`, which is the
                // domain `paceToTimeline` works in. Mixing the NTP value straight into the anchor
                // was the bug: it is ~4e18, four orders of magnitude past the monotonic clock.
                if (clockSynced) {
                    val monoNow = System.nanoTime()
                    val ntpNow = localNtpNs()
                    val playLocalNs = monoNow + (senderNtpNs - clockOffsetNs - ntpNow)
                    pipeline.setTimingAnchor(nowRtpTime, playLocalNs)
                    if (syncPackets <= 2) {
                        SinkLog.i(
                            "Timing anchor: rtp=$nowRtpTime senderNtp=$senderNtpNs " +
                                "playLocal=$playLocalNs now=$monoNow",
                        )
                    }
                }
            }
            // The clock-sync reply to our 0xD2 request. It carries the sender's receive and
            // transmit NTP timestamps; pairing them with our own departure/arrival times yields the
            // offset between the two clocks.
            TYPE_TIMING_REPLY -> handleTimingReply(datagram)
            // The sender answers a resend request to the address it came from, and we send ours
            // from the control socket — so this is where the retransmission lands, not on the
            // audio port. Reading only the sync type here is why 2129 requests went out over a
            // four minute session and not one retransmission was ever counted: they were arriving
            // the whole time and being dropped on sight.
            TYPE_RESEND_REPLY -> handleAudio(datagram, resendLayout, isResend = true)
            else -> {
                unknownControlPackets++
                if (unknownControlPackets <= 3) {
                    SinkLog.w(
                        "Unexpected RTP type 0x${Integer.toHexString(type)} on the $name port " +
                            "(${datagram.length}B)",
                    )
                }
            }
        }
    }

    /**
     * Turns the sender's 0xD3 reply into a clock offset, matching shairport-sync's
     * `rtp_timing_receiver`.
     *
     * Reply layout (offsets from the packet start):
     *  * 16..19 — the sender's *receive* NTP time, seconds
     *  * 20..23 — the sender's receive NTP time, fraction (units of 2^-32 s)
     *  * 24..27 — the sender's *transmit* NTP time, seconds
     *  * 28..31 — the sender's transmit NTP time, fraction
     *
     * The request we sent recorded our departure time; the instant this handler runs is our arrival
     * time. Assuming a symmetric path, the sender's clock when it transmitted is `transmit`, and it
     * is now `arrival - rtt/2` in our clock — so remote = local + (transmit - (arrival - rtt/2)).
     */
    private fun handleTimingReply(datagram: DatagramPacket) {
        if (datagram.length < 32) return
        val data = datagram.data
        val arrivalMonoNs = System.nanoTime()
        val departureNs = lastRequestDepartureNs
        if (departureNs == 0L) return

        val rttNs = arrivalMonoNs - departureNs
        if (rttNs <= 0 || rttNs > TIMING_MAX_RTT_NS) {
            // Unusable: either we never sent a matching request, or the reply took too long to be
            // a meaningful clock sample.
            return
        }

        val distantReceiveNs = ntpToNanos(data, 16)
        val distantTransmitNs = ntpToNanos(data, 24)

        // The sender's clock at the moment it transmitted, projected to the midpoint of the
        // round-trip in *its* frame: transmit + (rtt/2 - processing). The processing time is
        // transmit - receive, so this simplifies to receive + rtt/2.
        val remoteTimeNs = distantReceiveNs + rttNs / 2

        // Both sides of this subtraction are NTP epoch nanoseconds: the offset is the difference
        // between the two *clocks*, and it belongs to the NTP domain, not the monotonic one. The
        // previous code subtracted `System.nanoTime()` here, mixing an arbitrary small monotonic
        // value with an absolute ~4e18 NTP value, which yielded a garbage ~4e18 offset and, from
        // there, an anchor the pipeline could never line up against — the progress bar stayed off.
        val offsetNs = remoteTimeNs - localNtpNs()

        clockOffsetNs = offsetNs
        clockSynced = true
        if (timingRequestCount <= TIMING_EARLY_COUNT) {
            SinkLog.i(
                "Timing reply: rtt=${rttNs / 1_000_000}ms, clock offset " +
                    "${offsetNs / 1_000_000}ms (sender ${if (offsetNs >= 0) "ahead" else "behind"})",
            )
            // Raw diagnostics: the NTP seconds/fraction as sent, and how they land against our
            // local NTP clock so a wrong epoch is visible instead of silently poisoning the anchor.
            SinkLog.i(
                "Timing diag: arriveMono=$arrivalMonoNs depart=$departureNs " +
                    "distantRecv=$distantReceiveNs distantXmit=$distantTransmitNs " +
                    "localNtp=${localNtpNs()} offset=$offsetNs",
            )
        }
    }

    /**
     * Reads an NTP timestamp (32 bit seconds + 32 bit fraction) at [offset] and returns it in
     * nanoseconds. The fraction's unit is 2^-32 of a second, so the nanosecond value is
     * `fraction * 1e9 / 2^32`.
     */
    private fun ntpToNanos(data: ByteArray, offset: Int): Long {
        val seconds = Codec.be32(data, offset).toLong() and 0xFFFFFFFFL
        val fraction = Codec.be32(data, offset + 4).toLong() and 0xFFFFFFFFL
        val nanos = (fraction * 1_000_000_000L) ushr 32
        return seconds * 1_000_000_000L + nanos
    }
}
