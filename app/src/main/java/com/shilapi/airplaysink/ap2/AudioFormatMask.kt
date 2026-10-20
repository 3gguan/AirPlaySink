package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.audio.AudioCodec

/**
 * The format an AirPlay 2 `SETUP` announces in its `audioFormat` bitmask.
 *
 * Each supported combination is one bit, and the bit number is what the sender also sends as
 * `audioFormatIndex` — Apple Music sends `{audioFormat: 0x00800000, audioFormatIndex: 23}` for
 * AAC-LC at 48 kHz, which is the same number two ways and the reason this table is trustworthy.
 *
 * The block is documented at <https://emanuelecozzi.net/docs/airplay2/audio/>, and the same values
 * are what the RTP header's SSRC field encodes (`0x15000000` = ALAC 48k, `0x16000000` = AAC 44.1k,
 * `0x17000000` = AAC 48k in shairport-sync's `player.h`), so a mis-parse here shows up as a
 * disagreement between two independent fields rather than as silence.
 *
 * Only the combinations this receiver can actually decode are named. Anything else returns null and
 * the caller falls back to the `sr` the SETUP carried, which is what every sender sends anyway.
 */
data class AudioFormatMask(
    val codec: AudioCodec,
    val sampleRate: Int,
    val channels: Int,
    /** 16 or 24, as announced. Only ALAC distinguishes them; AAC decodes to 16-bit PCM here. */
    val bitDepth: Int,
) {

    companion object {

        /** Bit number → format. Bit 18 is `0x40000`, i.e. ALAC/44.1/16/stereo, the oldest one. */
        private val BIT_FORMATS: Map<Int, AudioFormatMask> = mapOf(
            18 to AudioFormatMask(AudioCodec.ALAC, 44100, 2, 16),
            19 to AudioFormatMask(AudioCodec.ALAC, 48000, 2, 16),
            20 to AudioFormatMask(AudioCodec.ALAC, 44100, 2, 24),
            21 to AudioFormatMask(AudioCodec.ALAC, 48000, 2, 24),
            22 to AudioFormatMask(AudioCodec.AAC, 44100, 2, 16),
            23 to AudioFormatMask(AudioCodec.AAC, 48000, 2, 16),
        )

        /**
         * The format [mask] names, or null when it names none this receiver knows.
         *
         * A mask may carry several bits — a receiver offering more than one format answers that
         * way — but a sender's `SETUP` names the one it picked. When several are set the lowest
         * bit wins, which is the same choice shairport-sync's decoder table makes.
         */
        fun of(mask: Long): AudioFormatMask? {
            if (mask == 0L) return null
            for (bit in 0 until 64) {
                if (mask and (1L shl bit) == 0L) continue
                return BIT_FORMATS[bit]
            }
            return null
        }
    }
}
