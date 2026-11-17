package com.example.giraffeaddiction.engine

import com.example.giraffeaddiction.data.BlockedWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The calendar maths of the engine, in one place and with no Android in it.
 *
 * A "day" starts at the reset hour, so 03:59 belongs to yesterday. Everything is computed with
 * [ZonedDateTime] in the device zone so daylight-saving changes land the reset at the right wall
 * clock time.
 */
object ResetClock {

    /** The reset-aligned day that [nowMillis] falls in, as an ISO date. */
    fun dayKey(nowMillis: Long, resetHour: Int, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).minusHours(resetHour.toLong()).toLocalDate().toString()

    /** The next moment the reset hour comes round after [nowMillis]. */
    fun nextResetMillis(nowMillis: Long, resetHour: Int, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var candidate = now.toLocalDate().atTime(resetHour, 0).atZone(zone)
        if (!candidate.isAfter(now)) {
            candidate = now.toLocalDate().plusDays(1).atTime(resetHour, 0).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli()
    }

    /** Minute of the local day, 0..1439. */
    fun minuteOfDay(nowMillis: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    /**
     * The next window edge (a start or an end) strictly after [nowMillis], across every window, or
     * null when there are no windows. One alarm at this moment is enough: whoever handles it
     * re-evaluates every rule and asks for the edge after that.
     */
    fun nextWindowBoundary(windows: List<BlockedWindow>, nowMillis: Long, zone: ZoneId): Long? {
        if (windows.isEmpty()) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        var best: ZonedDateTime? = null
        for (w in windows) {
            for (minute in listOf(w.startMinute, w.endMinute)) {
                for (dayOffset in 0L..1L) {
                    val at = today.plusDays(dayOffset).atTime(minute / 60, minute % 60).atZone(zone)
                    if (at.isAfter(now) && (best == null || at.isBefore(best))) best = at
                }
            }
        }
        return best?.toInstant()?.toEpochMilli()
    }

    fun dayKeyDaysAgo(nowMillis: Long, resetHour: Int, zone: ZoneId, days: Long): String =
        LocalDate.parse(dayKey(nowMillis, resetHour, zone)).minusDays(days).toString()

    /** The moment the reset-aligned day that [nowMillis] falls in began. */
    fun dayStartMillis(nowMillis: Long, resetHour: Int, zone: ZoneId): Long =
        LocalDate.parse(dayKey(nowMillis, resetHour, zone)).atTime(resetHour, 0).atZone(zone).toInstant().toEpochMilli()
}
