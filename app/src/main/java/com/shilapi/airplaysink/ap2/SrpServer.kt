package com.shilapi.airplaysink.ap2

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a, server side, exactly as HAP's transient pairing uses it.
 *
 * AirPlay 2 speakers pair with "transient" pairing: the sender sets the transient flag in M1, the
 * accessory never displays a code, and both sides use the well-known code `3939`. It is still full
 * SRP — the code just is not secret — and the resulting session key (SHA-512 output, 64 bytes) is
 * what encrypts the control channel afterwards. There is no M5/M6 exchange in this mode; see
 * shairport-sync's `handle_pair_setup`, which configures the cipher straight after M4.
 *
 * The arithmetic follows csrp/pair_ap so the proofs match bit for bit:
 *
 *  - `k = H(PAD(N) ‖ PAD(g))`, `u = H(PAD(A) ‖ PAD(B))` — padded to the group size, 384 bytes;
 *  - `x = H(salt ‖ H(user ":" password))`, `S = (A · v^u)^b mod N`;
 *  - `K = H(S)` over the *minimal* big-endian form of S, not padded;
 *  - `M1 = H(H(N) ⊕ H(g) ‖ H(I) ‖ salt ‖ A ‖ B ‖ K)`, again all minimal.
 *
 * That last detail is where naive SRP implementations diverge from Apple: RFC 5054 pads A and B in
 * M1, csrp does not, and HAP clients follow csrp.
 */
class SrpServer(
    private val username: String,
    private val password: String,
) {

    companion object {
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B" +
                "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485" +
                "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F" +
                "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23" +
                "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32" +
                "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558" +
                "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521" +
                "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7" +
                "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817" +
                "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82" +
                "D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val G = BigInteger.valueOf(5)
        private const val N_BYTES = 384

        private val random = SecureRandom()

        private fun sha512(vararg chunks: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            for (chunk in chunks) digest.update(chunk)
            return digest.digest()
        }

        /** Big-endian bytes without the sign byte `BigInteger.toByteArray()` adds. */
        private fun minimal(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }

        private fun padded(value: BigInteger, length: Int = N_BYTES): ByteArray {
            val raw = minimal(value)
            if (raw.size >= length) return raw
            val out = ByteArray(length)
            System.arraycopy(raw, 0, out, length - raw.size, raw.size)
            return out
        }

        private fun xor(left: ByteArray, right: ByteArray): ByteArray {
            val out = ByteArray(minOf(left.size, right.size))
            for (index in out.indices) out[index] = (left[index].toInt() xor right[index].toInt()).toByte()
            return out
        }
    }

    /**
     * 16 random bytes with the top bit forced, so the salt is always 16 bytes once converted to a
     * number. A leading zero byte would otherwise shorten it and change `x` on one side only.
     */
    val salt: ByteArray = ByteArray(16).also {
        random.nextBytes(it)
        it[0] = (it[0].toInt() or 0x80).toByte()
    }

    private val k = BigInteger(1, sha512(padded(N), padded(G)))
    private val x = BigInteger(1, sha512(salt, sha512("$username:$password".toByteArray())))
    private val verifier = G.modPow(x, N)
    private val b = BigInteger(256, random)

    /** `B`, sent as the minimal big-endian form — csrp sends `bnum_num_bytes`, not padded. */
    val publicKey: ByteArray = minimal(k.multiply(verifier).add(G.modPow(b, N)).mod(N))

    /** Set once [verify] succeeds; this is the 64-byte key the control channel is encrypted with. */
    var sessionKey: ByteArray? = null
        private set

    /**
     * Checks the sender's proof and returns the server proof (`M2`) to send back, or null when the
     * sender is not who it claims to be.
     */
    fun verify(clientPublic: ByteArray, clientProof: ByteArray): ByteArray? {
        if (clientPublic.isEmpty()) return null
        val a = BigInteger(1, clientPublic)
        if (a.mod(N).signum() == 0) return null

        val bigB = BigInteger(1, publicKey)
        val u = BigInteger(1, sha512(padded(a), padded(bigB)))
        if (u.signum() == 0) return null

        val s = a.multiply(verifier.modPow(u, N)).mod(N).modPow(b, N)
        val key = sha512(minimal(s))

        val expected = sha512(
            xor(sha512(minimal(N)), sha512(minimal(G))),
            sha512(username.toByteArray()),
            minimal(BigInteger(1, salt)),
            minimal(a),
            minimal(bigB),
            key,
        )
        if (!MessageDigest.isEqual(expected, clientProof)) return null

        sessionKey = key
        return sha512(minimal(a), expected, key)
    }
}
