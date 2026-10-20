package com.shilapi.airplaysink.raop

import com.shilapi.airplaysink.ap2.Ap2Streams
import com.shilapi.airplaysink.ap2.BPlistReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `POST /feedback` is answered with a description of what is playing, not with a bare 200.
 *
 * An empty body is the receiver saying "not playing", and a sender that keeps a playback timeline
 * (Apple Music does) reacts to that by stopping the stream it just started. The shape here is
 * shairport-sync's: `{streams:[{type,sr}]}` — one entry describing this stream, `sr` as a real.
 */
class FeedbackReplyTest {

    private fun parse(body: ByteArray): Map<*, *> = BPlistReader.parse(body) as Map<*, *>

    private fun onlyStream(type: Long, rate: Int): Map<*, *> {
        val streams = parse(ap2FeedbackBody(type, rate))["streams"] as List<*>
        assertEquals("the reply describes exactly one stream", 1, streams.size)
        return streams[0] as Map<*, *>
    }

    @Test
    fun `a playing realtime stream is reported with its type and rate`() {
        val stream = onlyStream(Ap2Streams.STREAM_TYPE_REALTIME, 44100)
        assertEquals(96L, stream["type"])
        // A real, not an integer: shairport-sync writes plist_new_real and iOS reads it as one.
        assertEquals(44100.0, stream["sr"] as Double, 0.0)
    }

    @Test
    fun `a buffered stream reports 103, so the two are never confused`() {
        assertEquals(103L, onlyStream(Ap2Streams.STREAM_TYPE_BUFFERED, 48000)["type"])
    }

    @Test
    fun `the entry carries only the two keys iOS reads`() {
        assertEquals(
            setOf("type", "sr"),
            onlyStream(Ap2Streams.STREAM_TYPE_REALTIME, 44100).keys.map { it.toString() }.toSet(),
        )
    }
}
