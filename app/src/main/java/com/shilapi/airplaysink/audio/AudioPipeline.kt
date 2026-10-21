package com.shilapi.airplaysink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.shilapi.airplaysink.util.SeqNo
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable

/**
 * Re-orders incoming RAOP audio packets, decodes them with [AlacDecoder] and pushes PCM into an
 * [AudioTrack].
 *
 * Two clocks run here, and the design is shairport-sync's: a packet waits in the queue until its
 * own RTP-timestamped instant comes round, then it is handed to the speaker, which holds
 * [BACKEND_BUFFER_MS] of it. The playhead is therefore never "kept fed" — it is *scheduled*, and
 * the amount of audio in hand is whatever the sender's latency minus the backend happens to be.
 *
 * Nothing here ever pauses to rebuild that. A hole in the sequence is written out as silence and
 * stepped over the moment it reaches the playhead; the retransmission that may still recover it is
 * chased separately, by [ResendTracker], on the network thread. That way a lost packet costs a
 * short silence at worst, and never stalls the stream behind it.
 */
class AudioPipeline(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val framesPerPacket: Int = 352,
    /**
     * How much audio to hold in front of the speaker, in milliseconds.
     *
     * This is the one knob that trades latency for stability, and there is no way to have both:
     * every millisecond here is a millisecond the speaker is behind the phone, and the same
     * millisecond of link trouble the pipeline can absorb before the speaker runs dry. The default
     * is what an AirPort Express ships with; a link that stalls for longer than that needs a
     * bigger number and will sound late by exactly as much.
     */
    private val cushionMs: Int = TARGET_CUSHION_MS.toInt(),
    /**
     * Which codec the packets are in.
     *
     * AirPlay 1 only ever sends ALAC, and so does an AirPlay 2 session from most third-party apps.
     * Apple Music does not: it asks for AAC-LC at 48 kHz in the `SETUP`, and the difference is not
     * a detail — feeding AAC to the ALAC decoder rejects nearly every frame and turns the few that
     * slip through into noise, with no other symptom to go on.
     */
    private val codec: AudioCodec = AudioCodec.ALAC,
) : Closeable {

    private companion object {
        /**
         * The default cushion: audio held in front of the speaker, in milliseconds.
         *
         * One number is both the playback latency and the amount of network jitter the link can
         * ride out, and the two cannot be pulled apart — audio that is being held is audio that
         * plays late. 250 ms is what an AirPort Express reports as its own latency
         * (`RaopSession.CONTROL_LATENCY_FRAMES`), so the figure is the protocol's, not an invented
         * one. The setting on the main screen overrides it per session; see the constructor.
         *
         * It has to be applied *before* playback starts. Measured on the head unit, the old 12
         * packets (96 ms) left the pipeline running a 65 ms cushion — and since a skip costs the
         * cushion its packet and nothing ever pays it back, that number only ever goes down. Any
         * hiccup longer than what is left is an audible dropout.
         */
        const val TARGET_CUSHION_MS = 250L

        /**
         * If the first packets are slow to arrive, start anyway rather than sitting silent.
         *
         * Added to the cushion, not used alone: waiting for a 2 s cushion cannot possibly finish
         * in 400 ms, so a fixed figure would mean a deep buffer never actually gets built and the
         * setting would quietly do nothing.
         */
        const val PREBUFFER_TIMEOUT_MS = 400L
        const val MAX_PENDING_PACKETS = 512
        const val STATS_INTERVAL_PACKETS = 500L

        /**
         * How much decoded audio the speaker holds, in milliseconds — shairport-sync's
         * `audio_backend_buffer_desired_length`.
         *
         * This is the only "level" the pipeline maintains, and it is a fixed figure rather than
         * something derived from the cushion. It is the boundary on the timeline: a packet is
         * handed over `BACKEND_BUFFER_MS` before the instant the sender stamped it for, so the
         * playhead and the write head stay a fixed distance apart no matter how deep the queue is.
         *
         * 660 ms is what this platform can hold without underrunning: the AudioTrack ring itself is
         * one second (`AudioTrack.getMinBufferSize` rounds up to it), and the decode thread has to
         * survive being descheduled by AudioFlinger. shairport-sync uses 150 ms, which is right for
         * a direct ALSA write and far too thin behind an Android mixer.
         */
        const val BACKEND_BUFFER_MS = 660L

        /**
         * How far behind the playback point a packet may be and still be called late rather than
         * stale. 64 packets is half a second: well beyond any resend this pipeline would ask for,
         * and well inside the seconds-old repeats that come back unsolicited.
         */
        const val LATE_WINDOW_PACKETS = 64L

        /**
         * Samples over which the audio is faded back up after a hole.
         *
         * The hole itself is silence, which is what shairport-sync writes too, but stepping from
         * digital silence into a non-zero sample is a click at every seam. Neither end of that step
         * needs a special mechanism: the silence run is faded out from whatever the speaker was
         * holding, and the next real packet is faded in here. 88 samples is 2 ms.
         */
        const val DECLICK_FRAMES = 88

        /** Fade back in over this many frames after a hole; short enough to be inaudible as a dip. */
        const val FADE_IN_FRAMES = 88

        /**
         * Fade the very first packet of a stream in from silence.
         *
         * The talker hands over whatever the waveform happened to be doing at that instant, and
         * starting a speaker from digital silence on a non-zero sample is a click at the top of
         * every track — the same defect as the seam above, at the other end of the stream.
         */
        const val START_FADE_IN_FRAMES = 220

        /**
         * The most a single packet may be held back waiting for its timestamped play instant.
         *
         * Pacing only ever *slows* playback toward the sender's timeline — it never chases a late
         * packet — but a bad anchor (clock not yet synced, or a wildly wrong offset) could otherwise
         * stall the loop indefinitely. 200 ms is more than the clock sync should ever need and small
         * enough that a mis-anchor degrades to a brief hesitation rather than a dead speaker.
         */
        const val MAX_PACE_WAIT_MS = 200L

        /**
         * How many decode failures are described in the log before going quiet.
         *
         * A codec mismatch fails on every single packet — the sound of it is unmistakable, but a
         * warning per packet buries everything else in the file, and the stats line still carries
         * the count.
         */
        const val MAX_DECODE_FAILURE_LOGS = 10

        /**
         * How many write-offs are described one by one before switching to a periodic summary.
         *
         * Being rare is what makes each one worth its own line — but a bad link makes them common,
         * and the rule that silences them at ten is how the 2026-10-21 session hid 1400 lost
         * packets: the counter in the stats line proves a loss happened and says nothing about
         * when, how large, or how much cushion was left when it did.
         */
        const val MAX_GAP_LOG_LINES = 10

        /** How often the rest are summarised, in the same spirit as the stats line. */
        const val GAP_SUMMARY_INTERVAL_MS = 60_000L

        /**
         * How far the sequence may jump before it stops looking like a loss.
         *
         * A lost packet is a hole of one or two, occasionally a few dozen when the link is bad.
         * A jump of thousands is the sender having moved somewhere else in the track, and the
         * old packets are never coming — at 1024 frames a packet this is over half a minute of
         * audio, which no network hiccup produces.
         */
        const val TIMELINE_RESYNC_PACKETS = 1000L
    }

    /** The cushion, in frames — also the amount of pre-buffer playback waits for. */
    private val targetCushionFrames = sampleRate.toLong() * cushionMs / 1000

    /** How long playback waits for that cushion before starting with whatever turned up. */
    private val prebufferTimeoutMs = cushionMs + PREBUFFER_TIMEOUT_MS

    /**
     * The audio the speaker is handed, in frames: the fixed distance between write head and playhead.
     *
     * Everything else the receiver holds stays in [pending] as compressed packets. The split is
     * shairport-sync's (`audio_backend_buffer_desired_length` against the stream latency), and its
     * value does not move: a deep cushion makes the *queue* deeper, never the speaker buffer.
     */
    private val backendBufferFrames = sampleRate.toLong() * BACKEND_BUFFER_MS / 1000

    /** The same cushion expressed the way the queue counts, i.e. in packets. */
    private val prebufferPackets =
        (targetCushionFrames / framesPerPacket).toInt().coerceAtLeast(2)

    private val decoder: AudioDecoder = when (codec) {
        AudioCodec.ALAC -> AlacDecoder(
            sampleSize = 16,
            numChannels = channels,
            maxSamplesPerFrame = framesPerPacket,
        )

        AudioCodec.AAC -> AacDecoder(
            sampleRate = sampleRate,
            channels = channels,
            maxFramesPerPacket = framesPerPacket,
        )
    }

    /**
     * One packet's worth of PCM, sized by the decoder rather than by the sender's `spf`.
     *
     * They are the same number whenever the sender is telling the truth, but an under-sized buffer
     * is a decode that throws on the audio thread, so the decoder gets the final say.
     */
    private val pcmFrames = maxOf(framesPerPacket, decoder.maxFramesPerPacket)
    private val pcm = ShortArray(pcmFrames * maxOf(2, channels))
    private val pending = java.util.TreeMap<Long, ByteArray>()
    private val lock = Object()

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var track: AudioTrack? = null

    private var nextSequence = -1L
    private var firstPacketAt = 0L
    private var decodedPackets = 0L
    private var receivedPackets = 0L
    private var skippedPackets = 0L

    // Write-off reporting; see [MAX_GAP_LOG_LINES] and [GAP_SUMMARY_INTERVAL_MS].
    private var writeOffLines = 0
    private var gaveUpPackets = 0L
    private var gaveUpGaps = 0L
    private var lastGapReportAt = 0L

    /** How many failed decodes have been described in the log so far; see [MAX_DECODE_FAILURE_LOGS]. */
    private var decodeFailures = 0

    /** The RTP timestamp carried by the first packet, and its sequence — the timeline anchor. */
    private var firstRtpSequence = -1L
    private var firstRtpTime = 0L

    /** The sequence of the packet just handed to the decode loop, for pacing. */
    private var lastTakenSequence = -1L

    /**
     * Packets that arrived after the playhead had already stepped over them.
     *
     * The playhead never waits — a hole is written out as silence as soon as it is reached — so a
     * retransmission only helps if it lands before that. This is the count of the ones that did
     * not; it climbing alongside `skipped` is the sender answering too late to be of use, and it
     * staying at zero while `skipped` climbs is the sender not answering at all.
     */
    private var tooLatePackets = 0L
    private var lastStatsAt = 0L

    /**
     * Frames handed to the AudioTrack since the last flush, and the playback head as it stood at
     * that moment. Their difference is how much audio is still queued in front of the speaker.
     */
    private var writtenFrames = 0L
    private var headBaseFrames = 0L

    /** Packets of silence the decode loop still owes for a hole it has already stepped over. */
    private var pendingSilence = 0

    /** The last sample handed to the AudioTrack, per channel: where a silence run has to begin. */
    private val lastSamples = ShortArray(maxOf(2, channels))

    /**
     * Frames of fade-in still owed to the next packet written.
     *
     * Set after a hole and at the top of a stream, consumed by [decodeLoop]. Written from the
     * control thread on flush, hence @Volatile.
     */
    @Volatile
    private var fadeInFrames = 0

    private val silenceBuffer = ShortArray(pcmFrames * maxOf(2, channels))
    private var silentPackets = 0L

    // --- RTP-timestamp-driven playback pacing -----------------------------------------
    //
    // The sender stamps every frame with an RTP timestamp and, via the sync packet plus the clock
    // offset the transport measures, tells us the exact local instant each frame should be heard.
    // Without this the pipeline plays on arrival, which is always *early* relative to what the
    // sender assumed — that is the whole of the "progress bar is late" symptom. These fields turn
    // an RTP timestamp into a local `System.nanoTime()` instant so the decode loop can hold back.
    @Volatile
    private var timingAnchorRtpTime = 0L

    @Volatile
    private var timingAnchorLocalNs = 0L

    @Volatile
    private var timingSynced = false

    /**
     * Whether the sender's clock is running, which is the only thing that says whether *we* should
     * be playing.
     *
     * An AirPlay 2 sender announces this in `SETRATEANCHORTIME`: bit 0 of `rate` set means the
     * clock is running (shairport-sync sends it the PTP "B"eginning control message and calls it
     * `ap2_play_enabled = 1`), clear means it is stopped. AirPlay 2 sends no sync packets at all, so
     * this is the *only* play/pause signal that exists — answering 200 and ignoring `rate` leaves
     * the pipeline playing through every pause and every seek.
     *
     * True by default: a session that never sends `SETRATEANCHORTIME` (every classic RAOP sender)
     * must keep playing exactly as before.
     */
    @Volatile
    private var playEnabled = true

    /**
     * Called when the sender starts or stops its clock.
     *
     * Stopping drops what is already in the speaker rather than letting it finish: the audio in the
     * track belongs to the position the sender is leaving, so playing it out is exactly the old
     * track bleeding past the pause.
     *
     * Starting has two cases, and they are not the same. Pressing play again resumes the position
     * that was just left, so the queue is still the cushion it was and writing carries straight on
     * — stopping there would only hold up audio that is already in hand. A sender that jumped
     * elsewhere while it was stopped needs the queue dropped, because everything in it belongs to
     * the position being left.
     *
     * The queue is what tells them apart: after a resume its first block is the one the playhead
     * was about to play, after a jump it is somewhere else, in either direction — hence a signed
     * difference rather than a plain ordering.
     */
    fun setPlayEnabled(enabled: Boolean) {
        if (playEnabled == enabled) return
        playEnabled = enabled
        if (enabled) {
            val jumped = synchronized(lock) {
                val firstQueued = if (pending.isEmpty()) -1L else pending.firstKey()
                val resumes = nextSequence >= 0L && firstQueued >= 0L &&
                    SeqNo.difference(firstQueued, nextSequence) in 0L..MAX_PENDING_PACKETS.toLong()
                if (!resumes) {
                    // Nothing queued can be played from the new position, and leaving it there
                    // would make the pipeline take the old blocks as the start of the new
                    // timeline — the previous track playing again, a few packets of it.
                    pending.clear()
                    nextSequence = -1L
                    firstPacketAt = 0L
                    firstRtpSequence = -1L
                    firstRtpTime = 0L
                    pendingSilence = 0
                }
                lock.notifyAll()
                !resumes
            }
            fadeInFrames = START_FADE_IN_FRAMES
            java.util.Arrays.fill(lastSamples, 0)
            runCatching { track?.play() }
            SinkLog.i(
                if (jumped) {
                    "Audio output resumed at a new position — dropping the queue"
                } else {
                    "Audio output resumed — carrying on with the queue as it stands"
                },
            )
        } else {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            synchronized(lock) {
                writtenFrames = 0L
                headBaseFrames = currentHeadFrames()
                lock.notifyAll()
            }
            SinkLog.i("Audio output paused — the sender stopped its clock")
        }
    }

    /** Reported on the decode thread; the UI must post to the main thread itself. */
    var onStats: ((String) -> Unit)? = null

    val isRunning: Boolean get() = running

    /** The sample rate in use, exposed so the transport can relate RTP timestamps to wall time. */
    val sampleRateHz: Int get() = sampleRate

    /**
     * What the speaker holds, in milliseconds — the figure the retransmission window is derived
     * from. See [BACKEND_BUFFER_MS].
     */
    val backendBufferMs: Long get() = BACKEND_BUFFER_MS

    fun start() {
        if (running) return
        running = true

        val channelMask =
            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // A fixed, small playback buffer, not one that scales with the cushion.
        //
        // The cushion (a.k.a. the pre-buffer in the pending queue) is what rides out network
        // jitter — it holds *compressed* ALAC packets, so a couple of seconds costs little memory.
        // This AudioTrack buffer, by contrast, holds *decoded* PCM and is only the slack the decode
        // loop needs to stay ahead of the speaker. Scaling it to 3x the cushion (6 s at a 2 s
        // cushion) let the decode loop shovel the whole pending queue into it and park ~6 s of audio
        // in front of the speaker on top of the cushion, which is the whole of the "progress bar is
        // several seconds ahead" symptom. One second is more than any measured stall (the deepest
        // burst seen was ~400 ms) and keeps the latency floor at the cushion instead of the cushion
        // plus a full reservoir.
        val bufferSize = maxOf(
            minBuffer,
            sampleRate * channels * 2, // 1 second of 16-bit PCM
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        track = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { it.play() }

        SinkLog.i(
            "Audio output started: ${codec.label} ${sampleRate}Hz ${channels}ch, " +
                "AudioTrack buffer ${bufferSize} bytes (min ${minBuffer}), " +
                "backend target ${BACKEND_BUFFER_MS}ms; " +
                "cushion ${cushionMs}ms = $prebufferPackets packets of pre-buffer, " +
                "starting if nothing better arrives within ${prebufferTimeoutMs}ms",
        )
        lastStatsAt = SystemClock.elapsedRealtime()
        fadeInFrames = START_FADE_IN_FRAMES
        java.util.Arrays.fill(lastSamples, 0)

        thread = Thread({ decodeLoop() }, "raop-decode").also { it.start() }
    }

    fun submit(sequence: Long, rtpTime: Long, payload: ByteArray) {
        synchronized(lock) {
            receivedPackets++
            // The sender's own word that this audio is stale — it is discarding what it had
            // buffered, usually because the listener seeked. Such a packet is not late, it is
            // unwanted: playing it would put the old track back on top of the new one.
            //
            // The comparison is modular rather than plain, because the counter is 23 bits wide and
            // wraps: a packet that is *behind* the endpoint can carry the larger number once the
            // wrap is crossed, and treating it as "past the endpoint" would start playing the
            // stale backlog all over again. [SeqNo.difference] is the same arithmetic
            // shairport-sync uses.
            val from = staleFrom
            val until = staleUntil
            if (until >= 0L) {
                val pastEndpoint = SeqNo.difference(sequence, until) >= 0L
                val unwanted = if (from < 0L) {
                    !pastEndpoint
                } else {
                    SeqNo.difference(sequence, from) >= 0L && !pastEndpoint
                }
                if (unwanted) {
                    flushedPackets++
                    return
                }
                if (pastEndpoint) {
                    // Past the endpoint: the flush is over and the new timeline has begun.
                    staleFrom = -1L
                    staleUntil = -1L
                }
            }
            if (nextSequence >= 0L && sequence < nextSequence) {
                // Its moment has already been played, so there is nowhere to put it. Counted
                // rather than stored: see [tooLatePackets].
                //
                // Only a near miss is worth counting. A resend reply routinely repeats packets
                // that were played seconds ago, and those were inflating this figure to three
                // times `skipped` — which made the one number that says whether the gap wait is
                // long enough meaningless. A packet that missed its slot by more than
                // [LATE_WINDOW_PACKETS] was never going to make it and says nothing about timing.
                if (nextSequence - sequence <= LATE_WINDOW_PACKETS) tooLatePackets++
                return
            }
            if (pending.size >= MAX_PENDING_PACKETS) {
                // The queue is full: the sender is further ahead of us than we can hold. The
                // oldest entry is the one the playhead is about to ask for, so drop it and move
                // the playhead to what is left — the audio in front of the playhead is stale by
                // definition anyway, and the sender keeps pushing.
                val dropped = pending.pollFirstEntry()
                if (dropped != null) {
                    skippedPackets++
                    if (dropped.key <= nextSequence && pending.isNotEmpty()) {
                        nextSequence = pending.firstKey()
                    }
                }
            }
            pending[sequence] = payload
            // Record the RTP timestamp of the first packet: every later packet's timestamp is this
            // plus framesPerPacket per sequence step, so this single anchor lets the decode loop
            // relate any packet to the playback timeline.
            if (firstRtpSequence < 0L) {
                firstRtpSequence = sequence
                firstRtpTime = rtpTime
            }
            if (firstPacketAt == 0L) {
                firstPacketAt = SystemClock.elapsedRealtime()
                SinkLog.i("First audio packet received (seq $sequence)")
            }
            lock.notifyAll()
        }
    }

    /**
     * The sender's word that a stretch of audio is stale and must not be played.
     *
     * A negative [staleFrom] means "everything up to [staleUntil]"; otherwise the range
     * `[staleFrom, staleUntil]` is the stale one. Set by [flushStale] and cleared by itself once
     * a packet beyond the endpoint turns up.
     */
    @Volatile
    private var staleFrom = -1L

    @Volatile
    private var staleUntil = -1L

    /** How many packets were dropped because the sender said they were stale. */
    private var flushedPackets = 0L

    /**
     * Drops the audio the sender says is stale, and starts a fresh timeline.
     *
     * AirPlay 2's buffered stream lets the phone throw away what it has buffered — after a seek,
     * or when the backlog is too old to be worth sending. It says so with `FLUSHBUFFERED`, naming
     * the sequence number the flush runs to.
     *
     * Not answering that request is not a small omission. The phone carries on from the new
     * position, so every packet it sends after the flush has a sequence number thousands ahead of
     * where the playhead is: the pipeline sees a hole that will never be filled, waits for it,
     * gives up, and books thousands of packets as lost. The measured symptom was `skipped 20028`
     * and an audio track that slowed to a crawl. Worse, the old position kept playing until the
     * playhead caught up — a seek that does not seek.
     *
     * So the flush is answered in two halves: what is already queued goes now, and what is still
     * on the wire up to the endpoint is dropped as it arrives ([submit]).
     *
     * @param fromSeq the first stale sequence, or -1 for "everything up to [untilSeq]".
     */
    fun flushStale(fromSeq: Long, untilSeq: Long) {
        synchronized(lock) {
            pending.clear()
            nextSequence = -1L
            firstRtpSequence = -1L
            firstRtpTime = 0L
            pendingSilence = 0
            staleFrom = fromSeq
            staleUntil = untilSeq
            lock.notifyAll()
        }
        // Whatever is already in the speaker belongs to the old position too.
        runCatching {
            track?.pause()
            track?.flush()
            // Only restart the speaker if the sender's clock is still running. A flush that lands
            // inside a pause window (which is where they land — ~150 ms apart) must leave it
            // stopped, or the loop below starts writing again the moment it is next reached.
            if (playEnabled) track?.play()
        }
        synchronized(lock) {
            writtenFrames = 0L
            headBaseFrames = currentHeadFrames()
        }
        java.util.Arrays.fill(lastSamples, 0)
        fadeInFrames = START_FADE_IN_FRAMES
        SinkLog.i(
            "Audio pipeline flushed up to sequence $untilSeq" +
                (if (fromSeq >= 0L) " (range from $fromSeq)" else " (everything before it)"),
        )
    }

    /** Called on FLUSH: everything queued belongs to the old timeline. */
    fun flush() {
        synchronized(lock) {
            pending.clear()
            nextSequence = -1L
            firstPacketAt = 0L
            firstRtpSequence = -1L
            firstRtpTime = 0L
            pendingSilence = 0
            staleFrom = -1L
            staleUntil = -1L
            lock.notifyAll()
        }
        runCatching {
            track?.pause()
            track?.flush()
            if (playEnabled) track?.play()
        }
        synchronized(lock) {
            // Everything queued was just thrown away, so the playback head restarts: re-anchor the
            // counters on whatever it reads now instead of assuming it went back to zero.
            writtenFrames = 0L
            headBaseFrames = currentHeadFrames()
        }
        // The old timeline's last sample says nothing about the new one, and the new one starts
        // wherever the talker happens to be in its waveform: fade in rather than step.
        java.util.Arrays.fill(lastSamples, 0)
        fadeInFrames = START_FADE_IN_FRAMES
        paceDiagnosticsRemaining = 30
        SinkLog.i("Audio pipeline flushed")
    }

    fun setVolume(linear: Float) {
        val clamped = linear.coerceIn(0f, 1f)
        runCatching { track?.setVolume(clamped) }
    }

    /**
     * Anchors the RTP timeline to the local clock.
     *
     * The transport calls this from the sync packet once the clock offset is known: [rawRtpTime] is
     * the RTP timestamp of the frame the sender was playing at its instant `playLocalNs`, expressed
     * in our `System.nanoTime()` domain. From then on every frame's due instant is
     * `playLocalNs + (rtpTime - rawRtpTime) / sampleRate`, and the decode loop holds each packet
     * until that instant instead of playing it the moment it arrives.
     */
    fun setTimingAnchor(rawRtpTime: Long, playLocalNs: Long) {
        timingAnchorRtpTime = rawRtpTime
        timingAnchorLocalNs = playLocalNs
        timingSynced = true
    }

    /**
     * The sender's own latency, in frames, learned from the sync packet (`raw - now`).
     *
     * This is the delay the sender *expects* the receiver to hold: the sender stamps each frame
     * with a "raw" RTP timestamp and, separately, tells us which frame should be sounding now, and
     * the gap is its latency (about 1.75 s on an iPhone). The receiver's total buffered audio
     * should sit at exactly this much, not at the locally configured cushion, or the progress bar
     * runs ahead of the sound by the difference. Once known it becomes the pre-buffer target, so
     * the receiver holds `senderLatency` in front of the speaker and the two clocks agree.
     */
    @Volatile
    private var senderLatencyFrames = 0L

    fun setSenderLatencyFrames(frames: Long) {
        if (frames > 0) senderLatencyFrames = frames
    }

    /**
     * The number of packets to pre-buffer before starting playback.
     *
     * Normally the cushion; once the sender's own latency is known it wins, because matching it is
     * what makes the progress bar line up with the sound. A sender that never announces a latency
     * falls back to the cushion, so a generous local setting still protects against jitter.
     */
    private val effectivePrebufferPackets: Int
        get() {
            val frames = if (senderLatencyFrames > 0L) senderLatencyFrames else targetCushionFrames
            return (frames / framesPerPacket).toInt().coerceAtLeast(2)
        }

    override fun close() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.let { runCatching { it.join(1000) } }
        thread = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
        // The AAC path owns a platform codec, which is a hardware resource and has to be handed
        // back explicitly; a stream that comes and goes without this leaks one decoder per switch.
        runCatching { decoder.close() }
        synchronized(lock) {
            pending.clear()
            nextSequence = -1L
            firstPacketAt = 0L
            firstRtpSequence = -1L
            firstRtpTime = 0L
            pendingSilence = 0
            decodedPackets = 0L
            receivedPackets = 0L
            skippedPackets = 0L
            writeOffLines = 0
            gaveUpPackets = 0L
            gaveUpGaps = 0L
            lastGapReportAt = 0L
            tooLatePackets = 0L
            silentPackets = 0L
            flushedPackets = 0L
            staleFrom = -1L
            staleUntil = -1L
            fadeInFrames = 0
            java.util.Arrays.fill(lastSamples, 0)
            writtenFrames = 0L
            headBaseFrames = 0L
        }
        SinkLog.i("Audio output stopped")
    }

    private fun decodeLoop() {
        // This thread is what keeps the AudioTrack fed; if it is descheduled for longer than the
        // buffer holds, the speaker goes quiet. Without the audio priority it competes with
        // everything else on the head unit at default priority, where a few tens of milliseconds of
        // starvation are routine. Raising it needs MODIFY_AUDIO_SETTINGS, so a refusal is survivable
        // rather than fatal.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onFailure { SinkLog.w("Could not raise the decode thread priority: ${it.message}") }

        val out = pcm
        while (running) {
            // While the sender's clock is stopped we must not write: the packets still arriving
            // belong to the position it is leaving, and the AudioTrack has just been emptied. The
            // queue keeps filling (bounded by [MAX_PENDING_PACKETS]) so that the moment the clock
            // restarts there is a cushion to play from.
            if (!playEnabled) {
                synchronized(lock) { waitLocked(50) }
                continue
            }
            var payload: ByteArray? = null
            synchronized(lock) {
                if (pending.isEmpty()) {
                    waitLocked(50)
                } else if (nextSequence < 0L) {
                val waitedLongEnough = firstPacketAt != 0L &&
                    SystemClock.elapsedRealtime() - firstPacketAt > prebufferTimeoutMs
                    if (pending.size < effectivePrebufferPackets && !waitedLongEnough) {
                        waitLocked(20)
                    } else {
                        nextSequence = pending.firstKey()
                        payload = takeNextLocked()
                    }
                } else {
                    payload = takeNextLocked()
                }
            }

            if (payload == null) {
                // Nothing to decode. Either the queue is empty — the loop was woken by its timeout
                // and no audio has arrived yet — or the playhead has just stepped over a hole.
                // Only the second case owes the speaker anything, and it owes it silence: writing
                // that keeps the timeline running instead of stalling behind a packet that may
                // never come.
                val owe = synchronized(lock) {
                    val n = pendingSilence
                    pendingSilence = 0
                    n
                }
                if (owe > 0 && !writeSilence(owe)) return
                continue
            }

            val body = payload
            val samples = decoder.decodeFrame(body, 0, body.size, out)
            if (samples == 0) {
                // The codec has nothing for this packet yet. Normal while it primes, and not a
                // lost packet: its audio is still queued inside the codec.
                reportStatsIfDue()
                continue
            }
            if (samples < 0) {
                if (decodeFailures < MAX_DECODE_FAILURE_LOGS) {
                    decodeFailures++
                    SinkLog.w(
                        "Dropping undecodable ${codec.label} frame (${decoder.lastError})",
                    )
                }
                // A stream that fails to decode used to be described by nothing but this warning:
                // the stats below sat behind a successful write, so the one case where they matter
                // most was the one case that never printed them.
                reportStatsIfDue()
                continue
            }
            val player = track ?: continue
            val shorts = samples * channels
            applyFadeIn(out, shorts)
            holdUntilDue()
            val written = try {
                player.write(out, 0, shorts)
            } catch (error: Exception) {
                SinkLog.e("AudioTrack write failed", error)
                -1
            }
            if (written < 0) {
                SinkLog.e("AudioTrack write returned $written; stopping pipeline")
                return
            }
            rememberLastSamples(out, shorts)
            synchronized(lock) { writtenFrames += samples.toLong() }
            decodedPackets++
            reportStatsIfDue()
        }
        SinkLog.i("Decode loop finished")
    }

    /** Records the final sample of a packet, which is where the next seamless sound has to start. */
    private fun rememberLastSamples(buffer: ShortArray, shorts: Int) {
        val base = shorts - channels
        if (base < 0) return
        for (channel in 0 until channels) {
            lastSamples[channel] = buffer[base + channel]
        }
    }

    /**
     * Ramps the head of [buffer] up from silence, consuming however much of [fadeInFrames] fits.
     *
     * Called on the decode thread only, so the counter needs no lock beyond its @Volatile for the
     * flush path.
     */
    private fun applyFadeIn(buffer: ShortArray, shorts: Int) {
        val total = fadeInFrames
        if (total <= 0) return
        val frames = minOf(total, shorts / channels)
        for (frame in 0 until frames) {
            val gain = frame.toFloat() / total
            val base = frame * channels
            for (channel in 0 until channels) {
                buffer[base + channel] = (buffer[base + channel] * gain).toInt().toShort()
            }
        }
        fadeInFrames = total - frames
    }

    /**
     * Holds the packet just decoded until the instant it is due to enter the speaker.
     *
     * shairport-sync's rule (`player.c:2682-2763`), which is a schedule rather than a level: a
     * frame stamped `T` has to be in the backend by `T - BACKEND_BUFFER_MS`, so the playhead lands
     * on `T` and the receiver stays aligned with the sender's idea of where the track is.
     *
     * Two branches, and the second is what keeps the guarantee: once the packet's instant has
     * passed there is nothing left to wait for, and if the backend is short of its target the write
     * goes in immediately regardless of the clock — the backend is refilled first, and the timeline
     * is what it converges to. The wait is capped, so a bad anchor degrades to a short hesitation
     * instead of stalling the speaker.
     */
    private fun holdUntilDue() {
        val dueNs = dueNsFor(lastTakenSequence) ?: return
        val behindNs = synchronized(lock) {
            // shairport-sync's `current_buffer_delay < desired_buffer_latency` branch: the backend
            // is below its target, so the next packet goes in now and the level is restored before
            // the schedule is consulted again.
            if (bufferedFramesLocked() < backendBufferFrames) return
            System.nanoTime() - dueNs
        }
        if (paceDiagnosticsRemaining > 0) {
            paceDiagnosticsRemaining--
            SinkLog.i(
                "Pace seq=$lastTakenSequence lateBy=${behindNs / 1_000_000}ms " +
                    "(due=$dueNs now=${System.nanoTime()})",
            )
        }
        if (behindNs >= 0L) return
        // `behindNs < 0` means the due instant is still ahead: wait it out. A signed division here
        // would yield a negative figure that the guard below silently drops, which once let the
        // decode loop sprint through the queue and run the speaker ahead of the sender.
        val waitMs = (-behindNs / 1_000_000L).coerceAtMost(MAX_PACE_WAIT_MS)
        if (waitMs <= 0L) return
        try {
            Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
            // The loop is about to wind down anyway; don't let a stray interrupt escape.
        }
    }

    /**
     * The local instant at which the packet [sequence] is due to enter the speaker, or null when
     * the timeline is not known yet.
     *
     * The sender stamps every frame with an RTP timestamp and, through the sync packet and the
     * measured clock offset, says which local instant each frame is for. Subtracting the backend
     * target turns "when it should be heard" into "when it has to be handed over".
     */
    private fun dueNsFor(sequence: Long): Long? {
        if (!timingSynced) return null
        if (sequence < 0L || firstRtpSequence < 0L) return null
        val rtpTime = firstRtpTime + (sequence - firstRtpSequence) * framesPerPacket
        val playNs =
            timingAnchorLocalNs + (rtpTime - timingAnchorRtpTime) * 1_000_000_000L / sampleRate
        return playNs - backendBufferFrames * 1_000_000_000L / sampleRate
    }

    /** Number of leading packets whose pacing the decoder reports before going quiet. */
    @Volatile
    private var paceDiagnosticsRemaining = 30

    /**
     * Writes [count] packets of stand-in audio, imitating the last frame that did decode: the run
     * starts on the very sample the speaker is holding, ramps down to silence across the hole, and
     * hands the next real packet a [fadeInFrames] fade-in.
     *
     * Frames are accounted for exactly as real ones are, which is the point: the cushion neither
     * gains nor loses here.
     */
    private fun writeSilence(count: Int): Boolean {
        val player = track ?: return false
        val bodyFrames = framesPerPacket
        if (bodyFrames <= 0 || count <= 0) return true
        val bodyShorts = bodyFrames * channels
        if (silenceBuffer.size < bodyShorts) return true

        for (packet in 0 until count) {
            java.util.Arrays.fill(silenceBuffer, 0)
            if (packet == 0) {
                // The run opens with the sample the speaker is still holding and blends it away
                // over DECLICK_FRAMES, so the step into silence is a ramp rather than a click.
                // shairport-sync writes plain silence here, which is right for its own backend;
                // this one has to hide the seam itself.
                val bridge = minOf(DECLICK_FRAMES, bodyFrames)
                for (frame in 0 until bridge) {
                    val gain = 1f - frame.toFloat() / bridge
                    val base = frame * channels
                    for (channel in 0 until channels) {
                        silenceBuffer[base + channel] =
                            (lastSamples[channel] * gain).toInt().toShort()
                    }
                }
            }
            val written = try {
                player.write(silenceBuffer, 0, bodyShorts)
            } catch (error: Exception) {
                SinkLog.e("AudioTrack write failed while filling a hole", error)
                -1
            }
            if (written < 0) return false
            rememberLastSamples(silenceBuffer, bodyShorts)
            synchronized(lock) { writtenFrames += bodyFrames.toLong() }
        }
        // Silence is what the speaker now holds; ease the next real packet up out of it.
        fadeInFrames = FADE_IN_FRAMES
        silentPackets += count
        return true
    }

    /**
     * Takes the packet at [nextSequence], or steps over the hole where it should be.
     *
     * Never waits, and that is the whole difference from what this used to do. shairport-sync's
     * `buffer_get_frame` only waits for a packet that is actually *there* — `curframe->ready` gates
     * the wait — and when the slot is empty it substitutes silence and advances `ab_read` on the
     * spot (`player.c:2809-2816`). Doing the same here means a loss costs one short hole instead of
     * stalling the stream behind it, and the retransmission gets the whole queue as its window
     * rather than whatever a gap wait could afford out of the speaker buffer.
     *
     * Must be called while holding [lock].
     */
    private fun takeNextLocked(): ByteArray? {
        while (pending.isNotEmpty() && pending.firstKey() < nextSequence) {
            pending.pollFirstEntry()
        }
        val exact = pending.remove(nextSequence)
        if (exact != null) {
            lastTakenSequence = nextSequence
            nextSequence++
            return exact
        }
        if (pending.isEmpty()) return null

        val jump = pending.firstKey()
        val lost = jump - nextSequence
        if (lost > TIMELINE_RESYNC_PACKETS) {
            // A hole this large is not a loss. The sender has moved to a different part of the
            // stream — a seek, or a flush we did not hear about — and the old sequence numbers
            // are never coming. Writing hundreds of packets of silence for them would stall the
            // speaker and then play silence where music should be, so the playhead simply moves to
            // what arrived and the timeline is re-anchored on it.
            SinkLog.w(
                "Sequence jumped forward by $lost; treating it as a new position rather than a " +
                    "loss, and re-anchoring on sequence $jump",
            )
            nextSequence = jump
            firstRtpSequence = -1L
            firstRtpTime = 0L
            return null
        }

        skippedPackets += lost
        // The decode loop owes the speaker one packet of silence per lost sequence, written as soon
        // as it is handed the bill. The hole is bounded by [TIMELINE_RESYNC_PACKETS] above, so this
        // cannot become a long run of silence by accident.
        pendingSilence = lost.toInt()
        val now = SystemClock.elapsedRealtime()
        val bufferedMs = bufferedFramesLocked() * 1000 / sampleRate
        // Each loss is a packet's worth of audio nobody will hear, so the first few get their own
        // line. After that they are summarised: going quiet at ten is how a whole session's losses
        // once became invisible, and the summary keeps the cost of a bad link out of the log
        // without hiding it.
        gaveUpPackets += lost
        gaveUpGaps++
        if (writeOffLines < MAX_GAP_LOG_LINES) {
            writeOffLines++
            SinkLog.w(
                "Stepped over $lost packet(s) at seq $jump " +
                    "(buffered ${bufferedMs}ms, queued ${pending.size})",
            )
        } else if (lastGapReportAt == 0L) {
            // Open the summary window here rather than at the tenth line: the individual lines are
            // already on the record, and counting them again would inflate the first summary.
            lastGapReportAt = now
            gaveUpPackets = 0L
            gaveUpGaps = 0L
        } else if (now - lastGapReportAt >= GAP_SUMMARY_INTERVAL_MS) {
            SinkLog.w(
                "Stepped over $gaveUpPackets packet(s) in $gaveUpGaps hole(s) over the last " +
                    "${(now - lastGapReportAt) / 1000}s (last $lost at seq $jump, " +
                    "buffered ${bufferedMs}ms)",
            )
            lastGapReportAt = now
            gaveUpPackets = 0L
            gaveUpGaps = 0L
        }
        nextSequence = jump
        return null
    }

    /** Must be called while holding [lock]. */
    private fun waitLocked(timeoutMs: Long) {
        try {
            lock.wait(timeoutMs)
        } catch (_: InterruptedException) {
            // Nothing interrupts this thread; treat a stray one as a no-op rather than letting it
            // escape and kill the loop.
        }
    }

    /** The AudioTrack's own counter, safe to read from either thread. */
    private fun currentHeadFrames(): Long =
        (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFF_FFFFL

    /** Frames queued in the AudioTrack and not yet played. Must be called while holding [lock]. */
    private fun bufferedFramesLocked(): Long {
        if (track == null) return 0L
        val played = currentHeadFrames() - headBaseFrames
        return (writtenFrames - played).coerceIn(0L, writtenFrames)
    }

    private fun reportStatsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAt < 5000) return
        lastStatsAt = now
        val queued: Int
        val bufferedMs: Long
        val received: Long
        synchronized(lock) {
            queued = pending.size
            bufferedMs = bufferedFramesLocked() * 1000 / sampleRate
            received = receivedPackets
        }
        // `buffered` is the audible safety margin: the audio in hand, which is both what a gap is
        // waited for and what a network stall has to burn through before the speaker goes quiet.
        // `underruns` is the AudioTrack's own count of the times it got there — if that number is
        // climbing, the stutter is real and it is starvation, not congestion.
        val underruns = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)
        val stats = "received $received packets, decoded $decodedPackets, " +
            "skipped $skippedPackets, silence $silentPackets, tooLate $tooLatePackets, " +
            "flushed $flushedPackets, " +
            "queued $queued, buffered ${bufferedMs}ms, underruns $underruns"
        SinkLog.i(stats)
        onStats?.invoke(stats)
    }
}
