package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.rtsp.RtspReader
import com.shilapi.airplaysink.rtsp.RtspResponse
import com.shilapi.airplaysink.rtsp.writeRtsp
import com.shilapi.airplaysink.util.SinkLog
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The AirPlay 2 audio half: the SETUP exchange, the event channel and the audio stream.
 *
 * AirPlay 2 sets up in two steps on the same control connection. The first `SETUP` carries no
 * `streams` array — it only asks for an event port, and the reply tells the sender where to report
 * playback events. The second one names the streams it wants and expects the ports back, already
 * listening, because the sender connects the instant it reads the reply.
 *
 * Two stream types are carried, because the sender picks between them and this receiver has to
 * accept whichever it is told:
 *
 * - `96` realtime: plain RTP over UDP, arriving at playback pace.
 * - `103` buffered: TCP, framed as
 *   `uint16 length ‖ RTP header ‖ ChaCha20-Poly1305 ciphertext ‖ tag ‖ nonce`, so a slow link
 *   stretches delivery without losing packets.
 *
 * Both are encrypted the same way: the shared secret from pairing, keyed through the SETUP's `shk`,
 * with the 8-byte nonce sent in the packet padded out to the 12 bytes ChaCha20-Poly1305-IETF wants.
 */
class Ap2Streams(
    private val localAddress: InetAddress,
    private val sharedSecret: ByteArray,
    /** Fires once, as soon as the SETUP names an audio format, so the caller can build a decoder. */
    private val onFormat: (Format) -> Unit,
    /** One decrypted audio frame: RTP sequence, RTP timestamp and the codec payload. */
    private val onAudio: (sequence: Long, timestamp: Long, payload: ByteArray) -> Unit,
) : Closeable {

    companion object {
        const val STREAM_TYPE_REALTIME = 96L
        const val STREAM_TYPE_BUFFERED = 103L

        /**
         * What the reply promises the sender may buffer.
         *
         * The sender takes this literally and keeps this much audio in flight, so it is also the
         * price of a stall: bigger survives more jitter, but seeks and pauses take longer to take
         * effect. 256 KiB is roughly two seconds of 16-bit ALAC stereo.
         */
        const val AUDIO_BUFFER_BYTES = 256 * 1024L

        /** Compression types from the AirPlay spec; only ALAC is decoded here. */
        const val COMPRESSION_ALAC = 2L

        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val RTP_HEADER_BYTES = 12
        private const val TAG_BYTES = 16
        private const val NONCE_BYTES = 8
        private const val OVERHEAD = RTP_HEADER_BYTES + TAG_BYTES + NONCE_BYTES
    }

    /** The minimum the decoder needs; anything else in the SETUP is ignored. */
    data class Format(
        val sampleRate: Int,
        val channels: Int,
        val framesPerPacket: Int,
        val compressionType: Long,
        val audioFormat: Long,
    )

    private val random = SecureRandom()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private var eventServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private var realtimeSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null

    private var audioKey: ByteArray? = null

    /** Samples per packet, used to turn the realtime timestamp into an ordering counter. */
    @Volatile
    private var audioFramesPerPacket = 352

    /** Set once the buffered stream exists, so a second SETUP does not open a second port pair. */
    private var streamDescriptor: Map<String, Any?>? = null

    private var audioFrames = 0
    private var realtimePackets = 0

    /**
     * Answers one `SETUP`. The plist is returned as the reply body, or null when the request names
     * nothing this receiver can carry — in which case the caller should answer 451 rather than
     * pretend a stream exists.
     */
    fun setup(plist: Map<String, Any?>): ByteArray? {
        @Suppress("UNCHECKED_CAST")
        val streams = plist["streams"] as? List<Map<String, Any?>>

        if (streams == null) {
            val protocol = plist["timingProtocol"] as? String
            SinkLog.i(
                "AP2: SETUP (initial) timingProtocol=${protocol ?: "-"} keys=${plist.keys}",
            )
            val eventPort = openEventChannel()
            val response = linkedMapOf<String, Any?>(
                "eventPort" to eventPort.toLong(),
                // PTP is the sender's own clock; there is no NTP socket to point it at. shairport-sync
                // answers 0 here as well, and iOS treats it as "nothing extra to sync with".
                "timingPort" to 0L,
            )
            (plist["timingPeerInfo"] as? Map<*, *>)?.let { peer ->
                SinkLog.i("AP2: SETUP asks for timingPeerInfo: ${BPlistReader.describe(peer)}")
                response["timingPeerInfo"] = linkedMapOf<String, Any?>(
                    "ID" to localAddress.hostAddress,
                    "Addresses" to listOf(localAddress.hostAddress),
                )
            }
            return BPlist.write(response)
        }

        SinkLog.i("AP2: SETUP (streams) ${streams.size} stream(s): ${BPlistReader.describe(streams)}")
        val descriptors = ArrayList<Map<String, Any?>>(streams.size)
        for (stream in streams) {
            val descriptor = setupStream(stream) ?: continue
            descriptors.add(descriptor)
        }
        if (descriptors.isEmpty()) {
            SinkLog.w("AP2: SETUP named no stream this receiver can carry")
            return null
        }
        return BPlist.write(linkedMapOf<String, Any?>("streams" to descriptors))
    }

    // ------------------------------------------------------------------ event channel

    /**
     * Opens the port the sender reports playback events on.
     *
     * It is a plain RTSP connection, encrypted with the pairing secret under the `Events-Salt`
     * keys. Nothing here acts on those messages — volume and metadata come from the control
     * connection — but the port has to exist and answer, because a sender that cannot open it
     * treats the receiver as unable to start a session.
     */
    private fun openEventChannel(): Int {
        val existing = eventServer
        if (existing != null) return existing.localPort

        val server = ServerSocket(0, 4, localAddress)
        eventServer = server
        SinkLog.i("AP2: event port opened at ${localAddress.hostAddress}:${server.localPort}")
        thread(name = "ap2-events", isDaemon = true) {
            while (!closed.get()) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(name = "ap2-event-conn", isDaemon = true) { serveEvents(client) }
            }
        }
        return server.localPort
    }

    private fun serveEvents(client: Socket) {
        SinkLog.i("AP2: sender opened the event channel from ${client.remoteSocketAddress}")
        val link = Ap2SecureLink(
            client.getInputStream(),
            client.getOutputStream(),
            sharedSecret,
            salt = "Events-Salt",
            readKeyInfo = "Events-Read-Encryption-Key",
            writeKeyInfo = "Events-Write-Encryption-Key",
            label = "events",
        )
        val reader = RtspReader(link.input)
        try {
            while (!closed.get()) {
                val request = reader.readRequest() ?: break
                SinkLog.i(
                    "AP2: events <- ${request.method} ${request.uri} " +
                        "(${request.content.size}B ${request.contentType})",
                )
                if (request.content.isNotEmpty()) {
                    SinkLog.i("AP2: events   body ${request.content.toString(Charsets.ISO_8859_1).take(200)}")
                }
                link.output.writeRtsp(RtspResponse(200), request.cseq, "AirTunes/366.0")
            }
        } catch (error: Exception) {
            if (!closed.get()) SinkLog.w("AP2: event channel ended: ${error.message}")
        } finally {
            runCatching { client.close() }
            SinkLog.i("AP2: event channel closed")
        }
    }

    // ------------------------------------------------------------------ audio stream

    private fun setupStream(stream: Map<String, Any?>): Map<String, Any?>? {
        val type = stream["type"] as? Long
        if (type != STREAM_TYPE_REALTIME && type != STREAM_TYPE_BUFFERED) {
            SinkLog.w("AP2: stream type $type is not handled; only audio 96/103 is carried")
            return null
        }

        val existing = streamDescriptor
        if (existing != null) {
            SinkLog.i("AP2: reusing the stream already set up on port ${existing["dataPort"]}")
            return existing
        }

        val sampleRate = (stream["sr"] as? Long)?.toInt() ?: 44100
        val framesPerPacket = (stream["spf"] as? Long)?.toInt() ?: 352
        audioFramesPerPacket = framesPerPacket
        val compression = stream["ct"] as? Long ?: COMPRESSION_ALAC
        val audioFormat = stream["audioFormat"] as? Long ?: 0L
        val channels = channelsFor(audioFormat)
        val key = stream["shk"] as? ByteArray

        SinkLog.i(
            "AP2: ${if (type == STREAM_TYPE_REALTIME) "realtime" else "buffered"} stream: " +
                "${sampleRate}Hz ${channels}ch spf=$framesPerPacket ct=$compression " +
                "audioFormat=0x%08X key=%d bytes".format(audioFormat, key?.size ?: 0),
        )
        if (compression != COMPRESSION_ALAC) {
            SinkLog.w(
                "AP2: compression type $compression is not ALAC; this sink can only decode ALAC",
            )
        }
        if (key != null && key.size != Ap2Crypto.KEY_BYTES) {
            SinkLog.w("AP2: shk is ${key.size} bytes, expected ${Ap2Crypto.KEY_BYTES}")
        }

        audioKey = key

        // Both stream types keep an RTCP control port next to the audio port. Nothing acts on what
        // arrives there — playback is driven by the audio itself — but the port has to exist and be
        // drained, or the socket buffer fills and the sender's RTCP starts backing up.
        val control = DatagramSocket(InetSocketAddress(localAddress, 0))
        controlSocket = control
        thread(name = "ap2-audio-ctl", isDaemon = true) { drainControl(control) }

        onFormat(
            Format(
                sampleRate = sampleRate,
                channels = channels,
                framesPerPacket = framesPerPacket,
                compressionType = compression,
                audioFormat = audioFormat,
            ),
        )

        val streamId = random.nextInt().toLong() and 0xFFFF_FFFFL
        val descriptor: Map<String, Any?>
        if (type == STREAM_TYPE_REALTIME) {
            // Realtime audio is plain RTP over UDP: one datagram per packet, no framing on top.
            val data = DatagramSocket(InetSocketAddress(localAddress, 0))
            realtimeSocket = data
            SinkLog.i(
                "AP2: realtime audio port opened at ${localAddress.hostAddress}:${data.localPort}, " +
                    "control at :${control.localPort}",
            )
            thread(name = "ap2-audio-rt", isDaemon = true) { serveRealtime(data) }
            descriptor = linkedMapOf(
                "type" to STREAM_TYPE_REALTIME,
                "controlPort" to control.localPort.toLong(),
                "dataPort" to data.localPort.toLong(),
                "streamID" to streamId,
            )
        } else {
            val server = ServerSocket(0, 4, localAddress)
            audioServer = server
            SinkLog.i(
                "AP2: buffered audio port opened at ${localAddress.hostAddress}:${server.localPort}, " +
                    "control at :${control.localPort}",
            )
            thread(name = "ap2-audio", isDaemon = true) { serveAudio(server) }
            descriptor = linkedMapOf(
                "type" to STREAM_TYPE_BUFFERED,
                "controlPort" to control.localPort.toLong(),
                "dataPort" to server.localPort.toLong(),
                "audioBufferSize" to AUDIO_BUFFER_BYTES,
                "streamID" to streamId,
            )
        }
        streamDescriptor = descriptor
        return descriptor
    }

    /** `audioFormat` packs the channel count in bits 24-31 (0x40000 = 2 channels, 16-bit ALAC). */
    /**
     * Channel count for an `audioFormat` descriptor.
     *
     * The descriptor is a single bitmask, not separate fields, and every value the protocol defines
     * for audio is stereo: bit 18 is `ALAC/44100/16/2`, 19 the 24-bit variant, 20/21 the 48 kHz
     * ones, 22+ AAC. A mono sender does not pick another bit — it stays on two channels and fills
     * one of them.
     *
     * Reading a channel count out of the high byte (which is always zero) used to return 1, and a
     * mono ALAC cookie makes the decoder reject every channel-pair element. The result was a link
     * that connected cleanly and played nothing at all.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun channelsFor(audioFormat: Long): Int = 2

    private fun serveAudio(server: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            SinkLog.i("AP2: sender connected the audio stream from ${client.remoteSocketAddress}")
            try {
                readFrames(client)
                SinkLog.i("AP2: audio stream ended after $audioFrames frames")
            } catch (error: Exception) {
                if (!closed.get()) SinkLog.w("AP2: audio stream ended: ${error.message}")
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun readFrames(client: Socket) {
        val input = DataInputStream(BufferedInputStream(client.getInputStream(), 64 * 1024))
        while (!closed.get()) {
            val length = try {
                input.readUnsignedShort()
            } catch (_: EOFException) {
                return
            }
            if (length <= 2 || length > MAX_FRAME_BYTES) {
                SinkLog.w("AP2: bogus audio frame length $length")
                return
            }
            val packet = ByteArray(length - 2)
            input.readFully(packet)
            handlePacket(packet, realtime = false)
        }
    }

    /**
     * Realtime audio: one RTP packet per datagram, arriving at playback pace.
     *
     * Nothing here resends or reorders beyond what the pipeline already does for RAOP — the sender
     * would rather drop a packet than delay the stream.
     */
    private fun serveRealtime(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_FRAME_BYTES)
        while (!closed.get()) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (_: Exception) {
                return
            }
            if (datagram.length <= OVERHEAD) continue
            if (realtimePackets == 0) {
                SinkLog.i(
                    "AP2: first realtime packet ${datagram.length}B from ${datagram.socketAddress}",
                )
            }
            realtimePackets++
            if (realtimePackets <= 4) {
                // The sender's RTSP `rtp-info` and the sequence this header appears to carry do
                // not agree, so the layout is being read wrong somewhere. These four dumps settle
                // which field is which instead of guessing another offset.
                SinkLog.i(
                    "AP2: packet ${realtimePackets} (${datagram.length}B) " +
                        buffer.dumpHex(datagram.length),
                )
            }
            handlePacket(buffer.copyOf(datagram.length), realtime = true)
        }
    }

    private fun handlePacket(packet: ByteArray, realtime: Boolean) {
        if (packet.size <= OVERHEAD) return

        // Realtime packets are ordinary RTP, so the sequence sits where RTP puts it. The buffered
        // framing starts one byte earlier in the sender's implementation, which is why the two
        // offsets differ.
        //
        // Realtime ordering is taken from the timestamp instead of the sequence field, and the
        // reason is a 16-bit wrap: the header's counter runs out every 65536 packets, i.e. every
        // 8.7 minutes at 8 ms a packet, and the pipeline compares sequences as plain numbers, so
        // playback would stop dead at the wrap. The timestamp is 32-bit and every packet is exactly
        // `spf` samples, so dividing it yields the same counter monotonically for 27 hours.
        //
        // (This used to read the field with a 4-byte load starting at offset 2 and mask it to 16
        // bits, which actually returned the high half of the timestamp — a value that drifts far
        // too slowly to be a packet counter. 98% of the stream was then judged "already played"
        // and dropped, which is why the first AP2 sessions connected and stayed silent.)
        val timestamp = beUInt(packet, 4)
        val sequence = if (realtime) {
            timestamp / audioFramesPerPacket.coerceAtLeast(1).toLong()
        } else {
            beUInt(packet, 0) and 0x7FFF_FFL
        }
        val bodyEnd = packet.size - NONCE_BYTES

        val key = audioKey
        val payload = if (key == null) {
            packet.copyOfRange(RTP_HEADER_BYTES, bodyEnd)
        } else {
            val nonce = ByteArray(Ap2Crypto.NONCE_BYTES)
            System.arraycopy(packet, bodyEnd, nonce, Ap2Crypto.NONCE_BYTES - NONCE_BYTES, NONCE_BYTES)
            try {
                Ap2Crypto.open(
                    key,
                    nonce,
                    packet.copyOfRange(4, RTP_HEADER_BYTES),
                    packet.copyOfRange(RTP_HEADER_BYTES, bodyEnd),
                )
            } catch (error: Exception) {
                // One line per failure would flood the log at 125 frames a second, so the first
                // one explains the problem and the counter says how often it happens.
                if (++decryptFailures <= 3) {
                    SinkLog.w(
                        "AP2: audio frame $sequence failed to decrypt (${error.message}); " +
                            "packet ${packet.size}B",
                    )
                }
                return
            }
        }

        if (audioFrames == 0) {
            SinkLog.i(
                "AP2: first decrypted frame: seq=$sequence ts=$timestamp " +
                    "headerSeq=${beUShort(packet, 2)} " +
                    "ssrc=0x%08X payload=${payload.size}B".format(beUInt(packet, 8)),
            )
        }
        audioFrames++
        onAudio(sequence, timestamp, payload)
    }

    private var decryptFailures = 0

    /** First bytes as hex, grouped per RTP field width so the header can be read at a glance. */
    private fun ByteArray.dumpHex(limit: Int): String {
        val count = minOf(limit, 24)
        val text = StringBuilder(count * 3)
        for (index in 0 until count) {
            if (index > 0) text.append(if (index % 4 == 0) "  " else ' ')
            val value = this[index].toInt() and 0xFF
            text.append(Character.forDigit(value shr 4, 16))
            text.append(Character.forDigit(value and 0xF, 16))
        }
        return text.toString()
    }

    private fun beUInt(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    /** A 16-bit field. [beUInt] always consumes four bytes, so it cannot stand in for this. */
    private fun beUShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** The sender's RTCP reports are not acted on; draining the port keeps the kernel from filling. */
    private fun drainControl(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        var packets = 0
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                return
            }
            if (packets++ == 0) {
                SinkLog.i(
                    "AP2: first control packet ${packet.length}B from ${packet.socketAddress}",
                )
            }
        }
    }

    fun describe(): String = "audio stream, $audioFrames decoded frames"

    override fun close() {
        closed.set(true)
        runCatching { eventServer?.close() }
        runCatching { audioServer?.close() }
        runCatching { realtimeSocket?.close() }
        runCatching { controlSocket?.close() }
        eventServer = null
        audioServer = null
        realtimeSocket = null
        controlSocket = null
        streamDescriptor = null
        audioKey = null
    }
}
