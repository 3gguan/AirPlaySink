package com.shilapi.airplaysink.ap2

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two primitives AirPlay 2 needs besides SHA-512: HKDF and ChaCha20-Poly1305.
 *
 * Both are deliberately taken from the platform rather than implemented here. Conscrypt's
 * ChaCha20-Poly1305 runs with the ARMv8 crypto extensions, and this code sits on the path of every
 * control message; a hand-rolled version would be both slower and a place to get the AEAD wrong.
 * It is API 28 and up, which the target head unit far exceeds.
 */
object Ap2Crypto {

    const val TAG_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32

    /** HKDF-SHA512 (RFC 5869), one-shot. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt, "HmacSHA512"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: String, info: String, length: Int): ByteArray =
        hkdf(ikm, salt.toByteArray(), info.toByteArray(), length)

    /**
     * The nonce every HAP frame uses: an eight-byte little-endian counter in the last eight bytes
     * of a twelve-byte nonce. Both shairport-sync and Apple's own receivers do exactly this.
     */
    fun nonce(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        for (index in 0 until 8) {
            nonce[4 + index] = ((counter ushr (index * 8)) and 0xFF).toByte()
        }
        return nonce
    }

    /** Returns ciphertext with the 16-byte tag appended, which is how HAP frames carry it. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(sealed)
    }

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)
}
