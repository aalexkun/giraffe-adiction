package com.example.giraffeaddiction

import android.app.Application
import android.os.Build
import com.example.giraffeaddiction.service.Notifications
import org.lsposed.hiddenapibypass.HiddenApiBypass

class GiraffeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Lifts the hidden-API restrictions in this process; the worker does the same for its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        Graph.init(this)
        Notifications.createChannels(this)
    }
}
