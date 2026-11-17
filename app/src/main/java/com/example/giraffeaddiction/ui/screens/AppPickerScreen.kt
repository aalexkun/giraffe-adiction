package com.example.giraffeaddiction.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.ui.AppCatalog
import com.example.giraffeaddiction.ui.components.AppIcon
import com.example.giraffeaddiction.ui.components.GiraffeCard
import com.example.giraffeaddiction.ui.components.Hairline
import com.example.giraffeaddiction.ui.components.ListRow
import com.example.giraffeaddiction.ui.components.PillShape
import com.example.giraffeaddiction.ui.components.ScreenHeader
import com.example.giraffeaddiction.ui.theme.Ember
import com.example.giraffeaddiction.ui.theme.Moss
import com.example.giraffeaddiction.ui.theme.Tones
import kotlinx.coroutines.launch

/** Every launchable app with an icon and a search box. Tapping one adds it with the defaults. */
@Composable
fun AppPickerScreen(onPicked: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = Graph.repo
    val scope = rememberCoroutineScope()
    val rules by repo.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppCatalog.Entry>?>(null) }

    LaunchedEffect(Unit) { apps = AppCatalog.launchable(context) }

    val listed = rules.map { it.packageName }.toSet()
    val visible = (apps ?: emptyList()).filter { entry ->
        (showSystem || !entry.system) &&
            (query.isBlank() || entry.label.contains(query, ignoreCase = true) || entry.packageName.contains(query, ignoreCase = true))
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        ) {
            item {
                ScreenHeader(eyebrow = "Add an app", title = "Which one?")
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Tones.faint) },
                    singleLine = true,
                    shape = PillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Tones.rule,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Show preinstalled system apps", style = MaterialTheme.typography.bodySmall, color = Tones.muted)
                    Switch(
                        checked = showSystem,
                        onCheckedChange = { showSystem = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (apps == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ember)
                    }
                }
            } else {
                item {
                    GiraffeCard(padding = PaddingValues(vertical = 4.dp)) {
                        visible.forEachIndexed { index, entry ->
                            val already = entry.packageName in listed
                            ListRow(
                                title = entry.label,
                                subtitle = entry.packageName,
                                onClick = {
                                    if (already) {
                                        onPicked(entry.packageName)
                                    } else {
                                        scope.launch {
                                            repo.upsertRule(AppRule(packageName = entry.packageName, label = entry.label))
                                            onPicked(entry.packageName)
                                        }
                                    }
                                },
                                leading = { AppIcon(AppCatalog.icon(context, entry.packageName)) },
                                trailing = if (already) ({ Icon(Icons.Outlined.Check, contentDescription = "On the list", tint = Moss) }) else null,
                            )
                            if (index < visible.lastIndex) Hairline(Modifier.padding(horizontal = 20.dp))
                        }
                        if (visible.isEmpty()) {
                            Text("No app matches.", style = MaterialTheme.typography.bodyMedium, color = Tones.muted, modifier = Modifier.padding(20.dp))
                        }
                    }
                }
            }
        }
    }
}
