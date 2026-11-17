package com.example.giraffeaddiction.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Hands every alarm to the service, which is where the clock logic lives. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PauserForegroundService.start(context, intent.action ?: PauserForegroundService.ACTION_START)
    }
}
