package com.shilapi.airplaysink.rtsp

import com.shilapi.airplaysink.util.Codec

enum class RaopCodec { PCM, ALAC, AAC, UNKNOWN }

/**
 * Everything the receiver needs out of an `ANNOUNCE` body.
 *
 * The ALAC parameters arrive as `a=fmtp:` inside the SDP, laid out exactly like Apple's
 * `ALACSpecificConfig`: payload type, frameLength, compatibleVersion, bitDepth, pb, mb, kb,
 * numChannels, maxRun, maxFrameBytes, avgBitRate, sampleRate.
 */
class AnnounceInfo(
    val codec: RaopCodec,
    val fmtp: IntArray,
    val aesIv: ByteArray?,
    val rsaAesKey: ByteArray?,
    val minLatency: Int?,
    val maxLatency: Int?,
    sdpSession: String?,
) {
    val frameLength: Int get() = fmtp.getOrElse(1) { 352 }
    val bitDepth: Int get() = fmtp.getOrElse(3) { 16 }
    val channels: Int get() = fmtp.getOrElse(7) { 2 }
    val sampleRate: Int get() = fmtp.getOrElse(11) { 44100 }
    val riceHistoryMult: Int get() = fmtp.getOrElse(4) { 40 }
    val riceInitialHistory: Int get() = fmtp.getOrElse(5) { 10 }
    val riceKModifier: Int get() = fmtp.getOrElse(6) { 14 }

    val sessionId: String? = sdpSession

    val encrypted: Boolean get() = aesIv != null && rsaAesKey != null
}

object SdpParser {

    /** Apple's defaults, used when the `a=fmtp:` line is partial (see shairport-sync's rtsp.c). */
    private val DEFAULT_FMTP = intArrayOf(96, 352, 0, 16, 40, 10, 14, 2, 255, 0, 0, 44100)

    fun parse(text: String): AnnounceInfo? {
        var codec = RaopCodec.UNKNOWN
        var fmtp: IntArray? = null
        var aesIv: ByteArray? = null
        var rsaAesKey: ByteArray? = null
        var minLatency: Int? = null
        var maxLatency: Int? = null
        var sessionId: String? = null
        var sampleRate: Int? = null
        var channelCount: Int? = null

        for (rawLine in text.split("\r\n", "\n")) {
            val line = rawLine.trim()
            when {
                line.startsWith("a=fmtp:") -> {
                    fmtp = parseFmtp(line.substring("a=fmtp:".length))
                    codec = RaopCodec.ALAC
                }

                line.startsWith("a=rtpmap:") -> {
                    val body = line.substring("a=rtpmap:".length)
                    val encoding = body.substringAfter(' ', "").substringBefore('/').lowercase()
                    val parts = body.substringAfter(' ', "").split('/')
                    sampleRate = parts.getOrNull(1)?.toIntOrNull()
                    channelCount = parts.getOrNull(2)?.toIntOrNull()
                    codec = when {
                        encoding.contains("applelossless") || encoding.contains("alac") -> RaopCodec.ALAC
                        encoding.contains("l16") || encoding.contains("lpcm") -> RaopCodec.PCM
                        encoding.contains("mpeg4-generic") || encoding.contains("aac") -> RaopCodec.AAC
                        else -> codec
                    }
                }

                line.startsWith("a=aesiv:") ->
                    aesIv = decodeOrNull(line.substring("a=aesiv:".length))

                line.startsWith("a=rsaaeskey:") ->
                    rsaAesKey = decodeOrNull(line.substring("a=rsaaeskey:".length))

                line.startsWith("a=min-latency:") ->
                    minLatency = line.substring("a=min-latency:".length).trim().toIntOrNull()

                line.startsWith("a=max-latency:") ->
                    maxLatency = line.substring("a=max-latency:".length).trim().toIntOrNull()

                line.startsWith("o=") -> {
                    // o=iTunes <session id> <version> IN IP4 <address>
                    val fields = line.substring(2).trim().split(' ')
                    sessionId = fields.getOrNull(1)
                }
            }
        }

        if (codec == RaopCodec.UNKNOWN) return null

        val table = (fmtp ?: DEFAULT_FMTP).copyOf()
        if (fmtp == null && codec == RaopCodec.PCM) {
            table[1] = 352
            table[3] = 16
            table[7] = channelCount ?: 2
            table[11] = sampleRate ?: 44100
        }
        return AnnounceInfo(
            codec = codec,
            fmtp = table,
            aesIv = aesIv,
            rsaAesKey = rsaAesKey,
            minLatency = minLatency,
            maxLatency = maxLatency,
            sdpSession = sessionId,
        )
    }

    private fun parseFmtp(value: String): IntArray {
        val table = DEFAULT_FMTP.copyOf()
        val tokens = value.trim().split(' ', '\t').filter { it.isNotEmpty() }
        for (index in tokens.indices) {
            if (index >= table.size) break
            table[index] = tokens[index].toIntOrNull() ?: table[index]
        }
        return table
    }

    private fun decodeOrNull(value: String): ByteArray? =
        runCatching { Codec.base64Decode(value.trim()) }.getOrNull()
}
