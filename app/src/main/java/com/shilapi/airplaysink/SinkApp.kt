package com.shilapi.airplaysink

import android.app.Application
import android.os.Build
import com.shilapi.airplaysink.util.SinkLog
import java.io.File

/**
 * Initialises file logging before anything else in the process runs, so boot-time receiver logs
 * land in the same file as everything else.
 */
class SinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // External files dir is readable over adb and from the head unit's file manager without
        // any permission; fall back to internal storage on the rare device where it is null.
        val dir = getExternalFilesDir(null) ?: filesDir
        SinkLog.init(File(dir, "sink.log"), Build.VERSION.RELEASE)
    }
}
