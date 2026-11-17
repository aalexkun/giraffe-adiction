package com.example.giraffeaddiction.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the service back after a reboot or an update, and re-checks the day after a clock or
 * time-zone change. The service itself restores every app's state from the database.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PauserForegroundService.ACTION_START
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> PauserForegroundService.ACTION_RESET
            else -> return
        }
        PauserForegroundService.start(context, action)
    }
}
