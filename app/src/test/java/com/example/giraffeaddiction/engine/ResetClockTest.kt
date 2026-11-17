package com.example.giraffeaddiction.engine

import com.example.giraffeaddiction.data.BlockedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ResetClockTest {

    private val zone: ZoneId = ZoneId.of("Australia/Sydney")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the day rolls at the reset hour, not at midnight`() {
        assertEquals("2026-10-10", ResetClock.dayKey(at(2026, 10, 11, 3, 59), 4, zone))
        assertEquals("2026-10-11", ResetClock.dayKey(at(2026, 10, 11, 4, 0), 4, zone))
        assertEquals("2026-10-11", ResetClock.dayKey(at(2026, 10, 11, 23, 59), 4, zone))
        assertEquals("2026-10-11", ResetClock.dayKey(at(2026, 10, 12, 1, 0), 4, zone))
    }

    @Test
    fun `next reset is today if the hour is still ahead, else tomorrow`() {
        assertEquals(at(2026, 10, 11, 4), ResetClock.nextResetMillis(at(2026, 10, 11, 1), 4, zone))
        assertEquals(at(2026, 10, 12, 4), ResetClock.nextResetMillis(at(2026, 10, 11, 4), 4, zone))
        assertEquals(at(2026, 10, 12, 4), ResetClock.nextResetMillis(at(2026, 10, 11, 15), 4, zone))
    }

    @Test
    fun `next reset survives a daylight-saving change`() {
        // Sydney springs forward on 2026-10-04 at 02:00; the reset still lands at 04:00 wall clock.
        val next = ResetClock.nextResetMillis(at(2026, 10, 3, 23), 4, zone)
        assertEquals(at(2026, 10, 4, 4), next)
    }

    @Test
    fun `the next window boundary is the nearest start or end ahead`() {
        val windows = listOf(BlockedWindow(21 * 60, 7 * 60))
        assertEquals(at(2026, 10, 11, 21), ResetClock.nextWindowBoundary(windows, at(2026, 10, 11, 12), zone))
        assertEquals(at(2026, 10, 12, 7), ResetClock.nextWindowBoundary(windows, at(2026, 10, 11, 22), zone))
        assertEquals(at(2026, 10, 11, 7), ResetClock.nextWindowBoundary(windows, at(2026, 10, 11, 2), zone))
        assertNull(ResetClock.nextWindowBoundary(emptyList(), at(2026, 10, 11, 2), zone))
    }

    @Test
    fun `minute of day follows the local clock`() {
        assertEquals(21 * 60 + 15, ResetClock.minuteOfDay(at(2026, 10, 11, 21, 15), zone))
    }
}
