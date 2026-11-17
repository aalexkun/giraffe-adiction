package com.example.giraffeaddiction.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.example.giraffeaddiction.R
import com.example.giraffeaddiction.data.Settings
import com.example.giraffeaddiction.ui.MainActivity

/** The two notifications this app ever shows: the service's own, and "Shizuku stopped". */
class Notifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun serviceNotification(title: String, text: String, overrideActive: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_giraffe)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (overrideActive) {
            builder.addAction(action("Resume VPN automation", 0L, 10))
        } else {
            builder.addAction(action("Pause VPN 30 min", 30L * 60_000L, 11))
            builder.addAction(action("Pause 2 h", 2L * 60L * 60_000L, 12))
            builder.addAction(action("Until next WiFi", Settings.OVERRIDE_UNTIL_RECONNECT, 13))
        }
        return builder.build()
    }

    private fun action(label: String, overrideFor: Long, code: Int): Notification.Action {
        val until = when (overrideFor) {
            0L -> 0L
            Settings.OVERRIDE_UNTIL_RECONNECT -> Settings.OVERRIDE_UNTIL_RECONNECT
            else -> System.currentTimeMillis() + overrideFor
        }
        val intent = Intent(context, PauserForegroundService::class.java)
            .setAction(PauserForegroundService.ACTION_OVERRIDE)
            .putExtra(PauserForegroundService.EXTRA_OVERRIDE_UNTIL, until)
        val pending = PendingIntent.getForegroundService(
            context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null as Icon?, label, pending).build()
    }

    fun showShizukuDown(detail: String) {
        val shizuku = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(context, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            context, 1, shizuku.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_giraffe)
            .setContentTitle("Shizuku stopped")
            .setContentText("Paused apps stay paused until it is back. Tap to open Shizuku.")
            .setStyle(Notification.BigTextStyle().bigText("Paused apps stay paused until Shizuku is running again. Tap to open Shizuku and start it.\n$detail"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        manager.notify(ID_SHIZUKU, notification)
    }

    fun cancelShizukuDown() = manager.cancel(ID_SHIZUKU)

    fun update(notification: Notification) = manager.notify(ID_SERVICE, notification)

    companion object {
        const val CHANNEL_SERVICE = "watcher"
        const val CHANNEL_ALERTS = "alerts"
        const val ID_SERVICE = 1
        const val ID_SHIZUKU = 2
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    context.getString(R.string.notification_channel_service),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.notification_channel_service_desc) },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.notification_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.notification_channel_alerts_desc) },
            )
        }
    }
}
