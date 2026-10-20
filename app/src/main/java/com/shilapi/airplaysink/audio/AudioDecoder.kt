package com.shilapi.airplaysink.audio

import java.io.Closeable

/**
 * One packet in, one block of PCM out — the whole of what [AudioPipeline] needs from a codec.
 *
 * The pipeline was written around ALAC, whose packet layout is fixed (352 frames, decoded straight
 * into a pre-sized buffer). AirPlay 2 also offers AAC-LC, and there the decode has to go through
 * `MediaCodec`, which is asynchronous and whose first output can be late. This interface exists so
 * the pipeline can hold either one without knowing which it has.
 */
interface AudioDecoder : Closeable {
    /**
     * Decodes one packet payload into [out], interleaved, 16-bit.
     *
     * Returns the number of frames per channel written, `0` when the codec has nothing to give yet,
     * or a negative value when the packet is undecodable — in which case [lastError] says why.
     *
     * `0` and negative being different matters: the first is normal for a codec that buffers, and
     * logging it as a dropped packet would put a warning on every single packet of a session.
     */
    fun decodeFrame(input: ByteArray, offset: Int, inputLength: Int, out: ShortArray): Int

    /** Why the last call failed, or null. Read only after a negative return. */
    val lastError: String?

    /** The most frames a single packet can produce, i.e. how large [out] has to be per channel. */
    val maxFramesPerPacket: Int

    override fun close() {}
}

/** What the sender is putting in its packets. AirPlay 2 sends both; AirPlay 1 only ever ALAC. */
enum class AudioCodec(val label: String) {
    ALAC("ALAC"),
    AAC("AAC-LC"),
}
