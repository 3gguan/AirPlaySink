package com.shilapi.airplaysink

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.shilapi.airplaysink.dacp.DacpClient
import com.shilapi.airplaysink.util.SinkLog
import java.util.Locale
import kotlin.math.pow

/**
 * The only screen: one switch that runs the receiver, a status card, the advertised name and a live
 * log so the head unit can be diagnosed without adb.
 */
class MainActivity : AppCompatActivity(), SinkModel.Listener {

    private companion object {
        /** The on-screen log is capped so the page does not grow without bound. */
        const val VISIBLE_LOG_LINES = 200
        const val RESTART_DELAY_MS = 500L
    }

    private lateinit var rootScroll: ScrollView
    private lateinit var serviceSwitch: SwitchCompat
    private lateinit var bootSwitch: SwitchCompat
    private lateinit var batteryState: TextView
    private lateinit var batteryApplyButton: Button
    private lateinit var deviceNameInput: EditText
    private lateinit var bufferSeek: SeekBar
    private lateinit var bufferValue: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var logText: TextView
    private lateinit var previousButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var nextButton: Button

    private lateinit var preferences: SinkPreferences

    /** Label resource id -> value view, in the order the rows were added. */
    private val rows = LinkedHashMap<Int, TextView>()

    /** Set while the switch is being driven programmatically so the change is not echoed back. */
    private var suppressSwitchCallback = false
    private var logRefreshScheduled = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.ui_notification_permission, Toast.LENGTH_LONG).show()
            }
        }

    private val logListener: (String) -> Unit = { scheduleLogRefresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferences = SinkPreferences(this)

        rootScroll = findViewById(R.id.rootScroll)
        serviceSwitch = findViewById(R.id.serviceSwitch)
        bootSwitch = findViewById(R.id.bootSwitch)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        bufferSeek = findViewById(R.id.bufferSeek)
        bufferValue = findViewById(R.id.bufferValue)
        statusCard = findViewById(R.id.statusCard)
        logText = findViewById(R.id.logText)
        previousButton = findViewById(R.id.previousButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        nextButton = findViewById(R.id.nextButton)

        buildStatusRows()

        serviceSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            if (checked) {
                requestNotificationPermissionIfNeeded()
                SinkService.start(this)
            } else {
                SinkService.stop(this)
            }
        }

        bootSwitch.isChecked = preferences.startOnBoot
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.startOnBoot = checked
        }

        batteryState = findViewById(R.id.batteryState)
        batteryApplyButton = findViewById(R.id.batteryApplyButton)
        batteryApplyButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        bufferSeek.max = SinkPreferences.BUFFER_CHOICES.size - 1
        bufferSeek.progress = bufferIndex(preferences.bufferMs)
        renderBufferLabel()
        bufferSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Only preview while dragging; the value is written on release so a drag across
                // seven steps is one write, not seven.
                if (fromUser) renderBufferLabel(SinkPreferences.BUFFER_CHOICES[progress])
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                preferences.bufferMs = SinkPreferences.BUFFER_CHOICES[bufferSeek.progress]
                renderBufferLabel()
            }
        })
        findViewById<View>(R.id.applyBufferButton).setOnClickListener { applyBuffer() }

        deviceNameInput.setText(preferences.deviceName)
        findViewById<View>(R.id.applyNameButton).setOnClickListener { applyDeviceName() }
        findViewById<View>(R.id.copyLogButton).setOnClickListener { copyLogToClipboard() }
        findViewById<View>(R.id.clearLogButton).setOnClickListener {
            SinkLog.clear()
            renderLog()
        }

        previousButton.setOnClickListener {
            sendRemoteCommand(DacpClient.Command.PREVIOUS, R.string.ui_control_previous)
        }
        playPauseButton.setOnClickListener {
            sendRemoteCommand(DacpClient.Command.PLAY_PAUSE, R.string.ui_control_play_pause)
        }
        nextButton.setOnClickListener {
            sendRemoteCommand(DacpClient.Command.NEXT, R.string.ui_control_next)
        }

        // The switch remembers its last position across process restarts, so bring the receiver back
        // up if it was on but the process died in the meantime.
        if (preferences.serviceEnabled && !SinkService.isActive) {
            SinkLog.i("Receiver was left enabled; starting it again")
            SinkService.start(this)
        }
    }

    override fun onStart() {
        super.onStart()
        SinkLog.listener = logListener
        SinkModel.addListener(this)
        renderLog()
        renderBatteryState()
    }

    override fun onResume() {
        super.onResume()
        // The user may have answered the exemption dialog in system settings; re-read it.
        renderBatteryState()
    }

    override fun onStop() {
        SinkModel.removeListener(this)
        SinkLog.listener = null
        super.onStop()
    }

    // ------------------------------------------------------------------ status card

    private fun buildStatusRows() {
        for (label in intArrayOf(
            R.string.ui_status,
            R.string.ui_address,
            R.string.ui_advertised_name,
            R.string.ui_port,
            R.string.ui_client,
            R.string.ui_now_playing,
            R.string.ui_volume,
            R.string.ui_remote_control,
        )) {
            rows[label] = addRow(label)
        }
    }

    private fun addRow(labelRes: Int): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val label = TextView(this).apply {
            setText(labelRes)
            setTextColor(color(R.color.sink_text_dim))
            textSize = 13f
        }
        val value = TextView(this).apply {
            setText(R.string.ui_empty)
            setTextColor(color(R.color.sink_text))
            textSize = 13f
            gravity = Gravity.END
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        row.addView(value)
        statusCard.addView(row)
        return value
    }

    private fun setRow(labelRes: Int, text: String) {
        rows[labelRes]?.let { it.text = text }
    }

    // ------------------------------------------------------------------ rendering

    override fun onStateChanged(state: SinkModel.State) {
        runOnUiThread { render(state) }
    }

    private fun render(state: SinkModel.State) {
        if (isFinishing || isDestroyed) return

        suppressSwitchCallback = true
        serviceSwitch.isChecked = state.running || state.starting || state.waitingForNetwork
        serviceSwitch.isEnabled = !state.starting
        suppressSwitchCallback = false

        setRow(
            R.string.ui_status,
            when {
                state.starting -> getString(R.string.ui_state_starting)
                state.waitingForNetwork -> getString(R.string.ui_state_waiting_network)
                state.streaming -> getString(R.string.ui_state_streaming)
                state.client != null -> getString(R.string.ui_state_client)
                state.running -> getString(R.string.ui_state_running)
                else -> getString(R.string.ui_state_stopped)
            },
        )
        setRow(R.string.ui_address, state.address ?: getString(R.string.ui_empty))
        setRow(R.string.ui_advertised_name, state.adverisedName ?: getString(R.string.ui_empty))
        setRow(
            R.string.ui_port,
            if (state.port > 0) state.port.toString() else getString(R.string.ui_empty),
        )
        setRow(R.string.ui_client, state.client ?: getString(R.string.ui_empty))
        setRow(R.string.ui_now_playing, describeTrack(state))
        setRow(R.string.ui_volume, describeVolume(state))
        setRow(
            R.string.ui_remote_control,
            if (state.remoteControl) {
                getString(R.string.ui_remote_control_ready)
            } else {
                getString(R.string.ui_remote_control_pending)
            },
        )

        // The buttons only work once the iPhone has published its DACP endpoint.
        val remoteReady = state.running && state.remoteControl
        previousButton.isEnabled = remoteReady
        playPauseButton.isEnabled = remoteReady
        nextButton.isEnabled = remoteReady

        // The service reports the cushion it actually started with, so the label can say when the
        // slider has moved somewhere the running receiver has not caught up with yet.
        renderBufferLabel()
    }

    private fun describeTrack(state: SinkModel.State): String {
        val title = state.title
        val artist = state.artist
        val album = state.album
        if (title == null && artist == null && album == null) return getString(R.string.ui_empty)
        val head = listOfNotNull(artist, title).joinToString(" - ").ifEmpty { album.orEmpty() }
        val parts = mutableListOf(head)
        if (artist != null && title != null && album != null) parts += album
        if (state.progressTotalSeconds > 0) {
            parts += "${clock(state.progressCurrentSeconds)} / ${clock(state.progressTotalSeconds)}"
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun describeVolume(state: SinkModel.State): String {
        val linear = if (state.volumeDb <= -144.0) 0.0 else 10.0.pow(state.volumeDb / 20.0)
        return String.format(
            Locale.US,
            "%.1f dB (%.0f%%)",
            state.volumeDb,
            linear.coerceIn(0.0, 1.0) * 100.0,
        )
    }

    private fun clock(seconds: Int): String =
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    // ------------------------------------------------------------------ log view

    private fun scheduleLogRefresh() {
        if (logRefreshScheduled) return
        logRefreshScheduled = true
        logText.post {
            logRefreshScheduled = false
            if (isFinishing || isDestroyed) return@post
            renderLog()
        }
    }

    private fun renderLog() {
        val all = SinkLog.snapshot()
        val tail = if (all.size > VISIBLE_LOG_LINES) {
            all.subList(all.size - VISIBLE_LOG_LINES, all.size)
        } else {
            all
        }
        val followTail = isScrolledToBottom()
        logText.text = if (tail.isEmpty()) {
            getString(R.string.ui_log_empty)
        } else {
            tail.joinToString("\n")
        }
        // Only chase the tail when the user has not scrolled up to read something.
        if (followTail) rootScroll.post { rootScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun isScrolledToBottom(): Boolean {
        val content = rootScroll.getChildAt(0) ?: return true
        return rootScroll.scrollY + rootScroll.height >= content.bottom - dp(24)
    }

    private fun copyLogToClipboard() {
        val text = logText.text?.toString().orEmpty()
        if (text.isEmpty() || text == getString(R.string.ui_log_empty)) {
            toast(getString(R.string.ui_no_log_to_copy))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_log), text))
        toast(getString(R.string.ui_log_copied))
    }

    // ------------------------------------------------------------------ remote control

    /** These buttons drive the iPhone over DACP — the same channel the steering wheel uses. */
    private fun sendRemoteCommand(command: DacpClient.Command, labelRes: Int) {
        if (SinkService.remoteCommand(command)) {
            toast(getString(R.string.ui_control_sent, getString(labelRes)))
        } else {
            toast(getString(R.string.ui_control_unavailable))
        }
    }

    // ------------------------------------------------------------------ buffer depth

    /**
     * The cushion is chosen before a session is set up, so a change only lands on the next one.
     * Bouncing the service is the shortest honest way to get there: it is the same thing that
     * happens when the phone re-selects this device, without making the user go and do it.
     */
    private fun applyBuffer() {
        preferences.bufferMs = SinkPreferences.BUFFER_CHOICES[bufferSeek.progress]
        if (!SinkService.isActive) {
            toast(getString(R.string.ui_buffer_saved))
            renderBufferLabel()
            return
        }
        toast(getString(R.string.ui_buffer_applied, preferences.bufferMs))
        SinkService.stop(this)
        rootScroll.postDelayed({ SinkService.start(this) }, RESTART_DELAY_MS)
    }

    private fun renderBufferLabel(chosen: Int = preferences.bufferMs) {
        val live = SinkModel.state.bufferMs
        bufferValue.text = if (SinkService.isActive && live != chosen) {
            getString(R.string.ui_buffer_value_pending, chosen)
        } else {
            getString(R.string.ui_buffer_value, chosen)
        }
    }

    /** The stored value is one of the steps, but a value written by an older build may not be. */
    private fun bufferIndex(ms: Int): Int {
        val choices = SinkPreferences.BUFFER_CHOICES
        val exact = choices.indexOf(ms)
        if (exact >= 0) return exact
        return choices.indices.minByOrNull { kotlin.math.abs(choices[it] - ms) } ?: 0
    }

    // ------------------------------------------------------------------ device name

    private fun applyDeviceName() {
        preferences.deviceName = deviceNameInput.text.toString()
        deviceNameInput.setText(preferences.deviceName)
        deviceNameInput.clearFocus()

        if (!SinkService.isActive) {
            toast(getString(R.string.ui_name_saved))
            return
        }
        // The advertised name is fixed when the receiver starts, so bounce the service.
        toast(getString(R.string.ui_name_applied))
        SinkService.stop(this)
        rootScroll.postDelayed({ SinkService.start(this) }, RESTART_DELAY_MS)
    }

    // ------------------------------------------------------------------ battery optimization

    /**
     * Some head unit power managers (DiLink among them) put Doze or background freezes on apps
     * that are merely "not important", which can stall the hotspot link with the screen off. An
     * exemption is the standard, user-visible way out; ROMs that removed the dialog fall back to
     * the adb command shown in the hint.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun renderBatteryState() {
        val exempt = isIgnoringBatteryOptimizations()
        batteryState.setText(
            if (exempt) R.string.ui_battery_state_exempt else R.string.ui_battery_state_not_exempt,
        )
        batteryApplyButton.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure {
                toast(getString(R.string.ui_battery_request_failed))
                SinkLog.w("Battery exemption dialog unavailable: ${it.message}")
            }
    }

    // ------------------------------------------------------------------ plumbing

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
}
