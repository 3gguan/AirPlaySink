package com.shilapi.airplaysink.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small logger that also keeps the most recent lines in memory so the on-screen log view can show
 * what the receiver is doing without needing a cable and `adb logcat`.
 */
object SinkLog {

    private const val TAG = "AirPlaySink"
    private const val MAX_LINES = 400

    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Invoked on the thread that logged; the UI must hop to the main thread itself. */
    @Volatile
    var listener: ((String) -> Unit)? = null

    fun i(message: String) {
        Log.i(TAG, message)
        record("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        record("W", message)
    }

    fun e(message: String, error: Throwable? = null) {
        val text = if (error == null) message else "$message: ${error.javaClass.simpleName} ${error.message}"
        Log.e(TAG, text, error)
        record("E", text)
    }

    private fun record(level: String, message: String) {
        val line = "${stamp.format(Date())} $level  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        // A misbehaving UI listener must never break the audio thread that is logging.
        runCatching { listener?.invoke(line) }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toMutableList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
