package com.shilapi.airplaysink.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.shilapi.airplaysink.util.SinkLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAC-LC decoder, backed by the platform's own codec.
 *
 * AirPlay 2 does not always send ALAC. The `SETUP` names a compression type and an audio-format
 * mask, and Apple Music asks for `ct=4` / `0x00800000` — AAC-LC at 48 kHz, stereo, 1024 frames per
 * packet. Handing those frames to the ALAC decoder produces exactly what it sounds like: almost
 * every frame is rejected as impossible ALAC, the few that sneak through come out as noise, and the
 * timeline stalls while the packet queue fills. On 2026-10-20 that was the whole of "连接上了，不出声，
 * 还有杂音".
 *
 * The frames themselves are raw access units — no ADTS header — which is also what `MediaCodec`
 * expects once it has an AudioSpecificConfig: 2 bytes carrying object type 2 (AAC-LC), the sample
 * rate index and the channel count. [looksLikeAdts] still checks, because a sender that frames its
 * packets the other way is a 6-byte difference that would otherwise turn into a second afternoon of
 * this.
 */
class AacDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    override val maxFramesPerPacket: Int = 1024,
) : AudioDecoder {

    private companion object {
        /** Long enough for the codec to hand back an output buffer on a loaded head unit. */
        const val DEQUEUE_TIMEOUT_US = 200_000L
        const val INPUT_TIMEOUT_US = 20_000L
        const val MAX_INPUT_BYTES = 64 * 1024

        /** AAC-LC, so nothing to negotiate: the SETUP already named the format. */
        const val AUDIO_OBJECT_TYPE_LC = 2
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** True when the payloads carry their own ADTS headers and no `csd-0` is needed. */
    private var adtsFraming = false

    private var fedPackets = 0L
    private var emittedFrames = 0L

    /**
     * PCM left over when an output buffer holds more than the caller's array.
     *
     * AAC-LC is exactly one 1024-frame block per packet, so this should never be reached; it exists
     * so an output buffer that disagrees cannot throw on the decode thread and kill playback.
     */
    private var spill = ShortArray(0)
    private var spillOffset = 0
    private var spillCount = 0

    override var lastError: String? = null
        private set

    override fun decodeFrame(input: ByteArray, offset: Int, inputLength: Int, out: ShortArray): Int {
        lastError = null
        if (inputLength <= 0) {
            lastError = "empty payload"
            return -1
        }
        val codec = codec ?: open(input, offset, inputLength) ?: return -1
        if (spillCount > 0) {
            // Keep the codec fed while handing back what it already produced, so a large output
            // buffer costs one packet of latency rather than one packet of audio.
            feed(codec, input, offset, inputLength)
            return drainSpill(out)
        }
        if (!feed(codec, input, offset, inputLength)) return 0
        return collect(codec, out)
    }

    override fun close() {
        val codec = codec ?: return
        this.codec = null
        runCatching {
            codec.stop()
            codec.release()
        }
        SinkLog.i("AAC decoder closed after $fedPackets packets / $emittedFrames frames")
    }

    // ------------------------------------------------------------------ codec set-up

    private fun open(input: ByteArray, offset: Int, length: Int): MediaCodec? {
        adtsFraming = looksLikeAdts(input, offset, length)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channels,
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        )
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val config = audioSpecificConfig()
        if (!adtsFraming) format.setByteBuffer("csd-0", ByteBuffer.wrap(config))

        var created: MediaCodec? = null
        try {
            created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            created.configure(format, null, null, 0)
            created.start()
        } catch (error: Exception) {
            SinkLog.e("AAC decoder could not start", error)
            lastError = "codec start failed: ${error.message}"
            runCatching { created?.release() }
            return null
        }
        codec = created
        SinkLog.i(
            "AAC decoder ready: AAC-LC ${sampleRate}Hz ${channels}ch " +
                "spf=$maxFramesPerPacket; " +
                if (adtsFraming) {
                    "payloads carry ADTS headers"
                } else {
                    "raw access units, csd-0=%02X%02X".format(config[0], config[1])
                },
        )
        return created
    }

    /** `audioObjectType | frequencyIndex | channelConfiguration`, as the codec wants it. */
    private fun audioSpecificConfig(): ByteArray {
        val frequencyIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4
        }
        val value = (AUDIO_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            ((channels and 0x0F) shl 3)
        return byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    }

    /**
     * Whether a payload starts with an ADTS sync word (`0xFFF`) and a layer field of zero.
     *
     * A raw access unit starts with the element id, so this cannot misfire on one: an SCE begins
     * `0x0…` and a CPE `0x2…`, never twelve set bits.
     */
    private fun looksLikeAdts(input: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 7) return false
        val sync = (input[offset].toInt() and 0xFF) == 0xFF &&
            (input[offset + 1].toInt() and 0xF0) == 0xF0
        if (!sync) return false
        return (input[offset + 1].toInt() and 0x06) == 0
    }

    // ------------------------------------------------------------------ packet plumbing

    /**
     * Hands one packet to the codec.
     *
     * A packet that cannot be fed is not a packet that can be skipped: AAC frames predict from
     * their neighbours, so dropping one corrupts everything after it. So this waits for an input
     * buffer rather than giving up, and only reports a failure when the codec really has nowhere
     * to put the data.
     */
    private fun feed(codec: MediaCodec, input: ByteArray, offset: Int, length: Int): Boolean {
        val deadline = System.nanoTime() + DEQUEUE_TIMEOUT_US * 1000L
        while (true) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    lastError = "no input buffer object"
                    return false
                }
                if (length > buffer.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    lastError = "packet of $length bytes exceeds the codec's input buffer"
                    return false
                }
                buffer.clear()
                buffer.put(input, offset, length)
                // Presentation time is unused: the pipeline schedules audio from the RTP timestamp,
                // and a codec-side timeline would only be a second, competing one.
                codec.queueInputBuffer(index, 0, length, 0, 0)
                fedPackets++
                return true
            }
            if (System.nanoTime() >= deadline) {
                lastError = "no input buffer available within ${DEQUEUE_TIMEOUT_US / 1000}ms"
                return false
            }
        }
    }

    private fun collect(codec: MediaCodec, out: ShortArray): Int {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    lastError = "the codec produced nothing within ${DEQUEUE_TIMEOUT_US / 1000}ms"
                    return 0
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    SinkLog.i(
                        "AAC output format: ${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz " +
                            "${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch, " +
                            "pcm encoding ${format.getInteger(MediaFormat.KEY_PCM_ENCODING, 2)}",
                    )
                }

                index < 0 -> Unit

                else -> {
                    val frames = runCatching { readOutput(codec, index, out) }.getOrElse { error ->
                        SinkLog.e("AAC output could not be read", error)
                        0
                    }
                    // The buffer is released whatever happened, or the codec stalls on the next
                    // packet waiting for a buffer that is never given back.
                    codec.releaseOutputBuffer(index, false)
                    if (frames > 0) {
                        emittedFrames += frames
                        if (emittedFrames == frames.toLong()) {
                            SinkLog.i("AAC: first decoded block is $frames frames per channel")
                        }
                        return frames
                    }
                    // A zero-length output (codec setup, or priming) is not the packet being lost:
                    // keep looking, the audio for this packet is behind it in the queue.
                }
            }
        }
    }

    private fun readOutput(codec: MediaCodec, index: Int, out: ShortArray): Int {
        val buffer = codec.getOutputBuffer(index) ?: return 0
        if (bufferInfo.size <= 0) return 0
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val available = shorts.remaining()
        if (available <= 0) return 0
        val wanted = minOf(available, out.size - out.size % channels)
        shorts.get(out, 0, wanted)
        val extra = available - wanted
        if (extra > 0) {
            spill = ShortArray(extra)
            shorts.get(spill, 0, extra)
            spillOffset = 0
            spillCount = extra
        }
        return wanted / channels
    }

    private fun drainSpill(out: ShortArray): Int {
        val take = minOf(spillCount, out.size - out.size % channels)
        System.arraycopy(spill, spillOffset, out, 0, take)
        spillOffset += take
        spillCount -= take
        return take / channels
    }
}
