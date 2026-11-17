package com.example.giraffeaddiction.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.data.HomeNetwork
import com.example.giraffeaddiction.data.Settings
import com.example.giraffeaddiction.service.PauserForegroundService
import com.example.giraffeaddiction.ui.components.Eyebrow
import com.example.giraffeaddiction.ui.components.GhostButton
import com.example.giraffeaddiction.ui.components.GiraffeCard
import com.example.giraffeaddiction.ui.components.Hairline
import com.example.giraffeaddiction.ui.components.InfoRow
import com.example.giraffeaddiction.ui.components.PillShape
import com.example.giraffeaddiction.ui.components.ScreenHeader
import com.example.giraffeaddiction.ui.theme.MonoStyle
import com.example.giraffeaddiction.ui.theme.Tones
import kotlinx.coroutines.launch

/** The house rules: when the day resets, what counts as home, and which tunnel to drive. */
@Composable
fun SettingsScreen(onOpenChecklist: () -> Unit) {
    val context = LocalContext.current
    val repo = Graph.repo
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val runtime by repo.runtime.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return

    fun update(transform: (Settings) -> Settings) {
        scope.launch { repo.updateSettings(transform) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        ScreenHeader(eyebrow = "Settings", title = "The house rules.")
        Spacer(Modifier.height(28.dp))

        Eyebrow("Daily reset")
        Spacer(Modifier.height(10.dp))
        GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            InfoRow("Every app is unpaused at", "%02d:00".format(s.resetHour), mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                GhostButton("Earlier", onClick = { update { it.copy(resetHour = (it.resetHour + 23) % 24) } })
                GhostButton("Later", onClick = { update { it.copy(resetHour = (it.resetHour + 1) % 24) } })
            }
            Text("The day rolls at this hour, not at midnight, so a late night is still the same day.", style = MaterialTheme.typography.bodySmall, color = Tones.faint, modifier = Modifier.padding(bottom = 12.dp))
        }
        Spacer(Modifier.height(24.dp))

        Eyebrow("Home VPN")
        Spacer(Modifier.height(10.dp))
        GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            InfoRow("Drive WireGuard from the network") {
                Switch(
                    checked = s.vpnAutomationEnabled,
                    onCheckedChange = { on -> update { it.copy(vpnAutomationEnabled = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary),
                )
            }
            Hairline()
            Spacer(Modifier.height(12.dp))
            CommitField(
                label = "Tunnel name, exactly as WireGuard shows it",
                value = s.tunnelName,
                onCommit = { v -> update { it.copy(tunnelName = v) } },
            )
            Spacer(Modifier.height(12.dp))
            CommitField(
                label = "Seconds to wait before acting",
                value = s.debounceSec.toString(),
                keyboard = KeyboardType.Number,
                onCommit = { v -> v.toIntOrNull()?.let { n -> update { it.copy(debounceSec = n.coerceIn(1, 120)) } } },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))

        Eyebrow("What counts as home")
        Spacer(Modifier.height(10.dp))
        GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            Text("Home WiFi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Saved networks match by name and, when saved, by access point. Needs the location permission to read the name.",
                style = MaterialTheme.typography.bodySmall,
                color = Tones.faint,
            )
            Spacer(Modifier.height(8.dp))
            val homes = s.homeNetworkList
            homes.forEachIndexed { index, h ->
                InfoRow(h.ssid, h.bssid.ifBlank { "any access point" }, mono = true) {
                    IconButton(onClick = {
                        update { it.copy(homeNetworks = HomeNetwork.encodeList(it.homeNetworkList.filterIndexed { i, _ -> i != index })) }
                    }) { Icon(Icons.Outlined.Close, contentDescription = "Forget", tint = Tones.faint) }
                }
                if (index < homes.lastIndex) Hairline()
            }
            val r = runtime
            val currentSsid = r?.wifiSsid
            val currentBssid = r?.wifiBssid
            Spacer(Modifier.height(8.dp))
            GhostButton(
                text = when {
                    r == null || !r.wifiConnected -> "Not on WiFi"
                    currentSsid == null -> "WiFi name unreadable (grant location)"
                    homes.any { it.ssid == currentSsid && (it.bssid.isBlank() || it.bssid.equals(currentBssid, true)) } -> "$currentSsid is already home"
                    else -> "Mark $currentSsid as home"
                },
                enabled = r != null && r.wifiConnected && currentSsid != null &&
                    homes.none { it.ssid == currentSsid && (it.bssid.isBlank() || it.bssid.equals(currentBssid, true)) },
                onClick = {
                    if (currentSsid != null) {
                        update { it.copy(homeNetworks = HomeNetwork.encodeList(it.homeNetworkList + HomeNetwork(currentSsid, currentBssid ?: ""))) }
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
            Hairline()
            Spacer(Modifier.height(16.dp))
            Text("LAN probe", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "A host that only answers on your home LAN: the router's admin page, a NAS, the WireGuard server's LAN address. Needs no permission and proves the LAN is really reachable. If both this and a WiFi name are set, both must match.",
                style = MaterialTheme.typography.bodySmall,
                color = Tones.faint,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CommitField(
                    label = "Host or IP",
                    value = s.probeHost,
                    modifier = Modifier.weight(2f),
                    onCommit = { v -> update { it.copy(probeHost = v.trim()) } },
                )
                CommitField(
                    label = "Port",
                    value = s.probePort.toString(),
                    keyboard = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                    onCommit = { v -> v.toIntOrNull()?.let { p -> update { it.copy(probePort = p.coerceIn(1, 65535)) } } },
                )
            }
            if (r != null && r.wifiConnected && r.homeReason.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Right now: ${r.homeReason}", style = MonoStyle, color = Tones.muted)
            }
        }
        Spacer(Modifier.height(24.dp))

        Eyebrow("Housekeeping")
        Spacer(Modifier.height(10.dp))
        GiraffeCard(padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Setup checklist", onClick = onOpenChecklist)
                GhostButton("Reconcile now", onClick = { PauserForegroundService.start(context, PauserForegroundService.ACTION_RECONCILE) })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Reconcile re-reads every app's real paused state from Android and lines the database up with it. The service does this by itself at boot and whenever Shizuku comes back.",
                style = MaterialTheme.typography.bodySmall,
                color = Tones.faint,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** A text field that writes through when focus leaves it, not on every keystroke. */
@Composable
private fun CommitField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = PillShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Tones.rule,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (focused && !state.isFocused && text != value) onCommit(text)
                focused = state.isFocused
            },
    )
}
