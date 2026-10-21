package com.shilapi.airplaysink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asking half of retransmission, tested on its own clock.
 *
 * The cases worth pinning are the ones that decide whether a hole is ever asked about at all:
 * reordering (not a loss), a late packet (not a gap), a wrap (must not look like a gap), and a seek
 * (a gap too large to be a loss). Each of those, read the wrong way, either floods the sender with
 * requests or silently never asks.
 *
 * The rest of the file is about the gate that replaced the attempt counter. shairport-sync stops
 * asking because the packet is due at the speaker, not because it has asked four times, so the
 * tests below drive the clock rather than counting sends.
 */
class ResendTrackerTest {

    private val requests = ArrayList<Resend.Request>()

    private fun tracker() = ResendTracker()

    private fun ResendTracker.collect(now: Long, accept: Boolean = true): Int {
        var sent = 0
        fireDue(now) { request ->
            requests.add(request)
            sent++
            accept
        }
        return sent
    }

    @Test
    fun `consecutive packets ask for nothing`() {
        val tracker = tracker()
        var now = 1_000L
        repeat(20) {
            tracker.onPacket(100 + it, now)
            now += 8
        }
        assertEquals(0, tracker.collect(now))
        assertEquals(120, tracker.expectedSequence())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a gap is asked for once, after the head start`() {
        val tracker = tracker()
        tracker.onPacket(100, 1_000L)
        // Three packets went missing: the next one that arrives is 104.
        tracker.onPacket(104, 1_008L)
        assertEquals(0, tracker.collect(1_008L + Resend.FIRST_CHECK_MS - 1))
        assertEquals(1, tracker.collect(1_008L + Resend.FIRST_CHECK_MS))
        assertEquals(Resend.Request(first = 101, count = 3), requests.single())
        // Asking again on the very next tick would be the flood this is meant to avoid.
        assertEquals(0, tracker.collect(1_008L + Resend.FIRST_CHECK_MS + 1))
    }

    @Test
    fun `asking stops when the packet is due, not after a fixed number of tries`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        tracker.onPacket(104, 0L)
        // Walk the clock in 20 ms steps for three seconds; every send that is due goes out.
        var now = 0L
        while (now <= 3_000L) {
            tracker.collect(now)
            now += 20L
        }
        // The budget is the queue window less the last-check margin, and asks are one interval
        // apart: 1240 ms of budget fits an ask at 100, 350, 600, 850 and 1100 ms — five, and no
        // more, however long the clock runs.
        val budget = Resend.QUEUE_WINDOW_MS - Resend.LAST_CHECK_MS
        val expected = ((budget - Resend.FIRST_CHECK_MS) / Resend.CHECK_INTERVAL_MS + 1).toInt()
        assertEquals(expected, requests.size)
        assertTrue(requests.all { it == Resend.Request(101, 3) })
        // And the hole is gone from the table once it is too late to matter.
        assertEquals(0, tracker.pendingGaps())
    }

    @Test
    fun `a hole is dropped just before the packet is due`() {
        val tracker = ResendTracker(windowMs = 500L)
        tracker.onPacket(100, 0L)
        tracker.onPacket(104, 0L)
        // 500 ms window, 100 ms last check: at 400 ms the hole is written off.
        tracker.collect(399L)
        assertEquals(1, tracker.pendingGaps())
        tracker.collect(400L)
        assertEquals(0, tracker.pendingGaps())
    }

    @Test
    fun `a packet that turns up cancels the rest of its requests`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        tracker.onPacket(104, 0L)
        tracker.collect(Resend.FIRST_CHECK_MS)
        assertEquals(1, requests.size)

        // The retransmissions land. Each one looks like any other packet here, which is the point:
        // it says "this sequence is not missing" and the further asks stop on their own. Only the
        // packets that actually came back count — one arrival shrinks the hole, it does not clear
        // it, which is what keeping a slot per sequence means.
        tracker.onPacket(101, 60L)
        assertEquals(1, tracker.pendingGaps())
        tracker.onPacket(102, 61L)
        tracker.onPacket(103, 62L)
        assertEquals(0, tracker.pendingGaps())
        var now = 62L + Resend.CHECK_INTERVAL_MS
        repeat(3) {
            tracker.collect(now)
            now += Resend.CHECK_INTERVAL_MS
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a hole that is partly filled keeps asking for the rest`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        // Four missing: 101..104.
        tracker.onPacket(105, 0L)
        assertEquals(1, tracker.collect(Resend.FIRST_CHECK_MS))
        assertEquals(Resend.Request(101, 4), requests.single())

        // Two of them come back: only 103 and 104 are still missing.
        tracker.onPacket(101, 50L)
        tracker.onPacket(102, 50L)
        assertEquals(1, tracker.pendingGaps())
        tracker.collect(Resend.FIRST_CHECK_MS + Resend.CHECK_INTERVAL_MS)
        assertEquals(Resend.Request(103, 2), requests.last())
    }

    @Test
    fun `a late packet is not a gap`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        tracker.onPacket(99, 8L)
        assertEquals(0, tracker.collect(1_000L))
        // The expectation must not move back, or every following packet would look like a gap.
        assertEquals(101, tracker.expectedSequence())
        tracker.onPacket(101, 16L)
        assertEquals(0, tracker.collect(1_000L))
        assertEquals(102, tracker.expectedSequence())
    }

    @Test
    fun `a wrap is not a gap`() {
        val tracker = tracker()
        tracker.onPacket(65534, 0L)
        tracker.onPacket(65535, 8L)
        // Two packets lost across the 16-bit wrap.
        tracker.onPacket(2, 16L)
        assertEquals(0, tracker.collect(16L + Resend.FIRST_CHECK_MS - 1))
        assertEquals(1, tracker.collect(16L + Resend.FIRST_CHECK_MS))
        assertEquals(Resend.Request(first = 0, count = 2), requests.single())
    }

    @Test
    fun `a jump is a new position, not a loss`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        // A seek: far too many packets to ask for, and the old ones are never coming back.
        tracker.onPacket(100 + Resend.MAX_SPAN + 50, 8L)
        assertEquals(0, tracker.collect(10_000L))
        assertEquals(100 + Resend.MAX_SPAN + 51, tracker.expectedSequence())
        assertTrue(tracker.pendingGaps() == 0)
    }

    @Test
    fun `a request that could not be sent is kept, not dropped`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        tracker.onPacket(104, 0L)
        // Nothing could be sent yet — no address, or the socket is backing off after a failure.
        assertEquals(1, tracker.collect(Resend.FIRST_CHECK_MS, accept = false))
        assertEquals(1, tracker.pendingGaps())
        // It is still there on the next tick rather than being written off.
        assertEquals(1, tracker.collect(Resend.FIRST_CHECK_MS + 20L))
        assertEquals(2, requests.size)
    }

    @Test
    fun `the request datagram is eight bytes of Apple's resend`() {
        val bytes = Resend.encode(first = 0x1234, count = 3)
        assertEquals(8, bytes.size)
        assertEquals(0x80, bytes[0].toInt() and 0xFF)
        assertEquals(Resend.TYPE, bytes[1].toInt() and 0xFF)
        assertEquals(1, bytes[3].toInt() and 0xFF)
        assertEquals(0x12, bytes[4].toInt() and 0xFF)
        assertEquals(0x34, bytes[5].toInt() and 0xFF)
        assertEquals(3, bytes[7].toInt() and 0xFF)

        // A count that needs both bytes must not lose its high half.
        val wide = Resend.encode(first = 0xFFFF, count = 128)
        assertEquals(0xFF, wide[4].toInt() and 0xFF)
        assertEquals(0xFF, wide[5].toInt() and 0xFF)
        assertEquals(0x00, wide[6].toInt() and 0xFF)
        assertEquals(128, wide[7].toInt() and 0xFF)
    }

    @Test
    fun `reset forgets the stream`() {
        val tracker = tracker()
        tracker.onPacket(100, 0L)
        tracker.onPacket(110, 8L)
        assertEquals(1, tracker.pendingGaps())
        tracker.reset()
        assertEquals(0, tracker.pendingGaps())
        assertEquals(-1, tracker.expectedSequence())
        // The first packet of the new stream re-anchors instead of looking like a 100-packet gap.
        tracker.onPacket(900, 20L)
        assertEquals(901, tracker.expectedSequence())
        assertFalse(tracker.pendingGaps() > 0)
    }
}
