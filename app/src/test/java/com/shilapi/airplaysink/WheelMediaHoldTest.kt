package com.shilapi.airplaysink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WheelMediaHoldTest {

    private val next = 87
    private val previous = 88

    @Test
    fun `a press released before the timeout is a tap`() {
        val hold = WheelMediaHold()
        hold.down(next)

        assertEquals(WheelMediaHold.Action.TAP, hold.up(next))
    }

    @Test
    fun `a press that outlives the timeout scans and the release stops it`() {
        val hold = WheelMediaHold()
        hold.down(next)

        assertEquals(WheelMediaHold.Action.SCAN_START, hold.hold())
        assertEquals(next, hold.scanningKey)
        assertEquals(WheelMediaHold.Action.SCAN_STOP, hold.up(next))
        assertEquals(0, hold.scanningKey)
    }

    @Test
    fun `the timeout is the delay the caller waits`() {
        val hold = WheelMediaHold(holdMillis = 500L)

        assertEquals(500L, hold.down(previous))
    }

    @Test
    fun `a release before the timer fires never starts a scan`() {
        val hold = WheelMediaHold()
        hold.down(next)
        hold.up(next)

        // The timer still runs — callbacks already posted cannot be unposted — so it has to find
        // nothing to do rather than start a scan of a key that is no longer down.
        assertEquals(WheelMediaHold.Action.NONE, hold.hold())
        assertEquals(0, hold.scanningKey)
    }

    @Test
    fun `a second hold of the same key during a scan is ignored`() {
        val hold = WheelMediaHold()
        hold.down(next)
        hold.hold()

        // Key auto-repeat, or a stale down: neither may restart anything.
        assertNull(hold.down(next))
        assertEquals(WheelMediaHold.Action.NONE, hold.hold())
    }

    @Test
    fun `the other key going down during a scan does not steal it`() {
        val hold = WheelMediaHold()
        hold.down(next)
        hold.hold()

        assertNull(hold.down(previous))
        // Its release is not ours either: the scan belongs to the key that started it.
        assertEquals(WheelMediaHold.Action.NONE, hold.up(previous))
        assertEquals(next, hold.scanningKey)
        assertEquals(WheelMediaHold.Action.SCAN_STOP, hold.up(next))
    }

    @Test
    fun `a cancelled release ends the scan without turning into a tap`() {
        val hold = WheelMediaHold()
        hold.down(next)
        hold.hold()

        // ACTION_CANCEL: the window lost focus with the key still down. Nothing is being tapped.
        assertEquals(WheelMediaHold.Action.SCAN_STOP, hold.cancel())
        assertEquals(WheelMediaHold.Action.NONE, hold.up(next))
    }

    @Test
    fun `stopping while nothing scans does nothing`() {
        val hold = WheelMediaHold()

        assertEquals(WheelMediaHold.Action.NONE, hold.cancel())
    }

    @Test
    fun `a tap after a cancelled press is not swallowed`() {
        val hold = WheelMediaHold()
        hold.down(next)
        hold.cancel()

        // The next press is a fresh press: a cancel must clear the key rather than leave a ghost
        // that makes the following tap look like a repeat.
        assertEquals(WheelMediaHold.DEFAULT_HOLD_MILLIS, hold.down(next))
        assertEquals(WheelMediaHold.Action.TAP, hold.up(next))
    }

    @Test
    fun `a resolved press scans without waiting and its release stops it`() {
        val hold = WheelMediaHold()

        // BYD's own long-press code: nothing is timed, the press already says what it means.
        assertEquals(true, hold.begin(next))
        assertEquals(next, hold.scanningKey)
        assertEquals(WheelMediaHold.Action.SCAN_STOP, hold.end(next))
        assertEquals(0, hold.scanningKey)
    }

    @Test
    fun `the second copy of one press does not restart the scan`() {
        // The head unit hands the wheel key to the window and to the media session, so the same
        // press arrives twice. The copy must be refused, not stacked on top of the running scan.
        val hold = WheelMediaHold()

        assertEquals(true, hold.begin(next))
        assertEquals(false, hold.begin(next))
        assertEquals(next, hold.scanningKey)
    }

    @Test
    fun `a resolved press inside a timed hold is the same scan`() {
        // A wheel that sends 87 first and then its long-press code: the timer may already have
        // started the scan, and the code that follows must not start a second one.
        val hold = WheelMediaHold()
        hold.down(next)
        assertEquals(WheelMediaHold.Action.SCAN_START, hold.hold())

        assertEquals(false, hold.begin(next))
        assertEquals(WheelMediaHold.Action.SCAN_STOP, hold.end(next))
        // The release of the underlying key must not turn into a track change after a scan.
        assertEquals(WheelMediaHold.Action.NONE, hold.up(next))
    }

    @Test
    fun `the other key coming up does not end a scan`() {
        val hold = WheelMediaHold()
        hold.begin(previous)

        assertEquals(WheelMediaHold.Action.NONE, hold.end(next))
        assertEquals(previous, hold.scanningKey)
    }
}
