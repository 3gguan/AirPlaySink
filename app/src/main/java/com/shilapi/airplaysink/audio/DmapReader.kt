package com.shilapi.airplaysink.audio

import com.shilapi.airplaysink.util.Codec

/**
 * Minimal DMAP reader for the `application/x-dmap-tagged` payloads that AirPlay sends alongside the
 * audio. Only the three fields the UI shows are extracted: title, artist and album.
 *
 * DMAP items are `code(4) + length(4, big endian) + value`. A container whose length is 8 nests one
 * more item header that carries the real length.
 */
object DmapReader {

    class TrackInfo(val title: String?, val artist: String?, val album: String?)

    fun parse(payload: ByteArray): TrackInfo? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        walk(payload, 0, payload.size) { code, value ->
            when (code) {
                "minm" -> title = decodeText(value)
                "asar" -> artist = decodeText(value)
                "asal" -> album = decodeText(value)
            }
        }

        if (title == null && artist == null && album == null) return null
        return TrackInfo(title, artist, album)
    }

    private fun walk(data: ByteArray, from: Int, to: Int, visitor: (String, ByteArray) -> Unit) {
        var offset = from
        while (offset + 8 <= to) {
            val code = String(data, offset, 4, Charsets.ISO_8859_1)
            val declared = Codec.be32(data, offset + 4)
            var cursor = offset + 8

            if (declared == 8) {
                // Nested header: the real length lives inside the payload.
                if (cursor + 8 > to) return
                val innerCode = String(data, cursor, 4, Charsets.ISO_8859_1)
                val innerLength = Codec.be32(data, cursor + 4)
                val start = cursor + 8
                val end = start + innerLength
                if (innerLength < 0 || end > to) {
                    // Tolerate a malformed tail rather than dropping the whole item.
                    visitor(innerCode, ByteArray(0))
                    return
                }
                val value = data.copyOfRange(start, end)
                if (isContainer(innerLength, innerCode)) {
                    walk(data, start, end, visitor)
                } else {
                    visitor(innerCode, value)
                }
                cursor = end
            } else {
                val end = cursor + declared
                if (declared < 0 || end > to) return
                val value = data.copyOfRange(cursor, end)
                if (isContainer(declared, code)) {
                    walk(data, cursor, end, visitor)
                } else {
                    visitor(code, value)
                }
                cursor = end
            }
            offset = cursor
        }
    }

    private fun isContainer(length: Int, code: String): Boolean =
        code == "mlit" || code == "mper" || (length == 8)

    private fun decodeText(value: ByteArray): String? {
        if (value.isEmpty()) return null
        val text = String(value, Charsets.UTF_8).trim()
        return text.ifEmpty { null }
    }
}
