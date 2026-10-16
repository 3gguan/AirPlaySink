package com.shilapi.airplaysink.crypto

import com.shilapi.airplaysink.util.Codec
import com.shilapi.airplaysink.util.SinkLog
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Cipher

/**
 * Apple's `Apple-Challenge` / `Apple-Response` exchange.
 *
 * A classic AirPlay request may carry an `Apple-Challenge` header holding up to 16 random bytes. The
 * receiver signs `challenge || its own address || a 6 byte hardware address`, zero padded to at
 * least 32 bytes, and answers with the result in an `Apple-Response` header. shairport-sync runs this
 * on *every* request, before dispatching the method, which is a strong hint that the sender expects
 * it before it will commit to a session.
 *
 * The signature is a bare PKCS#1 v1.5 private-key operation — no digest is involved. In JCA terms
 * that is `Cipher` + `ENCRYPT_MODE` with a private key, mirroring OpenSSL's
 * `RSA_private_encrypt(..., RSA_PKCS1_PADDING)`. The reply is Base64 with the `=` padding stripped,
 * matching the dialect Apple uses elsewhere in the protocol.
 */
object AppleChallenge {

    /** Apple never sends more than this, and shairport-sync refuses anything longer. */
    private const val MAX_CHALLENGE_BYTES = 16

    /** The signed blob is zero padded up to this length. */
    private const val MIN_PAYLOAD_BYTES = 0x20

    private const val HARDWARE_ADDRESS_BYTES = 6

    /**
     * The 6 byte hardware address in the signed blob.
     *
     * Classic RAOP publishes the instance as `<12 hex digits>@<display name>`, and the sender treats
     * those first six bytes as *the* hardware address of the receiver — the same value it read out
     * of the `_raop._tcp` name it browsed. Signing a different value is signing a device the sender
     * never talked to, so the digits from the published name are used verbatim and only a name that
     * cannot supply them falls back to an MD5 of the whole string.
     */
    fun hardwareAddressFor(serviceName: String): ByteArray {
        val label = serviceName.substringBefore('@').replace(":", "").replace("-", "").trim()
        if (label.length >= HARDWARE_ADDRESS_BYTES * 2) {
            val bytes = ByteArray(HARDWARE_ADDRESS_BYTES)
            var valid = true
            for (i in 0 until HARDWARE_ADDRESS_BYTES) {
                val value = label.substring(i * 2, i * 2 + 2).toIntOrNull(16)
                if (value == null) {
                    valid = false
                    break
                }
                bytes[i] = value.toByte()
            }
            if (valid) return bytes
        }
        return MessageDigest.getInstance("MD5")
            .digest(serviceName.toByteArray(Charsets.UTF_8))
            .copyOf(HARDWARE_ADDRESS_BYTES)
    }

    /**
     * Returns the `Apple-Response` value for [challengeBase64], or null when the header is unusable.
     * [localAddress] is the address the sender reached us on.
     */
    fun respond(
        challengeBase64: String,
        localAddress: InetAddress,
        hardwareAddress: ByteArray,
    ): String? {
        val challenge = runCatching { Codec.base64Decode(challengeBase64) }.getOrNull()
        if (challenge == null || challenge.size > MAX_CHALLENGE_BYTES) {
            SinkLog.w("Apple-Challenge is unusable (${challenge?.size ?: -1} bytes); not answering it")
            return null
        }

        val address = localAddress.address
        val payload = ByteArray(
            maxOf(MIN_PAYLOAD_BYTES, challenge.size + address.size + hardwareAddress.size),
        )
        System.arraycopy(challenge, 0, payload, 0, challenge.size)
        System.arraycopy(address, 0, payload, challenge.size, address.size)
        System.arraycopy(
            hardwareAddress,
            0,
            payload,
            challenge.size + address.size,
            minOf(HARDWARE_ADDRESS_BYTES, hardwareAddress.size),
        )

        return runCatching {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, AirPortKey.privateKey())
            Codec.base64Encode(cipher.doFinal(payload)).trimEnd('=')
        }.onSuccess { response ->
            // Worth logging: a sender that rejects this answer hangs up silently, and the only
            // visible symptom is "it connects and then nothing plays".
            SinkLog.i(
                "Apple-Response: challenge ${challenge.size}B + address " +
                    "${localAddress.hostAddress} (${address.size}B) + hw " +
                    "${hardwareAddress.take(HARDWARE_ADDRESS_BYTES).joinToString("") { "%02x".format(it) }} " +
                    "-> ${payload.size}B signed, ${response.length} chars",
            )
        }.getOrElse { error ->
            SinkLog.w("Could not sign Apple-Challenge: ${error.message}")
            null
        }
    }
}
