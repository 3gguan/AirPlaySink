package com.shilapi.airplaysink.audio

import com.shilapi.airplaysink.crypto.RaopCrypto
import com.shilapi.airplaysink.util.Codec
import com.shilapi.airplaysink.util.SinkLog
import android.os.SystemClock
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Receives the RAOP audio stream.
 *
 * Wire format, taken from shairport-sync's `rtp.c`/`player.c`:
 *
 *  * 12 byte RTP header. Byte 1 with the marker bit masked off is the packet type: 0x60 regular
 *    audio, 0x56 a retransmission (which carries a 4 byte prefix), 0x54 a timing/sync packet sent
 *    to the *timing* port rather than the audio port.
 *  * The 16 bit sequence number and 32 bit RTP timestamp follow in the header.
 *  * The payload is AES-128-CBC encrypted with the session IV from ANNOUNCE. Only whole 16 byte
 *    blocks are encrypted; the trailing remainder is passed through unchanged.
 *  * A missing sequence is re-requested with an 8 byte "resend" datagram sent to the sender's
 *    control port.
 */
class RaopAudioTransport(
    private val audioSocket: DatagramSocket,
    private val controlSocket: DatagramSocket,
    private val timingSocket: DatagramSocket,
    private val senderControl: InetSocketAddress,
    private val pipeline: AudioPipeline,
    private val sessionKey: ByteArray?,
    private val sessionIv: ByteArray?,
) : Closeable {

    private companion object {
        const val TYPE_AUDIO = 0x60
        const val TYPE_RESEND_REPLY = 0x56
        const val TYPE_SYNC = 0x54
        const val MAX_DATAGRAM = 4096
        const val MAX_RESEND_SPAN = 128
        const val RESEND_BACKOFF_MS = 250L
        const val SOCKET_TIMEOUT_MS = 400

        /**
         * How long a session may stay silent after RECORD before we say so out loud. A phone that
         * accepted SETUP and then streams nothing is indistinguishable, in the rest of the log,
         * from a phone that never tried — and those two need completely different fixes.
         */
        const val SILENT_WARN_MS = 4000L
    }

    /** Audio packets carry the RTP header inline; resent packets have an extra 4 byte prefix. */
    private class Layout(val payloadOffset: Int, val sequenceOffset: Int, val timestampOffset: Int)

    private val liveLayout = Layout(payloadOffset = 12, sequenceOffset = 2, timestampOffset = 4)
    private val resendLayout = Layout(payloadOffset = 16, sequenceOffset = 6, timestampOffset = 8)

    @Volatile
    private var running = false

    private val decryptBuffer = ByteArray(MAX_DATAGRAM)
    private var audioThread: Thread? = null
    private var controlThread: Thread? = null
    private var watchdogThread: Thread? = null

    @Volatile
    private var startedAt = 0L

    private var expectedSequence = -1
    private var lastResendAt = 0L

    // Sequence numbers are 16 bit; they are unwrapped into a monotonic counter for the pipeline.
    private var highestRaw = -1
    private var highestAbsolute = 0L

    private var audioPackets = 0L
    private var resendReplies = 0L
    private var resendRequests = 0L
    private var syncPackets = 0L

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        SinkLog.i(
            "Audio transport on data :${audioSocket.localPort}, control :${controlSocket.localPort}, " +
                "timing :${timingSocket.localPort}; sender control $senderControl; " +
                "encryption ${if (sessionKey != null && sessionIv != null) "on" else "off"}",
        )
        audioThread = Thread({ audioLoop() }, "raop-audio-rx").also { it.start() }
        controlThread = Thread({ controlLoop() }, "raop-rtcp-rx").also { it.start() }
        watchdogThread = Thread({ watchdog() }, "raop-silence-watchdog").also { it.start() }
    }

    /**
     * Shouts once when a fully negotiated session stays silent. Everything else in the log looks
     * identical whether the sender is streaming or not, so without this the only clue is the
     * absence of a message.
     */
    private fun watchdog() {
        while (running) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            if (audioPackets + resendReplies > 0) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < SILENT_WARN_MS) continue
            SinkLog.e(
                "RECORD was acknowledged but no audio has arrived after ${elapsed} ms. The sender " +
                    "is not streaming to this output — check that it is actually playing, and " +
                    "that it reaches us on ${senderControl.address?.hostAddress}.",
            )
            return
        }
    }

    override fun close() {
        running = false
        runCatching { audioSocket.close() }
        runCatching { controlSocket.close() }
        runCatching { timingSocket.close() }
        runCatching { audioThread?.join(500) }
        runCatching { controlThread?.join(500) }
        watchdogThread?.interrupt()
        runCatching { watchdogThread?.join(500) }
        audioThread = null
        controlThread = null
        watchdogThread = null
        SinkLog.i(
            "Audio transport closed: $audioPackets audio packets, $resendReplies retransmissions, " +
                "$resendRequests resend requests, $syncPackets sync packets",
        )
    }

    private fun audioLoop() {
        val datagram = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        while (running) {
            try {
                audioSocket.receive(datagram)
            } catch (error: Exception) {
                if (running) SinkLog.w("Audio socket receive failed: ${error.message}")
                return
            }
            val type = if (datagram.length >= 2) datagram.data[1].toInt() and 0x7F else -1
            when (type) {
                TYPE_AUDIO -> handleAudio(datagram, liveLayout, isResend = false)
                TYPE_RESEND_REPLY -> handleAudio(datagram, resendLayout, isResend = true)
                else -> SinkLog.w(
                    "Unexpected RTP type 0x${Integer.toHexString(type)} on the audio port (${datagram.length}B)",
                )
            }
        }
    }

    private fun handleAudio(datagram: DatagramPacket, layout: Layout, isResend: Boolean) {
        val data = datagram.data
        val length = datagram.length
        if (length < layout.payloadOffset + 16) {
            SinkLog.w("Ignoring short audio packet: ${length}B")
            return
        }

        val rawSequence = Codec.be16(data, layout.sequenceOffset)
        val rtpTime = Codec.be32(data, layout.timestampOffset)
        val sequence = unwrapSequence(rawSequence)
        val payloadLength = length - layout.payloadOffset

        val key = sessionKey
        val iv = sessionIv
        val payload: ByteArray
        if (key != null && iv != null) {
            RaopCrypto.decryptAudioPayload(data, layout.payloadOffset, payloadLength, key, iv, decryptBuffer)
            payload = ByteArray(payloadLength)
            System.arraycopy(decryptBuffer, 0, payload, 0, payloadLength)
        } else {
            payload = ByteArray(payloadLength)
            System.arraycopy(data, layout.payloadOffset, payload, 0, payloadLength)
        }

        if (isResend) resendReplies++ else audioPackets++
        val total = audioPackets + resendReplies
        if (total <= 8) {
            SinkLog.i(
                "Audio ${if (isResend) "retransmission" else "packet"} seq=$rawSequence rtpTime=$rtpTime " +
                    "${payloadLength}B encrypted=${key != null} " +
                    "from ${datagram.address?.hostAddress}:${datagram.port}",
            )
        }
        pipeline.submit(sequence, payload)
    }

    /** Turns the 16 bit RTP sequence into a monotonic counter rooted at the first packet seen. */
    private fun unwrapSequence(raw: Int): Long {
        if (highestRaw < 0) {
            highestRaw = raw
            highestAbsolute = 0L
        } else {
            val forward = (raw - highestRaw) and 0xFFFF
            if (forward in 1..0x7FFF) {
                highestRaw = raw
                highestAbsolute += forward.toLong()
            } else if (forward > 0x7FFF) {
                return highestAbsolute - (((highestRaw - raw) and 0xFFFF).toLong())
            }
        }

        if (expectedSequence >= 0) {
            val gap = (raw - expectedSequence) and 0xFFFF
            if (gap in 1..MAX_RESEND_SPAN) {
                requestResend(expectedSequence, gap)
                expectedSequence = (raw + 1) and 0xFFFF
            } else if (gap > MAX_RESEND_SPAN) {
                // A real discontinuity, or a very old packet: resynchronise without asking.
                expectedSequence = (raw + 1) and 0xFFFF
            }
        } else {
            expectedSequence = (raw + 1) and 0xFFFF
        }
        return highestAbsolute
    }

    private fun requestResend(first: Int, count: Int) {
        val now = System.currentTimeMillis()
        if (now - lastResendAt < RESEND_BACKOFF_MS) return
        lastResendAt = now

        val request = ByteArray(8)
        request[0] = 0x80.toByte()
        request[1] = 0xD5.toByte() // Apple "resend"
        Codec.putBe16(request, 2, 1)
        Codec.putBe16(request, 4, first)
        Codec.putBe16(request, 6, count)
        resendRequests++
        runCatching {
            controlSocket.send(DatagramPacket(request, request.size, senderControl))
            if (resendRequests <= 3) SinkLog.i("Requesting resend of $count packet(s) from seq $first")
        }.onFailure { SinkLog.w("Resend request failed: ${it.message}") }
    }

    private fun controlLoop() {
        val datagram = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        controlSocket.soTimeout = SOCKET_TIMEOUT_MS
        timingSocket.soTimeout = SOCKET_TIMEOUT_MS
        while (running) {
            readControl(controlSocket, datagram, "control")
            readControl(timingSocket, datagram, "timing")
        }
    }

    private fun readControl(socket: DatagramSocket, datagram: DatagramPacket, name: String) {
        try {
            datagram.length = MAX_DATAGRAM
            socket.receive(datagram)
        } catch (_: java.net.SocketTimeoutException) {
            return
        } catch (error: Exception) {
            if (running) SinkLog.w("$name socket receive failed: ${error.message}")
            return
        }
        val data = datagram.data
        val type = data[1].toInt() and 0x7F
        if (type == TYPE_SYNC && datagram.length >= 16) {
            syncPackets++
            if (syncPackets <= 2) {
                // Sync packet: RTP timestamp at +4, NTP time at +8 / +12.
                SinkLog.i(
                    "Timing sync #$syncPackets rtpTime=${Codec.be32(data, 4)} " +
                        "ntp=${Codec.be32(data, 8)}.${Codec.be32(data, 12)}",
                )
            }
        }
    }
}
