package com.example.giraffeaddiction.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.giraffeaddiction.Graph
import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.GiraffeRepository
import com.example.giraffeaddiction.data.RuntimeState
import com.example.giraffeaddiction.data.Settings
import com.example.giraffeaddiction.data.ShizukuStatus
import com.example.giraffeaddiction.engine.EngineRunner
import com.example.giraffeaddiction.engine.ResetClock
import com.example.giraffeaddiction.engine.ShizukuBridge
import com.example.giraffeaddiction.vpn.HomeDetector
import com.example.giraffeaddiction.vpn.NetworkWatcher
import com.example.giraffeaddiction.vpn.VpnAutomation
import com.example.giraffeaddiction.vpn.WireGuardController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The one always-on process of the app. It holds the pause engine, the Shizuku bridge and the VPN
 * automation, and it is the only thing that writes day state, runtime state and the log. The UI
 * never binds to it: it writes rules and settings to the database and this service reacts.
 */
class PauserForegroundService : LifecycleService(), ShizukuBridge.Listener {

    private lateinit var repo: GiraffeRepository
    private lateinit var notifications: Notifications
    private lateinit var alarms: AlarmScheduler
    private lateinit var bridge: ShizukuBridge
    private lateinit var engine: EngineRunner
    private lateinit var watcher: NetworkWatcher
    private lateinit var vpn: VpnAutomation

    private var lastRuntime: RuntimeState = RuntimeState()
    private var lastSettings: Settings = Settings()
    private var ruleCount = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> lifecycleScope.launch { engine.onScreenOff() }
                Intent.ACTION_USER_PRESENT -> lifecycleScope.launch { engine.onScreenOn() }
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> lifecycleScope.launch {
                    engine.checkReset("clock changed")
                    armAlarms()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        repo = Graph.repo
        notifications = Notifications(this)
        goForeground()

        alarms = AlarmScheduler(this)
        bridge = ShizukuBridge(this, this)
        engine = EngineRunner(
            context = this,
            scope = lifecycleScope,
            repo = repo,
            bridge = bridge,
            onApplyFailed = { reason -> if (!bridge.isConnected) notifications.showShizukuDown(reason) },
            onStateChanged = { refreshNotification() },
        )
        watcher = NetworkWatcher(this)
        vpn = VpnAutomation(lifecycleScope, repo, watcher, HomeDetector(), WireGuardController(this), beforeUp = ::allowWireGuardStart)

        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        bridge.start()
        watcher.start()
        vpn.start()

        lifecycleScope.launch {
            repo.updateRuntime { it.copy(serviceRunning = true) }
            repo.log(EventKind.SERVICE, "service started")
            armAlarms()
            bridge.bind()
        }
        lifecycleScope.launch {
            repo.rules.collect { rules ->
                ruleCount = rules.size
                engine.onRulesChanged(rules)
                armScheduleAlarm()
                refreshNotification()
            }
        }
        lifecycleScope.launch {
            repo.settings.collect { settings ->
                lastSettings = settings
                refreshNotification()
            }
        }
        lifecycleScope.launch {
            repo.settings.map { it.resetHour }.distinctUntilChanged().drop(1).collect {
                engine.checkReset("reset hour changed")
                armAlarms()
            }
        }
        lifecycleScope.launch {
            repo.runtime.collect { runtime ->
                lastRuntime = runtime
                refreshNotification()
            }
        }
        lifecycleScope.launch {
            bridge.status.collect { status ->
                repo.updateRuntime { it.copy(shizuku = status) }
                if (status == ShizukuStatus.CONNECTED) notifications.cancelShizukuDown()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_RESET -> lifecycleScope.launch {
                engine.checkReset("reset alarm")
                armAlarms()
            }
            ACTION_SCHEDULE_BOUNDARY -> lifecycleScope.launch {
                engine.applyAll("schedule boundary")
                armScheduleAlarm()
            }
            ACTION_OVERRIDE -> {
                val until = intent.getLongExtra(EXTRA_OVERRIDE_UNTIL, 0L)
                lifecycleScope.launch { vpn.setOverride(until) }
            }
            ACTION_RECONCILE -> lifecycleScope.launch {
                bridge.bind()
                engine.onWorkerConnected()
                watcher.poke()
            }
            else -> {
                // Opened from the UI or restarted: the app was just in front, so the location FGS
                // type can be (re)claimed and the WiFi name read again; a bind attempt is cheap.
                goForeground()
                bridge.bind()
                watcher.refreshWifi()
                watcher.poke()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        engine.stop()
        watcher.stop()
        bridge.stop()
        lifecycleScope.launch { repo.updateRuntime { it.copy(serviceRunning = false) } }
        super.onDestroy()
    }

    // --- ShizukuBridge.Listener -----------------------------------------------------------------

    override fun onConnected() {
        lifecycleScope.launch {
            val mode = bridge.call { it.observerMode } ?: ""
            repo.updateRuntime { it.copy(observerMode = mode) }
            repo.log(EventKind.SHIZUKU_UP, "worker connected (foreground via $mode)")
            notifications.cancelShizukuDown()
            engine.onWorkerConnected()
        }
    }

    override fun onDisconnected(reason: String) {
        lifecycleScope.launch {
            repo.updateRuntime { it.copy(observerMode = "") }
            repo.log(EventKind.SHIZUKU_DOWN, reason)
            if (ruleCount > 0) notifications.showShizukuDown(reason)
            refreshNotification()
        }
    }

    override fun onForegroundChanged(packageName: String, foreground: Boolean) {
        lifecycleScope.launch { engine.onForegroundChanged(packageName, foreground) }
    }

    override fun onObserverFailed(reason: String) {
        lifecycleScope.launch {
            repo.updateRuntime { it.copy(observerMode = "polling") }
            repo.log(EventKind.ERROR, "process observer unavailable, polling every 2 s instead: $reason")
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Asks the Shizuku worker to put WireGuard on the temporary allowlist for a minute, so Android
     * lets it start its VpnService from the background when the tunnel-up broadcast lands. A note
     * for the log, or null when there is no worker to ask.
     */
    private suspend fun allowWireGuardStart(): String? {
        if (!bridge.isConnected) return null
        val result = bridge.call { it.allowBackgroundStart(WireGuardController.PACKAGE, 60) ?: "" } ?: return null
        return if (result.isEmpty()) "WireGuard allowlisted for 60 s via Shizuku" else "Shizuku allowlist failed: $result"
    }

    private fun goForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(Notifications.ID_SERVICE, buildNotification(), types)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground with location type failed, retrying without", t)
            startForeground(Notifications.ID_SERVICE, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private suspend fun armAlarms() {
        val settings = repo.settingsNow()
        val next = ResetClock.nextResetMillis(System.currentTimeMillis(), settings.resetHour, ZoneId.systemDefault())
        alarms.armReset(next)
        repo.updateRuntime { it.copy(nextResetAt = next) }
        armScheduleAlarm()
    }

    private suspend fun armScheduleAlarm() {
        val windows = repo.allRules().filter { it.enabled }.flatMap { it.windows }
        alarms.armScheduleBoundary(ResetClock.nextWindowBoundary(windows, System.currentTimeMillis(), ZoneId.systemDefault()))
    }

    private fun refreshNotification() {
        runCatching { notifications.update(buildNotification()) }
    }

    private fun buildNotification() = run {
        val r = lastRuntime
        val s = lastSettings
        val overrideActive = s.overrideActive(System.currentTimeMillis())
        val engineLine = when (r.shizuku) {
            ShizukuStatus.CONNECTED -> "$ruleCount app${if (ruleCount == 1) "" else "s"} watched"
            ShizukuStatus.NO_PERMISSION -> "Shizuku permission missing"
            ShizukuStatus.CONNECTING -> "connecting to Shizuku"
            ShizukuStatus.DEAD -> "Shizuku worker stopped"
            ShizukuStatus.NOT_RUNNING -> "Shizuku not running"
        }
        val vpnLine = when {
            !s.vpnAutomationEnabled -> "VPN automation off"
            overrideActive -> "VPN automation paused"
            r.isHome -> "home WiFi, tunnel ${if (r.vpnUp) "still up" else "down"}"
            r.wifiConnected -> "away WiFi, tunnel ${if (r.vpnUp) "up" else "down"}"
            r.anyInternet -> "mobile data, tunnel ${if (r.vpnUp) "up" else "down"}"
            else -> "no network"
        }
        notifications.serviceNotification("Giraffe is watching", "$engineLine · $vpnLine", overrideActive)
    }

    companion object {
        private const val TAG = "PauserService"
        private const val PREFIX = "com.example.giraffeaddiction.action."
        const val ACTION_START = PREFIX + "START"
        const val ACTION_RESET = PREFIX + "RESET"
        const val ACTION_SCHEDULE_BOUNDARY = PREFIX + "SCHEDULE_BOUNDARY"
        const val ACTION_OVERRIDE = PREFIX + "OVERRIDE"
        const val ACTION_RECONCILE = PREFIX + "RECONCILE"
        const val EXTRA_OVERRIDE_UNTIL = "overrideUntil"

        fun start(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, PauserForegroundService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "could not start the service for $action", t)
            }
        }

        fun override(context: Context, until: Long) {
            val intent = Intent(context, PauserForegroundService::class.java)
                .setAction(ACTION_OVERRIDE)
                .putExtra(EXTRA_OVERRIDE_UNTIL, until)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
