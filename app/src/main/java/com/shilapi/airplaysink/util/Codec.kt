package com.shilapi.airplaysink.util

import java.util.Locale

/**
 * Apple's RAOP uses its own Base64 dialect: padding is often omitted. [base64Decode] restores it
 * before handing the text to the platform decoder, matching what shairport-sync does.
 */
object Codec {

    private const val HEX = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    /** Hex string without a leading "0x", used for the device id in the mDNS record. */
    fun toHexUpper(bytes: ByteArray): String = toHex(bytes).uppercase(Locale.US)

    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** Base64 decode tolerating the missing padding used by Apple. */
    fun base64Decode(text: String): ByteArray {
        val trimmed = text.trim().replace("\r", "").replace("\n", "")
        val padded = when (trimmed.length % 4) {
            0 -> trimmed
            2 -> "$trimmed=="
            3 -> "$trimmed="
            else -> "$trimmed"
        }
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    fun base64Encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun be16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun be32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    fun putBe16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 1] = (value and 0xFF).toByte()
    }
}
