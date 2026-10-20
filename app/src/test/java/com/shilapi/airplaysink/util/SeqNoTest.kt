package com.shilapi.airplaysink.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqNoTest {

    @Test
    fun `the endpoint from the real session narrows to the counter the blocks use`() {
        // The FLUSHBUFFERED of the 2026-10-20 session named 10299533, and the first audio block
        // of the same stream carried 1904383. Narrowed, the endpoint is 1910925 — 6542 blocks
        // past the first one, which is exactly what the RTP timestamps moved
        // (6699008 samples / 1024 frames a packet).
        assertEquals(1910925L, SeqNo.narrow(10299533L))
        assertEquals(6542L, SeqNo.difference(1910925L, 1904383L))
    }

    @Test
    fun `a plain comparison against the unnarrowed endpoint calls every block stale`() {
        // This is the bug, stated as plainly as it can be: the endpoint as it arrives is larger
        // than any 23-bit number, so "is this block at or before the endpoint?" is true for every
        // block the stream will ever deliver. The pipeline answered that question with plain
        // arithmetic, dropped the whole session, and the audio counters froze.
        val endpointAsSent = 10299533L
        val blocks = longArrayOf(1904383L, 1904384L, 1950000L, 2097151L)
        for (block in blocks) {
            assertTrue("block $block looks stale", block <= endpointAsSent)
        }
        // Narrowed, the same question separates the blocks that precede the endpoint from the one
        // that follows it — which is the distinction the pipeline needs. The endpoint sits at
        // 1910925, so 1904383 (the first block of the session) is behind it and 1950000 is past it.
        val endpoint = SeqNo.narrow(endpointAsSent)
        assertTrue(SeqNo.difference(1904383L, endpoint) < 0L)
        assertTrue(SeqNo.difference(1910924L, endpoint) < 0L)
        assertEquals(0L, SeqNo.difference(endpoint, endpoint))
        assertTrue(SeqNo.difference(1950000L, endpoint) > 0L)
        assertTrue(SeqNo.difference(2097151L, endpoint) > 0L)
    }

    @Test
    fun `blocks before the endpoint are stale and the one past it is not`() {
        val endpoint = SeqNo.narrow(10299533L)
        assertTrue(SeqNo.difference(endpoint - 100, endpoint) < 0L)
        assertTrue(SeqNo.difference(endpoint + 1, endpoint) >= 0L)
    }

    @Test
    fun `the comparison survives the counter wrapping`() {
        // The counter is 23 bits wide, so it wraps every 8388608 blocks. A block that is *behind*
        // the endpoint can carry the larger number once the wrap is crossed, and a plain
        // comparison would call it "past the endpoint" and start playing the stale backlog.
        val endpoint = 0x000100L
        val block = 0x7FFF00L
        assertTrue("the block is numerically larger", block > endpoint)
        assertTrue("but it is behind the endpoint", SeqNo.difference(block, endpoint) < 0L)
        assertTrue(SeqNo.difference(endpoint, block) > 0L)
    }

    @Test
    fun `the distance is symmetric`() {
        val a = 1904383L
        val b = 1910925L
        assertEquals(-SeqNo.difference(a, b), SeqNo.difference(b, a))
    }

    @Test
    fun `narrowing leaves the counter's own values alone`() {
        // A number that already fits in 23 bits must survive the narrowing unchanged, or every
        // sequence number in the stream would be altered on its way through.
        for (value in listOf(0L, 1L, 1904383L, 8388607L)) {
            assertEquals(value, SeqNo.narrow(value))
        }
        assertFalse(SeqNo.narrow(8388608L) == 8388608L)
    }
}
