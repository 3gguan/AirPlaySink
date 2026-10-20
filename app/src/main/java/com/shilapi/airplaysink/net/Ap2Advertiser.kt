package com.shilapi.airplaysink.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.shilapi.airplaysink.ap2.Ap2Session
import com.shilapi.airplaysink.ap2.Ap2Txt
import com.shilapi.airplaysink.raop.RaopServerConfig
import com.shilapi.airplaysink.util.SinkLog
import java.io.Closeable

/**
 * Publishes the `_airplay._tcp` record that makes iOS treat this device as an AirPlay 2 target.
 *
 * There is no second listener behind it: AirPlay 2 runs on the RTSP port RAOP already owns —
 * `/info`, `/pair-setup` and the plist `SETUP` are paths on that connection — so this class only
 * advertises, and always points at the control port.
 *
 * That port matters. iOS merges the `_airplay` and `_raop` records that share a device id: it takes
 * the connection details from `_raop` and decides the generation from this record's TXT. Pointing
 * it anywhere else would advertise an address the sender then opens and finds nothing on.
 */
class Ap2Advertiser(
    context: Context,
    private val config: RaopServerConfig,
) : Closeable {

    companion object {
        const val SERVICE_TYPE = "_airplay._tcp"
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    @Volatile
    private var registration: NsdManager.RegistrationListener? = null

    /**
     * Publishes the record with the TXT of an audio-only speaker.
     *
     * `features` is the part iOS reads before it commits: the low word enables audio (9), ALAC (19),
     * AAC (20), unencrypted audio (22) and metadata (16, 17); the high word enables buffered
     * streaming (40), transient pairing (48) and the FairPlay bit (14) without which iOS pairs and
     * then hangs up. No video bit is set anywhere, which is what keeps this a speaker rather than a
     * screen target.
     *
     * `pk` is shared with `/info` — [Ap2Txt.properties] builds both from the same functions, so the
     * two can never disagree about what this device is.
     */
    fun start(controlPort: Int) {
        val manager = nsd
        if (manager == null) {
            SinkLog.w("AP2: this ROM has no NSD service; only the RAOP record will be published")
            return
        }

        val attributes = Ap2Txt.properties(
            deviceId = config.deviceId,
            model = Ap2Session.MODEL,
            sourceVersion = Ap2Session.SOURCE_VERSION,
            statusFlags = config.statusFlags,
            features = Ap2Session.FEATURES,
        )

        val service = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = config.instanceName
            port = controlPort
            for ((key, value) in attributes) setAttribute(key, value)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                SinkLog.i("AP2: published \"${info.serviceName}\" as $SERVICE_TYPE on port $controlPort")
                SinkLog.i(
                    "AP2 TXT: " + attributes.entries.joinToString(" ") { "${it.key}=${it.value}" },
                )
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                SinkLog.w("AP2: registration failed (code $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                SinkLog.i("AP2: advertisement withdrawn")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                SinkLog.w("AP2: could not unregister (code $errorCode)")
            }
        }
        registration = listener
        runCatching { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { SinkLog.w("AP2: registerService threw: ${it.message}") }
    }

    /**
     * Withdraws the record. No goodbye packet is sent here: [RaopReceiver] sends one on the next
     * start when AirPlay 2 is off, which is the case that actually matters — a sender that cached
     * this record keeps talking AirPlay 2 to a receiver that no longer serves it. Sending it from
     * both places would race the fresh announcement of the next run.
     */
    override fun close() {
        val listener = registration
        registration = null
        val manager = nsd
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
        SinkLog.i("AP2: advertisement withdrawn")
    }
}
