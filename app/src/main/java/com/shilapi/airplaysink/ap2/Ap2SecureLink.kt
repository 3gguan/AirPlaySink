package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.util.SinkLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * The encrypted wrapper the control connection switches into once pairing has produced a key.
 *
 * HAP frames the stream as `length (2 bytes, little endian) ‖ ciphertext ‖ tag (16 bytes)`, with
 * ChaCha20-Poly1305, the frame length as associated data, and a nonce that is a per-direction
 * counter. Everything the RTSP layer writes goes in as one or more frames; everything it reads comes
 * back as plaintext, so the layer above does not know the difference.
 *
 * Key direction follows shairport-sync's `cipher_new` for the control channel: what *we* write uses
 * `Control-Read-Encryption-Key` and what we read uses `Control-Write-Encryption-Key`. The names are
 * from the sender's point of view, which is why they look inverted here.
 */
class Ap2SecureLink(
    private val rawInput: InputStream,
    private val rawOutput: OutputStream,
    sharedSecret: ByteArray,
    /*
     * AirPlay 2 derives one key pair per channel from the same pairing secret, differing only in
     * the HKDF salt and info strings: `Control-Salt` for the RTSP connection, `Events-Salt` for
     * the channel the sender opens for playback events. Defaults are the control channel, which
     * is the one every session builds.
     */
    salt: String = "Control-Salt",
    readKeyInfo: String = "Control-Read-Encryption-Key",
    writeKeyInfo: String = "Control-Write-Encryption-Key",
    private val label: String = "control",
) {

    companion object {
        /** Largest plaintext block per frame; larger writes are split. */
        private const val MAX_BLOCK = 1024

        /** A frame that stalls this long is treated as a dead connection, not a slow one. */
        private const val STALL_LIMIT_MS = 60_000L
    }

    private val outKey = Ap2Crypto.hkdf(sharedSecret, salt, readKeyInfo, Ap2Crypto.KEY_BYTES)
    private val inKey = Ap2Crypto.hkdf(sharedSecret, salt, writeKeyInfo, Ap2Crypto.KEY_BYTES)

    private var outCounter = 0L
    private val writeLock = Any()

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(writeLock) {
                var offset = off
                var remaining = len
                while (remaining > 0) {
                    val block = minOf(remaining, MAX_BLOCK)
                    val lengthBytes = byteArrayOf(
                        (block and 0xFF).toByte(),
                        ((block ushr 8) and 0xFF).toByte(),
                    )
                    val sealed = Ap2Crypto.seal(
                        outKey,
                        Ap2Crypto.nonce(outCounter),
                        lengthBytes,
                        b.copyOfRange(offset, offset + block),
                    )
                    rawOutput.write(lengthBytes)
                    rawOutput.write(sealed)
                    outCounter++
                    offset += block
                    remaining -= block
                }
                rawOutput.flush()
            }
        }

        override fun flush() {
            rawOutput.flush()
        }
    }

    val input: InputStream = DecryptingInput(rawInput, inKey)

    private class DecryptingInput(
        private val raw: InputStream,
        private val key: ByteArray,
    ) : InputStream() {

        private var counter = 0L
        private var pending = ByteArray(0)
        private var pendingOffset = 0

        override fun read(): Int {
            if (!fill()) return -1
            return pending[pendingOffset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val count = minOf(len, pending.size - pendingOffset)
            System.arraycopy(pending, pendingOffset, b, off, count)
            pendingOffset += count
            return count
        }

        override fun available(): Int = pending.size - pendingOffset

        /** Loads the next frame into [pending]; false at end of stream. */
        private fun fill(): Boolean {
            if (pendingOffset < pending.size) return true

            val head = ByteArray(2)
            if (!readFully(head)) return false
            val length = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
            if (counter == 0L) {
                // One line that answers the only question a silent close leaves open: did the
                // sender really send nothing, or did it send something we could not read?
                SinkLog.i(
                    "AP2: first frame header on the encrypted channel: " +
                        "%02x %02x (length %d)".format(head[0], head[1], length),
                )
            }
            if (length <= 0 || length > MAX_BLOCK) {
                throw IOException("HAP frame of $length bytes is out of range")
            }

            val sealed = ByteArray(length + Ap2Crypto.TAG_BYTES)
            if (!readFully(sealed)) return false

            pending = try {
                Ap2Crypto.open(key, Ap2Crypto.nonce(counter), head, sealed)
            } catch (error: Exception) {
                throw IOException("HAP frame $counter failed to authenticate: ${error.message}")
            }
            pendingOffset = 0
            counter++
            return true
        }

        /**
         * Reads until [buffer] is full. A read timeout is not a failure here: the session polls its
         * socket every few seconds so it can run its silence checks, and a frame may legitimately be
         * split across those windows. Only a peer that stops mid-frame for a minute is treated as
         * gone, and closing the socket from the session thread breaks the loop anyway.
         */
        private fun readFully(buffer: ByteArray): Boolean {
            var read = 0
            var idleSince = System.currentTimeMillis()
            while (read < buffer.size) {
                val count = try {
                    raw.read(buffer, read, buffer.size - read)
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - idleSince > STALL_LIMIT_MS) {
                        throw IOException("HAP frame stalled for ${STALL_LIMIT_MS / 1000}s")
                    }
                    continue
                }
                if (count < 0) {
                    if (read == 0) {
                        if (counter == 0L) {
                            SinkLog.i("AP2: sender closed the encrypted channel without sending a frame")
                        }
                        return false
                    }
                    throw IOException("HAP frame truncated after $read of ${buffer.size} bytes")
                }
                read += count
                idleSince = System.currentTimeMillis()
            }
            return true
        }
    }

    fun describe(): String = "$label channel encrypted (read key ${inKey.size}B, write key ${outKey.size}B)"

    init {
        SinkLog.i("AP2: ${describe()}")
    }
}
