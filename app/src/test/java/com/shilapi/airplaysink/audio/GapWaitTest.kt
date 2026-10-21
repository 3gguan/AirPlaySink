package com.shilapi.airplaysink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapWaitTest {

    @Test
    fun `a deep cushion lets the full ceiling through`() {
        // More audio in hand than the ceiling plus the reserve: the ceiling is what binds, which
        // is the whole point of asking for a bigger cushion in the first place. 1150 ms is the
        // crossover — one millisecond less and the reserve starts trimming the wait.
        assertEquals(900L, GapWait.budgetMs(bufferedMs = 1_200L, ceilingMs = 900L, reserveMs = 250L))
        assertEquals(900L, GapWait.budgetMs(bufferedMs = 1_150L, ceilingMs = 900L, reserveMs = 250L))
        assertEquals(650L, GapWait.budgetMs(bufferedMs = 900L, ceilingMs = 900L, reserveMs = 250L))
    }

    @Test
    fun `a thin cushion shortens the wait to what it can pay`() {
        // The 2026-10-21 session, to the millisecond: 665 ms of AudioTrack against a 900 ms
        // ceiling. The old code waited the ceiling and left 0 ms, which is where the underrun came
        // from; the budget stops at the reserve instead, and the swap costs 415 ms of recovery
        // room rather than all of it.
        val budget = GapWait.budgetMs(bufferedMs = 665L, ceilingMs = 900L, reserveMs = 250L)
        assertEquals(415L, budget)
        assertEquals(250L, 665L - budget)
    }

    @Test
    fun `the reserve is what survives the wait`() {
        // Stated as the invariant rather than as one case: whatever the cushion, giving up at the
        // budget must leave at least the reserve in the speaker.
        val range = 0L..1_500L
        for (buffered in range step 5) {
            val budget = GapWait.budgetMs(buffered, 900L, 250L)
            val left = buffered - budget
            assertTrue(
                "buffered $buffered -> budget $budget leaves $left",
                left >= 250L || budget == 0L,
            )
        }
    }

    @Test
    fun `a cushion below the reserve is not a budget to spend`() {
        // Already too thin to trade. 0 means "stop waiting now", and the caller reads it that way;
        // it must never fall through to a negative number that reads as "wait forever".
        assertEquals(0L, GapWait.budgetMs(bufferedMs = 200L, ceilingMs = 900L, reserveMs = 250L))
        assertEquals(0L, GapWait.budgetMs(bufferedMs = 0L, ceilingMs = 900L, reserveMs = 250L))
        assertEquals(0L, GapWait.budgetMs(bufferedMs = -40L, ceilingMs = 900L, reserveMs = 250L))
    }

    @Test
    fun `a slice never sleeps for zero`() {
        // Object.wait(0) waits forever. The arithmetic rounds down at the end of a budget, so this
        // is the one conversion that can stall the decode thread permanently if it is wrong.
        assertEquals(50L, GapWait.sleepMs(remainingMs = 415L, sliceMs = 50L))
        assertEquals(30L, GapWait.sleepMs(remainingMs = 30L, sliceMs = 50L))
        assertEquals(1L, GapWait.sleepMs(remainingMs = 0L, sliceMs = 50L))
        assertEquals(1L, GapWait.sleepMs(remainingMs = -5L, sliceMs = 50L))
        assertEquals(1L, GapWait.sleepMs(remainingMs = 10L, sliceMs = 0L))
    }
}
