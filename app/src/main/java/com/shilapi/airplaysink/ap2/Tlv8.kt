package com.shilapi.airplaysink.ap2

/**
 * The TLV8 container used by every HAP pairing message.
 *
 * A value longer than 255 bytes is split into consecutive items of the same type, and a value that
 * is an exact multiple of 255 is followed by an empty item of the same type so that the receiver
 * knows where it ends. [encode] and [decode] both handle that, which matters because the SRP public
 * key is 384 bytes and the proofs are 64 — every one of them spans more than one item.
 */
object Tlv8 {

    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val FLAGS = 0x13

    const val STATE_M1 = 1
    const val STATE_M2 = 2
    const val STATE_M3 = 3
    const val STATE_M4 = 4

    const val METHOD_PAIR_SETUP = 0x00
    const val FLAG_TRANSIENT = 0x10

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val values = LinkedHashMap<Int, ByteArray>()
        var index = 0
        while (index + 2 <= data.size) {
            val type = data[index].toInt() and 0xFF
            val length = data[index + 1].toInt() and 0xFF
            val start = index + 2
            if (start + length > data.size) break
            val chunk = data.copyOfRange(start, start + length)
            val existing = values[type]
            values[type] = if (existing == null) chunk else existing + chunk
            index = start + length
        }
        return values
    }

    fun encode(values: List<Pair<Int, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((type, value) in values) {
            if (value.isEmpty()) {
                out.write(type)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val length = minOf(255, value.size - offset)
                out.write(type)
                out.write(length)
                out.write(value, offset, length)
                offset += length
            }
            // A value of exactly n*255 bytes needs the empty terminator item, otherwise the
            // receiver cannot tell it was not truncated at the last full chunk.
            if (value.size % 255 == 0) {
                out.write(type)
                out.write(0)
            }
        }
        return out.toByteArray()
    }

    fun encode(vararg values: Pair<Int, ByteArray>): ByteArray = encode(values.toList())

    fun state(state: Int): Pair<Int, ByteArray> = STATE to byteArrayOf(state.toByte())

    fun u8(type: Int, value: Int): Pair<Int, ByteArray> = type to byteArrayOf(value.toByte())
}
