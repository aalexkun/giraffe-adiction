package com.example.giraffeaddiction.engine

import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.BlockedWindow
import com.example.giraffeaddiction.data.DayState
import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.PauseState

enum class TimerKind { GRACE, MAX_SESSION, DAILY_BUDGET, COOLDOWN }

sealed interface PauseEvent {
    /** The app came to the foreground. */
    data object Enter : PauseEvent

    /** The app left the foreground, or the screen went off. */
    data object Leave : PauseEvent

    data object GraceExpired : PauseEvent
    data object MaxSessionReached : PauseEvent

    /** The day's minutes ran out in the middle of a session. */
    data object BudgetExhausted : PauseEvent

    data object CooldownExpired : PauseEvent

    /** The reset hour came round: a new [dayKey] starts unpaused with fresh counters. */
    data class Reset(val dayKey: String) : PauseEvent
}

/**
 * What the service must do after a transition. Suspension itself is deliberately not an effect:
 * it is derived from the new state by [PauseEngine.desiredSuspended] and applied by the service,
 * so a failed Shizuku call never desynchronises the machine from reality.
 */
sealed interface Effect {
    data class ArmTimer(val kind: TimerKind, val atMillis: Long) : Effect
    data class CancelTimer(val kind: TimerKind) : Effect
    data class Log(val kind: EventKind, val detail: String) : Effect
}

data class Transition(val state: DayState, val effects: List<Effect>) {
    val changed: Boolean get() = effects.isNotEmpty()
}

/** "2 min", "1 min 30 s", "45 s". */
fun formatSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return when {
        m == 0 -> "$s s"
        s == 0 -> "$m min"
        else -> "$m min $s s"
    }
}

/**
 * The per-app state machine as a pure reducer: no clock, no Android, no database. The service
 * feeds it events with the current time and persists what comes back.
 *
 * ```
 * UNPAUSED --Enter--> IN_SESSION --Leave--> GRACE --GraceExpired--> (session over)
 *                         ^                   |
 *                         +------Enter--------+
 *     IN_SESSION or GRACE --MaxSessionReached / BudgetExhausted--> (session over)
 *
 *     session over:  limits for the day reached   -> PAUSED   (until Reset)
 *                    else cooldown switched on    -> COOLDOWN --CooldownExpired--> UNPAUSED
 *                    else                          -> UNPAUSED
 *     anything --Reset--> UNPAUSED (new day, counters cleared)
 * ```
 *
 * The limits are the openings-a-day count and the minutes-a-day budget; both, either or neither
 * can be on, and the cooldown sits between sessions whichever of them is. Curfew windows are
 * not a state at all: [desiredSuspended] reads them straight from the clock.
 */
object PauseEngine {

    private val cancelAll: List<Effect> = TimerKind.entries.map { Effect.CancelTimer(it) }

    fun reduce(state: DayState, rule: AppRule, event: PauseEvent, now: Long): Transition {
        if (event is PauseEvent.Reset) {
            val fresh = DayState(
                packageName = state.packageName,
                dayKey = event.dayKey,
                state = PauseState.UNPAUSED,
                appliedSuspended = state.appliedSuspended,
                applyPending = false,
                updatedAt = now,
            )
            return Transition(fresh, cancelAll + Effect.Log(EventKind.RESET, "${rule.label}: new day ${event.dayKey}"))
        }
        // Curfew-only and disabled rules do not track sessions at all.
        if (!rule.enabled || !rule.tracksSessions) return none(state)

        return when (event) {
            PauseEvent.Enter -> when (state.state) {
                PauseState.UNPAUSED -> startSession(state, rule, now)
                PauseState.IN_SESSION -> none(state)
                PauseState.GRACE -> Transition(
                    state.copy(state = PauseState.IN_SESSION, leftAt = null, updatedAt = now),
                    listOf(
                        Effect.CancelTimer(TimerKind.GRACE),
                        Effect.Log(EventKind.SESSION, "${rule.label}: back within grace"),
                    ),
                )
                // Suspension did not stick (or has not been applied yet): mark it so it is re-sent.
                PauseState.COOLDOWN, PauseState.PAUSED -> Transition(
                    state.copy(appliedSuspended = false, updatedAt = now),
                    listOf(
                        Effect.Log(
                            EventKind.ERROR,
                            "${rule.label}: opened while ${if (state.state == PauseState.COOLDOWN) "cooling down" else "paused"}, re-suspending",
                        ),
                    ),
                )
            }

            PauseEvent.Leave ->
                if (state.state != PauseState.IN_SESSION) {
                    none(state)
                } else if (rule.graceSec <= 0) {
                    endSession(state, rule, now, "left")
                } else {
                    Transition(
                        state.copy(state = PauseState.GRACE, leftAt = now, updatedAt = now),
                        listOf(
                            Effect.ArmTimer(TimerKind.GRACE, now + rule.graceSec * 1_000L),
                            Effect.Log(EventKind.SESSION, "${rule.label}: left, ${rule.graceSec}s grace"),
                        ),
                    )
                }

            PauseEvent.GraceExpired ->
                if (state.state == PauseState.GRACE) endSession(state, rule, now, "grace expired") else none(state)

            PauseEvent.MaxSessionReached ->
                if (inSession(state)) endSession(state, rule, now, "longest session reached") else none(state)

            PauseEvent.BudgetExhausted ->
                if (inSession(state)) endSession(state, rule, now, "time for today used up", forcePause = true) else none(state)

            PauseEvent.CooldownExpired ->
                if (state.state == PauseState.COOLDOWN) {
                    Transition(
                        state.copy(state = PauseState.UNPAUSED, cooldownEndsAt = null, updatedAt = now),
                        listOf(
                            Effect.CancelTimer(TimerKind.COOLDOWN),
                            Effect.Log(EventKind.SESSION, "${rule.label}: cooldown over, ${left(rule, state)}"),
                        ),
                    )
                } else {
                    none(state)
                }

            is PauseEvent.Reset -> error("handled above")
        }
    }

    /**
     * Re-reads a stored state against a rule that may just have changed: a raised limit frees a
     * paused app, a lowered one pauses it, a switched-off cooldown ends early, and an app whose
     * session policies were all turned off is let go. A session in flight is left alone; its
     * timers are rebuilt from [timersFor] with the new numbers.
     */
    fun settle(state: DayState, rule: AppRule, now: Long): Transition {
        val label = rule.label
        if (!rule.enabled || !rule.tracksSessions) {
            if (state.state == PauseState.UNPAUSED) return none(state)
            val why = if (inSession(state)) "session dropped" else "unpaused"
            return Transition(
                state.copy(state = PauseState.UNPAUSED, sessionStartedAt = null, leftAt = null, cooldownEndsAt = null, updatedAt = now),
                cancelAll + Effect.Log(EventKind.SESSION, "$label: no session limits any more, $why"),
            )
        }
        val out = exhausted(rule, state)
        return when (state.state) {
            PauseState.UNPAUSED ->
                if (out) {
                    Transition(
                        state.copy(state = PauseState.PAUSED, pausedAt = now, updatedAt = now),
                        listOf(Effect.Log(EventKind.SESSION, "$label: ${usage(rule, state)}; paused until the reset")),
                    )
                } else {
                    none(state)
                }
            PauseState.PAUSED ->
                if (!out) {
                    Transition(
                        state.copy(state = PauseState.UNPAUSED, updatedAt = now),
                        listOf(Effect.Log(EventKind.SESSION, "$label: limit raised, ${left(rule, state)}")),
                    )
                } else {
                    none(state)
                }
            PauseState.COOLDOWN -> when {
                out -> Transition(
                    state.copy(state = PauseState.PAUSED, pausedAt = now, cooldownEndsAt = null, updatedAt = now),
                    listOf(
                        Effect.CancelTimer(TimerKind.COOLDOWN),
                        Effect.Log(EventKind.SESSION, "$label: ${usage(rule, state)}; paused until the reset"),
                    ),
                )
                !rule.cooldownEnabled -> Transition(
                    state.copy(state = PauseState.UNPAUSED, cooldownEndsAt = null, updatedAt = now),
                    listOf(
                        Effect.CancelTimer(TimerKind.COOLDOWN),
                        Effect.Log(EventKind.SESSION, "$label: cooldown switched off, ${left(rule, state)}"),
                    ),
                )
                else -> none(state)
            }
            PauseState.IN_SESSION, PauseState.GRACE -> none(state)
        }
    }

    /**
     * Whether the package should be suspended right now. Curfew windows apply whatever else is
     * on, so a curfew can sit on top of any mix of session policies.
     */
    fun desiredSuspended(rule: AppRule, state: DayState?, minuteOfDay: Int): Boolean {
        if (!rule.enabled) return false
        if (BlockedWindow.anyContains(rule.windows, minuteOfDay)) return true
        return rule.tracksSessions && (state?.state == PauseState.PAUSED || state?.state == PauseState.COOLDOWN)
    }

    /** Whether the day's limits are reached, counting the sessions already finished. */
    fun exhausted(rule: AppRule, state: DayState): Boolean =
        (rule.openingsEnabled && state.sessionsUsed >= rule.dailyOpenings) ||
            (rule.timeBudgetEnabled && state.usedMillis >= rule.budgetMillis)

    /** The timers a freshly started service must rebuild for [state]. Past deadlines fire at once. */
    fun timersFor(state: DayState, rule: AppRule): List<Effect.ArmTimer> {
        if (!rule.enabled || !rule.tracksSessions) return emptyList()
        val timers = ArrayList<Effect.ArmTimer>(3)
        val started = state.sessionStartedAt
        fun sessionTimers() {
            if (started == null) return
            timers += Effect.ArmTimer(TimerKind.MAX_SESSION, started + rule.maxSessionMin * 60_000L)
            if (rule.timeBudgetEnabled) {
                timers += Effect.ArmTimer(TimerKind.DAILY_BUDGET, started + (rule.budgetMillis - state.usedMillis).coerceAtLeast(0L))
            }
        }
        when (state.state) {
            PauseState.IN_SESSION -> sessionTimers()
            PauseState.GRACE -> {
                val left = state.leftAt
                if (left != null) timers += Effect.ArmTimer(TimerKind.GRACE, left + rule.graceSec * 1_000L)
                sessionTimers()
            }
            PauseState.COOLDOWN -> timers += Effect.ArmTimer(TimerKind.COOLDOWN, state.cooldownEndsAt ?: 0L)
            else -> Unit
        }
        return timers
    }

    /** "2 of 3 openings used, 12 of 30 min used", or "" when no daily limit is on. */
    fun usage(rule: AppRule, state: DayState): String {
        val parts = ArrayList<String>(2)
        if (rule.openingsEnabled) parts += "${state.sessionsUsed} of ${rule.dailyOpenings} opening${if (rule.dailyOpenings == 1) "" else "s"} used"
        if (rule.timeBudgetEnabled) parts += "${(state.usedMillis / 60_000L)} of ${rule.dailyMinutes} min used"
        return parts.joinToString(", ")
    }

    /** "2 openings left, 18 min left today", or "ready again" when no daily limit is on. */
    fun left(rule: AppRule, state: DayState): String {
        val parts = ArrayList<String>(2)
        if (rule.openingsEnabled) {
            val n = (rule.dailyOpenings - state.sessionsUsed).coerceAtLeast(0)
            parts += "$n opening${if (n == 1) "" else "s"} left"
        }
        if (rule.timeBudgetEnabled) {
            val min = ((rule.budgetMillis - state.usedMillis).coerceAtLeast(0L) + 59_999L) / 60_000L
            parts += "$min min left today"
        }
        return parts.joinToString(", ").ifEmpty { "ready again" }
    }

    // --- internals ------------------------------------------------------------------------------

    private fun none(state: DayState) = Transition(state, emptyList())

    private fun inSession(state: DayState) =
        state.state == PauseState.IN_SESSION || state.state == PauseState.GRACE

    private fun startSession(state: DayState, rule: AppRule, now: Long): Transition {
        val effects = ArrayList<Effect>(3)
        effects += Effect.ArmTimer(TimerKind.MAX_SESSION, now + rule.maxSessionMin * 60_000L)
        if (rule.timeBudgetEnabled) {
            effects += Effect.ArmTimer(TimerKind.DAILY_BUDGET, now + (rule.budgetMillis - state.usedMillis).coerceAtLeast(0L))
        }
        val limits = ArrayList<String>(3)
        limits += "${rule.maxSessionMin} min max"
        if (rule.openingsEnabled) limits += "opening ${state.sessionsUsed + 1} of ${rule.dailyOpenings}"
        if (rule.timeBudgetEnabled) limits += "${((rule.budgetMillis - state.usedMillis).coerceAtLeast(0L) + 59_999L) / 60_000L} min left today"
        effects += Effect.Log(EventKind.SESSION, "${rule.label}: session started (${limits.joinToString(", ")})")
        return Transition(
            state.copy(state = PauseState.IN_SESSION, sessionStartedAt = now, leftAt = null, updatedAt = now),
            effects,
        )
    }

    /**
     * Closes the running session, books it, and decides what comes next: the day is over when a
     * limit is reached, otherwise a cooldown when one is on, otherwise straight back to ready.
     */
    private fun endSession(state: DayState, rule: AppRule, now: Long, why: String, forcePause: Boolean = false): Transition {
        val started = state.sessionStartedAt ?: now
        val closed = state.copy(
            sessionsUsed = state.sessionsUsed + 1,
            usedMillis = state.usedMillis + (now - started).coerceAtLeast(0L),
            sessionStartedAt = null,
            leftAt = null,
            updatedAt = now,
        )
        val label = rule.label
        val used = usage(rule, closed)
        return when {
            forcePause || exhausted(rule, closed) -> Transition(
                closed.copy(state = PauseState.PAUSED, pausedAt = now, cooldownEndsAt = null),
                cancelAll + Effect.Log(EventKind.SESSION, line("$label: session over ($why)", used, "paused until the reset")),
            )
            rule.cooldownEnabled -> {
                val ends = now + rule.cooldownMillis
                Transition(
                    closed.copy(state = PauseState.COOLDOWN, cooldownEndsAt = ends),
                    cancelAll + Effect.ArmTimer(TimerKind.COOLDOWN, ends) +
                        Effect.Log(EventKind.SESSION, line("$label: session over ($why)", "cooling down for ${formatSeconds(rule.cooldownSec)}", used)),
                )
            }
            else -> Transition(
                closed.copy(state = PauseState.UNPAUSED),
                cancelAll + Effect.Log(EventKind.SESSION, line("$label: session over ($why)", used)),
            )
        }
    }

    private fun line(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString("; ")
}
