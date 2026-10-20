package com.shilapi.airplaysink.util

/**
 * The sequence numbers AirPlay 2 speaks in, and the one place they are made to agree.
 *
 * The buffered audio stream numbers its blocks with a 23-bit counter read out of the RTP header
 * (`nctohl(&packet[0]) & 0x7FFFFF` in shairport-sync), and it wraps every 8 388 608 blocks —
 * about 50 hours of playback at 1024 frames a packet.
 *
 * The trouble is that the sender names the same counter in a *wider* field when it flushes.
 * `FLUSHBUFFERED` carries `flushUntilSeq` as a full 32-bit number, and shairport-sync masks it
 * down before comparing: `flushUntilSeq & 0x7fffff`. The real session named `10299533`, which is
 * `1910925` once narrowed — and `1910925` sits 6542 blocks past the first block's `1904383`, which
 * is exactly the distance the RTP timestamps moved (`6699008 / 1024`).
 *
 * Comparing the two as they arrive is not a small mismatch. The un-narrowed endpoint sits far
 * above every number the stream will ever produce, so *every* packet looks stale: the pipeline
 * discards the whole session and the audio counters freeze. The symptom was silence from the
 * first flush onwards, with nothing in the log but a healthy-looking `FLUSHBUFFERED 200`.
 */
object SeqNo {
    /** The width of the counter the audio blocks carry. */
    const val MASK: Long = 0x7F_FFFFL

    /**
     * Narrows a sequence number to the width the audio blocks use.
     *
     * Applied to both sides of every comparison, so a value that arrives wide and a value that
     * arrives narrow end up in the same space.
     */
    fun narrow(value: Long): Long = value and MASK

    /**
     * The signed distance from [b] to [a] in the 23-bit space, the way shairport-sync's
     * `a_minus_b_mod23` computes it.
     *
     * Plain subtraction is not enough: the counter wraps, so a block that is *behind* the
     * endpoint can have the larger number once the wrap is crossed. The difference is taken
     * modulo 2^23 and then read as a signed 23-bit value, which makes "before" and "after"
     * unambiguous as long as the two are within 2^22 of each other — always true here, since
     * the endpoint is at most a few thousand blocks away.
     */
    fun difference(a: Long, b: Long): Long {
        val diff = (narrow(a) - narrow(b)) and MASK
        // Bit 22 is the sign bit of a 23-bit two's-complement number.
        return if (diff and 0x40_0000L != 0L) diff - 0x80_0000L else diff
    }
}
