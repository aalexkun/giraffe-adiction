package com.example.giraffeaddiction.engine

import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.BlockedWindow
import com.example.giraffeaddiction.data.DayState
import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.PauseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every transition of the per-app machine, driven by a fake clock, policy by policy and mixed. */
class PauseEngineTest {

    /** The default rule: one opening a day, 60 s grace, 20 min max. */
    private val rule = AppRule(packageName = "com.example.feed", label = "Feed", graceSec = 60, maxSessionMin = 20)
    private val t0 = 1_000_000L
    private val minute = 60_000L
    private fun fresh() = DayState("com.example.feed", "2026-10-11", updatedAt = t0)

    private fun timers(t: Transition) = t.effects.filterIsInstance<Effect.ArmTimer>()
    private fun cancels(t: Transition) = t.effects.filterIsInstance<Effect.CancelTimer>()
    private fun logs(t: Transition) = t.effects.filterIsInstance<Effect.Log>()

    /** Enter, then leave for longer than grace: one whole session, ended at [leaveAt] + grace. */
    private fun session(state: DayState, r: AppRule, enterAt: Long, leaveAt: Long): Transition {
        val inSession = PauseEngine.reduce(state, r, PauseEvent.Enter, enterAt).state
        val grace = PauseEngine.reduce(inSession, r, PauseEvent.Leave, leaveAt).state
        return PauseEngine.reduce(grace, r, PauseEvent.GraceExpired, leaveAt + r.graceSec * 1_000L)
    }

    // --- the session itself -------------------------------------------------------------------

    @Test
    fun `first opening starts a session and arms the max-session timer`() {
        val t = PauseEngine.reduce(fresh(), rule, PauseEvent.Enter, t0)
        assertEquals(PauseState.IN_SESSION, t.state.state)
        assertEquals(t0, t.state.sessionStartedAt)
        assertEquals(listOf(Effect.ArmTimer(TimerKind.MAX_SESSION, t0 + 20 * minute)), timers(t))
    }

    @Test
    fun `leaving starts grace and arms the grace timer`() {
        val inSession = PauseEngine.reduce(fresh(), rule, PauseEvent.Enter, t0).state
        val t = PauseEngine.reduce(inSession, rule, PauseEvent.Leave, t0 + 5_000)
        assertEquals(PauseState.GRACE, t.state.state)
        assertEquals(t0 + 5_000, t.state.leftAt)
        assertEquals(listOf(Effect.ArmTimer(TimerKind.GRACE, t0 + 5_000 + 60_000)), timers(t))
    }

    @Test
    fun `coming back within grace resumes the same session`() {
        val inSession = PauseEngine.reduce(fresh(), rule, PauseEvent.Enter, t0).state
        val grace = PauseEngine.reduce(inSession, rule, PauseEvent.Leave, t0 + 5_000).state
        val t = PauseEngine.reduce(grace, rule, PauseEvent.Enter, t0 + 30_000)
        assertEquals(PauseState.IN_SESSION, t.state.state)
        assertEquals(t0, t.state.sessionStartedAt)
        assertNull(t.state.leftAt)
        assertEquals(0, t.state.sessionsUsed)
        assertEquals(listOf(Effect.CancelTimer(TimerKind.GRACE)), cancels(t))
    }

    @Test
    fun `grace expiring ends the session and, with one opening a day, pauses`() {
        val t = session(fresh(), rule, t0, t0 + 5_000)
        assertEquals(PauseState.PAUSED, t.state.state)
        assertEquals(t0 + 65_000, t.state.pausedAt)
        assertEquals(1, t.state.sessionsUsed)
        assertEquals(65_000L, t.state.usedMillis)
        assertTrue(cancels(t).contains(Effect.CancelTimer(TimerKind.MAX_SESSION)))
        assertTrue(PauseEngine.desiredSuspended(rule, t.state, 600))
    }

    @Test
    fun `max session cuts a running session off`() {
        val inSession = PauseEngine.reduce(fresh(), rule, PauseEvent.Enter, t0).state
        val t = PauseEngine.reduce(inSession, rule, PauseEvent.MaxSessionReached, t0 + 20 * minute)
        assertEquals(PauseState.PAUSED, t.state.state)
        assertEquals(20 * minute, t.state.usedMillis)
        assertTrue(PauseEngine.desiredSuspended(rule, t.state, 600))
    }

    @Test
    fun `max session also cuts a session that is in grace`() {
        val inSession = PauseEngine.reduce(fresh(), rule, PauseEvent.Enter, t0).state
        val grace = PauseEngine.reduce(inSession, rule, PauseEvent.Leave, t0 + 19 * minute).state
        val t = PauseEngine.reduce(grace, rule, PauseEvent.MaxSessionReached, t0 + 20 * minute)
        assertEquals(PauseState.PAUSED, t.state.state)
        assertTrue(cancels(t).contains(Effect.CancelTimer(TimerKind.GRACE)))
    }

    @Test
    fun `zero grace pauses the moment you leave`() {
        val strict = rule.copy(graceSec = 0)
        val inSession = PauseEngine.reduce(fresh(), strict, PauseEvent.Enter, t0).state
        val t = PauseEngine.reduce(inSession, strict, PauseEvent.Leave, t0 + 1)
        assertEquals(PauseState.PAUSED, t.state.state)
    }

    @Test
    fun `a stray timer does nothing once paused`() {
        val paused = fresh().copy(state = PauseState.PAUSED, pausedAt = t0)
        for (event in listOf(PauseEvent.GraceExpired, PauseEvent.MaxSessionReached, PauseEvent.BudgetExhausted, PauseEvent.CooldownExpired)) {
            val t = PauseEngine.reduce(paused, rule, event, t0 + 1)
            assertEquals(paused, t.state)
            assertTrue(t.effects.isEmpty())
        }
    }

    @Test
    fun `opening a paused or cooling app flags the suspension for re-sending`() {
        for (state in listOf(PauseState.PAUSED, PauseState.COOLDOWN)) {
            val r = if (state == PauseState.COOLDOWN) rule.copy(cooldownEnabled = true) else rule
            val locked = fresh().copy(state = state, appliedSuspended = true)
            val t = PauseEngine.reduce(locked, r, PauseEvent.Enter, t0 + 1)
            assertEquals(state, t.state.state)
            assertFalse(t.state.appliedSuspended)
            assertTrue(logs(t).any { it.kind == EventKind.ERROR })
        }
    }

    // --- openings a day -----------------------------------------------------------------------

    @Test
    fun `three openings a day pause only after the third session`() {
        val three = rule.copy(dailyOpenings = 3)
        val first = session(fresh(), three, t0, t0 + minute)
        assertEquals(PauseState.UNPAUSED, first.state.state)
        assertEquals(1, first.state.sessionsUsed)
        assertFalse(PauseEngine.desiredSuspended(three, first.state, 600))

        val second = session(first.state, three, t0 + 10 * minute, t0 + 12 * minute)
        assertEquals(PauseState.UNPAUSED, second.state.state)
        assertEquals(2, second.state.sessionsUsed)

        val third = session(second.state, three, t0 + 30 * minute, t0 + 31 * minute)
        assertEquals(PauseState.PAUSED, third.state.state)
        assertEquals(3, third.state.sessionsUsed)
        assertTrue(PauseEngine.desiredSuspended(three, third.state, 600))
        assertTrue(logs(third).single().detail.contains("3 of 3 openings used"))
    }

    // --- cooldown -----------------------------------------------------------------------------

    @Test
    fun `cooldown locks the app after a session and frees it when the timer fires`() {
        val cool = rule.copy(openingsEnabled = false, cooldownEnabled = true, cooldownSec = 120)
        val t = session(fresh(), cool, t0, t0 + minute)
        assertEquals(PauseState.COOLDOWN, t.state.state)
        val ends = t0 + minute + 60_000L + 120_000L
        assertEquals(ends, t.state.cooldownEndsAt)
        assertEquals(listOf(Effect.ArmTimer(TimerKind.COOLDOWN, ends)), timers(t))
        assertTrue(PauseEngine.desiredSuspended(cool, t.state, 600))
        assertTrue(logs(t).single().detail.contains("cooling down for 2 min"))

        val over = PauseEngine.reduce(t.state, cool, PauseEvent.CooldownExpired, ends)
        assertEquals(PauseState.UNPAUSED, over.state.state)
        assertNull(over.state.cooldownEndsAt)
        assertFalse(PauseEngine.desiredSuspended(cool, over.state, 600))

        val again = PauseEngine.reduce(over.state, cool, PauseEvent.Enter, ends + 1)
        assertEquals(PauseState.IN_SESSION, again.state.state)
        assertEquals(1, again.state.sessionsUsed)
    }

    @Test
    fun `cooldown sits between openings and the last opening still pauses for the day`() {
        val mix = rule.copy(dailyOpenings = 2, cooldownEnabled = true, cooldownSec = 60)
        val first = session(fresh(), mix, t0, t0 + minute)
        assertEquals(PauseState.COOLDOWN, first.state.state)
        val freed = PauseEngine.reduce(first.state, mix, PauseEvent.CooldownExpired, first.state.cooldownEndsAt!!).state
        val second = session(freed, mix, t0 + 10 * minute, t0 + 11 * minute)
        assertEquals("the day is over, so no cooldown", PauseState.PAUSED, second.state.state)
        assertNull(second.state.cooldownEndsAt)
        assertTrue(timers(second).isEmpty())
    }

    @Test
    fun `cooldown timer is rebuilt from the stored deadline`() {
        val cool = rule.copy(cooldownEnabled = true)
        val cooling = fresh().copy(state = PauseState.COOLDOWN, cooldownEndsAt = t0 + 90_000)
        assertEquals(listOf(Effect.ArmTimer(TimerKind.COOLDOWN, t0 + 90_000)), PauseEngine.timersFor(cooling, cool))
    }

    // --- time a day ---------------------------------------------------------------------------

    @Test
    fun `time budget arms a day timer from what is left and pauses when it fires`() {
        val budget = rule.copy(openingsEnabled = false, timeBudgetEnabled = true, dailyMinutes = 10)
        val first = PauseEngine.reduce(fresh(), budget, PauseEvent.Enter, t0)
        assertTrue(timers(first).contains(Effect.ArmTimer(TimerKind.DAILY_BUDGET, t0 + 10 * minute)))

        val ended = session(fresh(), budget, t0, t0 + 3 * minute)
        assertEquals(PauseState.UNPAUSED, ended.state.state)
        assertEquals(4 * minute, ended.state.usedMillis)

        val second = PauseEngine.reduce(ended.state, budget, PauseEvent.Enter, t0 + 20 * minute)
        assertTrue(timers(second).contains(Effect.ArmTimer(TimerKind.DAILY_BUDGET, t0 + 20 * minute + 6 * minute)))

        val out = PauseEngine.reduce(second.state, budget, PauseEvent.BudgetExhausted, t0 + 26 * minute)
        assertEquals(PauseState.PAUSED, out.state.state)
        assertEquals(10 * minute, out.state.usedMillis)
        assertTrue(PauseEngine.desiredSuspended(budget, out.state, 600))
    }

    @Test
    fun `a session that runs past the budget on its own pauses too`() {
        val budget = rule.copy(openingsEnabled = false, timeBudgetEnabled = true, dailyMinutes = 10, cooldownEnabled = true)
        val inSession = PauseEngine.reduce(fresh(), budget, PauseEvent.Enter, t0).state
        val t = PauseEngine.reduce(inSession, budget, PauseEvent.MaxSessionReached, t0 + 20 * minute)
        assertEquals("the budget beats the cooldown", PauseState.PAUSED, t.state.state)
    }

    @Test
    fun `budget timer is rebuilt for a session in flight`() {
        val budget = rule.copy(timeBudgetEnabled = true, dailyMinutes = 30)
        val running = fresh().copy(state = PauseState.IN_SESSION, sessionStartedAt = t0, usedMillis = 10 * minute)
        assertEquals(
            setOf(
                Effect.ArmTimer(TimerKind.MAX_SESSION, t0 + 20 * minute),
                Effect.ArmTimer(TimerKind.DAILY_BUDGET, t0 + 20 * minute),
            ),
            PauseEngine.timersFor(running, budget).toSet(),
        )
    }

    // --- curfew and switches ------------------------------------------------------------------

    @Test
    fun `curfew only ignores foreground events and follows the clock`() {
        val curfew = rule.copy(openingsEnabled = false, blockedWindows = "21:00-07:00")
        assertFalse(curfew.tracksSessions)
        val t = PauseEngine.reduce(fresh(), curfew, PauseEvent.Enter, t0)
        assertEquals(PauseState.UNPAUSED, t.state.state)
        assertTrue(t.effects.isEmpty())
        assertTrue(PauseEngine.desiredSuspended(curfew, t.state, 22 * 60))
        assertFalse(PauseEngine.desiredSuspended(curfew, t.state, 12 * 60))
        assertTrue("crosses midnight", PauseEngine.desiredSuspended(curfew, t.state, 3 * 60))
    }

    @Test
    fun `a curfew window sits on top of any session policy`() {
        val belt = rule.copy(blockedWindows = "21:00-07:00")
        val state = fresh()
        assertTrue(PauseEngine.desiredSuspended(belt, state, 23 * 60))
        assertFalse(PauseEngine.desiredSuspended(belt, state, 12 * 60))
        assertTrue(PauseEngine.desiredSuspended(belt, state.copy(state = PauseState.PAUSED), 12 * 60))
        val cool = belt.copy(cooldownEnabled = true)
        assertTrue(PauseEngine.desiredSuspended(cool, state.copy(state = PauseState.COOLDOWN), 12 * 60))
    }

    @Test
    fun `a disabled rule never wants suspension and ignores events`() {
        val off = rule.copy(enabled = false)
        val paused = fresh().copy(state = PauseState.PAUSED)
        assertFalse(PauseEngine.desiredSuspended(off, paused, 600))
        assertTrue(PauseEngine.reduce(fresh(), off, PauseEvent.Enter, t0).effects.isEmpty())
    }

    // --- reset ----------------------------------------------------------------------------------

    @Test
    fun `reset while paused starts a new unpaused day with fresh counters and wants the app unsuspended`() {
        val paused = fresh().copy(state = PauseState.PAUSED, pausedAt = t0, sessionsUsed = 1, usedMillis = 5 * minute, appliedSuspended = true)
        val t = PauseEngine.reduce(paused, rule, PauseEvent.Reset("2026-10-12"), t0 + 86_400_000L)
        assertEquals("2026-10-12", t.state.dayKey)
        assertEquals(PauseState.UNPAUSED, t.state.state)
        assertEquals(0, t.state.sessionsUsed)
        assertEquals(0L, t.state.usedMillis)
        assertNull(t.state.cooldownEndsAt)
        assertTrue("the applied flag is carried so the service knows to unsuspend", t.state.appliedSuspended)
        assertFalse(t.state.applyPending)
        assertFalse(PauseEngine.desiredSuspended(rule, t.state, 600))
        assertEquals(TimerKind.entries.toSet(), cancels(t).map { it.kind }.toSet())
    }

    @Test
    fun `reset while Shizuku is down leaves the pending flag to the service`() {
        val paused = fresh().copy(state = PauseState.PAUSED, appliedSuspended = true, applyPending = true)
        val t = PauseEngine.reduce(paused, rule, PauseEvent.Reset("2026-10-12"), t0)
        assertTrue(t.state.appliedSuspended)
    }

    @Test
    fun `reset mid-session cancels the timers`() {
        val inSession = PauseEngine.reduce(fresh(), rule, PauseEvent.Enter, t0).state
        val t = PauseEngine.reduce(inSession, rule, PauseEvent.Reset("2026-10-12"), t0 + 1)
        assertEquals(PauseState.UNPAUSED, t.state.state)
        assertNull(t.state.sessionStartedAt)
        assertTrue(cancels(t).map { it.kind }.containsAll(listOf(TimerKind.GRACE, TimerKind.MAX_SESSION)))
    }

    // --- rule edits -----------------------------------------------------------------------------

    @Test
    fun `settle frees a paused app when its limit is raised and pauses a ready one when lowered`() {
        val paused = fresh().copy(state = PauseState.PAUSED, sessionsUsed = 1)
        val raised = PauseEngine.settle(paused, rule.copy(dailyOpenings = 3), t0)
        assertEquals(PauseState.UNPAUSED, raised.state.state)
        assertTrue(logs(raised).single().detail.contains("2 openings left"))

        val ready = fresh().copy(sessionsUsed = 2)
        val lowered = PauseEngine.settle(ready, rule.copy(dailyOpenings = 2), t0)
        assertEquals(PauseState.PAUSED, lowered.state.state)
        assertTrue(PauseEngine.settle(ready, rule.copy(dailyOpenings = 3), t0).effects.isEmpty())
    }

    @Test
    fun `settle ends a cooldown that was switched off and lets go of an app with no policies`() {
        val cooling = fresh().copy(state = PauseState.COOLDOWN, cooldownEndsAt = t0 + 60_000, sessionsUsed = 1)
        val off = PauseEngine.settle(cooling, rule.copy(dailyOpenings = 3, cooldownEnabled = false), t0)
        assertEquals(PauseState.UNPAUSED, off.state.state)
        assertNull(off.state.cooldownEndsAt)
        assertTrue(cancels(off).contains(Effect.CancelTimer(TimerKind.COOLDOWN)))

        val paused = fresh().copy(state = PauseState.PAUSED, sessionsUsed = 1)
        val none = PauseEngine.settle(paused, rule.copy(openingsEnabled = false), t0)
        assertEquals(PauseState.UNPAUSED, none.state.state)
        assertFalse(PauseEngine.desiredSuspended(rule.copy(openingsEnabled = false), none.state, 600))
    }

    @Test
    fun `settle leaves a session in flight alone`() {
        val running = fresh().copy(state = PauseState.IN_SESSION, sessionStartedAt = t0)
        assertTrue(PauseEngine.settle(running, rule.copy(dailyOpenings = 5, cooldownEnabled = true), t0 + 1).effects.isEmpty())
    }

    // --- helpers --------------------------------------------------------------------------------

    @Test
    fun `timers are rebuilt from the stored timestamps`() {
        val grace = fresh().copy(state = PauseState.GRACE, sessionStartedAt = t0, leftAt = t0 + 100_000)
        val rebuilt = PauseEngine.timersFor(grace, rule)
        assertEquals(
            setOf(
                Effect.ArmTimer(TimerKind.GRACE, t0 + 100_000 + 60_000),
                Effect.ArmTimer(TimerKind.MAX_SESSION, t0 + 20 * minute),
            ),
            rebuilt.toSet(),
        )
        assertTrue(PauseEngine.timersFor(fresh(), rule).isEmpty())
        assertTrue(PauseEngine.timersFor(grace.copy(state = PauseState.PAUSED), rule).isEmpty())
        assertTrue(PauseEngine.timersFor(grace, rule.copy(openingsEnabled = false)).isEmpty())
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("10 s", formatSeconds(10))
        assertEquals("2 min", formatSeconds(120))
        assertEquals("1 min 30 s", formatSeconds(90))
        assertEquals("5 min", formatSeconds(300))
    }

    @Test
    fun `rules are clamped to their ranges`() {
        val wild = rule.copy(dailyOpenings = 9, dailyMinutes = 5, cooldownSec = 1_000, graceSec = -5).sanitized()
        assertEquals(5, wild.dailyOpenings)
        assertEquals(10, wild.dailyMinutes)
        assertEquals(300, wild.cooldownSec)
        assertEquals(0, wild.graceSec)
    }

    @Test
    fun `blocked windows round-trip through their encoding`() {
        val windows = listOf(BlockedWindow(21 * 60, 7 * 60), BlockedWindow(12 * 60, 13 * 60 + 30))
        assertEquals(windows, BlockedWindow.parseList(BlockedWindow.encodeList(windows)))
        assertEquals("21:00-07:00;12:00-13:30", BlockedWindow.encodeList(windows))
        assertNull(BlockedWindow.parse("25:00-07:00"))
    }
}
