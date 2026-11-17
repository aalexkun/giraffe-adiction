package com.example.giraffeaddiction.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.BlockedWindow
import com.example.giraffeaddiction.engine.formatSeconds
import com.example.giraffeaddiction.ui.AppCatalog
import com.example.giraffeaddiction.ui.components.AppIcon
import com.example.giraffeaddiction.ui.components.Eyebrow
import com.example.giraffeaddiction.ui.components.GhostButton
import com.example.giraffeaddiction.ui.components.GiraffeCard
import com.example.giraffeaddiction.ui.components.Hairline
import com.example.giraffeaddiction.ui.components.InfoRow
import com.example.giraffeaddiction.ui.components.PrimaryButton
import com.example.giraffeaddiction.ui.theme.Ember
import com.example.giraffeaddiction.ui.theme.MonoStyle
import com.example.giraffeaddiction.ui.theme.Tones
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One app's rule: four policies that mix freely (openings a day, time a day, cooldown, curfew),
 * the shape of a session, the on/off switch, and the way out.
 */
@Composable
fun RuleEditorScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = Graph.repo
    val scope = rememberCoroutineScope()
    val stored by repo.rule(packageName).collectAsStateWithLifecycle(initialValue = null)
    var draft by remember { mutableStateOf<AppRule?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var addingWindow by remember { mutableStateOf(false) }

    LaunchedEffect(stored) {
        if (draft == null && stored != null) draft = stored
    }
    val rule = draft ?: return
    val dirty = rule != stored

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppCatalog.icon(context, packageName), 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(rule.label, style = MaterialTheme.typography.headlineMedium)
                    Text(packageName, style = MonoStyle, color = Tones.faint)
                }
            }
            Spacer(Modifier.height(28.dp))

            Eyebrow("Policies")
            Spacer(Modifier.height(6.dp))
            Text(
                "Mix any of them. While one holds, Android itself keeps the app closed; there is no snooze.",
                style = MaterialTheme.typography.bodySmall,
                color = Tones.muted,
            )
            Spacer(Modifier.height(14.dp))

            PolicyCard(
                title = "Openings a day",
                summary = if (rule.openingsEnabled) "${rule.dailyOpenings}" else "off",
                on = rule.openingsEnabled,
                onToggle = { draft = rule.copy(openingsEnabled = it) },
            ) {
                SliderRow(
                    label = "Openings",
                    value = rule.dailyOpenings.toFloat(),
                    display = "${rule.dailyOpenings}",
                    range = AppRule.OPENINGS_RANGE.first.toFloat()..AppRule.OPENINGS_RANGE.last.toFloat(),
                    steps = AppRule.OPENINGS_RANGE.last - AppRule.OPENINGS_RANGE.first - 1,
                    onChange = { draft = rule.copy(dailyOpenings = it.roundToInt().coerceIn(AppRule.OPENINGS_RANGE)) },
                )
                Hint("Each opening is one session. After the last one the app rests until the reset hour.")
            }
            Spacer(Modifier.height(12.dp))

            PolicyCard(
                title = "Time a day",
                summary = if (rule.timeBudgetEnabled) "${rule.dailyMinutes} min" else "off",
                on = rule.timeBudgetEnabled,
                onToggle = { draft = rule.copy(timeBudgetEnabled = it) },
            ) {
                SliderRow(
                    label = "Minutes in all",
                    value = rule.dailyMinutes.toFloat(),
                    display = "${rule.dailyMinutes} min",
                    range = AppRule.MINUTES_RANGE.first.toFloat()..AppRule.MINUTES_RANGE.last.toFloat(),
                    steps = (AppRule.MINUTES_RANGE.last - AppRule.MINUTES_RANGE.first) / 5 - 1,
                    onChange = { draft = rule.copy(dailyMinutes = ((it / 5f).roundToInt() * 5).coerceIn(AppRule.MINUTES_RANGE)) },
                )
                Hint("Sessions add up, grace included. When the minutes are gone the app rests until the reset hour.")
            }
            Spacer(Modifier.height(12.dp))

            PolicyCard(
                title = "Cooldown",
                summary = if (rule.cooldownEnabled) formatSeconds(rule.cooldownSec) else "off",
                on = rule.cooldownEnabled,
                onToggle = { draft = rule.copy(cooldownEnabled = it) },
            ) {
                SliderRow(
                    label = "Locked after each session",
                    value = rule.cooldownSec.toFloat(),
                    display = formatSeconds(rule.cooldownSec),
                    range = AppRule.COOLDOWN_RANGE.first.toFloat()..AppRule.COOLDOWN_RANGE.last.toFloat(),
                    steps = (AppRule.COOLDOWN_RANGE.last - AppRule.COOLDOWN_RANGE.first) / 10 - 1,
                    onChange = { draft = rule.copy(cooldownSec = ((it / 10f).roundToInt() * 10).coerceIn(AppRule.COOLDOWN_RANGE)) },
                )
                Hint(
                    "Like a skill cooldown in a game: once you leave, the app stays closed for this long before it can be opened again. " +
                        "Each return becomes a decision instead of a reflex. With one opening a day it changes nothing; with more, or with a time budget, it spaces them out.",
                )
            }
            Spacer(Modifier.height(12.dp))

            GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                val windows = rule.windows
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Curfew", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (windows.isEmpty()) "off" else "${windows.size} window${if (windows.size == 1) "" else "s"}", style = MonoStyle, color = Tones.muted)
                }
                Spacer(Modifier.height(4.dp))
                if (windows.isEmpty()) {
                    Hint("Paused inside fixed windows, say 21:00 to 07:00, whatever else is on. No sessions involved.")
                }
                windows.forEachIndexed { index, w ->
                    InfoRow(w.toString(), mono = true) {
                        IconButton(onClick = {
                            draft = rule.copy(blockedWindows = BlockedWindow.encodeList(windows.filterIndexed { i, _ -> i != index }))
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = Tones.faint)
                        }
                    }
                    if (index < windows.lastIndex) Hairline()
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("Add a window", onClick = { addingWindow = true })
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(24.dp))

            if (rule.tracksSessions) {
                Eyebrow("Session shape")
                Spacer(Modifier.height(10.dp))
                GiraffeCard {
                    SliderRow(
                        label = "Grace when you leave",
                        value = rule.graceSec.toFloat(),
                        display = "${rule.graceSec} s",
                        range = 0f..300f,
                        steps = 29,
                        onChange = { draft = rule.copy(graceSec = it.roundToInt()) },
                    )
                    Hint("A glance at a notification does not end the session, or start a cooldown.")
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    SliderRow(
                        label = "Longest session",
                        value = rule.maxSessionMin.toFloat(),
                        display = "${rule.maxSessionMin} min",
                        range = 1f..120f,
                        steps = 118,
                        onChange = { draft = rule.copy(maxSessionMin = it.roundToInt()) },
                    )
                    Hint("Counted from the opening, grace included.")
                }
                Spacer(Modifier.height(24.dp))
            } else if (rule.windows.isEmpty()) {
                Text(
                    "Nothing is on: the app is free all day. Switch a policy on or add a curfew window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(24.dp))
            }

            GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
                InfoRow("Rule switched on") {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { draft = rule.copy(enabled = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Tones.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            GhostButton("Remove from the list", onClick = { confirmRemove = true }, danger = true)
            Text(
                "Removing an app unpauses it immediately. That is the only way out; there is no snooze.",
                style = MaterialTheme.typography.bodySmall,
                color = Tones.faint,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            PrimaryButton(
                text = if (dirty) "Save" else "Saved",
                enabled = dirty,
                onClick = {
                    scope.launch {
                        repo.upsertRule(rule)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${rule.label}?") },
            text = { Text("It is unpaused at once and the giraffe stops watching it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        repo.deleteRule(packageName)
                        onBack()
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (addingWindow) {
        WindowDialog(
            onDismiss = { addingWindow = false },
            onAdd = { w ->
                addingWindow = false
                draft = rule.copy(blockedWindows = BlockedWindow.encodeList(rule.windows + w))
            },
        )
    }
}

/** A policy: its name, a switch, and its controls when it is on. */
@Composable
private fun PolicyCard(
    title: String,
    summary: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(summary, style = MonoStyle, color = Tones.muted)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Tones.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary),
            )
        }
        if (on) {
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tones.faint)
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(display, style = MonoStyle, color = Tones.muted)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Ember,
            activeTrackColor = Tones.primary,
            inactiveTrackColor = Tones.rule,
            activeTickColor = Tones.primary,
            inactiveTickColor = Tones.rule,
        ),
    )
}

/** Two dials, start then end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowDialog(onDismiss: () -> Unit, onAdd: (BlockedWindow) -> Unit) {
    var pickingEnd by remember { mutableStateOf(false) }
    val start = rememberTimePickerState(initialHour = 21, initialMinute = 0, is24Hour = true)
    val end = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pickingEnd) "Curfew ends at" else "Curfew starts at") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TimePicker(state = if (pickingEnd) end else start)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!pickingEnd) {
                    pickingEnd = true
                } else {
                    onAdd(BlockedWindow(start.hour * 60 + start.minute, end.hour * 60 + end.minute))
                }
            }) { Text(if (pickingEnd) "Add" else "Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
