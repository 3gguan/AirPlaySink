package com.shilapi.airplaysink.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small logger that keeps the most recent lines in memory for the on-screen log view, and appends
 * every line to a file so a problem that happened while nobody was watching can still be read
 * back afterwards.
 *
 * The file sink is optional: until [init] runs, logging behaves exactly like before. Writes go
 * through a single background thread, so a slow or missing disk never stalls the audio threads
 * that log. The file is size-capped — once it passes [MAX_FILE_BYTES] it is rotated to a `.old`
 * sibling and a fresh file starts, so disk usage stays at roughly two files at most.
 */
object SinkLog {

    private const val TAG = "AirPlaySink"
    private const val MAX_LINES = 400

    /** Rotate at 1 MiB; a few weeks of head-unit sessions fit comfortably under this. */
    private const val MAX_FILE_BYTES = 1L shl 20

    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Invoked on the thread that logged; the UI must hop to the main thread itself. */
    @Volatile
    var listener: ((String) -> Unit)? = null

    @Volatile
    private var logFile: File? = null
    private val fileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sink-log").apply { isDaemon = true }
    }

    /**
     * Points the file sink at [file]. Idempotent: later calls simply retarget the writer. Writes a
     * session marker first, so the file reads sensibly across process restarts.
     */
    fun init(file: File, androidVersion: String?) {
        logFile = file
        fileExecutor.execute {
            runCatching {
                rotateIfNeeded(file)
                val version = androidVersion ?: "android"
                file.appendText("---- process start (Android $version) ${stamp.format(Date())} ----\n")
            }
        }
    }

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

        val file = logFile ?: return
        fileExecutor.execute {
            runCatching {
                rotateIfNeeded(file)
                file.appendText(line + "\n")
            }
        }
    }

    /**
     * Renames an oversized file to `<name>.old`, replacing the previous `.old`. Opening and closing
     * per line is deliberate: log volume here is a few lines per second at most, and per-line
     * opens make rotation and process death harmless with no long-lived handle to leak.
     */
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val old = File(file.parentFile, file.name + ".old")
        old.delete()
        if (!file.renameTo(old)) file.delete()
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toMutableList() }

    /** Clears the in-memory view only; the file keeps its history on purpose. */
    fun clear() = synchronized(lines) { lines.clear() }
}
