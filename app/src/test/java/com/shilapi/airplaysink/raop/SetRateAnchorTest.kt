package com.shilapi.airplaysink.raop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SETRATEANCHORTIME` clock bit, which is the only play/pause signal an AirPlay 2 sender sends.
 *
 * These pin shairport-sync's `handle_setrateanchortime`: `rate & 1` set means the clock runs. There
 * are no sync packets in an AirPlay 2 session, so nothing else tells the receiver whether the sender
 * thinks it is playing — and a receiver that reads this backwards writes into the cushion the paired
 * `FLUSHBUFFERED` just emptied, which is audible as a dropout on every seek.
 */
class SetRateAnchorTest {

    @Test
    fun `rate 1 means the clock is running`() {
        // The shape a real Apple Music session sends when it starts playing.
        assertTrue(setRateAnchorRunsClock(1L))
    }

    @Test
    fun `rate 0 means the clock is stopped`() {
        assertFalse(setRateAnchorRunsClock(0L))
    }

    @Test
    fun `only the lowest bit decides`() {
        // The upper bits are a timeline identifier; they must not be mistaken for the run state.
        assertFalse(setRateAnchorRunsClock(2L))
        assertFalse(setRateAnchorRunsClock(4L))
        assertFalse(setRateAnchorRunsClock(0xFFFE))
        assertTrue(setRateAnchorRunsClock(0xFFFFL))
        assertTrue(setRateAnchorRunsClock(0x0101L))
    }

    @Test
    fun `a negative rate still reads bit 0`() {
        // The plist reader hands back a signed 64-bit integer. A rate that arrived with the top bit
        // set must not silently read as "stopped" just because it looks negative.
        assertTrue(setRateAnchorRunsClock(-1L))
        assertFalse(setRateAnchorRunsClock(-2L))
    }
}