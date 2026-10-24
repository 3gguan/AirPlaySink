package com.shilapi.airplaysink

/**
 * A steering-wheel next/previous key that is being held: a tap changes track, a hold scans.
 *
 * The decision cannot be made when the key goes down — a tap and a hold start identically. It is
 * made by whichever happens first:
 *
 * * the key comes up before [holdMillis] → [Action.TAP];
 * * [holdMillis] passes with the key still down → [Action.SCAN_START], and the release then gives
 *   [Action.SCAN_STOP].
 *
 * Pure on purpose: no Android types and no clock of its own, so the timing rules can be checked in
 * a plain JVM test. The caller owns the timer and passes the moments in.
 */
internal class WheelMediaHold(private val holdMillis: Long = DEFAULT_HOLD_MILLIS) {

    enum class Action { NONE, TAP, SCAN_START, SCAN_STOP }

    /** The key that is down and has already turned into a scan, or 0 when nothing is scanning. */
    var scanningKey: Int = 0
        private set

    private var pressedKey = 0

    /**
     * The key went down. Returns the milliseconds to wait before calling [hold], or null when this
     * press is not ours to track (a repeat, or a different key pressed while another is down).
     */
    fun down(key: Int): Long? {
        if (scanningKey != 0) return null
        if (pressedKey != 0 && pressedKey != key) return null
        if (pressedKey == key) return null
        pressedKey = key
        return holdMillis
    }

    /** The hold timer fired. Only the key that is still down may start a scan. */
    fun hold(): Action {
        if (pressedKey == 0 || scanningKey != 0) return Action.NONE
        scanningKey = pressedKey
        return Action.SCAN_START
    }

    /** The key came up: a tap if the scan never started, otherwise the end of the scan. */
    fun up(key: Int): Action = when {
        scanningKey != 0 && scanningKey == key -> {
            scanningKey = 0
            pressedKey = 0
            Action.SCAN_STOP
        }
        pressedKey == key -> {
            pressedKey = 0
            Action.TAP
        }
        else -> Action.NONE
    }

    /**
     * A press the wheel has already resolved for us: its own long-press code, or its own
     * fast-forward key. Nothing is timed — [key] is the scan's identity, not the code it arrived
     * under, so the same press is recognised whichever route and whichever code carried it.
     *
     * Returns false when a scan is already running, which is what the second copy of one press
     * looks like on a head unit that hands the key to both the window and the media session.
     */
    fun begin(key: Int): Boolean {
        if (scanningKey != 0) return false
        pressedKey = 0
        scanningKey = key
        return true
    }

    /** The resolved press came up. Only the key that is scanning ends anything. */
    fun end(key: Int): Action = if (scanningKey != 0 && scanningKey == key) {
        scanningKey = 0
        pressedKey = 0
        Action.SCAN_STOP
    } else {
        Action.NONE
    }

    /**
     * Whatever was down is gone: the window lost focus, the session ended, the receiver stopped.
     * A scan still running must be stopped by the caller, which is what the return value says.
     */
    fun cancel(): Action {
        pressedKey = 0
        if (scanningKey == 0) return Action.NONE
        scanningKey = 0
        return Action.SCAN_STOP
    }

    companion object {
        /**
         * Long enough that a deliberate tap never reaches it, short enough that the scan starts
         * while the finger is still down. 500ms is Android's own long-press timeout.
         */
        const val DEFAULT_HOLD_MILLIS = 500L
    }
}
