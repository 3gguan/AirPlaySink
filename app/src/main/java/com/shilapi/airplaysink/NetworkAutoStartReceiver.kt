package com.shilapi.airplaysink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.shilapi.airplaysink.util.SinkLog

/**
 * Brings the receiver back up when the head unit (re)joins a Wi-Fi network and the process died
 * in the meantime — the case "一键清理" or the system killed the process while the screen was off.
 *
 * Why Wi-Fi state and not CONNECTIVITY_CHANGE: manifest-declared CONNECTIVITY_CHANGE receivers
 * stopped firing on Android 7+, while `android.net.wifi.STATE_CHANGE` still reaches manifest
 * receivers on the Android 10 this firmware runs. The service itself watches finer-grained
 * network events through ConnectivityManager while it is alive; this receiver only covers the
 * "nothing is alive" case.
 *
 * One honest limitation: if the head unit truly force-stops the app (not just kills the process),
 * Android stops delivering broadcasts to it entirely and nothing short of the user reopening the
 * app can revive it. START_STICKY covers plain process deaths, this receiver covers process
 * deaths that happen while nothing else is watching; together they close every case short of a
 * real force-stop.
 */
class NetworkAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return

        val preferences = SinkPreferences(context)
        if (!preferences.serviceEnabled) {
            // The user turned the receiver off through the switch; stay off.
            return
        }
        if (SinkService.isActive) {
            return
        }

        SinkLog.i("Network came up and the receiver was enabled; starting it again")
        runCatching { SinkService.start(context) }
            .onFailure { SinkLog.e("Could not restart the receiver on network up", it) }
    }
}
