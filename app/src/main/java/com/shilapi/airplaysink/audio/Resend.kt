package com.shilapi.airplaysink.audio

/**
 * Asking the sender for packets that never arrived.
 *
 * AirPlay has no forward error correction and the sender never retransmits on its own: a packet
 * that is lost stays lost unless the receiver names it. What the receiver is allowed to do is send
 * a small [Request] to the sender's control port, and the sender answers with the packet it still
 * has in hand. It only keeps a few seconds, so the ask has to go out promptly.
 *
 * The timing below is shairport-sync's, taken from `shairport.c` (`resend_control_*`) and
 * `player.c:1770-1791`. There is deliberately no attempt counter anywhere: a hole is asked about
 * for as long as it is worth asking about, and the clock — not a constant — decides when that stops.
 */
object Resend {

    /** The second byte of the request datagram: Apple's "resend" type. */
    const val TYPE = 0xD5

    /**
     * The deepest gap worth asking about, in packets.
     *
     * Past this the "gap" is not a loss — the sender has moved somewhere else in the stream (a
     * seek, or a flush that never reached us) and the old sequence numbers are never coming back.
     * Asking for a hundred at once would also be answered with a hundred datagrams, which is
     * exactly the traffic a link that is already dropping packets does not need.
     */
    const val MAX_SPAN = 128

    /**
     * How long a hole has to stay a hole before it is worth asking about.
     *
     * Most holes in the sequence are packets that merely arrived out of order and sort themselves
     * out within a few milliseconds. Asking on the first one turns a reordering into a
     * retransmission. shairport-sync's `resend_control_first_check_time`, 0.10 s.
     */
    const val FIRST_CHECK_MS = 100L

    /**
     * How long one hole waits between two asks.
     *
     * shairport-sync's `resend_control_check_interval_time`, 0.25 s. It can afford to be this
     * relaxed because the playhead does not wait for the hole — see [ResendTracker].
     */
    const val CHECK_INTERVAL_MS = 250L

    /**
     * How much notice to leave before a hole is written off.
     *
     * The ask has to be off the wire while the packet is still useful, so the last one is fired
     * this long before the packet is due at the speaker — shairport-sync's
     * `resend_control_last_check_time`, 0.10 s.
     */
    const val LAST_CHECK_MS = 100L

    /**
     * How long all requests are held back after one of them fails to go out.
     *
     * A dead link makes `sendto` fail on every tick, and retrying a socket that is not there
     * achieves nothing but load. shairport-sync's `resend_error_backoff_time`, 0.3 s.
     */
    const val ERROR_BACKOFF_MS = 300L

    /** Ceiling on the number of holes tracked at once, so a dead sender cannot grow it forever. */
    const val MAX_QUEUE = 64

    /**
     * The longest a packet can sit in the queue before it is due at the speaker.
     *
     * This is the number the whole retransmission budget is derived from, and it is the same one
     * shairport-sync uses: the latency the sender announced, less what the audio backend holds.
     * An iPhone announces about two seconds and the backend holds 660 ms here, leaving ~1.3 s of
     * queue time — five or six asks at the interval above.
     *
     * Derived rather than configured: a caller that knows better passes its own figure.
     */
    const val QUEUE_WINDOW_MS = 1_340L

    /** One hole: [count] packets starting at 16-bit RTP sequence [first]. */
    data class Request(val first: Int, val count: Int)

    /**
     * The 8-byte datagram that carries one [Request].
     *
     * Both transports send the same bytes to the same kind of port; only the address it is aimed at
     * differs. Kept here so the two cannot drift apart — a malformed request is silently ignored by
     * the sender, which looks exactly like a sender that has nothing left to give.
     */
    fun encode(first: Int, count: Int): ByteArray {
        val out = ByteArray(8)
        out[0] = 0x80.toByte()
        out[1] = TYPE.toByte()
        // The third field is a "packet count of one request" marker in every capture of this
        // message; the sender ignores it, but it is written as observed rather than as zero.
        out[2] = 0
        out[3] = 1
        out[4] = (first ushr 8).toByte()
        out[5] = first.toByte()
        out[6] = (count ushr 8).toByte()
        out[7] = count.toByte()
        return out
    }
}

/**
 * The asking half of retransmission: which holes to request, when, and when to stop.
 *
 * Modelled on shairport-sync's hole table (`player.c:1732-1829`), and the important part of that
 * model is what it does *not* do: it never holds the playhead. A packet that has not arrived is a
 * hole the decode loop writes silence for and steps over; the request goes out from the network
 * thread on its own schedule. Playback and recovery are therefore independent, which is what makes
 * a relaxed 250 ms retry interval affordable — nothing is blocked while it waits.
 *
 * Three gates decide whether a hole is asked about right now, evaluated on every packet and on the
 * socket's idle tick:
 *
 * | gate          | condition                                   | default |
 * |---------------|---------------------------------------------|---------|
 * | too early     | the hole is younger than [firstCheckMs]      | 100 ms  |
 * | too soon      | it was asked this recently                   | 250 ms  |
 * | too late      | it is about to be due at the speaker         | 100 ms  |
 *
 * "Too late" is the only termination condition: a hole is dropped once less than [lastCheckMs]
 * remains of [windowMs]. There is no attempt limit, and no counting of attempts at all.
 *
 * Everything is decided from the 16-bit RTP sequence, not from the counter the pipeline orders by.
 * The two differ: AirPlay 2 realtime audio is sequenced by `timestamp / framesPerPacket` for
 * playback, but a request names the header's own 16-bit field, so a gap has to be detected in that
 * space or the ask would name the wrong packets.
 *
 * Thread-safe: packets arrive on the audio thread, requests are fired from it and from the control
 * thread's idle tick.
 */
class ResendTracker(
    private val windowMs: Long = Resend.QUEUE_WINDOW_MS,
    private val firstCheckMs: Long = Resend.FIRST_CHECK_MS,
    private val checkIntervalMs: Long = Resend.CHECK_INTERVAL_MS,
    private val lastCheckMs: Long = Resend.LAST_CHECK_MS,
    private val maxQueue: Int = Resend.MAX_QUEUE,
) {

    private class Hole(val first: Int, val count: Int, val discoveredAt: Long) {
        /** When this hole was last asked about, or 0 while it never has been. */
        var lastAskedAt = 0L
    }

    private val holes = ArrayDeque<Hole>()

    /** The sequence we expect next, in 16-bit space; -1 before the first packet. */
    private var expected = -1

    /**
     * Packets in a row that arrived behind [expected].
     *
     * One late packet says nothing about where the stream is. A run of them means the sender
     * restarted its counter rather than that these are all late, and the expectation has to be
     * moved or nothing would ever be asked for again.
     */
    private var latePackets = 0

    fun reset() {
        synchronized(this) {
            holes.clear()
            expected = -1
            latePackets = 0
        }
    }

    /** Holes currently being chased. Exposed for tests and for the log line. */
    fun pendingGaps(): Int = synchronized(this) { holes.size }

    /** The next sequence this tracker is waiting for, or -1. Exposed for tests. */
    fun expectedSequence(): Int = synchronized(this) { expected }

    /**
     * Note an arriving packet, ordinary or retransmitted.
     *
     * Anything already in hand is not missing, so its hole is dropped first. That is also how a
     * retransmission cancels its own retries: the packet that came back looks like any other
     * packet here.
     */
    fun onPacket(rawSequence: Int, nowMs: Long) {
        val raw = rawSequence and 0xFFFF
        synchronized(this) {
            cancelLocked(raw)
            if (expected < 0) {
                expected = (raw + 1) and 0xFFFF
                return
            }
            when (val delta = (raw - expected) and 0xFFFF) {
                0 -> {
                    latePackets = 0
                    expected = (raw + 1) and 0xFFFF
                }
                // A gap: the packets between what we were waiting for and this one never arrived.
                // The expectation moves past them so the same hole is not counted again by every
                // following packet.
                in 1..Resend.MAX_SPAN -> {
                    latePackets = 0
                    addHoleLocked(expected, delta, nowMs)
                    expected = (raw + 1) and 0xFFFF
                }
                // Behind what we are waiting for: merely late. Moving the expectation back would
                // make every following packet look like a fresh gap.
                else -> {
                    if (delta >= 0x8000) {
                        if (++latePackets > RESYNC_AFTER_LATE) {
                            latePackets = 0
                            expected = (raw + 1) and 0xFFFF
                        }
                    } else {
                        // Too far ahead to be a gap: a new position in the stream, not a loss.
                        latePackets = 0
                        expected = (raw + 1) and 0xFFFF
                    }
                }
            }
        }
    }

    /**
     * Send every request that has come due.
     *
     * Consecutive holes are merged into one request, exactly as shairport-sync does: a burst loss
     * is one datagram and one answer, not a dozen of each.
     *
     * [send] returns false when the write could not go out — throttled by the transport after a
     * failure, or no address learned yet. The holes stay in the table, so a later tick tries again;
     * only the "too late" deadline ever removes one.
     */
    fun fireDue(nowMs: Long, send: (Resend.Request) -> Boolean) {
        synchronized(this) {
            dropExpiredLocked(nowMs)
            if (holes.isEmpty()) return

            val batches = ArrayList<IntArray>(2)
            val sought = ArrayList<Hole>(holes.size)
            var runFirst = -1
            var runCount = 0

            fun closeRun() {
                if (runFirst >= 0) {
                    batches.add(intArrayOf(runFirst, runCount))
                    runFirst = -1
                    runCount = 0
                }
            }

            for (hole in holes) {
                val askable = nowMs - hole.discoveredAt >= firstCheckMs &&
                    (hole.lastAskedAt == 0L || nowMs - hole.lastAskedAt >= checkIntervalMs)
                if (!askable) {
                    closeRun()
                    continue
                }
                if (runFirst < 0) {
                    runFirst = hole.first
                    runCount = hole.count
                } else if (((hole.first - runFirst) and 0xFFFF) == runCount) {
                    runCount += hole.count
                } else {
                    closeRun()
                    runFirst = hole.first
                    runCount = hole.count
                }
                sought.add(hole)
            }
            closeRun()

            var sent = 0
            for (batch in batches) {
                if (!send(Resend.Request(batch[0], batch[1]))) break
                sent++
            }
            if (sent == 0) return
            for (hole in sought) hole.lastAskedAt = nowMs
        }
    }

    /** Must be called while holding the lock. */
    private fun addHoleLocked(first: Int, count: Int, nowMs: Long) {
        if (holes.size >= maxQueue) holes.removeFirst()
        holes.addLast(Hole(first, count, nowMs))
    }

    /**
     * Must be called while holding the lock.
     *
     * The deadline is the whole of the termination policy: a hole discovered at `t` is due at the
     * speaker at `t + windowMs`, and the last ask that can still be useful has to leave
     * [lastCheckMs] before that.
     */
    private fun dropExpiredLocked(nowMs: Long) {
        val budget = (windowMs - lastCheckMs).coerceAtLeast(0L)
        val iterator = holes.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().discoveredAt >= budget) iterator.remove()
        }
    }

    /**
     * Must be called while holding the lock.
     *
     * A packet that turned up is one packet — shairport-sync keeps a slot per sequence, so filling
     * the middle of a run leaves both ends still missing. A range has to be split to say the same
     * thing, and the halves inherit the last ask so a hole nobody has asked about yet is not
     * suddenly treated as freshly asked.
     */
    private fun cancelLocked(raw: Int) {
        if (holes.isEmpty()) return
        var index = 0
        while (index < holes.size) {
            val hole = holes[index]
            val offset = (raw - hole.first) and 0xFFFF
            if (offset >= hole.count) {
                index++
                continue
            }
            holes.removeAt(index)
            val discoveredAt = hole.discoveredAt
            val askedAt = hole.lastAskedAt
            if (offset > 0) {
                holes.add(
                    index,
                    Hole(hole.first, offset, discoveredAt).also { it.lastAskedAt = askedAt },
                )
            }
            val afterCount = hole.count - offset - 1
            if (afterCount > 0) {
                holes.add(
                    index + (if (offset > 0) 1 else 0),
                    Hole((raw + 1) and 0xFFFF, afterCount, discoveredAt)
                        .also { it.lastAskedAt = askedAt },
                )
            }
            index++
        }
    }

    private companion object {
        /** How many late packets in a row mean "the counter restarted", mirroring the RAOP path. */
        const val RESYNC_AFTER_LATE = 96
    }
}
