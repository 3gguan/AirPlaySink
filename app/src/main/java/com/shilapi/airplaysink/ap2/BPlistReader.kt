package com.shilapi.airplaysink.ap2

import java.util.Locale

/**
 * Reads `bplist00`, the binary property list every AirPlay 2 control message is written in.
 *
 * Only what the sender actually uses is supported — dictionaries, arrays, strings, integers,
 * booleans, reals and data — but those are decoded exactly, because a half-parsed SETUP would mean
 * guessing at port numbers and audio formats. Values come back as the obvious Kotlin types:
 * [Map], [List], [String], [Long], [Boolean], [Double] and [ByteArray].
 */
object BPlistReader {

    class Malformed(message: String) : Exception(message)

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size < 8 + TRAILER_BYTES) throw Malformed("only ${bytes.size} bytes")
        if (String(bytes, 0, 8, Charsets.ISO_8859_1) != "bplist00") {
            throw Malformed("not a bplist00 header")
        }

        val trailer = bytes.size - TRAILER_BYTES
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val refSize = bytes[trailer + 7].toInt() and 0xFF
        val objectCount = readLong(bytes, trailer + 8, 8).toInt()
        val topObject = readLong(bytes, trailer + 16, 8).toInt()
        val offsetTable = readLong(bytes, trailer + 24, 8).toInt()

        if (offsetSize !in 1..8 || refSize !in 1..8) {
            throw Malformed("offset/ref size $offsetSize/$refSize")
        }
        if (objectCount <= 0 || topObject >= objectCount) {
            throw Malformed("$objectCount objects, top $topObject")
        }
        if (offsetTable < 8 || offsetTable + objectCount * offsetSize > bytes.size) {
            throw Malformed("offset table at $offsetTable outside the file")
        }

        val offsets = IntArray(objectCount) {
            readLong(bytes, offsetTable + it * offsetSize, offsetSize).toInt()
        }
        return Decoder(bytes, offsets, refSize).objectAt(topObject)
    }

    /** Same as [parse], but only for a dictionary body — which is what RTSP plists always are. */
    @Suppress("UNCHECKED_CAST")
    fun parseDictionary(bytes: ByteArray): Map<String, Any?>? = parse(bytes) as? Map<String, Any?>

    /**
     * Renders a parsed plist on one log line.
     *
     * The point is the SETUP body: it names the stream types, the audio format and the ports the
     * sender expects back, and it only ever appears on the wire once.
     */
    fun describe(value: Any?, limit: Int = 1200): String {
        val text = StringBuilder()
        appendValue(text, value)
        return if (text.length > limit) text.substring(0, limit) + "…" else text.toString()
    }

    private fun appendValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, child) in value) {
                    if (!first) out.append(", ")
                    first = false
                    out.append(key)
                    out.append(':')
                    appendValue(out, child)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, child ->
                    if (index > 0) out.append(", ")
                    appendValue(out, child)
                }
                out.append(']')
            }
            is String -> out.append('"').append(value).append('"')
            is ByteArray -> out.append(
                value.joinToString("") { String.format(Locale.US, "%02x", it) },
            )
            else -> out.append(value.toString())
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private class Decoder(
        private val bytes: ByteArray,
        private val offsets: IntArray,
        private val refSize: Int,
    ) {

        /** Guards against a corrupt file that points a dictionary at itself. */
        private val visiting = HashSet<Int>()

        fun objectAt(index: Int): Any? {
            if (index < 0 || index >= offsets.size) throw Malformed("reference $index out of range")
            if (!visiting.add(index)) throw Malformed("object $index refers to itself")
            try {
                return decode(offsets[index])
            } finally {
                visiting.remove(index)
            }
        }

        private fun decode(offset: Int): Any? {
            if (offset < 0 || offset >= bytes.size) throw Malformed("object at $offset")
            val marker = bytes[offset].toInt() and 0xFF
            val kind = marker shr 4
            val info = marker and 0x0F

            return when (kind) {
                0x0 -> when (info) {
                    0x0 -> null
                    0x8 -> false
                    0x9 -> true
                    else -> null // fill bytes carry no value
                }
                0x1 -> {
                    // Integers are 1, 2, 4 or 8 bytes; the low nibble is log2(size).
                    val size = 1 shl info
                    readLong(bytes, offset + 1, size.coerceAtMost(8))
                }
                0x2 -> {
                    val size = 1 shl info
                    val bits = readLong(bytes, offset + 1, size.coerceAtMost(8))
                    Double.fromBits(bits)
                }
                0x3 -> {
                    // A date: seconds since 2001-01-01, encoded as a double. Nothing here needs the
                    // value itself, so it is kept as the raw number rather than a LocalDateTime.
                    Double.fromBits(readLong(bytes, offset + 1, 8))
                }
                0x4 -> {
                    val (count, body) = lengthAndBody(offset, info)
                    bytes.copyOfRange(body, body + count)
                }
                0x5 -> {
                    val (count, body) = lengthAndBody(offset, info)
                    String(bytes, body, count, Charsets.US_ASCII)
                }
                0x6 -> {
                    val (count, body) = lengthAndBody(offset, info)
                    String(bytes, body, count * 2, Charsets.UTF_16BE)
                }
                0x8 -> readLong(bytes, offset + 1, info + 1)
                0xA, 0xB, 0xC -> {
                    val (count, body) = lengthAndBody(offset, info)
                    List(count) { objectAt(readLong(bytes, body + it * refSize, refSize).toInt()) }
                }
                0xD -> {
                    val (count, body) = lengthAndBody(offset, info)
                    val map = LinkedHashMap<String, Any?>(count * 2)
                    for (index in 0 until count) {
                        val key = objectAt(readLong(bytes, body + index * refSize, refSize).toInt())
                        val value = objectAt(
                            readLong(bytes, body + (count + index) * refSize, refSize).toInt(),
                        )
                        map[key?.toString() ?: index.toString()] = value
                    }
                    map
                }
                else -> throw Malformed("marker 0x%02x at $offset".format(marker))
            }
        }

        /**
         * Returns how many items the object holds and where its body starts. Counts of 15 and over
         * are stored in a following integer object instead of the marker's low nibble.
         */
        private fun lengthAndBody(offset: Int, info: Int): Pair<Int, Int> {
            if (info != 0x0F) return info to offset + 1
            val countMarker = bytes[offset + 1].toInt() and 0xFF
            if (countMarker shr 4 != 0x1) throw Malformed("extended count is not an integer")
            val size = 1 shl (countMarker and 0x0F)
            val count = readLong(bytes, offset + 2, size.coerceAtMost(8)).toInt()
            return count to offset + 2 + size
        }
    }

    private const val TRAILER_BYTES = 32
}
