package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.audio.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `audioFormat` bitmask is the only statement of the stream's format that survives to the
 * decoder, and every value here was taken off a real session: `0x00040000` from an AirPlay 2 ALAC
 * session, `0x00800000` from an Apple Music one that arrived as AAC-LC and was decoded as ALAC
 * until 2026-10-20.
 */
class AudioFormatMaskTest {

    @Test
    fun `alac at 44100 is bit 18`() {
        val format = AudioFormatMask.of(0x00040000L)!!
        assertEquals(AudioCodec.ALAC, format.codec)
        assertEquals(44100, format.sampleRate)
        assertEquals(2, format.channels)
    }

    @Test
    fun `aac-lc at 48000 is bit 23`() {
        val format = AudioFormatMask.of(0x00800000L)!!
        assertEquals(AudioCodec.AAC, format.codec)
        assertEquals(48000, format.sampleRate)
        assertEquals(2, format.channels)
    }

    @Test
    fun `aac-lc at 44100 is bit 22`() {
        val format = AudioFormatMask.of(0x00400000L)!!
        assertEquals(AudioCodec.AAC, format.codec)
        assertEquals(44100, format.sampleRate)
    }

    @Test
    fun `the bit number is the index the sender also sends`() {
        // Apple Music sends {audioFormat: 0x00800000, audioFormatIndex: 23} — the same fact twice.
        assertEquals(23, java.lang.Long.numberOfTrailingZeros(0x00800000L))
        assertEquals(18, java.lang.Long.numberOfTrailingZeros(0x00040000L))
    }

    @Test
    fun `an unknown mask says nothing rather than guessing`() {
        assertNull(AudioFormatMask.of(0L))
        assertNull(AudioFormatMask.of(0x01000000L)) // AAC-ELD, which this sink cannot decode
        assertNull(AudioFormatMask.of(0x10000000L)) // Opus
    }
}
