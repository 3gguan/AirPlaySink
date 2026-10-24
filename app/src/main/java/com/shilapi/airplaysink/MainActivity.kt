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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.shilapi.airplaysink.ap2.Ap2Streams
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

        /**
         * How long a held skip button may keep scanning before the receiver stops it anyway.
         *
         * A scan has no natural end on the sender's side: it keeps winding until it is told to
         * stop. The release normally does that, but a lost `ACTION_UP` (a call arriving, the page
         * being covered) would leave the phone scrubbing to the end of the track, so there is a
         * ceiling that no interaction can lift. 20 s of scrubbing is already far more than anyone
         * does inside a car.
         */
        const val SEEK_MAX_HOLD_MS = 20_000L

        /** The four halves of a scan; sending one of these never interrupts a running scan. */
        val SCAN_COMMANDS = setOf(
            DacpClient.Command.SEEK_BACKWARD,
            DacpClient.Command.SEEK_FORWARD,
            DacpClient.Command.SEEK_BACKWARD_STOP,
            DacpClient.Command.SEEK_FORWARD_STOP,
        )
    }

    private lateinit var rootScroll: ScrollView
    private lateinit var serviceSwitch: SwitchCompat
    private lateinit var protocolSwitch: SwitchCompat
    private lateinit var ap2RemoteSwitch: SwitchCompat
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

    /** Fires the stop command if the finger never seems to come up; see [SEEK_MAX_HOLD_MS]. */
    private val seekHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The stop half of the scan that is currently running, or null when nothing is scanning.
     *
     * Holding it here is what lets release send the *matching* stop: a forward scan must be ended
     * with 9 and a backward one with 11, and the two are not interchangeable.
     */
    private var activeSeekStop: DacpClient.Command? = null
    private var activeSeekLabel = 0

    /** Label resource id -> value view, in the order the rows were added. */
    private val rows = LinkedHashMap<Int, TextView>()

    /** Set while the switch is being driven programmatically so the change is not echoed back. */
    private var suppressSwitchCallback = false
    private var logRefreshScheduled = false

    /**
     * True while the page is parked at the bottom, meaning new log lines should be chased. Updated
     * by the scroll listener only, never sampled at refresh time: once the user scrolls up the flag
     * stays down until they come back to the bottom themselves, so a fresh line cannot pull the
     * page out from under them.
     */
    private var followLogTail = true

    /** True between a finger going down and lifting, so a refresh never fights a running drag. */
    private var userDragging = false

    /** True once the user taps the name field, so the window-focus handler stops clearing it. */
    private var nameEditRequested = false

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
        protocolSwitch = findViewById(R.id.protocolSwitch)
        ap2RemoteSwitch = findViewById(R.id.ap2RemoteSwitch)
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

        // The on-screen log is the only thing that changes on its own, so it is also the only
        // thing that could move the page while the user is reading. Two guards: a finger on the
        // screen disables chasing outright, and the scroll position decides when chasing resumes.
        rootScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                // MOVE counts too: once the ScrollView takes over an intercepted drag the original
                // DOWN went to a child, and this listener only starts seeing the stream here.
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> userDragging = true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> userDragging = false
            }
            false
        }
        rootScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            followLogTail = isScrolledToBottom()
        }

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

        protocolSwitch.isChecked = preferences.airPlay2
        protocolSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            if (checked == preferences.airPlay2) return@setOnCheckedChangeListener
            preferences.airPlay2 = checked
            applyProtocol()
        }

        // Changing this only changes what the receiver does with a press, so it still needs a
        // restart — the switch is read once when the receiver starts.
        ap2RemoteSwitch.isChecked = preferences.ap2Remote
        ap2RemoteSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            if (checked == preferences.ap2Remote) return@setOnCheckedChangeListener
            preferences.ap2Remote = checked
            applyProtocol(R.string.ui_ap2_remote_applied)
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
        // A long press on either skip button scans instead of skipping. The pair is what a car
        // expects: tap changes track, hold scrubs.
        bindSeek(
            previousButton,
            DacpClient.Command.SEEK_BACKWARD,
            DacpClient.Command.SEEK_BACKWARD_STOP,
            R.string.ui_seek_back,
        )
        bindSeek(
            nextButton,
            DacpClient.Command.SEEK_FORWARD,
            DacpClient.Command.SEEK_FORWARD_STOP,
            R.string.ui_seek_forward,
        )

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
        // Resume chasing only if the page is still parked at the bottom where the user left it.
        followLogTail = isScrolledToBottom()
        renderLog()
        renderBatteryState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // As soon as the window takes focus the framework focuses the only text-input view on the
        // page, which would open the screen in edit mode with a blinking caret. Hand that focus
        // back unless the user is the one who asked for the field.
        if (!hasFocus || nameEditRequested) return
        if (deviceNameInput.hasFocus()) deviceNameInput.clearFocus()
    }

    /**
     * The steering wheel's skip keys arrive here while this page is in front — BYD gives them to the
     * focused window rather than to the media session, which is why they have to be caught twice
     * (here and in [SinkService]'s session callback).
     *
     * Returning true swallows the press so the head unit's own player never sees an unpaired key.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        WheelKeys.onKey(event, "window") || super.dispatchKeyEvent(event)

    /**
     * The name field is the only editable thing on the screen, so a tap anywhere else means "I am
     * done editing": drop the focus and the keyboard with it. Without this the field keeps focus
     * after the keyboard is dismissed and the caret goes on blinking over a page nobody is typing on.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ::deviceNameInput.isInitialized) {
            if (isInsideNameField(event.x, event.y)) {
                nameEditRequested = true
            } else if (deviceNameInput.hasFocus()) {
                nameEditRequested = false
                deviceNameInput.clearFocus()
                hideKeyboard()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Both coordinates live in the window's space, which is the space dispatchTouchEvent gets. */
    private fun isInsideNameField(x: Float, y: Float): Boolean {
        val origin = IntArray(2)
        deviceNameInput.getLocationInWindow(origin)
        return x >= origin[0] &&
            x <= origin[0] + deviceNameInput.width &&
            y >= origin[1] &&
            y <= origin[1] + deviceNameInput.height
    }

    private fun hideKeyboard() {
        val input = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        input.hideSoftInputFromWindow(deviceNameInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        // The user may have answered the exemption dialog in system settings; re-read it.
        renderBatteryState()
    }

    override fun onStop() {
        // A finger that was still down when the screen went away never sends ACTION_UP here, and a
        // scan outliving the page would leave the phone winding with nobody able to stop it.
        stopSeeking()
        WheelKeys.stop()
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
            R.string.ui_protocol,
            R.string.ui_client,
            R.string.ui_now_playing,
            R.string.ui_volume,
            R.string.ui_remote_control,
            // Only meaningful for the experiment, but always present: a row that appears and
            // disappears would move everything below it while the user is reading.
            R.string.ui_ap2_remote,
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
        ap2RemoteSwitch.isChecked = preferences.ap2Remote
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
        setRow(R.string.ui_protocol, describeProtocol(state))
        setRow(R.string.ui_client, state.client ?: getString(R.string.ui_empty))
        setRow(R.string.ui_now_playing, describeTrack(state))
        setRow(R.string.ui_volume, describeVolume(state))
        setRow(
            R.string.ui_remote_control,
            when {
                state.remoteControl -> getString(R.string.ui_remote_control_ready)
                // Under AirPlay 2 the DACP credentials are never sent, but the protocol has its own
                // channel for the same job — say which of the two is in play instead of "unavailable".
                state.protocol == SinkProtocol.AP2 && state.ap2RemoteEnabled ->
                    getString(R.string.ui_remote_control_ap2_channel)
                state.protocol == SinkProtocol.AP2 -> getString(R.string.ui_remote_control_ap2)
                else -> getString(R.string.ui_remote_control_pending)
            },
        )
        setRow(R.string.ui_ap2_remote, describeAp2Remote(state))

        // Left enabled while the receiver runs so a press can explain itself — a greyed button in
        // an AirPlay 2 session looks like a bug, which is exactly how it was reported.
        val running = state.running
        previousButton.isEnabled = running
        playPauseButton.isEnabled = running
        nextButton.isEnabled = running

        // The service reports the cushion it actually started with, so the label can say when the
        // slider has moved somewhere the running receiver has not caught up with yet.
        renderBufferLabel()
    }

    /**
     * What the connected sender is speaking, falling back to what the switch asks for.
     *
     * The two can differ: iOS keeps an `_airplay` record cached for up to 75 minutes, so a device
     * switched back to RAOP can still be opened over AirPlay 2 for a while. Showing the session is
     * the only way that difference is ever noticed.
     */
    private fun describeProtocol(state: SinkModel.State): String {
        val live = state.protocol
        val configured = if (preferences.airPlay2) SinkProtocol.AP2 else SinkProtocol.AP1
        val name = getString(
            when (live ?: configured) {
                SinkProtocol.AP2 -> R.string.ui_protocol_ap2
                SinkProtocol.AP1 -> R.string.ui_protocol_ap1
            },
        )
        return when {
            live != null -> getString(R.string.ui_protocol_active, name)
            state.running -> getString(R.string.ui_protocol_waiting, name)
            else -> getString(R.string.ui_protocol_idle, name)
        }
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

    /**
     * The AirPlay 2 remote control experiment's own status line.
     *
     * Three separate facts, and the row names whichever one is missing: whether the switch is on,
     * whether the phone opened the channel, and how the last press ended. "The button does nothing"
     * has three different causes here and only one of them is the phone refusing the command.
     */
    private fun describeAp2Remote(state: SinkModel.State): String {
        if (!state.ap2RemoteEnabled) return getString(R.string.ui_ap2_remote_off)
        val result = state.remoteCommandResult?.let {
            // A "sent" from a channel that has since gone away is history, not status: the next
            // press cannot go anywhere, and the row has to say that rather than keep the old luck.
            if (it == Ap2Streams.Delivery.SENT && !state.eventChannelOpen) {
                Ap2Streams.Delivery.NO_EVENT_CHANNEL
            } else {
                it
            }
        }
        if (result != null) {
            if (result == Ap2Streams.Delivery.SENT && state.ap2CommandReply != null) {
                // The phone answered. This is the only confirmation the protocol offers, and on a
                // head unit it is the difference between "the button worked" and "the row is stale".
                return getString(
                    R.string.ui_ap2_remote_accepted,
                    state.ap2CommandReply.removePrefix("RTSP/1.0 "),
                )
            }
            return getString(
                when (result) {
                    Ap2Streams.Delivery.SENT -> R.string.ui_ap2_remote_sent
                    Ap2Streams.Delivery.NO_EVENT_CHANNEL -> R.string.ui_ap2_remote_no_channel
                    Ap2Streams.Delivery.WRITE_FAILED -> R.string.ui_ap2_remote_write_failed
                    Ap2Streams.Delivery.NO_STREAM -> R.string.ui_ap2_remote_no_stream
                },
            )
        }
        return getString(
            if (state.eventChannelOpen) {
                R.string.ui_ap2_remote_ready
            } else {
                R.string.ui_ap2_remote_waiting
            },
        )
    }

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
        val text = if (tail.isEmpty()) {
            getString(R.string.ui_log_empty)
        } else {
            tail.joinToString("\n")
        }
        // Re-setting identical text would still cost a layout pass over the whole buffer, and each
        // of those passes is a chance for the page to shift. Only touch the view for a real change.
        if (text != logText.text?.toString()) logText.text = text
        // Only chase the tail when the user is parked at the bottom and not dragging the page.
        if (followLogTail && !userDragging) {
            rootScroll.post {
                if (followLogTail && !userDragging) rootScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        // Before the first layout there is no answer: "unknown" must not be read as "at bottom",
        // or the very first log line would drag the freshly opened page down to the log.
        if (rootScroll.height == 0) return false
        val content = rootScroll.getChildAt(0) ?: return true
        return rootScroll.scrollY + rootScroll.height >= content.bottom - dp(24)
    }

    private fun copyLogToClipboard() {
        val text = logText.text?.toString().orEmpty()
        if (text.isEmpty() || text == getString(R.string.ui_log_empty)) {
            toast(getString(R.string.ui_no_log_to_copy))
            return
        }
        copyToClipboard(getString(R.string.ui_log), text)
        toast(getString(R.string.ui_log_copied))
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    // ------------------------------------------------------------------ remote control

    /**
     * These buttons drive the iPhone: DACP on AirPlay 1, `sendMediaRemoteCommand` on AirPlay 2.
     *
     * Every press leaves a trace, both in the log and on the status card. A click that does nothing
     * at all is the one outcome this must never have — the head unit's toasts are easy to miss, and
     * without the trace there is nothing to tell "not sent" apart from "sent and ignored".
     *
     * [quiet] suppresses only the toast. The two halves of a scan go out this way — a toast when the
     * finger goes down and again when it comes up would be noise, since the status row already
     * carries the outcome.
     */
    private fun sendRemoteCommand(command: DacpClient.Command, labelRes: Int, quiet: Boolean = false) {
        // Pressing anything else while a scan is running means the user has moved on; end the scan
        // first, or the phone keeps winding behind whatever they asked for next.
        if (activeSeekStop != null && command !in SCAN_COMMANDS) stopSeeking()
        val state = SinkModel.state
        val label = getString(labelRes)
        SinkLog.i(
            "UI: \"$label\" pressed (running=${state.running}, protocol=${state.protocol}, " +
                "ap2Remote=${state.ap2RemoteEnabled}, eventChannel=${state.eventChannelOpen})",
        )
        if (state.protocol == SinkProtocol.AP2) {
            // The switch can be on while the running receiver still predates that change — it only
            // takes effect on restart. Saying so beats sending into the void.
            if (preferences.ap2Remote && !state.ap2RemoteEnabled) {
                if (!quiet) toast(getString(R.string.ui_ap2_remote_needs_restart))
                return
            }
            if (state.ap2RemoteEnabled) {
                // Immediate feedback, then the outcome replaces it: the send goes off the main
                // thread, and a silent half-second looks exactly like a dead button.
                setRow(R.string.ui_ap2_remote, getString(R.string.ui_ap2_remote_sending, label))
                SinkService.ap2Remote(command) { delivery -> reportAp2Remote(delivery, label, quiet) }
                return
            }
            offerAp2Remote()
            return
        }
        if (state.remoteControl && SinkService.remoteCommand(command)) {
            if (!quiet) toast(getString(R.string.ui_control_sent, label))
            return
        }
        if (!quiet) toast(getString(R.string.ui_control_unavailable))
    }

    /** Turns one delivery result into the sentence the user sees. */
    private fun reportAp2Remote(delivery: Ap2Streams.Delivery, label: String, quiet: Boolean = false) {
        if (quiet) {
            // A held seek reports through the status row only; one toast per step would bury the
            // screen. Failures still move the row, because a seek that silently stops working is
            // the thing the row exists to catch.
            if (delivery != Ap2Streams.Delivery.SENT) {
                setRow(R.string.ui_ap2_remote, deliverySentence(delivery, label))
            }
            return
        }
        toast(deliverySentence(delivery, label))
    }

    private fun deliverySentence(delivery: Ap2Streams.Delivery, label: String): String = when (delivery) {
        Ap2Streams.Delivery.SENT -> getString(R.string.ui_control_ap2_sent, label)
        Ap2Streams.Delivery.NO_EVENT_CHANNEL -> getString(R.string.ui_control_ap2_no_channel)
        Ap2Streams.Delivery.WRITE_FAILED -> getString(R.string.ui_control_ap2_write_failed)
        Ap2Streams.Delivery.NO_STREAM -> getString(R.string.ui_control_ap2_no_stream)
    }

    // ------------------------------------------------------------------ seeking

    /**
     * Makes a skip button scan when it is held down.
     *
     * A tap still skips: the long-click listener only fires after the platform's own hold timeout,
     * and returning `true` from it cancels the click. The touch listener is what notices the finger
     * coming up — `ACTION_UP` never reaches a long-click handler, so without it a scan would never
     * be told to stop.
     */
    private fun bindSeek(
        button: Button,
        start: DacpClient.Command,
        stop: DacpClient.Command,
        labelRes: Int,
    ) {
        button.setOnLongClickListener {
            startSeeking(start, stop, labelRes)
            true
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopSeeking()
            }
            // Never consume: consuming would starve the click and long-click handling above.
            false
        }
    }

    /**
     * Begins a scan that runs for as long as the button is held.
     *
     * One command starts it and one stops it, which is how the phone models scrubbing: 8 runs
     * forward until 9 arrives, 10 runs backward until 11. There is deliberately no repeat loop —
     * a scan is continuous, and re-sending "start" would only re-issue something already running.
     */
    private fun startSeeking(start: DacpClient.Command, stop: DacpClient.Command, labelRes: Int) {
        val state = SinkModel.state
        val label = getString(labelRes)
        if (state.protocol != SinkProtocol.AP2 || !state.ap2RemoteEnabled) {
            SinkLog.i(
                "UI: \"$label\" held, but scanning needs AirPlay 2 remote control " +
                    "(protocol=${state.protocol}, ap2Remote=${state.ap2RemoteEnabled})",
            )
            toast(getString(R.string.ui_seek_needs_ap2))
            return
        }
        // A second button pressed while the first is still scanning: end that one first, or its
        // stop command would be lost and the phone would keep winding in that direction.
        stopSeeking()
        activeSeekStop = stop
        activeSeekLabel = labelRes
        SinkLog.i("UI: \"$label\" held — scanning until released")
        if (state.senderScrubbing == false) {
            // The app published a command list without scrubbing in it. The phone will still answer
            // 200 and do nothing, which is exactly the dead end this line exists to name.
            SinkLog.w(
                "UI: \"$label\" — this app does not offer scrubbing; expect the phone to ignore it " +
                    "(${state.mediaCommands})",
            )
        }
        sendRemoteCommand(start, labelRes, quiet = true)
        seekHandler.postDelayed({ stopSeeking(forced = true) }, SEEK_MAX_HOLD_MS)
    }

    /** Ends the running scan, if any. Safe to call when nothing is scanning. */
    private fun stopSeeking(forced: Boolean = false) {
        val stop = activeSeekStop ?: return
        val labelRes = activeSeekLabel
        activeSeekStop = null
        seekHandler.removeCallbacksAndMessages(null)
        SinkLog.i(
            if (forced) {
                "UI: \"${getString(labelRes)}\" still held after ${SEEK_MAX_HOLD_MS / 1000}s — " +
                    "stopping the scan anyway"
            } else {
                "UI: \"${getString(labelRes)}\" released — stopping the scan"
            },
        )
        sendRemoteCommand(stop, labelRes, quiet = true)
    }

    /**
     * The AP2 remote control switch is off while the receiver is speaking AirPlay 2.
     *
     * This generation has no other channel for a press to travel on, so the dialog offers the switch
     * first — turning it on is far less disruptive than dropping back to AirPlay 1 — and keeps the
     * protocol fallback as the third choice.
     */
    private fun offerAp2Remote() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_control_ap2_title)
            .setMessage(R.string.ui_control_ap2_message)
            .setPositiveButton(R.string.ui_control_ap2_enable) { _, _ -> enableAp2Remote() }
            .setNeutralButton(R.string.ui_control_ap2_switch) { _, _ -> switchToAirPlayOne() }
            .setNegativeButton(R.string.ui_control_ap2_keep, null)
            .show()
    }

    private fun enableAp2Remote() {
        preferences.ap2Remote = true
        suppressSwitchCallback = true
        ap2RemoteSwitch.isChecked = true
        suppressSwitchCallback = false
        SinkLog.i("Remote control needs the AP2 remote channel; turning the switch on request")
        applyProtocol(R.string.ui_ap2_remote_applied)
    }

    private fun switchToAirPlayOne() {
        preferences.airPlay2 = false
        suppressSwitchCallback = true
        protocolSwitch.isChecked = false
        suppressSwitchCallback = false
        SinkLog.i("Remote control needs AirPlay 1; switching the protocol off on request")
        applyProtocol(R.string.ui_control_ap2_switched)
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

    // ------------------------------------------------------------------ protocol

    /**
     * Which protocol is served decides which records are published, and the advertisement is fixed
     * when the receiver starts — so the switch bounces the service, exactly like the name and the
     * cushion. Turning AirPlay 2 off also makes the restart send a goodbye for the old record; that
     * is what stops the phone from pairing against a receiver that no longer answers.
     */
    private fun applyProtocol(messageRes: Int = R.string.ui_airplay2_applied) {
        if (!SinkService.isActive) {
            setRow(R.string.ui_protocol, describeProtocol(SinkModel.state))
            return
        }
        toast(getString(messageRes))
        SinkService.stop(this)
        rootScroll.postDelayed({ SinkService.start(this) }, RESTART_DELAY_MS)
    }

    // ------------------------------------------------------------------ device name

    private fun applyDeviceName() {
        preferences.deviceName = deviceNameInput.text.toString()
        deviceNameInput.setText(preferences.deviceName)
        deviceNameInput.clearFocus()
        nameEditRequested = false
        hideKeyboard()

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

        // Some head units register a placeholder for this action instead of a settings screen.
        // BYD DiLink 3.0 (Android 10) answers with com.byd.systemsettings/.unsupport.UnsupportActivity,
        // which flashes "多媒体系统不支持该操作" and closes — launching it is worse than doing nothing,
        // because from the driver's seat it looks like the app broke. A resolver hit that is only
        // such a stub is therefore refused and the adb command goes to the clipboard instead.
        // A null resolver is NOT treated as a stub: package visibility on Android 11+ can hide
        // the settings app from us even when the dialog would work, so that case still tries.
        val handler = packageManager.resolveActivity(intent, 0)?.activityInfo?.name
        if (handler != null && handler.contains("unsupport", ignoreCase = true)) {
            SinkLog.w("Battery exemption dialog is a stub ($handler); copy adb command instead")
            copyBatteryHint()
            return
        }

        runCatching { startActivity(intent) }.onFailure {
            SinkLog.w("Battery exemption dialog unavailable: ${it.message}")
            copyBatteryHint()
        }
    }

    /** The adb escape hatch, on the clipboard so it survives the trip to a terminal. */
    private fun copyBatteryHint() {
        copyToClipboard(getString(R.string.ui_battery), getString(R.string.ui_battery_adb_commands))
        toast(getString(R.string.ui_battery_adb_copied))
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
