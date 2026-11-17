package com.example.giraffeaddiction.engine

import com.example.giraffeaddiction.data.AppVisit
import com.example.giraffeaddiction.data.Recommendation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reflex-check detector on hand-written days. */
class PatternAnalyzerTest {

    private var clock = 0L

    /** Appends a visit of [seconds] right after the previous one. */
    private fun MutableList<AppVisit>.then(pkg: String, seconds: Int) {
        add(AppVisit(packageName = pkg, startedAt = clock, endedAt = clock + seconds * 1_000L))
        clock += seconds * 1_000L
    }

    private fun MutableList<AppVisit>.gap(seconds: Int) {
        clock += seconds * 1_000L
    }

    private fun bouncyDay(): MutableList<AppVisit> = ArrayList<AppVisit>().apply {
        then("x", 60)
        then("gmail", 4)
        then("youtube", 120)
        then("gmail", 3)
        then("chat", 90)
        then("gmail", 5)
        then("browser", 30)
        then("gmail", 4)
    }

    @Test
    fun `short visits squeezed between other apps make a bounce`() {
        val bounces = PatternAnalyzer.bounces(bouncyDay())
        val gmail = bounces.single()
        assertEquals("gmail", gmail.packageName)
        assertEquals(4, gmail.count)
        assertEquals(4_000L, gmail.avgMillis)
        assertEquals(256_000L, gmail.spanMillis)
        assertEquals(listOf("browser", "chat", "youtube", "x"), gmail.between)
    }

    @Test
    fun `long reads are not checks`() {
        val visits = ArrayList<AppVisit>().apply {
            then("x", 60)
            then("gmail", 60)
            then("youtube", 120)
            then("gmail", 45)
            then("chat", 90)
            then("gmail", 50)
            then("browser", 30)
            then("gmail", 40)
        }
        assertTrue(PatternAnalyzer.bounces(visits).isEmpty())
    }

    @Test
    fun `fewer than four checks are not a pattern`() {
        val visits = ArrayList<AppVisit>().apply {
            then("x", 60)
            then("gmail", 4)
            then("youtube", 120)
            then("gmail", 3)
            then("chat", 90)
            then("gmail", 5)
        }
        assertTrue(PatternAnalyzer.bounces(visits).isEmpty())
    }

    @Test
    fun `a long gap splits the chain, and the wider gap setting joins it again`() {
        val visits = ArrayList<AppVisit>().apply {
            then("x", 60)
            then("gmail", 4)
            then("youtube", 120)
            then("gmail", 3)
            gap(20 * 60)
            then("chat", 90)
            then("gmail", 5)
            then("browser", 30)
            then("gmail", 4)
            then("maps", 30)
            then("gmail", 4)
        }
        assertTrue("3 + 2, neither is enough", PatternAnalyzer.bounces(visits).isEmpty())
        val joined = PatternAnalyzer.bounces(visits, PatternAnalyzer.Params(maxGapMillis = 30 * 60_000L)).single()
        assertEquals(5, joined.count)
    }

    @Test
    fun `a second visit of the same app in a row is a continuation, not a check`() {
        val visits = ArrayList<AppVisit>().apply {
            then("x", 60)
            then("gmail", 4)
            then("gmail", 3)
            then("youtube", 120)
            then("gmail", 3)
            then("gmail", 2)
            then("chat", 90)
            then("gmail", 5)
        }
        assertTrue(PatternAnalyzer.bounces(visits).isEmpty())
    }

    @Test
    fun `sub-second blips are ignored altogether`() {
        val visits = bouncyDay()
        // A half-second flash of the share sheet between two visits should not break anything.
        visits.add(AppVisit(packageName = "share", startedAt = 64_000L, endedAt = 64_400L))
        assertEquals(4, PatternAnalyzer.bounces(visits).single().count)
    }

    @Test
    fun `several apps are reported, biggest chain first`() {
        val visits = bouncyDay().apply {
            then("news", 3)
            then("x", 30)
            then("news", 2)
            then("youtube", 30)
            then("news", 3)
            then("chat", 30)
            then("news", 3)
            then("x", 30)
            then("news", 4)
        }
        val bounces = PatternAnalyzer.bounces(visits)
        assertEquals(listOf("news", "gmail"), bounces.map { it.packageName })
        assertEquals(5, bounces[0].count)
    }

    @Test
    fun `the summary reads as one sentence`() {
        val rec = Recommendation(
            packageName = "gmail", label = "Gmail", count = 6, avgMillis = 4_200L, spanMillis = 38 * 60_000L,
            between = "YouTube, Chrome, Slack", firstSeenAt = 0L, updatedAt = 0L,
        )
        assertEquals("Checked 6 times in 38 min, about 4 s each, between YouTube, Chrome and Slack.", rec.summary())
        assertEquals(
            "Checked 6 times in 38 min, about 4 s each, between YouTube and Chrome.",
            rec.copy(between = "YouTube, Chrome").summary(),
        )
        assertEquals(
            "Checked 6 times in 38 min, about 4 s each, between YouTube and the home screen.",
            rec.copy(between = "YouTube").summary(),
        )
        assertTrue(rec.pitch().contains("2 min cooldown"))
    }
}
