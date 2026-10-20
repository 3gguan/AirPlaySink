package com.shilapi.airplaysink

import com.shilapi.airplaysink.ap2.Ap2Streams
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which AirPlay generation a session actually negotiated.
 *
 * Not the same thing as the configured one: a sender that still has an `_airplay` record cached
 * will speak AirPlay 2 to a receiver set to RAOP, and the status card has to show what happened
 * rather than what was asked for.
 */
enum class SinkProtocol { AP1, AP2 }

/**
 * The one piece of shared state the UI renders. The service writes, the activity reads.
 */
object SinkModel {

    data class State(
        val running: Boolean = false,
        val starting: Boolean = false,
        /** The service is up but has no IPv4 address yet, so nothing is being advertised. */
        val waitingForNetwork: Boolean = false,
        val address: String? = null,
        val adverisedName: String? = null,
        val port: Int = 0,
        val client: String? = null,
        /** The generation the connected sender is speaking; null while nobody is connected. */
        val protocol: SinkProtocol? = null,
        val streaming: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val volumeDb: Double = 0.0,
        val progressCurrentSeconds: Int = 0,
        val progressTotalSeconds: Int = 0,
        /** True once the sender has handed over a usable DACP reverse control endpoint. */
        val remoteControl: Boolean = false,
        /**
         * The running receiver was started with the AirPlay 2 remote control switch on.
         *
         * Read from the service, not from the preference: the switch changes the preference first
         * and the running receiver only after the restart.
         */
        val ap2RemoteEnabled: Boolean = false,
        /** The sender opened the encrypted event connection that `POST /command` travels on. */
        val eventChannelOpen: Boolean = false,
        /** How the last remote command the UI sent ended; null until one has been sent. */
        val remoteCommandResult: Ap2Streams.Delivery? = null,
        /**
         * The phone's status line for that command, e.g. `RTSP/1.0 200`.
         *
         * Cleared on every new press, so a stale 200 can never make a dead command look accepted.
         */
        val ap2CommandReply: String? = null,
        /** The cushion the receiver was started with, in milliseconds. */
        val bufferMs: Int = 250,
        val lastError: String? = null,
        val lastStats: String? = null,
    )

    fun interface Listener {
        fun onStateChanged(state: State)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()

    @Volatile
    var state: State = State()
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Applies a change and notifies listeners on the calling thread. */
    fun update(transform: (State) -> State) {
        val next: State
        synchronized(lock) {
            next = transform(state)
            if (next == state) return
            state = next
        }
        for (listener in listeners) {
            runCatching { listener.onStateChanged(next) }
        }
    }

    fun reset() {
        update { State() }
    }

    fun fail(message: String) {
        update { it.copy(lastError = message) }
    }
}
