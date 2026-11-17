package com.example.giraffeaddiction.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.DayState
import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.GiraffeRepository
import com.example.giraffeaddiction.data.PauseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Drives [PauseEngine] from the real world: foreground events from the Shizuku worker, screen
 * on/off, the reset alarm, rule edits and reconnects. Every entry point takes one mutex, so the
 * machine only ever moves one step at a time; timers are coroutine jobs rebuilt from the stored
 * timestamps whenever the service starts. The same foreground events feed the [UsageTracker].
 */
class EngineRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: GiraffeRepository,
    private val bridge: ShizukuBridge,
    private val onApplyFailed: (String) -> Unit,
    private val onStateChanged: () -> Unit,
) {
    private val mutex = Mutex()
    private val timers = HashMap<Pair<String, TimerKind>, Job>()
    private var rules: Map<String, AppRule> = emptyMap()
    private var initialized = false
    private val tracker = UsageTracker(context, repo) { rules }

    @Volatile
    var foregroundPackage: String? = null
        private set

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun now() = System.currentTimeMillis()

    val ruleCount: Int get() = rules.size

    /** The first emission of the rules flow seeds the engine; later ones are diffed. */
    suspend fun onRulesChanged(newRules: List<AppRule>) = mutex.withLock {
        val newMap = newRules.associateBy { it.packageName }
        if (!initialized) {
            initialized = true
            rules = newMap
            rebuildLocked("service start")
            return@withLock
        }
        val old = rules
        rules = newMap
        val now = now()
        for ((pkg, oldRule) in old) {
            if (pkg in newMap) continue
            cancelTimers(pkg)
            val stored = repo.latestDayState(pkg)
            if (stored?.appliedSuspended == true || realSuspended(pkg) == true) {
                val err = bridge.setSuspended(pkg, false)
                if (err == null) repo.log(EventKind.UNPAUSED, "${oldRule.label}: unsuspended (removed from the list)")
                else repo.log(EventKind.ERROR, "${oldRule.label}: removed but could not unsuspend ($err)")
            }
            repo.deleteDayStates(pkg)
            repo.log(EventKind.SERVICE, "${oldRule.label}: removed")
        }
        val settings = repo.settingsNow()
        val today = ResetClock.dayKey(now, settings.resetHour, zone)
        for ((pkg, rule) in newMap) {
            val prev = old[pkg]
            if (prev == rule) continue
            cancelTimers(pkg)
            var state = repo.latestDayState(pkg) ?: DayState(pkg, today, updatedAt = now)
            if (prev == null) {
                val real = realSuspended(pkg)
                if (real != null) state = state.copy(appliedSuspended = real)
                repo.log(EventKind.SERVICE, "${rule.label}: added (${describe(rule)})")
            } else {
                repo.log(EventKind.SERVICE, "${rule.label}: rule changed (${describe(rule)})")
                val settled = PauseEngine.settle(state, rule, now)
                for (effect in settled.effects) runEffect(pkg, effect)
                state = settled.state
            }
            state = applyDesiredLocked(rule, state, now)
            repo.upsertDayState(state)
            for (t in PauseEngine.timersFor(state, rule)) armTimer(pkg, t.kind, t.atMillis)
        }
        // A new cooldown policy may answer an open recommendation.
        tracker.analyze(now)
        onStateChanged()
    }

    suspend fun onForegroundChanged(packageName: String, foreground: Boolean) = mutex.withLock {
        foregroundLocked(packageName, foreground, now())
    }

    suspend fun onScreenOff() = mutex.withLock {
        val now = now()
        foregroundPackage = null
        tracker.onScreenOff(now)
        for (rule in rules.values) {
            val state = repo.latestDayState(rule.packageName) ?: continue
            if (state.state == PauseState.IN_SESSION) dispatchLocked(rule, state, PauseEvent.Leave, now)
        }
        repo.updateRuntime { it.copy(foregroundPackage = null) }
    }

    suspend fun onScreenOn() = mutex.withLock {
        val top = bridge.call { it.foregroundPackage } ?: return@withLock
        foregroundLocked(top, true, now())
    }

    suspend fun onWorkerConnected() = mutex.withLock {
        if (initialized) rebuildLocked("Shizuku worker connected")
    }

    /** Rolls any app whose stored day is older than today; safe to call at any time. */
    suspend fun checkReset(reason: String) = mutex.withLock {
        if (!initialized) return@withLock
        if (checkDayLocked(now(), reason)) onStateChanged()
    }

    /** Re-derives and applies the desired suspension of every app (schedule edges, reconnects). */
    suspend fun applyAll(reason: String) = mutex.withLock {
        if (!initialized) return@withLock
        val now = now()
        for (rule in rules.values) {
            val state = repo.latestDayState(rule.packageName) ?: continue
            val next = applyDesiredLocked(rule, state, now)
            if (next != state) repo.upsertDayState(next)
        }
        repo.log(EventKind.SERVICE, "rules re-applied ($reason)")
        onStateChanged()
    }

    fun stop() {
        for (job in timers.values) job.cancel()
        timers.clear()
    }

    // --- internals, always under the mutex ------------------------------------------------------

    private suspend fun rebuildLocked(reason: String) {
        val now = now()
        checkDayLocked(now, reason)
        reconcileLocked(now)
        for (rule in rules.values) {
            val state = repo.latestDayState(rule.packageName) ?: continue
            cancelTimers(rule.packageName)
            for (t in PauseEngine.timersFor(state, rule)) armTimer(rule.packageName, t.kind, t.atMillis)
        }
        val top = bridge.call { it.foregroundPackage }
        if (top != null) foregroundLocked(top, true, now)
        repo.log(EventKind.SERVICE, "engine rebuilt ($reason): ${rules.size} app(s)")
        onStateChanged()
    }

    /** Returns true when at least one app rolled to a new day. */
    private suspend fun checkDayLocked(now: Long, reason: String): Boolean {
        val settings = repo.settingsNow()
        val today = ResetClock.dayKey(now, settings.resetHour, zone)
        var rolled = false
        for (rule in rules.values) {
            val current = repo.latestDayState(rule.packageName)
            if (current == null) {
                val real = realSuspended(rule.packageName) ?: false
                var fresh = DayState(rule.packageName, today, appliedSuspended = real, updatedAt = now)
                fresh = applyDesiredLocked(rule, fresh, now)
                repo.upsertDayState(fresh)
            } else if (current.dayKey < today) {
                dispatchLocked(rule, current, PauseEvent.Reset(today), now)
                rolled = true
            }
        }
        if (rolled) repo.log(EventKind.RESET, "daily reset ($reason)")
        repo.pruneDayStates(ResetClock.dayKeyDaysAgo(now, settings.resetHour, zone, 14))
        repo.pruneVisits(now - VISIT_RETENTION_MS)
        repo.pruneRecommendations(now - RECOMMENDATION_RETENTION_MS)
        return rolled
    }

    /** Lines the database up with what the package manager says, then applies the desired state. */
    private suspend fun reconcileLocked(now: Long) {
        for (rule in rules.values) {
            val real = realSuspended(rule.packageName) ?: continue
            val stored = repo.latestDayState(rule.packageName) ?: continue
            var state = stored
            if (real != state.appliedSuspended) {
                repo.log(
                    EventKind.SERVICE,
                    "${rule.label}: is ${if (real) "suspended" else "not suspended"}, the database said otherwise",
                )
                state = state.copy(appliedSuspended = real)
            }
            state = applyDesiredLocked(rule, state, now)
            if (state != stored) repo.upsertDayState(state)
        }
    }

    private suspend fun foregroundLocked(packageName: String, foreground: Boolean, now: Long) {
        if (foreground) {
            val previous = foregroundPackage
            foregroundPackage = packageName
            tracker.onForeground(packageName, now)
            if (previous != null && previous != packageName) {
                rules[previous]?.let { rule -> dispatchLocked(rule, null, PauseEvent.Leave, now) }
            }
            rules[packageName]?.let { rule ->
                repo.log(EventKind.FOREGROUND, "${rule.label}: in front")
                dispatchLocked(rule, null, PauseEvent.Enter, now)
            }
        } else {
            if (foregroundPackage == packageName) foregroundPackage = null
            tracker.onBackground(packageName, now)
            rules[packageName]?.let { rule -> dispatchLocked(rule, null, PauseEvent.Leave, now) }
        }
        repo.updateRuntime { it.copy(foregroundPackage = foregroundPackage) }
    }

    private suspend fun dispatchLocked(rule: AppRule, current: DayState?, event: PauseEvent, now: Long) {
        val settings = repo.settingsNow()
        val state = current
            ?: repo.latestDayState(rule.packageName)
            ?: DayState(rule.packageName, ResetClock.dayKey(now, settings.resetHour, zone), updatedAt = now)
        val transition = PauseEngine.reduce(state, rule, event, now)
        if (transition.state == state && transition.effects.isEmpty()) return
        for (effect in transition.effects) runEffect(rule.packageName, effect)
        val next = applyDesiredLocked(rule, transition.state, now)
        repo.upsertDayState(next)
        onStateChanged()
    }

    private suspend fun runEffect(packageName: String, effect: Effect) {
        when (effect) {
            is Effect.ArmTimer -> armTimer(packageName, effect.kind, effect.atMillis)
            is Effect.CancelTimer -> cancelTimer(packageName, effect.kind)
            is Effect.Log -> repo.log(effect.kind, effect.detail)
        }
    }

    /**
     * Sends the suspension the state calls for, if it differs from what was last applied. A
     * failure (Shizuku down) leaves the database untouched apart from the pending flag; the
     * failure direction is always "stays paused".
     */
    private suspend fun applyDesiredLocked(rule: AppRule, state: DayState, now: Long): DayState {
        val desired = PauseEngine.desiredSuspended(rule, state, ResetClock.minuteOfDay(now, zone))
        if (desired == state.appliedSuspended) {
            return if (state.applyPending) state.copy(applyPending = false, updatedAt = now) else state
        }
        val err = bridge.setSuspended(rule.packageName, desired)
        return if (err == null) {
            repo.log(
                if (desired) EventKind.PAUSED else EventKind.UNPAUSED,
                "${rule.label}: ${if (desired) "suspended" else "unsuspended"}",
            )
            state.copy(appliedSuspended = desired, applyPending = false, updatedAt = now)
        } else {
            repo.log(EventKind.ERROR, "${rule.label}: could not ${if (desired) "suspend" else "unsuspend"} ($err)")
            onApplyFailed(err)
            state.copy(applyPending = true, updatedAt = now)
        }
    }

    private fun armTimer(packageName: String, kind: TimerKind, atMillis: Long) {
        cancelTimer(packageName, kind)
        timers[packageName to kind] = scope.launch {
            val wait = atMillis - now()
            if (wait > 0) delay(wait)
            mutex.withLock {
                timers.remove(packageName to kind)
                val rule = rules[packageName] ?: return@withLock
                val event = when (kind) {
                    TimerKind.GRACE -> PauseEvent.GraceExpired
                    TimerKind.MAX_SESSION -> PauseEvent.MaxSessionReached
                    TimerKind.DAILY_BUDGET -> PauseEvent.BudgetExhausted
                    TimerKind.COOLDOWN -> PauseEvent.CooldownExpired
                }
                dispatchLocked(rule, null, event, now())
            }
        }
    }

    private fun cancelTimer(packageName: String, kind: TimerKind) {
        timers.remove(packageName to kind)?.cancel()
    }

    private fun cancelTimers(packageName: String) {
        for (kind in TimerKind.entries) cancelTimer(packageName, kind)
    }

    /** True/false from the package manager, null if the package is gone. */
    private fun realSuspended(packageName: String): Boolean? = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.flags and ApplicationInfo.FLAG_SUSPENDED != 0
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun describe(rule: AppRule): String = buildString {
        val parts = ArrayList<String>(4)
        if (rule.openingsEnabled) parts += "${rule.dailyOpenings} opening${if (rule.dailyOpenings == 1) "" else "s"} a day"
        if (rule.timeBudgetEnabled) parts += "${rule.dailyMinutes} min a day"
        if (rule.cooldownEnabled) parts += "${formatSeconds(rule.cooldownSec)} cooldown"
        if (rule.windows.isNotEmpty()) parts += "curfew ${rule.windows.joinToString { it.toString() }}"
        append(if (parts.isEmpty()) "no limits" else parts.joinToString(", "))
        if (rule.tracksSessions) append("; grace ${rule.graceSec}s, max ${rule.maxSessionMin} min")
        if (!rule.enabled) append("; disabled")
    }

    companion object {
        const val VISIT_RETENTION_MS = 7L * 24 * 3_600_000L
        const val RECOMMENDATION_RETENTION_MS = 24L * 3_600_000L
    }
}
