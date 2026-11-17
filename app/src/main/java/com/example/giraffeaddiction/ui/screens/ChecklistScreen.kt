package com.example.giraffeaddiction.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.giraffeaddiction.R
import com.example.giraffeaddiction.data.Settings
import com.example.giraffeaddiction.ui.CheckActions
import com.example.giraffeaddiction.ui.SetupCheck
import com.example.giraffeaddiction.ui.SetupChecks
import com.example.giraffeaddiction.ui.components.GhostButton
import com.example.giraffeaddiction.ui.components.GiraffeCard
import com.example.giraffeaddiction.ui.components.Hairline
import com.example.giraffeaddiction.ui.components.Mascot
import com.example.giraffeaddiction.ui.components.PrimaryButton
import com.example.giraffeaddiction.ui.components.ScreenHeader
import com.example.giraffeaddiction.ui.components.StatusDot
import com.example.giraffeaddiction.ui.components.Tone
import com.example.giraffeaddiction.ui.theme.Tones

/**
 * The first screen: one line per thing the giraffe needs, a dot that says whether it has it, and
 * a button that jumps to the right place. Nothing else works until the required ones are green.
 */
@Composable
fun ChecklistScreen(
    refreshTick: Int,
    settings: Settings,
    actions: CheckActions,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val checks = remember(refreshTick, settings) { SetupChecks.build(context, settings, actions) }
    val ready = SetupChecks.ready(checks)
    val allGood = checks.all { it.tone == Tone.GOOD }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ScreenHeader(
                        eyebrow = "Before the giraffe can watch",
                        title = if (allGood) "Everything in place." else if (ready) "Nearly there." else "A few doors to open.",
                        lede = if (ready) {
                            "The engine can run. The rest makes it reliable."
                        } else {
                            "The first three are the engine itself; the rest keep it alive and give the VPN half something to talk to."
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Mascot(if (allGood) R.drawable.giraffe_sparkle else R.drawable.giraffe_wtf, 112.dp)
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            GiraffeCard(padding = PaddingValues(vertical = 4.dp)) {
                checks.forEachIndexed { index, check ->
                    CheckRow(check)
                    if (index < checks.lastIndex) Hairline(Modifier.padding(horizontal = 20.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            PrimaryButton(
                text = if (ready) "Continue" else "Continue anyway",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The Shizuku approval and the WireGuard grant are per install: keep the same signing key.",
                style = MaterialTheme.typography.bodySmall,
                color = Tones.faint,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun CheckRow(check: SetupCheck) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(check.tone, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(check.title, style = MaterialTheme.typography.titleMedium)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = Tones.muted)
        }
        if (check.actionLabel != null && check.action != null) {
            Spacer(Modifier.width(12.dp))
            GhostButton(check.actionLabel, onClick = check.action)
        }
    }
}
