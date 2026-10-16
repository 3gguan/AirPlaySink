package com.shilapi.airplaysink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable

/**
 * Re-orders incoming RAOP audio packets, decodes them with [AlacDecoder] and pushes PCM into an
 * [AudioTrack].
 *
 * Pacing comes for free: `AudioTrack.write` in stream mode blocks once its internal buffer is full,
 * so the decode loop runs at playback speed as long as the sender keeps the network pace.
 */
class AudioPipeline(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    framesPerPacket: Int = 352,
) : Closeable {

    private companion object {
        /** Packets buffered before playback starts: 12 * 352 frames is about 96 ms. */
        const val PREBUFFER_PACKETS = 12
        /** If the first packets are slow to arrive, start anyway rather than sitting silent. */
        const val PREBUFFER_TIMEOUT_MS = 400L
        const val MAX_PENDING_PACKETS = 512
        const val STATS_INTERVAL_PACKETS = 500L
    }

    private val decoder = AlacDecoder(
        sampleSize = 16,
        numChannels = channels,
        maxSamplesPerFrame = framesPerPacket,
    )

    // Stereo is the floor even if the sender announced mono: a CPE element always produces two
    // interleaved channels, and a short output array would throw on the decode thread.
    private val pcm = ShortArray(framesPerPacket * maxOf(2, channels))
    private val pending = java.util.TreeMap<Long, ByteArray>()
    private val lock = Object()

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var track: AudioTrack? = null

    private var nextSequence = -1L
    private var firstPacketAt = 0L
    private var decodedPackets = 0L
    private var skippedPackets = 0L
    private var lastStatsAt = 0L

    /** Reported on the decode thread; the UI must post to the main thread itself. */
    var onStats: ((String) -> Unit)? = null

    val isRunning: Boolean get() = running

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
        // Aim for roughly 250 ms of AudioTrack buffer, never below the platform minimum.
        val bufferSize = maxOf(minBuffer, sampleRate * channels * 2 / 4)

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
            "Audio output started: ${sampleRate}Hz ${channels}ch, " +
                "AudioTrack buffer ${bufferSize} bytes (min ${minBuffer})",
        )
        lastStatsAt = SystemClock.elapsedRealtime()

        thread = Thread({ decodeLoop() }, "raop-decode").also { it.start() }
    }

    fun submit(sequence: Long, payload: ByteArray) {
        synchronized(lock) {
            if (pending.size >= MAX_PENDING_PACKETS) {
                pending.pollFirstEntry()
                skippedPackets++
            }
            pending[sequence] = payload
            if (firstPacketAt == 0L) {
                firstPacketAt = SystemClock.elapsedRealtime()
                SinkLog.i("First audio packet received (seq $sequence)")
            }
            lock.notifyAll()
        }
    }

    /** Called on FLUSH: everything queued belongs to the old timeline. */
    fun flush() {
        synchronized(lock) {
            pending.clear()
            nextSequence = -1L
            firstPacketAt = 0L
            lock.notifyAll()
        }
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
        SinkLog.i("Audio pipeline flushed")
    }

    fun setVolume(linear: Float) {
        val clamped = linear.coerceIn(0f, 1f)
        runCatching { track?.setVolume(clamped) }
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
        synchronized(lock) {
            pending.clear()
            nextSequence = -1L
            firstPacketAt = 0L
            decodedPackets = 0L
            skippedPackets = 0L
        }
        SinkLog.i("Audio output stopped")
    }

    private fun decodeLoop() {
        val out = pcm
        while (running) {
            var payload: ByteArray? = null
            synchronized(lock) {
                if (pending.isEmpty()) {
                    lock.wait(50)
                } else if (nextSequence < 0L) {
                    val waitedLongEnough = firstPacketAt != 0L &&
                        SystemClock.elapsedRealtime() - firstPacketAt > PREBUFFER_TIMEOUT_MS
                    if (pending.size < PREBUFFER_PACKETS && !waitedLongEnough) {
                        lock.wait(20)
                    } else {
                        nextSequence = pending.firstKey()
                        payload = takeNextLocked()
                    }
                } else {
                    payload = takeNextLocked()
                }
            }

            val body = payload ?: continue
            val samples = decoder.decodeFrame(body, 0, body.size, out)
            if (samples <= 0) {
                SinkLog.w("Dropping undecodable ALAC frame (${decoder.lastError})")
                continue
            }
            val player = track ?: continue
            val written = try {
                player.write(out, 0, samples * channels)
            } catch (error: Exception) {
                SinkLog.e("AudioTrack write failed", error)
                -1
            }
            if (written < 0) {
                SinkLog.e("AudioTrack write returned $written; stopping pipeline")
                return
            }
            decodedPackets++
            reportStatsIfDue()
        }
        SinkLog.i("Decode loop finished")
    }

    /** Must be called while holding [lock]. */
    private fun takeNextLocked(): ByteArray? {
        while (pending.isNotEmpty() && pending.firstKey() < nextSequence) {
            pending.pollFirstEntry()
        }
        val exact = pending.remove(nextSequence)
        if (exact != null) {
            nextSequence++
            return exact
        }
        if (pending.isNotEmpty()) {
            // A gap we are not going to fill: jump forward to what did arrive.
            val jump = pending.firstKey()
            skippedPackets += (jump - nextSequence)
            nextSequence = jump
            return null
        }
        return null
    }

    private fun reportStatsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAt < 5000) return
        lastStatsAt = now
        val stats = "decoded $decodedPackets packets, skipped $skippedPackets, queued ${pending.size}"
        SinkLog.i(stats)
        onStats?.invoke(stats)
    }
}
