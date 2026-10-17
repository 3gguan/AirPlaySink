package com.shilapi.airplaysink

import java.util.concurrent.CopyOnWriteArrayList

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
        val streaming: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val volumeDb: Double = 0.0,
        val progressCurrentSeconds: Int = 0,
        val progressTotalSeconds: Int = 0,
        /** True once the sender has handed over a usable DACP reverse control endpoint. */
        val remoteControl: Boolean = false,
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
