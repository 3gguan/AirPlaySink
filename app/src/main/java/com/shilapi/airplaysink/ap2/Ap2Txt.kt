package com.shilapi.airplaysink.ap2

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The AirPlay 2 identity, in the two forms the sender ever sees it.
 *
 * iOS learns what a device can do from two places that must agree: the TXT record of the
 * `_airplay._tcp` advertisement, and the `txtAirPlay` field of the `/info` reply. A sender that
 * finds them inconsistent treats the device as unknown, which is indistinguishable from "this
 * receiver is broken" — so both are generated from [properties] and nothing else.
 *
 * [encode] produces the DNS TXT wire format iOS expects in the plist: each `key=value` prefixed
 * with its own length byte, concatenated, with no other framing.
 */
object Ap2Txt {

    /** `features` as the TXT record spells it: low word first, both hex, comma separated. */
    fun featuresPair(features: Long): String = String.format(
        Locale.US, "0x%08X,0x%X", features and 0xFFFF_FFFFL, (features ushr 32) and 0xFFFF_FFFFL,
    )

    /**
     * `featuresEx`: the same 64-bit mask, little endian, base64 with the padding stripped.
     * shairport-sync builds it exactly this way and sends it in `/info` beside `features`.
     */
    fun featuresEx(features: Long): String {
        val bytes = ByteArray(8) { index -> ((features ushr (8 * index)) and 0xFF).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP).trimEnd('=')
    }

    /**
     * Every key a sender expects on an audio-only receiver, in the order real devices use.
     *
     * `sf` and `features` decide the whole negotiation: `sf=0x4` (audio cable attached, nothing
     * else) is what makes iOS use transient pairing and stop at pair-setup M4 instead of demanding
     * a pin, and the feature mask is the one shairport-sync ships and Apple's senders accept.
     */
    fun properties(
        deviceId: String,
        model: String,
        sourceVersion: String,
        statusFlags: Int,
        features: Long,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "deviceid" to deviceId.chunked(2).joinToString(":").uppercase(Locale.US),
        "features" to featuresPair(features),
        "flags" to "0x4",
        "pk" to Ap2Session.publicKey(deviceId).joinToString("") {
            String.format(Locale.US, "%02x", it)
        },
        "pi" to Ap2Session.pairingId(deviceId),
        "srcvers" to sourceVersion,
        "vv" to "2",
        "ov" to "11",
        "sf" to String.format(Locale.US, "0x%X", statusFlags),
        "am" to model,
        "md" to model,
        "gid" to Ap2Session.pairingId(deviceId),
    )

    /** DNS TXT record data: `<len><key=value>` repeated, which is what `txtAirPlay` carries. */
    fun encode(properties: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in properties) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            if (entry.size > 255) continue
            out.write(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }
}
