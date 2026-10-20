package com.shilapi.airplaysink.ap2

import com.shilapi.airplaysink.raop.RaopServerConfig
import com.shilapi.airplaysink.rtsp.RtspRequest
import com.shilapi.airplaysink.rtsp.RtspResponse
import com.shilapi.airplaysink.util.SinkLog
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * The AirPlay 2 half of a control connection: `/info`, transient HAP pairing and the FairPlay
 * handshake iOS insists on before it will talk about audio formats.
 *
 * Everything here rides on the connection the sender already opened for RAOP. Probing on a real
 * iPhone (2026-10-20) showed iOS asking `GET /info` and then `POST /pair-setup` on port 5000, never
 * touching the `_airplay._tcp` port — so there is no second server, just more paths.
 *
 * Only transient pairing is handled, and that is a deliberate simplification: it needs SRP and a
 * hash, no Ed25519 key pair, no pin to show. shairport-sync configures its control cipher straight
 * after M4 in this mode and never sets one up from `/pair-verify` (that block is commented out in
 * `rtsp.c`), which is the same shape as here.
 *
 * The advertised identity is deliberately shairport-sync's: `model=ShairportSync`,
 * `sourceVersion=366.0` and its feature mask are a combination Apple's senders accept, while the
 * `AudioAccessory5,1` (HomePod mini) identity this used to claim invites expectations — PTP timing,
 * HomeKit access control — that this receiver cannot meet.
 */
class Ap2Session(
    private val config: RaopServerConfig,
    /** `ip:port` of the sender, reported back as `senderAddress`. */
    private val sender: () -> String = { "" },
) {

    companion object {
        const val INFO_PATH = "/info"
        const val PAIR_SETUP_PATH = "/pair-setup"
        const val PAIR_VERIFY_PATH = "/pair-verify"
        const val FP_SETUP_PATH = "/fp-setup"

        /** Transient pairing's well-known code; iOS sorts out the rest on its own. */
        private const val TRANSIENT_PIN = "3939"
        private const val SRP_USERNAME = "Pair-Setup"

        /**
         * The feature mask shairport-sync ships for AirPlay 2 audio, minus every video capability.
         *
         * Bits 46/47 are the HomeKit pairing pair and 40 the access-control bit; 38 is legacy
         * pairing, 14 FairPlay. Two bits this receiver used to set are deliberately absent: 16/17
         * (classic text metadata) and 22. A working receiver's mask is worth more than a
         * hand-derived one — a sender that cannot match what it knows switches to another protocol
         * rather than reporting which bit it disliked.
         */
        const val FEATURES: Long = 0x0001C340401C4A00L
        const val FEATURES_LOW: Long = FEATURES and 0xFFFF_FFFFL
        const val FEATURES_HIGH: Long = (FEATURES ushr 32) and 0xFFFF_FFFFL

        const val SOURCE_VERSION = "366.0"
        const val MODEL = "ShairportSync"

        /** ALAC / 44100 / signed 16-bit / stereo — the only thing this sink's decoder handles. */
        private const val AUDIO_STREAM_FORMATS = 0x40000L

        /** `pi` is a stable UUID derived from the device id, the same one the TXT record carries. */
        fun pairingId(deviceId: String): String = UUID.nameUUIDFromBytes(
            MessageDigest.getInstance("SHA-256").digest("ap2:$deviceId".toByteArray()).copyOf(16),
        ).toString()

        /**
         * The accessory's "public key" from the TXT record and `/info`, 32 bytes.
         *
         * Transient pairing never uses it — there is no signature to check — but the two records
         * still have to agree, so both take it from here. A real device would use its Ed25519 key.
         */
        fun publicKey(deviceId: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("ap2-pk:$deviceId".toByteArray())

        fun deviceIdWithColons(deviceId: String): String =
            deviceId.chunked(2).joinToString(":").uppercase(Locale.US)

        /**
         * FairPlay v3 setup replies, copied byte for byte out of shairport-sync's `rtsp.c`.
         *
         * They are not derived from anything: the sender only checks that the reply is a plausible
         * FPLY record of the right length, so a fixed table is what every third-party receiver
         * ships. The first reply is selected by the request's mode byte, the second by sequence.
         */
        private val FP_REPLY = arrayOf(
            "46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d" +
                "22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953ea" +
                "b81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6" +
                "d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba",
            "46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0a" +
                "d67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cce" +
                "afc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a" +
                "44d9591c7256b9fbe6513898b80227721988571650942ad946688a",
            "46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d" +
                "4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7" +
                "d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2e" +
                "cd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce",
            "46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e9" +
                "3045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a" +
                "17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e" +
                "4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359",
        )

        /** `FPLY 03 01 04 00 00000014` — the header of the second setup exchange. */
        private const val FP_HEADER = "46504c590301040000000014"

        /** The second exchange echoes the last 20 bytes of the request after that header. */
        private const val FP_SUFFIX = 20

        private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) {
            text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private var srp: SrpServer? = null

    /**
     * Handed to the connection after the pairing reply: the key the control channel is encrypted
     * with. Cleared by [takePendingSharedSecret] so it is applied exactly once.
     */
    private var pendingSharedSecret: ByteArray? = null

    private val txtProperties: LinkedHashMap<String, String> by lazy {
        Ap2Txt.properties(
            config.deviceId, MODEL, SOURCE_VERSION, config.statusFlags, FEATURES,
        )
    }

    fun handles(uri: String): Boolean = when (uri) {
        INFO_PATH, PAIR_SETUP_PATH, PAIR_VERIFY_PATH, FP_SETUP_PATH -> true
        else -> false
    }

    fun handle(request: RtspRequest): RtspResponse = when (request.uri) {
        INFO_PATH -> info(request)
        PAIR_SETUP_PATH -> pairSetup(request)
        FP_SETUP_PATH -> fpSetup(request)
        PAIR_VERIFY_PATH -> {
            // Transient pairing has no long-term identity to verify, so a sender that asks for this
            // is on the wrong path; 470 is what shairport-sync answers when it cannot proceed.
            SinkLog.w("AP2: /pair-verify asked for; transient pairing has no keys to verify")
            RtspResponse(470)
        }
        else -> RtspResponse(404)
    }

    fun takePendingSharedSecret(): ByteArray? {
        val secret = pendingSharedSecret
        pendingSharedSecret = null
        return secret
    }

    // ------------------------------------------------------------------ /info

    /**
     * Answers the sender's "tell me about yourself" with the same facts the TXT record carries.
     *
     * iOS asks with a plist naming what it wants (`qualifier: ["txtAirPlay"]`), but the reply is the
     * full device description — that is what every implementation returns and what iOS parses. The
     * field set here is shairport-sync's, because it is the one known to be accepted: a missing
     * `supportedFormats` or `txtAirPlay` is not reported anywhere, it just makes the sender decide
     * this device cannot carry audio.
     */
    private fun info(request: RtspRequest): RtspResponse {
        SinkLog.i(
            "AP2: /info from ${config.deviceName} client, ${request.content.size}B " +
                "(${request.contentType})",
        )
        if (request.content.isNotEmpty()) {
            SinkLog.i("AP2:   /info request: ${request.content.hex(160)}")
        }

        val body = BPlist.write(infoPlist())
        SinkLog.i("AP2: /info answered with ${body.size}B bplist")
        return RtspResponse(200)
            .addHeader("Content-Type", "application/x-apple-binary-plist")
            .also { it.content = body }
    }

    /**
     * The device description, shared by two callers: the `/info` reply and the `updateInfo` message
     * the event channel opens with.
     *
     * They carry the same dictionary — shairport-sync builds the event-channel one from the same
     * `generateInfoPlist` and only drops the reply-only extras — so keeping one builder is what
     * makes the two impossible to drift apart.
     */
    fun infoPlist(): Map<String, Any?> {
        val deviceId = deviceIdWithColons(config.deviceId)
        return linkedMapOf<String, Any?>(
            "deviceID" to deviceId,
            "features" to FEATURES,
            "featuresEx" to Ap2Txt.featuresEx(FEATURES),
            "statusFlags" to config.statusFlags.toLong(),
            "pi" to pairingId(config.deviceId),
            "psi" to pairingId(config.deviceId),
            "name" to config.deviceName,
            "model" to MODEL,
            "protocolVersion" to "1.1",
            "sourceVersion" to SOURCE_VERSION,
            // Must be the same 32 bytes the TXT record publishes as `pk`; a sender that compares
            // the two will not talk to a device that leaves one of them out.
            "pk" to publicKey(config.deviceId),
            "vv" to 2L,
            "initialVolume" to 0.0,
            "volumeControlType" to 3L,
            "senderAddress" to sender(),
            // What the sender is allowed to send for a buffered stream, and what it can send live.
            // Only ALAC/16/44.1 is listed: the decoder is ALAC-only, so naming AAC here would just
            // talk the sender into a format that arrives as noise.
            "supportedFormats" to linkedMapOf<String, Any?>(
                "audioStream" to AUDIO_STREAM_FORMATS,
                "bufferStream" to AUDIO_STREAM_FORMATS,
            ),
            "playbackCapabilities" to linkedMapOf<String, Any?>(
                "supportsInterstitials" to false,
                "supportsFPSSecureStop" to false,
                "supportsUIForAudioOnlyContent" to false,
            ),
            "canRecordScreenStream" to false,
            "keepAliveSendStatsAsBody" to false,
            "screenDemoMode" to false,
            "receiverHDRCapability" to "4k60",
            // The TXT record again, in the wire form iOS expects. Sending the plist without it
            // leaves the sender with two disagreeing descriptions of the same device.
            "txtAirPlay" to Ap2Txt.encode(txtProperties),
        )
    }

    // ------------------------------------------------------------------ /fp-setup

    /**
     * FairPlay v3 setup: two exchanges, answered from a fixed table.
     *
     * iOS runs this before it will discuss audio whenever the feature mask advertises FairPlay
     * (bit 14). The replies carry no information — a sender only checks that the shape is right —
     * so there is nothing to compute here, and answering at all is what keeps the sender from
     * treating the device as unable to protect a stream.
     */
    private fun fpSetup(request: RtspRequest): RtspResponse {
        val body = request.content
        val sequence = body.getOrNull(6)?.toInt()?.and(0xFF) ?: -1
        val mode = body.getOrNull(14)?.toInt()?.and(0xFF) ?: 0
        SinkLog.i("AP2: /fp-setup sequence=$sequence mode=$mode (${body.size}B)")

        val reply = when (sequence) {
            1 -> FP_REPLY[mode.coerceIn(0, FP_REPLY.size - 1)].let(::unhex)
            3 -> if (body.size < FP_SUFFIX) {
                ByteArray(0)
            } else {
                unhex(FP_HEADER) + body.copyOfRange(body.size - FP_SUFFIX, body.size)
            }
            else -> ByteArray(0)
        }
        if (reply.isEmpty()) {
            SinkLog.w("AP2: /fp-setup sequence $sequence cannot be answered")
        }
        return RtspResponse(200)
            .addHeader("Content-Type", "application/octet-stream")
            .also { it.content = reply }
    }

    // ------------------------------------------------------------------ pair-setup

    private fun pairSetup(request: RtspRequest): RtspResponse {
        val tlv = Tlv8.decode(request.content)
        val state = tlv[Tlv8.STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        val method = tlv[Tlv8.METHOD]?.firstOrNull()?.toInt()?.and(0xFF)
        val flags = tlv[Tlv8.FLAGS]?.let { bytes ->
            bytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        }
        SinkLog.i(
            "AP2: pair-setup M$state method=$method flags=${flags?.let { "0x%02x".format(it) } ?: "-"} " +
                "(${request.content.size}B TLV: ${request.content.hex(64)})",
        )
        if (state == Tlv8.STATE_M3) {
            SinkLog.i("AP2:   M3 raw=${request.content.hex(600)}")
        }

        return when (state) {
            Tlv8.STATE_M1 -> pairSetupM1(flags ?: 0)
            Tlv8.STATE_M3 -> pairSetupM3(tlv)
            else -> {
                SinkLog.w("AP2: unexpected pair-setup state M$state")
                tlvResponse(listOf(Tlv8.state(Tlv8.STATE_M2), Tlv8.u8(Tlv8.ERROR, 0x01)))
            }
        }
    }

    /**
     * M1 → M2: start SRP. The reply carries the salt and `B`, nothing else; the transient flag is
     * not echoed back by Apple's accessories and iOS does not require it.
     */
    private fun pairSetupM1(flags: Int): RtspResponse {
        if (flags and Tlv8.FLAG_TRANSIENT == 0) {
            SinkLog.w(
                "AP2: pair-setup without the transient flag (0x%02x); a pin would be needed, " +
                    "which this receiver does not have".format(flags),
            )
        }
        val server = SrpServer(SRP_USERNAME, TRANSIENT_PIN)
        srp = server
        SinkLog.i("AP2: pair-setup M1 → M2 (salt 16B, B ${server.publicKey.size}B, transient pairing)")
        // Both values are already on the wire, and having them lets a failed M4 be recomputed
        // offline instead of guessed at.
        SinkLog.i("AP2:   salt=${server.salt.hex(64)} B=${server.publicKey.toHex()}")
        return tlvResponse(
            listOf(
                Tlv8.state(Tlv8.STATE_M2),
                Tlv8.SALT to server.salt,
                Tlv8.PUBLIC_KEY to server.publicKey,
            ),
        )
    }

    /** M3 → M4: check the sender's proof and hand it ours; the session key follows from SRP. */
    private fun pairSetupM3(tlv: Map<Int, ByteArray>): RtspResponse {
        val server = srp
        if (server == null) {
            SinkLog.w("AP2: pair-setup M3 without M1; restarting is the sender's only cure")
            return tlvResponse(listOf(Tlv8.state(Tlv8.STATE_M4), Tlv8.u8(Tlv8.ERROR, 0x01)))
        }

        val clientPublic = tlv[Tlv8.PUBLIC_KEY]
        val clientProof = tlv[Tlv8.PROOF]
        if (clientPublic == null || clientProof == null) {
            SinkLog.w("AP2: pair-setup M3 without a public key or proof")
            return tlvResponse(listOf(Tlv8.state(Tlv8.STATE_M4), Tlv8.u8(Tlv8.ERROR, 0x01)))
        }

        val serverProof = server.verify(clientPublic, clientProof)
        if (serverProof == null) {
            SinkLog.w("AP2: pair-setup M3 proof rejected — the sender used a different setup code")
            return tlvResponse(listOf(Tlv8.state(Tlv8.STATE_M4), Tlv8.u8(Tlv8.ERROR, 0x02)))
        }

        pendingSharedSecret = server.sessionKey
        SinkLog.i(
            "AP2: pair-setup complete — shared key ${server.sessionKey?.size} bytes; " +
                "the next request will be encrypted",
        )
        val body = Tlv8.encode(
            listOf(Tlv8.state(Tlv8.STATE_M4), Tlv8.PROOF to serverProof),
        )
        // The M4 body is the one thing a sender can silently reject, and a rejection looks exactly
        // like a closed connection, so keep the actual bytes on record.
        SinkLog.i("AP2: pair-setup M4 → ${body.size}B TLV: ${body.hex(96)}")
        return RtspResponse(200)
            .addHeader("Content-Type", "application/octet-stream")
            .also { it.content = body }
    }

    /**
     * Pairing replies are always `application/octet-stream`, whatever the request claimed.
     *
     * iOS sends its own as `application/x-apple-binary-plist`, which looks like an invitation to
     * echo it back — and echoing it means the next parser in the chain is handed a TLV block that
     * is not a plist. Real accessories answer with the TLV type regardless.
     */
    private fun tlvResponse(values: List<Pair<Int, ByteArray>>): RtspResponse =
        RtspResponse(200)
            .addHeader("Content-Type", "application/octet-stream")
            .also { it.content = Tlv8.encode(values) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Hex dump capped at [max] bytes; pairing messages are small but the log is shared with audio. */
    private fun ByteArray.hex(max: Int): String {
        val shown = size.coerceAtMost(max)
        val text = (0 until shown).joinToString(" ") {
            String.format(Locale.US, "%02x", this[it])
        }
        return if (size > max) "$text … (${size - max} more)" else text
    }
}
