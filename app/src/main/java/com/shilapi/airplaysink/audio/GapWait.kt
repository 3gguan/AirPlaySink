package com.shilapi.airplaysink.audio

/**
 * How long a missing packet may be waited for, given what the speaker still has in hand.
 *
 * Kept apart from [AudioPipeline] because it is the kind of arithmetic that is wrong in a way no
 * log shows: the wait is paid for out of the cushion, so a budget larger than the cushion is a
 * budget that guarantees an underrun. The 2026-10-21 session is the worked example — a 900 ms
 * ceiling against 665 ms of audio took the cushion to 62 ms on every write-off, and one of them
 * went all the way to silence.
 */
object GapWait {

    /**
     * The wait budget, in milliseconds.
     *
     * @param bufferedMs the audio actually sitting in the AudioTrack, i.e. what the wait spends.
     * @param ceilingMs the most a gap may be waited for at this cushion.
     * @param reserveMs what must be left when the wait gives up; 0 would spend the last of it.
     *
     * A cushion already below the reserve yields 0, which means "do not wait at all" rather than
     * "wait an unbounded amount of time" — the caller reads 0 as a spent budget.
     */
    fun budgetMs(bufferedMs: Long, ceilingMs: Long, reserveMs: Long): Long =
        minOf(ceilingMs, bufferedMs - reserveMs).coerceAtLeast(0L)

    /**
     * The longest single sleep inside a gap wait.
     *
     * A wait has to come back to measure the cushion again, or the reserve above is a statement
     * about the cushion before the wait rather than during it. Never 0: `Object.wait(0)` blocks
     * forever, which would hang the decode thread the first time the arithmetic rounded down.
     */
    fun sleepMs(remainingMs: Long, sliceMs: Long): Long =
        minOf(remainingMs, sliceMs).coerceAtLeast(1L)
}
