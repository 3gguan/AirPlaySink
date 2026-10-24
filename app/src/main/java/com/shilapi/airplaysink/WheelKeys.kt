package com.shilapi.airplaysink

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.airplaysink.dacp.DacpClient
import com.shilapi.airplaysink.util.SinkLog

/**
 * The two wheel keys that skip: a tap changes track, a hold scans in that direction.
 *
 * The command itself is not built here for the most part — every path ends in
 * [SinkService.remoteCommand], which is the one place that knows whether this session is AirPlay 1
 * (DACP) or AirPlay 2 (`sendMediaRemoteCommand`). Sending a scan down the DACP path would be
 * silently useless: the sink is an AirPlay 2 receiver, and DACP has no endpoint under it.
 *
 * Two routes feed this object, and a single press can reach both:
 *
 * * the activity's window, which is where BYD gives the wheel's keys while the page is in front;
 * * the media session, for the presses the head unit emits as media buttons.
 *
 * Everything is logged, because a key that does nothing on a head unit with no ADB leaves nothing
 * to look at otherwise. The app shows the log on screen, so the lines are the whole diagnosis.
 */
internal object WheelKeys {

    private const val TAG = "Wheel"

    private const val NEXT = KeyEvent.KEYCODE_MEDIA_NEXT
    private const val PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS

    /** A wheel that reports a hold as its own key instead of repeating the skip key. */
    private const val FAST_FORWARD = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    private const val REWIND = KeyEvent.KEYCODE_MEDIA_REWIND

    /**
     * BYD's own codes for "next/previous was held", taken from a DiLink 3 wheel (Android 10).
     *
     * They are not in the public SDK, so they cannot be named through [KeyEvent]; the framework
     * here prints them as `KEYCODE_AUTO_MEDIA_NEXT_LP` / `..._PREVIOUS_LP`. The wheel does **not**
     * repeat 87/88 while a key is held — it sends one of these instead, which is why timing a
     * held 87 never fires on this car.
     *
     * They are also a **pulse**: down and up arrive about 30ms apart however long the finger stays
     * down, so the code says "the driver held the key" and nothing about for how long. What the
     * driver gets for it is therefore a fixed burst ([BURST_MILLIS]) rather than a scan that lasts
     * as long as the finger does.
     */
    private const val BYD_NEXT_HELD = 302
    private const val BYD_PREVIOUS_HELD = 303

    /**
     * How far one BYD long press scans.
     *
     * The phone decides what a scan means, and it does not scan continuously: a `beginff` makes
     * iOS **jump** the playback position, then jump again about once a second for as long as the
     * scan is held. A three-second burst therefore skipped three times, which is why a press that
     * looked like one gesture moved the track three steps.
     *
     * The first jump lands ~80ms after `beginff` (that is when the sender posts its first
     * `/audioMode` notice); the second is ~1.05s later. Ending just under that — after the first
     * jump, before the second — leaves exactly one step per press.
     */
    private const val BURST_MILLIS = 900L

    /**
     * The longest down-to-up gap still taken as a pulse rather than as a real release.
     *
     * A pulse's release follows its press within tens of milliseconds, long before the finger
     * leaves the key, so it says nothing about how long the driver held it. A release that takes
     * longer than this cannot be that: it is the finger actually coming up, and ending the scan
     * there gives the driver exactly the hold they asked for. The two shapes are told apart here
     * instead of being assumed, so either kind of head unit ends up with the right behaviour.
     */
    private const val PULSE_RELEASE_MILLIS = 200L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val hold = WheelMediaHold()

    private var downAt = 0L

    private val holdDue = Runnable {
        if (hold.hold() == WheelMediaHold.Action.SCAN_START) {
            val key = hold.scanningKey
            SinkLog.i("$TAG: ${name(key)} held ${now() - downAt}ms — scanning until it comes up")
            scan(key, start = true)
        }
    }

    /**
     * A scan runs until it is told to stop, so a release that never arrives must not leave the
     * phone winding: this ends it after the longest hold worth honouring.
     */
    private val scanLimit = Runnable {
        val key = hold.scanningKey
        if (key == 0) return@Runnable
        SinkLog.w("$TAG: ${name(key)} still scanning after ${SCAN_LIMIT_MILLIS / 1000}s — stopping it")
        if (hold.cancel() == WheelMediaHold.Action.SCAN_STOP) scan(key, start = false)
    }

    /** The burst a BYD long press buys has run its course; nothing else will end it. */
    private val burstDone = Runnable {
        val key = hold.scanningKey
        if (key == 0) return@Runnable
        SinkLog.i("$TAG: $burstName burst over after ${BURST_MILLIS}ms — stopping the scan")
        if (hold.cancel() == WheelMediaHold.Action.SCAN_STOP) scan(key, start = false)
    }

    /**
     * The burst that is running, as the key that started it was named: the scan's identity is the
     * skip key, which would otherwise make a 302 press log itself as 87.
     */
    private var burstName = ""

    /**
     * Handles one key event. Returns true when the event was ours and must not be passed on, so
     * the head unit's own player does not act on a key that was meant for the phone.
     */
    fun onKey(event: KeyEvent, source: String): Boolean {
        val key = event.keyCode

        when (key) {
            BYD_NEXT_HELD, BYD_PREVIOUS_HELD -> return pulseKey(event, source)
            FAST_FORWARD, REWIND -> return heldKey(event, scanIdentityOf(key), source)
        }

        if (key != NEXT && key != PREVIOUS) {
            noteOtherKey(event, source)
            return false
        }
        // The receiver may be off: then the wheel belongs to whatever the car is playing, and a
        // press must pass through untouched instead of being swallowed by an app that would only
        // write a log line about it.
        if (!SinkService.isActive) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downAt = now()
                    SinkLog.i("$TAG: ${name(key)} down — watching for a hold ($source)")
                    hold.down(key)?.let { delay ->
                        handler.removeCallbacks(holdDue)
                        handler.postDelayed(holdDue, delay)
                    }
                }
                // AUTO repeats are dropped on purpose: the timer is the hold, and re-arming it on
                // every repeat would postpone the scan for as long as the driver keeps holding.
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(holdDue)
                val action = if (event.isCanceled) hold.cancel() else hold.up(key)
                when (action) {
                    WheelMediaHold.Action.TAP -> {
                        SinkLog.i("$TAG: ${name(key)} up after ${now() - downAt}ms — a tap")
                        send(commandForTap(key))
                    }
                    WheelMediaHold.Action.SCAN_STOP -> {
                        SinkLog.i("$TAG: ${name(key)} up after ${now() - downAt}ms — scan stopped")
                        scan(key, start = false)
                    }
                    else -> Unit
                }
            }
            else -> return false
        }
        return true
    }

    /** Ends a scan the session can no longer finish: the page closed, the receiver stopped. */
    fun stop() {
        handler.removeCallbacks(holdDue)
        handler.removeCallbacks(scanLimit)
        handler.removeCallbacks(burstDone)
        val key = hold.scanningKey
        if (hold.cancel() == WheelMediaHold.Action.SCAN_STOP && key != 0) scan(key, start = false)
    }

    // ------------------------------------------------------------------ the resolved shapes

    /**
     * The skip key an "already decided" code stands for.
     *
     * A resolved press is mapped to the skip key it replaces rather than to itself, so a scan has
     * one identity however its press arrived: that is what makes the duplicate copy — the same
     * press delivered to both routes — collapse onto the scan already running.
     */
    private fun scanIdentityOf(key: Int): Int = when (key) {
        FAST_FORWARD, BYD_NEXT_HELD -> NEXT
        else -> PREVIOUS
    }

    /**
     * A BYD long press: a pulse that says "the driver held it", with no say in how long.
     *
     * The release normally arrives about 30ms after the press, while the finger is still down, so
     * it cannot be what ends the scan — a 30ms scan is no scan at all. The burst is timed here for
     * that case. But the gap is measured rather than assumed: if this unit ever does hold the key
     * down and release it on the finger coming up, that release is later than [PULSE_RELEASE_MILLIS]
     * and ends the scan at the moment the driver let go.
     */
    private fun pulseKey(event: KeyEvent, source: String): Boolean {
        if (!SinkService.isActive) return false
        val key = event.keyCode
        val identity = scanIdentityOf(key)
        if (event.action == KeyEvent.ACTION_UP) {
            val held = now() - downAt
            SinkLog.i("$TAG: ${name(key)} up after ${held}ms ($source)")
            // Below the threshold this is the pulse's echo, and the burst timer owns the end.
            if (held >= PULSE_RELEASE_MILLIS && hold.end(identity) == WheelMediaHold.Action.SCAN_STOP) {
                SinkLog.i("$TAG: ${name(key)} released after ${held}ms — stopping the scan")
                scan(identity, start = false)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (!hold.begin(identity)) {
            // This head unit hands the wheel key to the window and to the media session, so one
            // press arrives twice, a few tens of milliseconds apart. The second copy must not
            // start a second burst on top of the first.
            SinkLog.i("$TAG: ${name(key)} down — already scanning, ignoring the copy ($source)")
            return true
        }
        downAt = now()
        SinkLog.i("$TAG: ${name(key)} down — a long press, scanning for ${BURST_MILLIS}ms ($source)")
        burstName = name(key)
        scan(identity, start = true)
        handler.removeCallbacks(burstDone)
        handler.postDelayed(burstDone, BURST_MILLIS)
        return true
    }

    /** A wheel whose own key is the scan: down starts it, up stops it. */
    private fun heldKey(event: KeyEvent, identity: Int, source: String): Boolean {
        if (!SinkService.isActive) return false
        val key = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                if (!hold.begin(identity)) {
                    SinkLog.i("$TAG: ${name(key)} down — already scanning, ignoring the copy ($source)")
                    return true
                }
                SinkLog.i("$TAG: ${name(key)} down — a hold, scanning ($source)")
                scan(identity, start = true)
            }
            KeyEvent.ACTION_UP -> {
                if (hold.end(identity) == WheelMediaHold.Action.SCAN_STOP) {
                    SinkLog.i("$TAG: ${name(key)} up — scan stopped ($source)")
                    scan(identity, start = false)
                }
            }
            else -> Unit
        }
        return true
    }

    // ------------------------------------------------------------------ commands

    private fun commandForTap(key: Int): DacpClient.Command =
        if (key == NEXT) DacpClient.Command.NEXT else DacpClient.Command.PREVIOUS

    private fun commandForScan(key: Int, start: Boolean): DacpClient.Command =
        if (key == NEXT) {
            if (start) DacpClient.Command.SEEK_FORWARD else DacpClient.Command.SEEK_FORWARD_STOP
        } else {
            if (start) DacpClient.Command.SEEK_BACKWARD else DacpClient.Command.SEEK_BACKWARD_STOP
        }

    private fun send(command: DacpClient.Command) {
        SinkLog.i("$TAG: sending \"${command.path}\"")
        SinkService.remoteCommand(command)
    }

    private fun scan(key: Int, start: Boolean) {
        handler.removeCallbacks(scanLimit)
        if (start) warnIfNotScrubbable()
        send(commandForScan(key, start))
        if (start) handler.postDelayed(scanLimit, SCAN_LIMIT_MILLIS)
    }

    /**
     * A scan needs the app to have offered fast-forward/rewind. When it has not, the phone still
     * answers `200` and does nothing, which is a dead end worth naming rather than leaving the
     * driver to wonder.
     */
    private fun warnIfNotScrubbable() {
        val state = SinkModel.state
        val commands = state.mediaCommands ?: return
        if (state.senderScrubbing != false) return
        if (commands.contains("ff")) return
        SinkLog.w("$TAG: this app offers no fast-forward/rewind; expect it to ignore the scan ($commands)")
    }

    /**
     * Records a key that is not a skip key, so a hold that arrives as some other code can be named
     * instead of looking like a key that never reached the app. Only the first press of each key is
     * written: the wheel's repeats would otherwise fill the log.
     */
    private fun noteOtherKey(event: KeyEvent, source: String) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val key = event.keyCode
        // Media keys and the 3xx block BYD uses for its own wheel keys. Anything else (letters, the
        // volume keys, the keyboard) is not worth a line.
        if (key !in 85..91 && key !in 126..127 && key !in 280..360) return
        SinkLog.i("$TAG: ${name(key)} down — not a skip key, passing it on ($source)")
    }

    private fun name(key: Int): String = "key $key (${KeyEvent.keyCodeToString(key)})"

    private fun now(): Long = android.os.SystemClock.uptimeMillis()

    /** How long a scan may run before it is ended regardless, in case the release is lost. */
    const val SCAN_LIMIT_MILLIS = 20_000L
}
