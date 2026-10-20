package com.shilapi.airplaysink.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.shilapi.airplaysink.raop.RaopServerConfig
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable
import java.util.Locale

/**
 * Advertises this device as an AirPlay 1 (RAOP / AirTunes) audio receiver.
 *
 * The announcement goes through the platform's own mDNS stack ([NsdManager], i.e. the system
 * `mdnsd`) instead of a second responder opened inside the app. That is not a style preference: on
 * Android the system already owns UDP 5353, and an app that opens its own multicast socket very
 * often ends up receiving nothing at all — the queries never reach it, so the device stays invisible
 * to the sender even though the app believes it published successfully. Going through `NsdManager`
 * puts us on exactly the same responder the rest of the device uses.
 *
 * Only `_raop._tcp` is published. That is the service iOS browses for audio-only AirPlay outputs,
 * and it is what a classic AirPlay 1 (RAOP) receiver looks like. `_airplay._tcp` is deliberately
 * *not* published: a device that advertises it is treated as an AirPlay 2 candidate, which means
 * pairing negotiation this receiver does not implement — and, more importantly here, iOS stops
 * sending the DACP headers that make the steering wheel keys work.
 *
 * The TXT record is the classic AirPort Express / AirTunes set. `et=0,1` says "no encryption or
 * RSA", which is what makes iOS wrap the session AES key with the AirPort Express public key, and
 * `cn=0,1` offers PCM and ALAC.
 */
class MdnsAdvertiser(
    context: Context,
    private val config: RaopServerConfig,
    private val onState: (name: String?, error: String?) -> Unit = { _, _ -> },
) : Closeable {

    private companion object {
        /** `NsdManager` wants the bare service type, without the `.local.` domain suffix. */
        const val SERVICE_TYPE = "_raop._tcp"

        /** Registration can lose a race with the system responder right after boot; retry a few times. */
        const val MAX_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 3000L
    }

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    @Volatile
    private var attempts = 0

    @Volatile
    private var listener: NsdManager.RegistrationListener? = null

    /** The name the platform actually published; it is renamed when another device claims it. */
    @Volatile
    var registeredName: String? = null
        private set

    private val properties: LinkedHashMap<String, String> = linkedMapOf(
        "txtvers" to "1",
        "ch" to "2",
        "cn" to "0,1",
        "et" to "0,1",
        "ek" to "1",
        "sv" to "false",
        "da" to "true",
        "sr" to "44100",
        "ss" to "16",
        "pw" to "false",
        "vn" to "65537",
        "tp" to "UDP",
        "md" to "0,2",
        "am" to config.model,
        "vs" to config.serverVersion,
        "sf" to String.format(Locale.US, "0x%X", config.statusFlags),
        "fv" to config.firmwareVersion,
    )

    /** Publishes the service. Registration is asynchronous; failures are retried, then reported. */
    fun start(port: Int) {
        if (closed) return

        val manager = nsd
        if (manager == null) {
            report(null, "this ROM has no NSD service")
            return
        }

        attempts++
        val requested = config.instanceName
        val service = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = requested
            this.port = port
            // Naming the address is not cosmetic, it is the whole reason this receiver was visible to
            // a phone on a hotspot. Left unset, the platform responder advertises *every* address the
            // device owns, and the sender picks one for us: on a phone hotspot that pick lands on the
            // global unicast address out of the phone's own cellular prefix, which the phone routes
            // over the radio and therefore cannot use to reach us. It opens the connection, gets the
            // SETUP answer, and abandons the session a millisecond later — measured 0 of 19, against
            // 21 of 21 for the sessions that arrived over link-local or a router-assigned address.
            // The advertised address has to be one both ends can reach, so pin it to the IPv4 the
            // sender is already able to see.
            // setHost rather than setHostAddresses: the latter is API 34 and this receiver has to run
            // on the head unit's Android 10, where setHost is still the only way to name an address.
            @Suppress("DEPRECATION")
            setHost(config.localAddress)
            for ((key, value) in properties) setAttribute(key, value)
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (closed || listener !== this) return
                val actual = info.serviceName ?: requested
                registeredName = actual
                if (actual != requested) {
                    SinkLog.i("mDNS: \"$requested\" was taken on this network; published as \"$actual\"")
                }
                // Report what we asked for, not what the callback carries: the NsdServiceInfo handed
                // to onServiceRegistered is only guaranteed to have the (possibly renamed) service
                // name filled in — serviceType and port come back empty or zero on many ROMs, which
                // used to make this line look like a broken registration.
                SinkLog.i(
                    "mDNS: published \"$actual\" as $SERVICE_TYPE on " +
                        "${config.localAddress.hostAddress}:$port",
                )
                SinkLog.i(
                    "mDNS TXT: " +
                        properties.entries.joinToString(" ") { "${it.key}=${it.value}" },
                )
                report(actual, null)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (closed || listener !== this) return
                SinkLog.w(
                    "mDNS: registration failed (code $errorCode), attempt $attempts/$MAX_ATTEMPTS",
                )
                retryOrFail(port, "registration failed (code $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                SinkLog.i("mDNS: unregistered \"${info.serviceName}\"")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                SinkLog.w("mDNS: could not unregister (code $errorCode)")
            }
        }

        listener = registration
        runCatching { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure { error ->
                if (listener === registration) listener = null
                SinkLog.w("mDNS: registerService threw: ${error.message}")
                retryOrFail(port, error.message ?: "registerService threw")
            }
    }

    private fun retryOrFail(port: Int, reason: String) {
        if (closed) return
        if (attempts < MAX_ATTEMPTS) {
            main.postDelayed({ if (!closed) start(port) }, RETRY_DELAY_MS)
            return
        }
        report(null, reason)
    }

    private fun report(name: String?, error: String?) {
        runCatching { onState(name, error) }
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)

        val registration = listener
        listener = null
        registeredName = null

        val manager = nsd
        if (manager != null && registration != null) {
            runCatching { manager.unregisterService(registration) }
        }
        SinkLog.i("mDNS: advertisement withdrawn")
    }
}
