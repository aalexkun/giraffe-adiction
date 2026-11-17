package com.example.giraffeaddiction.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Two alarms: the daily reset and the next blocked-window edge. Exact when the app may schedule
 * exact alarms, inexact-but-while-idle otherwise, which is why the checklist asks for the grant.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun armReset(atMillis: Long) = arm(CODE_RESET, PauserForegroundService.ACTION_RESET, atMillis)

    fun armScheduleBoundary(atMillis: Long?) {
        if (atMillis == null) cancel(CODE_SCHEDULE, PauserForegroundService.ACTION_SCHEDULE_BOUNDARY)
        else arm(CODE_SCHEDULE, PauserForegroundService.ACTION_SCHEDULE_BOUNDARY, atMillis)
    }

    val exactAllowed: Boolean get() = alarmManager.canScheduleExactAlarms()

    private fun pending(code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(code: Int, action: String, atMillis: Long) {
        val pi = pending(code, action)
        alarmManager.cancel(pi)
        // A boundary that is already past fires immediately rather than never.
        val at = maxOf(atMillis, System.currentTimeMillis() + 1_000L)
        if (exactAllowed) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancel(code: Int, action: String) = alarmManager.cancel(pending(code, action))

    companion object {
        private const val CODE_RESET = 100
        private const val CODE_SCHEDULE = 101
    }
}
