package com.example.giraffeaddiction.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.R
import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.EventLog
import com.example.giraffeaddiction.data.GiraffeRepository
import com.example.giraffeaddiction.data.PauseState
import com.example.giraffeaddiction.data.Recommendation
import com.example.giraffeaddiction.data.Settings
import com.example.giraffeaddiction.data.ShizukuStatus
import com.example.giraffeaddiction.engine.ResetClock
import com.example.giraffeaddiction.engine.pitch
import com.example.giraffeaddiction.engine.summary
import com.example.giraffeaddiction.service.PauserForegroundService
import com.example.giraffeaddiction.ui.AppCatalog
import com.example.giraffeaddiction.ui.CheckActions
import com.example.giraffeaddiction.ui.SetupChecks
import com.example.giraffeaddiction.ui.components.AppIcon
import com.example.giraffeaddiction.ui.components.Eyebrow
import com.example.giraffeaddiction.ui.components.GhostButton
import com.example.giraffeaddiction.ui.components.GiraffeCard
import com.example.giraffeaddiction.ui.components.Hairline
import com.example.giraffeaddiction.ui.components.Headline
import com.example.giraffeaddiction.ui.components.InfoRow
import com.example.giraffeaddiction.ui.components.Lede
import com.example.giraffeaddiction.ui.components.Mascot
import com.example.giraffeaddiction.ui.components.PrimaryButton
import com.example.giraffeaddiction.ui.components.StatusDot
import com.example.giraffeaddiction.ui.components.Tone
import com.example.giraffeaddiction.ui.components.rememberNow
import com.example.giraffeaddiction.ui.theme.MonoStyle
import com.example.giraffeaddiction.ui.theme.Tones
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
private val dayShort = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormat)
fun formatClock(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clockFormat)
fun formatDayShort(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dayShort)

/** The status screen: one picture, one sentence, the pinned recommendation, then the facts and the log. */
@Composable
fun TodayScreen(
    refreshTick: Int,
    settings: Settings,
    actions: CheckActions,
    onOpenChecklist: () -> Unit,
) {
    val context = LocalContext.current
    val repo = Graph.repo
    val scope = rememberCoroutineScope()
    val runtime by repo.runtime.collectAsStateWithLifecycle(initialValue = null)
    val rules by repo.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val states by repo.dayStates.collectAsStateWithLifecycle(initialValue = emptyList())
    val recommendations by repo.recommendations.collectAsStateWithLifecycle(initialValue = emptyList())
    val events by repo.events(150).collectAsStateWithLifecycle(initialValue = emptyList())
    val now = rememberNow()
    val checks = remember(refreshTick, settings) { SetupChecks.build(context, settings, actions) }
    val ready = SetupChecks.ready(checks)

    val r = runtime ?: return
    val watched = states.filter { s -> rules.any { it.packageName == s.packageName } }
    val pausedCount = watched.count { it.state == PauseState.PAUSED }
    val coolingCount = watched.count { it.state == PauseState.COOLDOWN }
    val overrideActive = settings.overrideActive(now)
    val shizukuOk = r.shizuku == ShizukuStatus.CONNECTED
    // Only what is still open: not put off, and not already answered by a cooldown on that app.
    val open = recommendations.filter { rec ->
        !rec.dismissed(now) && rules.none { it.packageName == rec.packageName && it.cooldownEnabled }
    }

    val hero = when {
        !ready -> Hero(R.drawable.giraffe_wtf, "Not quite set up.", "A few permissions are still missing. The checklist knows which.")
        rules.isNotEmpty() && !shizukuOk -> Hero(R.drawable.giraffe_scare, "Shizuku stopped.", "Paused apps stay paused until it is back. Start it from the Shizuku app.")
        pausedCount > 0 -> Hero(
            R.drawable.giraffe_arf,
            if (pausedCount == 1) "One app is resting." else "$pausedCount apps are resting.",
            "Back at ${"%02d:00".format(settings.resetHour)}. The rest of the day is yours.",
        )
        coolingCount > 0 -> Hero(
            R.drawable.giraffe_working,
            if (coolingCount == 1) "One app is cooling down." else "$coolingCount apps are cooling down.",
            "A short lock after each visit, so the next one is a choice.",
        )
        r.isHome && settings.vpnAutomationEnabled -> Hero(R.drawable.giraffe_love, "Welcome home.", if (r.vpnUp) "Tunnel is still up; asking WireGuard to drop it." else "Tunnel down, the LAN is yours.")
        rules.isEmpty() -> Hero(R.drawable.giraffe_sparkle, "A fresh start.", "Nothing on the list yet. Add an app and the giraffe takes it from there.")
        else -> Hero(R.drawable.giraffe_awesome, "All quiet.", "Watching ${rules.size} ${if (rules.size == 1) "app" else "apps"}. ${if (settings.vpnAutomationEnabled) "Tunnel up on the move, down at home." else "VPN automation is off."}")
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
    ) {
        item {
            Eyebrow("Today · " + Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(dayFormat))
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(hero.image, 208.dp)
                Spacer(Modifier.height(20.dp))
                Headline(hero.title, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    hero.lede,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tones.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (!ready) {
                    Spacer(Modifier.height(16.dp))
                    GhostButton("Open the checklist", onClick = onOpenChecklist)
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        if (open.isNotEmpty()) {
            item {
                Eyebrow("Recommended")
                Spacer(Modifier.height(10.dp))
                GiraffeCard(tinted = true) {
                    open.forEachIndexed { index, rec ->
                        RecommendationBlock(
                            rec = rec,
                            onApply = { scope.launch { applyRecommendation(repo, rec) } },
                            onLater = {
                                scope.launch {
                                    repo.dismissRecommendation(
                                        rec.packageName,
                                        ResetClock.nextResetMillis(now, settings.resetHour, ZoneId.systemDefault()),
                                    )
                                }
                            },
                        )
                        if (index < open.lastIndex) {
                            Spacer(Modifier.height(16.dp))
                            Hairline()
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item {
            Eyebrow("Engine")
            Spacer(Modifier.height(10.dp))
            GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
                InfoRow(
                    "Shizuku",
                    when (r.shizuku) {
                        ShizukuStatus.CONNECTED -> "connected" + if (r.observerMode.isNotBlank()) " · ${r.observerMode}" else ""
                        ShizukuStatus.CONNECTING -> "connecting"
                        ShizukuStatus.NO_PERMISSION -> "permission missing"
                        ShizukuStatus.DEAD -> "worker stopped"
                        ShizukuStatus.NOT_RUNNING -> "not running"
                    },
                    tone = if (shizukuOk) Tone.GOOD else if (r.shizuku == ShizukuStatus.CONNECTING) Tone.WARN else Tone.BAD,
                )
                Hairline()
                InfoRow(
                    "Watching",
                    buildString {
                        append("${rules.size} ${if (rules.size == 1) "app" else "apps"}, $pausedCount resting")
                        if (coolingCount > 0) append(", $coolingCount cooling down")
                    },
                    tone = if (rules.isEmpty()) Tone.OFF else Tone.LIVE,
                )
                Hairline()
                InfoRow("Next reset", if (r.nextResetAt > 0) "${formatDayShort(r.nextResetAt)} ${formatTime(r.nextResetAt)}" else "not armed", mono = true, tone = Tone.OFF)
                Hairline()
                InfoRow("Service", if (r.serviceRunning) "running" else "stopped", tone = if (r.serviceRunning) Tone.GOOD else Tone.BAD)
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            Eyebrow("Home VPN")
            Spacer(Modifier.height(10.dp))
            GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
                InfoRow(
                    "Network",
                    when {
                        r.wifiConnected -> "WiFi " + (r.wifiSsid ?: "(name hidden)")
                        r.anyInternet -> "mobile data"
                        else -> "nothing"
                    },
                    tone = if (r.wifiConnected || r.anyInternet) Tone.GOOD else Tone.OFF,
                )
                Hairline()
                InfoRow(
                    "Home",
                    if (!r.wifiConnected) "not on WiFi" else if (r.isHome) "yes" else "no",
                    tone = if (r.isHome) Tone.LIVE else Tone.OFF,
                )
                if (r.homeReason.isNotBlank()) {
                    Text(r.homeReason, style = MaterialTheme.typography.bodySmall, color = Tones.faint, modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
                }
                Hairline()
                val lastTarget = r.lastVpnTarget
                InfoRow(
                    "Tunnel",
                    buildString {
                        append(if (r.vpnUp) "up" else "down")
                        if (lastTarget != null) append(" · asked ${lastTarget.lowercase()} at ${formatTime(r.lastVpnSentAt)}")
                    },
                    tone = if (r.vpnUp) Tone.GOOD else Tone.OFF,
                )
                Hairline()
                InfoRow(
                    "Automation",
                    when {
                        !settings.vpnAutomationEnabled -> "off"
                        settings.overrideUntil == Settings.OVERRIDE_UNTIL_RECONNECT -> "paused until the next WiFi"
                        overrideActive -> "paused until ${formatTime(settings.overrideUntil)}"
                        else -> "on"
                    },
                    tone = if (settings.vpnAutomationEnabled && !overrideActive) Tone.GOOD else Tone.WARN,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    if (overrideActive) {
                        GhostButton("Resume", onClick = { PauserForegroundService.override(context, 0L) })
                    } else {
                        GhostButton("Pause 30 min", onClick = { PauserForegroundService.override(context, now + 30L * 60_000L) })
                        GhostButton("2 h", onClick = { PauserForegroundService.override(context, now + 2L * 3_600_000L) })
                        GhostButton("Until WiFi", onClick = { PauserForegroundService.override(context, Settings.OVERRIDE_UNTIL_RECONNECT) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Recent events", Modifier.weight(1f))
                if (events.isNotEmpty()) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tones.terracotta,
                        modifier = Modifier
                            .clickable { scope.launch { repo.clearLog() } }
                            .padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (events.isEmpty()) {
                Lede("Nothing has happened yet.")
            }
        }
        items(events, key = { it.id }) { event ->
            EventRow(event)
        }
    }
}

private data class Hero(val image: Int, val title: String, val lede: String)

/** One recommendation: the pattern in a sentence, what the policy would change, and two buttons. */
@Composable
private fun RecommendationBlock(rec: Recommendation, onApply: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(AppCatalog.icon(context, rec.packageName), 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${rec.label} is being checked, not read.", style = MaterialTheme.typography.titleMedium)
            Text(rec.summary(), style = MaterialTheme.typography.bodySmall, color = Tones.muted)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(rec.pitch(), style = MaterialTheme.typography.bodySmall, color = Tones.muted)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Add the cooldown", onClick = onApply)
        GhostButton("Later", onClick = onLater)
    }
}

/**
 * Applies exactly the recommended policy and nothing else: an app already on the list keeps its
 * other policies and gains the cooldown; a new one gets the cooldown alone, openings off.
 */
suspend fun applyRecommendation(repo: GiraffeRepository, rec: Recommendation) {
    val existing = repo.getRule(rec.packageName)
    val rule = existing?.copy(cooldownEnabled = true, cooldownSec = AppRule.DEFAULT_COOLDOWN_SEC, enabled = true)
        ?: AppRule(
            packageName = rec.packageName,
            label = rec.label,
            openingsEnabled = false,
            cooldownEnabled = true,
            cooldownSec = AppRule.DEFAULT_COOLDOWN_SEC,
        )
    repo.upsertRule(rule)
    repo.log(EventKind.PATTERN, "${rec.label}: 2 min cooldown added from the recommendation")
}

@Composable
private fun EventRow(event: EventLog) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(formatClock(event.at), style = MonoStyle, color = Tones.faint, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        StatusDot(event.kind.tone(), 8.dp, Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(event.kind.label(), style = MaterialTheme.typography.labelSmall, color = Tones.faint)
            Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

fun EventKind.tone(): Tone = when (this) {
    EventKind.PAUSED -> Tone.OFF
    EventKind.UNPAUSED, EventKind.RESET, EventKind.SHIZUKU_UP -> Tone.GOOD
    EventKind.FOREGROUND, EventKind.SESSION -> Tone.LIVE
    EventKind.PATTERN, EventKind.VPN_SENT, EventKind.WIFI_CHANGED, EventKind.OVERRIDE, EventKind.SERVICE -> Tone.WARN
    EventKind.VPN_MISMATCH, EventKind.SHIZUKU_DOWN, EventKind.ERROR -> Tone.BAD
}

fun EventKind.label(): String = name.lowercase().replace('_', ' ')
