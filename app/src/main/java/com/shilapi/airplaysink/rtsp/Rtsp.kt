package com.shilapi.airplaysink.rtsp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** One parsed RTSP request. Header keys are lower cased. */
class RtspRequest(
    val method: String,
    val uri: String,
    private val headers: Map<String, String>,
    val content: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val cseq: String get() = header("cseq") ?: "0"
    val contentType: String get() = header("content-type") ?: ""
    val contentLength: Int get() = content.size

    fun contentText(): String = String(content, Charsets.ISO_8859_1)

    /** One-line dump of every header, for the log. Header names are already lower cased. */
    fun headerSummary(): String =
        headers.entries.joinToString(" ") { "${it.key}=${it.value}" }

    override fun toString(): String = "$method $uri (${content.size}B)"
}

/** One outgoing RTSP response. */
class RtspResponse(val status: Int) {

    private val headers = mutableListOf<Pair<String, String>>()
    var content: ByteArray = ByteArray(0)

    fun addHeader(name: String, value: String): RtspResponse {
        headers.add(name to value)
        return this
    }

    fun serialize(cseq: String, server: String): ByteArray {
        val head = StringBuilder()
        head.append("RTSP/1.0 ").append(status).append(' ').append(reason(status)).append("\r\n")
        head.append("CSeq: ").append(cseq).append("\r\n")
        head.append("Server: ").append(server).append("\r\n")
        for ((name, value) in headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        if (content.isNotEmpty()) {
            head.append("Content-Length: ").append(content.size).append("\r\n")
        }
        head.append("\r\n")
        val headBytes = head.toString().toByteArray(Charsets.ISO_8859_1)
        if (content.isEmpty()) return headBytes
        val out = ByteArray(headBytes.size + content.size)
        System.arraycopy(headBytes, 0, out, 0, headBytes.size)
        System.arraycopy(content, 0, out, headBytes.size, content.size)
        return out
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        451 -> "Parameter Not Understood"
        453 -> "Not Enough Bandwidth"
        454 -> "Session Not Found"
        456 -> "Header Field Not Valid for Resource"
        470 -> "Connection Authorization Required"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}

/**
 * Reads RTSP requests off a socket. RAOP traffic is low volume, so a simple growable buffer with a
 * CRLFCRLF scan is plenty and keeps the code readable.
 */
class RtspReader(private val input: InputStream) {

    private var buffer = ByteArray(8192)
    private var start = 0
    private var end = 0

    /** Returns null at end of stream. */
    fun readRequest(): RtspRequest? {
        val headerEnd = findHeaderEnd() ?: return null
        val headText = String(buffer, start, headerEnd - start, Charsets.ISO_8859_1)
        val bodyStart = headerEnd + 4
        val lines = headText.split("\r\n")
        if (lines.isEmpty()) throw EOFException("empty RTSP request")

        val requestLine = lines[0]
        val parts = requestLine.split(' ')
        val method = parts.getOrElse(0) { "" }
        val uri = parts.getOrElse(1) { "" }

        val headers = LinkedHashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val wanted = bodyStart + contentLength
        while (end < wanted) {
            if (!fill()) throw EOFException("RTSP body truncated (${end - bodyStart}/$contentLength bytes)")
        }
        val body = ByteArray(contentLength)
        if (contentLength > 0) System.arraycopy(buffer, bodyStart, body, 0, contentLength)
        start = wanted
        compact()
        return RtspRequest(method, uri, headers, body)
    }

    private fun findHeaderEnd(): Int? {
        while (true) {
            val index = indexOfCrlfCrlf()
            if (index >= 0) return index
            if (!fill()) return null
        }
    }

    private fun indexOfCrlfCrlf(): Int {
        var i = start
        while (i + 3 < end) {
            if (buffer[i] == 13.toByte() && buffer[i + 1] == 10.toByte() &&
                buffer[i + 2] == 13.toByte() && buffer[i + 3] == 10.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun fill(): Boolean {
        compact()
        if (end == buffer.size) {
            if (start > 0) {
                compact()
            } else {
                buffer = buffer.copyOf(buffer.size * 2)
            }
        }
        val read = input.read(buffer, end, buffer.size - end)
        if (read < 0) return false
        end += read
        return true
    }

    private fun compact() {
        if (start == 0) return
        val remaining = end - start
        if (remaining > 0) System.arraycopy(buffer, start, buffer, 0, remaining)
        start = 0
        end = remaining
    }
}

/** Writes a response, re-synchronising the caller's stream state. */
fun OutputStream.writeRtsp(response: RtspResponse, cseq: String, server: String) {
    val bytes = response.serialize(cseq, server)
    write(bytes)
    flush()
}

/** Convenience for building simple textual RTSP bodies. */
fun textBody(vararg lines: String): ByteArray {
    val out = ByteArrayOutputStream()
    for (line in lines) {
        out.write(line.toByteArray(Charsets.ISO_8859_1))
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
    }
    return out.toByteArray()
}
