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
 * Pacing comes for free: `AudioTrack.write` in stream mode blocks once its internal buffer is full,
 * so the decode loop runs at playback speed as long as the sender keeps the network pace.
 *
 * That same property is what makes the pre-buffer the *only* thing standing between the network and
 * the speaker. The sender paces its stream at real time and never hands over a surplus, so nothing
 * refills the pipeline once playback is under way: whatever is queued before [decodeLoop] starts
 * writing is what the rest of the track runs on. See [TARGET_CUSHION_MS].
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
         * How long a missing sequence is waited for before the pipeline writes it off and jumps
         * forward to what did arrive.
         *
         * The transport has already asked the sender to resend it by the time the hole reaches this
         * class, and over a hotspot the reply is back in single-digit milliseconds — so this window
         * is deliberately generous. Jumping the moment the expected packet is not the head of the
         * queue is what turns a perfectly recoverable hiccup into a splice in the output, and it is
         * also what made the whole resend path pointless: the retransmission always arrived after
         * playback had already been moved past it and was dropped on sight.
         *
         * The wait is paid for out of the cushion, and the cushion is a full [TARGET_CUSHION_MS]
         * whenever bursts keep topping it up — measured 296-370 ms in hand at the moment this
         * window ran out on the head unit, i.e. the old 120 ms was leaving recovery time on the
         * table for no reason. `tooLate` in the stats line is the check on this number: it counts
         * retransmissions that arrived after playback had already gone past them. If it climbs in
         * step with `skipped`, this window is still too short. If it stays at zero, the sender is
         * not answering and no wait will help.
         *
         * At 150 ms the reservoir still had 365 ms at its lowest over a measured 37 minutes, and
         * about one gap in twelve was still being written off; 200 ms covers the fourth resend
         * request the transport sends at 135 ms, which is the last one that can land in time.
         *
         * This is the floor, not the figure in use: see [gapWaitMs]. A cushion deep enough to hold
         * two seconds can afford to chase a burst for half a second, and doing so is the whole
         * point of asking for the bigger cushion.
         */
        const val GAP_WAIT_MS = 200L

        /**
         * The most a hole may ever be waited for, in milliseconds.
         *
         * The sender only holds a few seconds of the current track, so beyond this a "resend" is
         * asking for audio that is already gone; 900 ms is generous against every measurement made
         * here (the deepest burst seen was ~400 ms).
         */
        const val MAX_GAP_WAIT_MS = 900L

        /**
         * How far behind the playback point a packet may be and still be called late rather than
         * stale. 64 packets is half a second: well beyond any resend this pipeline would ask for,
         * and well inside the seconds-old repeats that come back unsolicited.
         */
        const val LATE_WINDOW_PACKETS = 64L

        /**
         * Waiting for a gap is paid for out of the audio in hand, so it stops being a good trade
         * once little is left. Below this floor the wait would turn a small splice into an audible
         * dropout instead of preventing one.
         */
        const val GAP_WAIT_FLOOR_MS = 80L

        /**
         * Packets written in place of a hole that is being given up on, imitating the last frame
         * that did arrive.
         *
         * This is what keeps the cushion from being a one-way street. Nothing in this pipeline ever
         * adds audio that the sender did not send, so every skipped packet took its 8 ms out of the
         * cushion permanently and nothing paid it back: measured over 13 minutes of playback the
         * cushion lost 114 ms while `skipped` gained 14 — a 2 % match, i.e. skipping is the *only*
         * drain there is. A long session therefore always ends up here, cushion at zero and the
         * AudioTrack underrunning on every hiccup. Writing a stand-in instead of dropping the
         * packet leaves the cushion where it was, which is the whole point: a bad second costs a
         * bad second, it does not poison the rest of the track.
         *
         * It caps at 8 because a stand-in is only convincing for a few packets — beyond that it is
         * noise of its own.
         */
        const val MAX_CONCEAL_PACKETS = 8

        /**
         * Samples over which a stand-in is blended away from the sample the speaker is already
         * holding, and over which the next real packet is faded back up.
         *
         * A stand-in is the previous frame repeated, so it starts on `frame[0]` while the speaker
         * was left holding `frame[351]`: that difference is a step in the waveform, and a step in
         * the waveform is exactly what a click is. Repeating the block at full level, which is what
         * this used to do, made it worse in two ways at once — an audible 125 Hz buzz for the length
         * of the hold, sandwiched between two steps. Fading the run out to silence and the audio
         * back in costs 2 ms of level at the seam and removes both. 88 samples is 2 ms.
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
         * Below this the cushion is too thin to ride out anything, and concealment alone will not
         * dig it back out — it only stops the bleeding. Stop and refill instead.
         */
        const val REBUFFER_FLOOR_MS = 40L

        /** Give up refilling after this long and carry on with whatever turned up. */
        const val REBUFFER_TIMEOUT_MS = 1000L

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
     * How long a hole is waited for at this cushion.
     *
     * Three quarters of the cushion, clamped to [GAP_WAIT_MS]..[MAX_GAP_WAIT_MS]. The transport
     * sizes its retry schedule from this as well, so a deep buffer does not just sit there being
     * deep — it buys more chances for a lost packet to come back before the hole is written off,
     * which is the only way the setting can actually reduce what is heard as a pop.
     */
    val gapWaitMs: Long = (cushionMs * 3L / 4L).coerceIn(GAP_WAIT_MS, MAX_GAP_WAIT_MS)

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

    /** How many failed decodes have been described in the log so far; see [MAX_DECODE_FAILURE_LOGS]. */
    private var decodeFailures = 0

    /** The RTP timestamp carried by the first packet, and its sequence — the timeline anchor. */
    private var firstRtpSequence = -1L
    private var firstRtpTime = 0L

    /** The sequence of the packet just handed to the decode loop, for pacing. */
    private var lastTakenSequence = -1L

    /** Holes at [nextSequence] that the wait below actually filled, i.e. resends that landed. */
    private var recoveredPackets = 0L

    /**
     * Packets that arrived after playback had already gone past them.
     *
     * Only retransmissions can be this late, so this is the number that separates the two reasons a
     * hole is ever given up on: the answer came back but [GAP_WAIT_MS] was too short (`tooLate`
     * climbing with `skipped`), or the sender never answered at all (`tooLate` flat).
     */
    private var tooLatePackets = 0L
    private var lastStatsAt = 0L

    /**
     * Frames handed to the AudioTrack since the last flush, and the playback head as it stood at
     * that moment. Their difference is how much audio is still queued in front of the speaker,
     * which is the only slack a gap can be waited for.
     */
    private var writtenFrames = 0L
    private var headBaseFrames = 0L

    /** When the hole at [nextSequence] was first noticed, or 0 while there is no hole. */
    private var gapSince = 0L

    /** Packets of stand-in audio the decode loop still owes, set when a hole is given up on. */
    private var pendingConceal = 0

    /** The last frame that decoded cleanly, in interleaved shorts — what a stand-in imitates. */
    private val lastFrame = ShortArray(pcmFrames * maxOf(2, channels))
    private var lastFrameShorts = 0

    /** The last sample handed to the AudioTrack, per channel: where a stand-in has to begin. */
    private val lastSamples = ShortArray(maxOf(2, channels))

    /**
     * Frames of fade-in still owed to the next packet written.
     *
     * Set after a hole and at the top of a stream, consumed by [decodeLoop]. Written from the
     * control thread on flush, hence @Volatile.
     */
    @Volatile
    private var fadeInFrames = 0

    private val concealBuffer = ShortArray(pcmFrames * maxOf(2, channels))
    private var concealedPackets = 0L
    private var rebufferCount = 0L

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
     * Whether the cushion has ever reached its target since the last flush.
     *
     * The cushion starts at zero and takes the first few packets to build, so "below the floor" is
     * the normal state for the first moment of every track and is not something to react to. Only
     * a cushion that has *fallen* to the floor is worth stopping for.
     */
    private var primed = false

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
     * The damage is not subtle. Each pause is followed within ~150 ms by a `FLUSHBUFFERED`, which
     * empties the queue and the AudioTrack; the pipeline then keeps writing packets into that
     * emptied cushion as fast as they decode, so the speaker is handed silence while it waits for
     * the sender to restart — the cushion falls from 500 ms to 1 ms, `Cushion down to 21ms;
     * refilling` fires, and every one of those is an audible gap. Measured over a two-minute
     * session of seeking: 14 underruns, all of them inside a pause window.
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
     * track bleeding past the pause. Starting does not resume writing straight away — [primed] is
     * cleared so the loop first rebuilds a cushion, which is what keeps the pause from turning into
     * a dropout.
     */
    fun setPlayEnabled(enabled: Boolean) {
        if (playEnabled == enabled) return
        playEnabled = enabled
        if (enabled) {
            synchronized(lock) {
                primed = false
                firstPacketAt = 0L
                lock.notifyAll()
            }
            fadeInFrames = START_FADE_IN_FRAMES
            lastFrameShorts = 0
            java.util.Arrays.fill(lastSamples, 0)
            runCatching { track?.play() }
            SinkLog.i("Audio output resumed — the sender restarted its clock; rebuilding the cushion")
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
                "AudioTrack buffer ${bufferSize} bytes (min ${minBuffer}); " +
                "cushion target ${cushionMs}ms = $prebufferPackets packets of pre-buffer, " +
                "starting if nothing better arrives within ${prebufferTimeoutMs}ms",
        )
        lastStatsAt = SystemClock.elapsedRealtime()
        synchronized(lock) { primed = false }
        fadeInFrames = START_FADE_IN_FRAMES
        java.util.Arrays.fill(lastSamples, 0)
        lastFrameShorts = 0

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
                // oldest entry is the one the playhead is about to ask for, so dropping it would
                // leave a hole exactly where the decode loop is waiting — and the wait for a hole
                // that can no longer be filled is [gapWaitMs] long. Drop it and move the playhead
                // to what is left: the audio in front of the playhead is stale by definition
                // anyway, and the sender keeps pushing.
                val dropped = pending.pollFirstEntry()
                if (dropped != null) {
                    skippedPackets++
                    if (dropped.key <= nextSequence && pending.isNotEmpty()) {
                        nextSequence = pending.firstKey()
                        gapSince = 0L
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
            gapSince = 0L
            pendingConceal = 0
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
        lastFrameShorts = 0
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
            gapSince = 0L
            pendingConceal = 0
            primed = false
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
        lastFrameShorts = 0
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
            gapSince = 0L
            pendingConceal = 0
            decodedPackets = 0L
            receivedPackets = 0L
            skippedPackets = 0L
            recoveredPackets = 0L
            tooLatePackets = 0L
            concealedPackets = 0L
            rebufferCount = 0L
            flushedPackets = 0L
            staleFrom = -1L
            staleUntil = -1L
            primed = false
            lastFrameShorts = 0
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
                val owe = synchronized(lock) {
                    val n = pendingConceal
                    pendingConceal = 0
                    n
                }
                if (owe > 0 && !writeConcealed(owe)) return
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
            paceToTimeline()
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
            System.arraycopy(out, 0, lastFrame, 0, shorts)
            lastFrameShorts = shorts
            rememberLastSamples(out, shorts)
            synchronized(lock) { writtenFrames += samples.toLong() }
            decodedPackets++
            rebufferIfStarving()
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
     * Holds the packet just decoded until the instant the sender timestamped it for.
     *
     * This is the whole of the progress-alignment fix. The sender paces its stream assuming the
     * receiver plays each frame at the RTP-timestamped instant, so a receiver that plays on arrival
     * runs *ahead* of where the sender thinks it is — the progress bar then lags the sound. Relating
     * the RTP timestamp to the local clock and waiting out the difference brings the two together.
     *
     * It only ever slows down: a packet whose instant has already passed plays immediately, so a
     * thin cushion or a laggy link can never be made worse by this. And the wait is capped, so a
     * bad anchor degrades to a short hesitation instead of stalling the speaker.
     */
    private fun paceToTimeline() {
        if (!timingSynced) return
        val seq = lastTakenSequence
        if (seq < 0L || firstRtpSequence < 0L) return
        val rtpTime = firstRtpTime + (seq - firstRtpSequence) * framesPerPacket
        // The anchor relates "frame nowRtpTime plays at anchorLocal" on the sender's timeline, so
        // pacing the *write* to that instant aligns the write head, not the play head. The play head
        // trails the write head by however much is still sitting in the AudioTrack (`buffered`), so
        // the sound runs that far behind the sender — the progress bar stays that far ahead. Compensate
        // by writing early: due = sender instant - buffered, so the *play head* lands on the sender's
        // now-frame and the two line up.
        val bufferedNs = synchronized(lock) { bufferedFramesLocked() } * 1_000_000_000L / sampleRate
        val dueNs =
            timingAnchorLocalNs + (rtpTime - timingAnchorRtpTime) * 1_000_000_000L / sampleRate - bufferedNs
        val lateByNs = System.nanoTime() - dueNs
        // Diagnose the first packets of a session so the residual lag is visible instead of
        // guessed at: `lateByNs > 0` means the packet is already past its due instant (the receiver
        // is running behind the sender's now-frame), which is the "progress still a touch off"
        // symptom; `lateByNs < 0` means the receiver is ahead and this method will wait it out.
        if (paceDiagnosticsRemaining > 0) {
            paceDiagnosticsRemaining--
            SinkLog.i(
                "Pace seq=$seq rtp=$rtpTime lateBy=${lateByNs / 1_000_000}ms " +
                    "(due=${dueNs} now=${System.nanoTime()})",
            )
        }
        if (lateByNs >= 0L) return
        // `lateByNs < 0` means the due instant is still ahead: wait it out. The wait is the
        // *negative* of `lateByNs` — a signed division here would yield a negative `waitMs` that
        // the `<= 0` guard below silently drops, so the ahead-branch never slept and the decode
        // loop sprinted through the pending queue until the AudioTrack filled, running the speaker
        // ahead of the sender's timeline. That is the last remaining "progress still a touch off".
        val waitMs = (-lateByNs / 1_000_000L).coerceAtMost(MAX_PACE_WAIT_MS)
        if (waitMs <= 0L) return
        try {
            Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
            // The loop is about to wind down anyway; don't let a stray interrupt escape.
        }
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
    private fun writeConcealed(count: Int): Boolean {
        val player = track ?: return false
        // A packet's worth of samples, taken from the last one that decoded so the stand-in has the
        // same shape as the audio either side of it. Before anything has decoded there is nothing
        // to imitate and the length has to come from the format instead.
        val bodyShorts = if (lastFrameShorts > 0) lastFrameShorts else framesPerPacket * channels
        val bodyFrames = bodyShorts / channels
        if (bodyFrames <= 0 || count <= 0) return true
        val totalFrames = bodyFrames.toLong() * count

        for (packet in 0 until count) {
            var index = 0
            for (frame in 0 until bodyFrames) {
                val position = packet.toLong() * bodyFrames + frame
                // Unity at the top of the hole, silence at the bottom: the last sample handed over
                // is zero, so the packet after the hole has nothing to step away from.
                val envelope =
                    if (totalFrames > 1) 1f - position.toFloat() / totalFrames else 0f
                // And the first sample *is* the one the speaker is holding, blended away over
                // DECLICK_FRAMES. Without this the stand-in opens with the step it exists to hide.
                val bridge = (1f - position.toFloat() / DECLICK_FRAMES).coerceAtLeast(0f)
                for (channel in 0 until channels) {
                    val source =
                        if (lastFrameShorts > 0) lastFrame[frame * channels + channel].toFloat()
                        else 0f
                    val value = (source + (lastSamples[channel] - source) * bridge) * envelope
                    concealBuffer[index++] = value.toInt().toShort()
                }
            }
            val written = try {
                player.write(concealBuffer, 0, index)
            } catch (error: Exception) {
                SinkLog.e("AudioTrack write failed while filling a hole", error)
                -1
            }
            if (written < 0) return false
            rememberLastSamples(concealBuffer, index)
            synchronized(lock) { writtenFrames += bodyFrames.toLong() }
        }
        // The hole ends at silence; ease the next real packet up out of it.
        fadeInFrames = FADE_IN_FRAMES
        concealedPackets += count
        return true
    }

    /**
     * Concealment stops the cushion from draining; it cannot put back what was already lost. When
     * it has been eaten down to nearly nothing, stop, let packets pile up and start again with a
     * full cushion — one clean gap instead of an unending run of dropouts.
     */
    private fun rebufferIfStarving() {
        val player = track ?: return
        val starving = synchronized(lock) {
            val current = bufferedFramesLocked()
            if (current >= targetCushionFrames) primed = true
            // A pause can only help if the audio is still missing. When the queue already holds a
            // cushion's worth, the packets are here — they simply have not been written yet, and
            // the loop resumes writing them at once. Pausing in that case buys nothing and, worse,
            // the wait below is satisfied before it starts, so the refill returns immediately,
            // `primed` is still true and the very next packet starts the whole thing again:
            // after a FLUSH this ran ten pause/play cycles in fifty milliseconds.
            primed && nextSequence >= 0L && current < rebufferFloorFrames &&
                pending.size < effectivePrebufferPackets
        }
        if (!starving) return

        // It has to earn its way back in by rebuilding the cushion, otherwise a pipeline that
        // really is starving re-enters here on every packet it writes and never gets anywhere.
        synchronized(lock) { primed = false }
        rebufferCount++
        SinkLog.w(
            "Cushion down to ${synchronized(lock) { bufferedFramesLocked() * 1000 / sampleRate }}ms; " +
                "refilling (refill #$rebufferCount)",
        )
        runCatching { player.pause() }
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + REBUFFER_TIMEOUT_MS
            while (running && pending.size < effectivePrebufferPackets &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                waitLocked(20)
            }
        }
        runCatching { player.play() }
    }

    private val rebufferFloorFrames: Long get() = sampleRate / 1000 * REBUFFER_FLOOR_MS

    /** Must be called while holding [lock]. */
    private fun takeNextLocked(): ByteArray? {
        while (pending.isNotEmpty() && pending.firstKey() < nextSequence) {
            pending.pollFirstEntry()
        }
        val exact = pending.remove(nextSequence)
        if (exact != null) {
            if (gapSince != 0L) recoveredPackets++
            lastTakenSequence = nextSequence
            nextSequence++
            gapSince = 0L
            return exact
        }
        if (pending.isEmpty()) {
            gapSince = 0L
            return null
        }

        // The packet we want is missing while later ones are sitting right there: a reorder, or a
        // loss whose retransmission may already be on its way back. Hold on for it — but only on
        // time we can actually afford. The wait is paid out of the audio in hand, so once that has
        // thinned to the floor the trade stops being worthwhile and moving on wins.
        val now = SystemClock.elapsedRealtime()
        if (gapSince == 0L) gapSince = now
        val waited = now - gapSince
        if (waited < gapWaitMs && bufferedFramesLocked() >= gapWaitFloorFrames) {
            waitLocked((gapWaitMs - waited).coerceAtLeast(1L))
            return null
        }

        val jump = pending.firstKey()
        val lost = jump - nextSequence
        if (lost > TIMELINE_RESYNC_PACKETS) {
            // A hole this large is not a loss. The sender has moved to a different part of the
            // stream — a seek, or a flush we did not hear about — and the old sequence numbers
            // are never coming. Booking them as thousands of lost packets, and writing stand-in
            // audio for them, is worse than useless: it stalls the speaker for the whole wait and
            // then plays silence where music should be.
            //
            // So the playhead moves to what actually arrived, the timeline is re-anchored on it,
            // and the count of lost packets stays where it was.
            SinkLog.w(
                "Sequence jumped forward by $lost; treating it as a new position rather than a " +
                    "loss, and re-anchoring on sequence $jump",
            )
            nextSequence = jump
            gapSince = 0L
            firstRtpSequence = -1L
            firstRtpTime = 0L
            return null
        }
        skippedPackets += lost
        // The hole is still not filled, but it is no longer left empty: the decode loop writes a
        // stand-in for it so the cushion survives. Caps at MAX_CONCEAL_PACKETS because a stand-in
        // is only convincing for a few packets — beyond that it is noise of its own.
        pendingConceal = lost.coerceAtMost(MAX_CONCEAL_PACKETS.toLong()).toInt()
        // Skipping is the one thing here that permanently costs the cushion, and each skip is a
        // packet's worth of audio the listener will not hear, so say so while it is rare. Once it
        // is not rare the stats line carries the count and this would only be noise.
        if (skippedPackets <= 10) {
            SinkLog.w(
                "Gave up on $lost packet(s) at seq $nextSequence after waiting ${waited}ms " +
                    "(buffered ${bufferedFramesLocked() * 1000 / sampleRate}ms, " +
                    "queued ${pending.size})",
            )
        }
        nextSequence = jump
        gapSince = 0L
        return null
    }

    private val gapWaitFloorFrames: Long get() = sampleRate / 1000 * GAP_WAIT_FLOOR_MS

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
            "skipped $skippedPackets, " +
            "recovered $recoveredPackets, concealed $concealedPackets, tooLate $tooLatePackets, " +
            "flushed $flushedPackets, " +
            "queued $queued, buffered ${bufferedMs}ms, underruns $underruns"
        SinkLog.i(stats)
        onStats?.invoke(stats)
    }
}
