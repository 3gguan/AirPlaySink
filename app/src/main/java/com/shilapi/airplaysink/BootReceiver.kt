package com.shilapi.airplaysink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shilapi.airplaysink.util.SinkLog

/**
 * Brings the receiver back up after the head unit reboots, when the user asked for it.
 *
 * `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` are both explicitly exempt from the
 * Android 12+ background foreground-service start restrictions, so starting the service from here
 * is allowed without any further ceremony.
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT,
            ACTION_HTC_QUICKBOOT,
            -> Unit

            else -> return
        }

        val preferences = SinkPreferences(context)
        if (!preferences.startOnBoot) {
            SinkLog.i("Boot finished, but auto start is off")
            return
        }
        if (SinkService.isActive) {
            SinkLog.i("Boot finished, but the receiver is already running")
            return
        }

        SinkLog.i("Boot finished; starting the AirPlay receiver")
        runCatching { SinkService.start(context) }
            .onFailure { SinkLog.e("Could not start the receiver after boot", it) }
    }
}
