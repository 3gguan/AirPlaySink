package com.shilapi.airplaysink.ap2

import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/**
 * Just enough `bplist00` writing to answer AirPlay's `/info`.
 *
 * A plist library would be a dependency for one message, and the format is small: a header, the
 * objects back to back, an offset table, and a 32-byte trailer. Only maps, lists, strings, numbers,
 * booleans and byte arrays are accepted; anything else throws rather than being encoded wrong.
 */
object BPlist {

    fun write(root: Any?): ByteArray {
        // Object 0 must be the root. Children are registered while their container is visited, so a
        // node always has an index before anything refers to it.
        val objects = ArrayList<Any?>()
        val indices = IdentityHashMap<Any, Int>()

        fun register(value: Any?): Int {
            val existing = indices[value]
            if (existing != null) return existing
            val index = objects.size
            objects.add(value)
            indices[value] = index
            return index
        }

        register(root)
        var cursor = 0
        while (cursor < objects.size) {
            when (val value = objects[cursor]) {
                is Map<*, *> -> for (entry in value.entries) {
                    register(entry.key)
                    register(entry.value)
                }
                is List<*> -> for (child in value) register(child)
                else -> Unit
            }
            cursor++
        }

        val refSize = sizeOf(objects.size)
        val body = ByteArrayOutputStream()

        fun writeFixed(value: Long, size: Int) {
            for (shift in (size - 1) * 8 downTo 0 step 8) {
                body.write(((value ushr shift) and 0xFF).toInt())
            }
        }

        fun writeCount(marker: Int, count: Int) {
            if (count < 15) {
                body.write(marker + count)
            } else {
                body.write(marker + 0x0F)
                writeFixed(0x13, 1) // an 8-byte integer always fits a count
                writeFixed(count.toLong(), 8)
            }
        }

        fun writeNumber(value: Long) {
            when {
                value in 0..0xFF -> { body.write(0x10); writeFixed(value, 1) }
                value in 0..0xFFFF -> { body.write(0x11); writeFixed(value, 2) }
                value in 0..0xFFFF_FFFFL -> { body.write(0x12); writeFixed(value, 4) }
                else -> { body.write(0x13); writeFixed(value, 8) }
            }
        }

        val offsets = IntArray(objects.size)
        for (index in objects.indices) {
            offsets[index] = body.size()
            when (val value = objects[index]) {
                is Map<*, *> -> {
                    writeCount(0xD0, value.size)
                    for (key in value.keys) writeFixed(indices.getValue(key).toLong(), refSize)
                    for (entry in value.values) writeFixed(indices.getValue(entry).toLong(), refSize)
                }

                is List<*> -> {
                    writeCount(0xA0, value.size)
                    for (child in value) writeFixed(indices.getValue(child).toLong(), refSize)
                }

                is String -> {
                    // Every string here is a device name, model, version or identifier.
                    val bytes = value.toByteArray(Charsets.US_ASCII)
                    writeCount(0x50, bytes.size)
                    body.write(bytes)
                }

                is ByteArray -> {
                    writeCount(0x40, value.size)
                    body.write(value)
                }

                is Boolean -> body.write(if (value) 0x09 else 0x08)
                is Int -> writeNumber(value.toLong())
                is Long -> writeNumber(value)
                is Double -> {
                    body.write(0x23)
                    writeFixed(java.lang.Double.doubleToLongBits(value), 8)
                }

                null -> body.write(0x00)
                else -> throw IllegalArgumentException(
                    "bplist: ${value.javaClass.simpleName} has no encoding here",
                )
            }
        }

        // Offsets in a plist are absolute file offsets, so the 8-byte "bplist00" header has to be
        // counted even though it is written last-second below.
        val offsetTableAt = body.size() + HEADER_BYTES
        val offsetSize = sizeOf(offsetTableAt)
        for (offset in offsets) writeFixed((offset + HEADER_BYTES).toLong(), offsetSize)

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        body.writeTo(out)
        repeat(6) { out.write(0) }
        out.write(offsetSize)
        out.write(refSize)
        writeFixedTo(out, objects.size.toLong())
        writeFixedTo(out, 0L)
        writeFixedTo(out, offsetTableAt.toLong())
        return out.toByteArray()
    }

    private const val HEADER_BYTES = 8

    private fun writeFixedTo(out: ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    private fun sizeOf(value: Int): Int = when {
        value <= 0xFF -> 1
        value <= 0xFFFF -> 2
        else -> 4
    }
}
