package com.example.giraffeaddiction.ui

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.engine.ShizukuBridge
import com.example.giraffeaddiction.service.PauserForegroundService
import com.example.giraffeaddiction.ui.components.BottomBar
import com.example.giraffeaddiction.ui.components.Tab
import com.example.giraffeaddiction.ui.screens.AppPickerScreen
import com.example.giraffeaddiction.ui.screens.AppsScreen
import com.example.giraffeaddiction.ui.screens.ChecklistScreen
import com.example.giraffeaddiction.ui.screens.RuleEditorScreen
import com.example.giraffeaddiction.ui.screens.SettingsScreen
import com.example.giraffeaddiction.ui.screens.TodayScreen
import com.example.giraffeaddiction.ui.theme.GiraffeTheme
import com.example.giraffeaddiction.vpn.WireGuardController
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private var refreshTick by mutableIntStateOf(0)

    private val shizukuPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshTick++
        PauserForegroundService.start(this, PauserForegroundService.ACTION_RECONCILE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionResult) }
        setContent {
            GiraffeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(refreshTick) { refreshTick++ }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The app is in front: a good moment to (re)start the watcher and claim the location type.
        PauserForegroundService.start(this)
        refreshTick++
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult) }
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(refreshTick: Int, bumpRefresh: () -> Unit) {
    val nav = rememberNavState()
    val repo = Graph.repo
    val context = LocalContext.current
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bumpRefresh() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bumpRefresh()
        // Lets the service re-claim the location FGS type now that the SSID is readable.
        PauserForegroundService.start(context)
    }
    val wireGuardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bumpRefresh() }

    val actions = remember(nav) {
        CheckActions(
            requestNotifications = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            requestLocation = {
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            requestWireGuard = { wireGuardLauncher.launch(WireGuardController.PERMISSION) },
            openSettingsScreen = { nav.select(Tab.SETTINGS) },
        )
    }

    LifecycleResumeEffect(Unit) {
        bumpRefresh()
        onPauseOrDispose { }
    }

    val current = settings ?: return
    // First run: the checklist is the whole app until it has been seen once.
    var checklistShown by remember { mutableIntStateOf(0) }
    LaunchedEffect(current.setupSeen) {
        if (!current.setupSeen && nav.top !is Screen.Checklist && checklistShown == 0) {
            checklistShown = 1
            nav.push(Screen.Checklist)
        }
    }

    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val top = nav.top) {
                is Screen.Checklist -> ChecklistScreen(
                    refreshTick = refreshTick,
                    settings = current,
                    actions = actions,
                    onDone = {
                        scope.launch { repo.updateSettings { it.copy(setupSeen = true) } }
                        nav.pop()
                    },
                )
                is Screen.Picker -> AppPickerScreen(
                    onPicked = { pkg -> nav.replace(Screen.Editor(pkg)) },
                    onBack = { nav.pop() },
                )
                is Screen.Editor -> RuleEditorScreen(packageName = top.packageName, onBack = { nav.pop() })
                null -> when (nav.tab) {
                    Tab.TODAY -> TodayScreen(
                        refreshTick = refreshTick,
                        settings = current,
                        actions = actions,
                        onOpenChecklist = { nav.push(Screen.Checklist) },
                    )
                    Tab.APPS -> AppsScreen(
                        onAdd = { nav.push(Screen.Picker) },
                        onOpen = { pkg -> nav.push(Screen.Editor(pkg)) },
                    )
                    Tab.SETTINGS -> SettingsScreen(onOpenChecklist = { nav.push(Screen.Checklist) })
                }
            }
        }
        if (nav.top == null) BottomBar(nav.tab, onSelect = { nav.select(it) })
    }
}
