package com.shilapi.airplaysink.crypto

import com.shilapi.airplaysink.util.SinkLog
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two cryptographic operations classic AirPlay needs.
 *
 * 1. `ANNOUNCE` carries `a=rsaaeskey:` — a 16 byte AES key, RSA encrypted with the AirPort Express
 *    public key. Apple's own implementation uses RSA-OAEP with SHA-1; shairport-sync decrypts with
 *    `RSA_PKCS1_OAEP_PADDING` (OpenSSL) / `MBEDTLS_RSA_PKCS_V21 + MBEDTLS_MD_SHA1` (mbedTLS). Some
 *    older senders use PKCS#1 v1.5 instead, so both are attempted.
 *
 * 2. Audio packets are AES-128-CBC. Note the unusual-but-confirmed layout used by every RAOP
 *    receiver: the IV is the *fixed* `a=aesiv:` value from the ANNOUNCE for every packet, and only
 *    the part of the payload that is a multiple of 16 bytes is decrypted — a trailing remainder is
 *    copied through untouched.
 */
object RaopCrypto {

    private const val AES_BLOCK = 16

    private val privateKey: PrivateKey by lazy { AirPortKey.privateKey() }

    /**
     * Unwraps `a=rsaaeskey:`. Returns the 16 byte session key, or null when the value cannot be
     * decrypted with either padding scheme.
     */
    fun decryptSessionKey(rsaAesKey: ByteArray): ByteArray? {
        val attempts = listOf(
            "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
            "RSA/ECB/PKCS1Padding",
        )
        for (transformation in attempts) {
            val result = runCatching {
                val cipher = Cipher.getInstance(transformation)
                cipher.init(Cipher.DECRYPT_MODE, privateKey)
                cipher.doFinal(rsaAesKey)
            }
            val key = result.getOrNull()
            if (key != null) {
                if (key.size == AES_BLOCK) return key
                SinkLog.w("RSA session key via $transformation has ${key.size} bytes, expected 16")
            } else {
                SinkLog.w("RSA session key via $transformation failed: ${result.exceptionOrNull()?.message}")
            }
        }
        return null
    }

    /**
     * Decrypts one RTP payload in place-equivalent fashion. [out] must be at least [length] bytes.
     */
    fun decryptAudioPayload(payload: ByteArray, offset: Int, length: Int, key: ByteArray, iv: ByteArray, out: ByteArray) {
        val aesLength = length and 0xF.inv() // length & ~0xf
        if (aesLength > 0) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val body = cipher.doFinal(payload, offset, aesLength)
            System.arraycopy(body, 0, out, 0, aesLength)
        }
        // The trailing sub-block remainder is never encrypted.
        if (length > aesLength) {
            System.arraycopy(payload, offset + aesLength, out, aesLength, length - aesLength)
        }
    }
}
