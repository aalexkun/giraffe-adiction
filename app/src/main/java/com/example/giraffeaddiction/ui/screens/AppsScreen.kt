package com.example.giraffeaddiction.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.R
import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.BlockedWindow
import com.example.giraffeaddiction.data.DayState
import com.example.giraffeaddiction.data.PauseState
import com.example.giraffeaddiction.engine.PauseEngine
import com.example.giraffeaddiction.engine.ResetClock
import com.example.giraffeaddiction.engine.formatSeconds
import com.example.giraffeaddiction.ui.AppCatalog
import com.example.giraffeaddiction.ui.components.AppIcon
import com.example.giraffeaddiction.ui.components.Chip
import com.example.giraffeaddiction.ui.components.GiraffeCard
import com.example.giraffeaddiction.ui.components.Hairline
import com.example.giraffeaddiction.ui.components.ListRow
import com.example.giraffeaddiction.ui.components.Mascot
import com.example.giraffeaddiction.ui.components.PrimaryButton
import com.example.giraffeaddiction.ui.components.ScreenHeader
import com.example.giraffeaddiction.ui.components.Tone
import com.example.giraffeaddiction.ui.components.rememberNow
import com.example.giraffeaddiction.ui.theme.Tones
import java.time.ZoneId

/** The list of addictive apps, each with where it stands today. */
@Composable
fun AppsScreen(onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val repo = Graph.repo
    val rules by repo.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val states by repo.dayStates.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val now = rememberNow()
    val resetHour = settings?.resetHour ?: 4

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        ) {
            item {
                ScreenHeader(
                    eyebrow = "Addictive apps",
                    title = if (rules.isEmpty()) "The list is empty." else "The list.",
                    lede = if (rules.isEmpty()) {
                        "Each app on it gets a few openings a day, a time budget, a cooldown between visits, a curfew, or any mix, and is then closed by Android itself."
                    } else {
                        "Openings, minutes, cooldowns and curfews, mixed per app. Tap one to change its rule."
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
            if (rules.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Mascot(R.drawable.giraffe_sparkle, 180.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Add the app you keep reopening. The giraffe does the rest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tones.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                }
            } else {
                item {
                    GiraffeCard(padding = PaddingValues(vertical = 4.dp)) {
                        rules.forEachIndexed { index, rule ->
                            val state = states.firstOrNull { it.packageName == rule.packageName }
                            val (line, tone) = stateLine(rule, state, now, resetHour)
                            ListRow(
                                title = rule.label,
                                subtitle = line,
                                onClick = { onOpen(rule.packageName) },
                                leading = { AppIcon(AppCatalog.icon(context, rule.packageName)) },
                                trailing = { Chip(chipLabel(rule, state, now), tone) },
                            )
                            if (index < rules.lastIndex) Hairline(Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            PrimaryButton("Add an app", onClick = onAdd, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun chipLabel(rule: AppRule, state: DayState?, now: Long): String {
    val inWindow = BlockedWindow.anyContains(rule.windows, ResetClock.minuteOfDay(now, ZoneId.systemDefault()))
    return when {
        !rule.enabled -> "Off"
        inWindow -> "Curfew"
        !rule.tracksSessions -> if (rule.windows.isEmpty()) "No limit" else "Free"
        state?.state == PauseState.PAUSED -> "Resting"
        state?.state == PauseState.COOLDOWN -> "Cooldown"
        state?.state == PauseState.IN_SESSION -> "Open"
        state?.state == PauseState.GRACE -> "Away"
        else -> "Ready"
    }
}

/** The one-line status under an app's name, and the colour of its chip. */
fun stateLine(rule: AppRule, state: DayState?, now: Long, resetHour: Int): Pair<String, Tone> {
    val zone = ZoneId.systemDefault()
    val minute = ResetClock.minuteOfDay(now, zone)
    val inWindow = BlockedWindow.anyContains(rule.windows, minute)
    val pending = if (state?.applyPending == true) " · waiting for Shizuku" else ""
    if (!rule.enabled) return "Rule switched off" to Tone.OFF
    if (inWindow) {
        val end = ResetClock.nextWindowBoundary(rule.windows, now, zone)
        return ("Curfew until ${end?.let { formatTime(it) } ?: "later"}$pending") to Tone.OFF
    }
    if (!rule.tracksSessions) {
        val next = ResetClock.nextWindowBoundary(rule.windows, now, zone)
        return if (rule.windows.isEmpty()) "No limit set; open the rule and switch a policy on" to Tone.WARN
        else "Free until ${next?.let { formatTime(it) } ?: "later"}$pending" to Tone.GOOD
    }
    val resetAt = ResetClock.nextResetMillis(now, resetHour, zone)
    return when (state?.state) {
        PauseState.PAUSED -> {
            val used = PauseEngine.usage(rule, state)
            "Resting until ${formatTime(resetAt)}${if (used.isNotEmpty()) " · $used" else ""}$pending" to Tone.OFF
        }
        PauseState.COOLDOWN -> {
            val ends = state.cooldownEndsAt ?: now
            "Cooling down · ${remaining(ends - now)} left$pending" to Tone.WARN
        }
        PauseState.IN_SESSION -> {
            val started = state.sessionStartedAt ?: now
            var endsAt = started + rule.maxSessionMin * 60_000L
            if (rule.timeBudgetEnabled) endsAt = minOf(endsAt, started + (rule.budgetMillis - state.usedMillis).coerceAtLeast(0L))
            "In session · ${remaining(endsAt - now)} left" to Tone.LIVE
        }
        PauseState.GRACE -> {
            val graceEnds = (state.leftAt ?: now) + rule.graceSec * 1_000L
            "Away · ${remaining(graceEnds - now)} of grace left" to Tone.WARN
        }
        else -> "Ready · ${readySummary(rule, state)}" to Tone.GOOD
    }
}

/** What is left of the day's allowances, for a ready app. */
private fun readySummary(rule: AppRule, state: DayState?): String {
    val used = state?.sessionsUsed ?: 0
    val usedMillis = state?.usedMillis ?: 0L
    val parts = ArrayList<String>(3)
    if (rule.openingsEnabled) {
        val left = (rule.dailyOpenings - used).coerceAtLeast(0)
        parts += "$left of ${rule.dailyOpenings} opening${if (rule.dailyOpenings == 1) "" else "s"} left"
    }
    if (rule.timeBudgetEnabled) {
        val min = ((rule.budgetMillis - usedMillis).coerceAtLeast(0L) + 59_999L) / 60_000L
        parts += "$min of ${rule.dailyMinutes} min left"
    }
    if (rule.cooldownEnabled) parts += "${formatSeconds(rule.cooldownSec)} cooldown after each"
    return parts.joinToString(" · ")
}

private fun remaining(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m} min ${"%02d".format(s)} s" else "$s s"
}
