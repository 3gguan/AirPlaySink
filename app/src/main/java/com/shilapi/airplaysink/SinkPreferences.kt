package com.shilapi.airplaysink

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

/**
 * Everything the receiver advertises is configurable because different head units and iOS versions
 * are pickier about different fields.
 */
class SinkPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(value) {
            val trimmed = value.trim().ifEmpty { DEFAULT_DEVICE_NAME }
            prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var serverVersion: String
        get() = prefs.getString(KEY_SERVER_VERSION, DEFAULT_SERVER_VERSION) ?: DEFAULT_SERVER_VERSION
        set(value) = prefs.edit().putString(KEY_SERVER_VERSION, value.trim()).apply()

    var statusFlags: Int
        get() = prefs.getInt(KEY_STATUS_FLAGS, DEFAULT_STATUS_FLAGS)
        set(value) = prefs.edit().putInt(KEY_STATUS_FLAGS, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    /**
     * How much audio to hold in front of the speaker, in milliseconds.
     *
     * The receiver's latency and its tolerance for a stalling link are the same number, so this is
     * the knob for "it still hiccups sometimes" — at the price of the speaker falling further
     * behind the phone. Read when a session is set up, so a change lands on the next one.
     */
    var bufferMs: Int
        get() = prefs.getInt(KEY_BUFFER_MS, DEFAULT_BUFFER_MS)
            .coerceIn(BUFFER_CHOICES.first(), BUFFER_CHOICES.last())
        set(value) = prefs.edit()
            .putInt(KEY_BUFFER_MS, value.coerceIn(BUFFER_CHOICES.first(), BUFFER_CHOICES.last()))
            .apply()

    /**
     * A stable 12 hex digit identifier. iOS only uses it to tell devices apart, so a random value
     * generated once on first run is enough - and it avoids depending on the Wi-Fi MAC address,
     * which Android hides or randomises.
     */
    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null && existing.length == 12) return existing
            val bytes = ByteArray(6)
            SecureRandom().nextBytes(bytes)
            val generated = buildString {
                for (byte in bytes) append(String.format(Locale.US, "%02X", byte))
            }
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    /** The mDNS instance name classic RAOP expects: `<MAC>@<Display Name>`. */
    val instanceName: String get() = "$deviceId@$deviceName"

    companion object {
        private const val FILE = "airplay_sink"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MODEL = "model"
        private const val KEY_SERVER_VERSION = "server_version"
        private const val KEY_STATUS_FLAGS = "status_flags"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_BUFFER_MS = "buffer_ms"

        /**
         * The buffer sizes the main screen offers, in milliseconds.
         *
         * Coarse on purpose: the difference between 250 and 300 ms is not something anyone can
         * hear or tune by ear, while the difference between 250 and 2000 is the difference between
         * a link that drops out and one that does not.
         */
        val BUFFER_CHOICES = intArrayOf(250, 400, 600, 800, 1000, 1500, 2000)

        /** What an AirPort Express reports, and what the pipeline was tuned against. */
        const val DEFAULT_BUFFER_MS = 250

        /**
         * `AirPort4,107` is the AirPort Express audio model. It is the safest thing to claim for an
         * audio-only sink: iOS then expects exactly the feature set implemented here.
         */
        const val DEFAULT_MODEL = "AirPort4,107"
        const val DEFAULT_SERVER_VERSION = "130.14"
        const val DEFAULT_DEVICE_NAME = "BYD AirPlay"

        /** `sf` bit 2 means "audio cable is attached", the value an audio-only AirPort reports. */
        const val DEFAULT_STATUS_FLAGS = 0x4
    }
}
