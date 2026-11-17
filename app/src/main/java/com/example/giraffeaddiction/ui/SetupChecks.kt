package com.example.giraffeaddiction.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.core.content.ContextCompat
import com.example.giraffeaddiction.data.Settings
import com.example.giraffeaddiction.engine.ShizukuBridge
import com.example.giraffeaddiction.service.Notifications
import com.example.giraffeaddiction.ui.components.Tone
import com.example.giraffeaddiction.vpn.WireGuardController

/** One line of the first-run checklist. */
data class SetupCheck(
    val id: String,
    val title: String,
    val detail: String,
    val tone: Tone,
    /** Must be green before anything else works. */
    val required: Boolean,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/** What the checklist screen needs from the activity: the launchers that ask for permissions. */
class CheckActions(
    val requestNotifications: () -> Unit,
    val requestLocation: () -> Unit,
    val requestWireGuard: () -> Unit,
    val openSettingsScreen: () -> Unit,
)

object SetupChecks {

    fun build(context: Context, settings: Settings, actions: CheckActions): List<SetupCheck> {
        val checks = ArrayList<SetupCheck>(9)
        val pm = context.packageManager

        // 1. Shizuku installed and running
        val shizukuInstalled = runCatching { pm.getPackageInfo(Notifications.SHIZUKU_PACKAGE, 0) }.isSuccess
        val shizukuRunning = ShizukuBridge.isRunning()
        checks += SetupCheck(
            id = "shizuku",
            title = "Shizuku is running",
            detail = when {
                !shizukuInstalled -> "Install Shizuku from its GitHub release, then start it via Wireless debugging."
                shizukuRunning -> "Started. On Android 13+ turn on its Start on boot (wireless debugging) option so a reboot does not leave the engine dead."
                else -> "Installed but not started. Open Shizuku and start it via Wireless debugging."
            },
            tone = if (shizukuRunning) Tone.GOOD else Tone.BAD,
            required = true,
            actionLabel = if (shizukuInstalled) "Open Shizuku" else "Get Shizuku",
            action = {
                val intent = pm.getLaunchIntentForPackage(Notifications.SHIZUKU_PACKAGE)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )

        // 2. Shizuku permission
        val shizukuGranted = ShizukuBridge.hasPermission()
        checks += SetupCheck(
            id = "shizuku_permission",
            title = "Shizuku permission granted",
            detail = when {
                shizukuGranted -> "This app may use Shizuku. The approval lives in Shizuku's own list and survives updates signed with the same key."
                shizukuRunning -> "Ask once; approve in the dialog."
                else -> "Start Shizuku first."
            },
            tone = if (shizukuGranted) Tone.GOOD else if (shizukuRunning) Tone.WARN else Tone.BAD,
            required = true,
            actionLabel = if (!shizukuGranted && shizukuRunning) "Ask" else null,
            action = if (!shizukuGranted && shizukuRunning) ({ ShizukuBridge.requestPermission() }) else null,
        )

        // 3. Notifications
        val notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        checks += SetupCheck(
            id = "notifications",
            title = "Notifications allowed",
            detail = "The watcher's own notification, and how you hear that Shizuku stopped.",
            tone = if (notifications) Tone.GOOD else Tone.BAD,
            required = true,
            actionLabel = if (notifications) null else "Allow",
            action = if (notifications) null else actions.requestNotifications,
        )

        // 4. Battery
        val power = context.getSystemService(PowerManager::class.java)
        val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        checks += SetupCheck(
            id = "battery",
            title = "Battery: unrestricted",
            detail = "So the always-on service is not put to sleep. On Samsung or Xiaomi also remove it from any sleeping-apps list.",
            tone = if (unrestricted) Tone.GOOD else Tone.WARN,
            required = false,
            actionLabel = if (unrestricted) null else "Exempt",
            action = if (unrestricted) null else ({
                val intent = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }),
        )

        // 5. Exact alarms
        val alarms = context.getSystemService(AlarmManager::class.java)
        val exact = alarms.canScheduleExactAlarms()
        checks += SetupCheck(
            id = "alarms",
            title = "Exact alarms allowed",
            detail = "The daily reset and the schedule edges land on the minute. Without it they drift by up to ten minutes.",
            tone = if (exact) Tone.GOOD else Tone.WARN,
            required = false,
            actionLabel = if (exact) null else "Allow",
            action = if (exact) null else ({
                val intent = Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }),
        )

        // 6. WireGuard
        val wg = WireGuardController(context)
        val wgInstalled = wg.isInstalled()
        val wgPermission = wg.hasPermission()
        checks += SetupCheck(
            id = "wireguard",
            title = "WireGuard remote control",
            detail = when {
                !wgInstalled -> "WireGuard is not installed; the VPN half has nothing to talk to."
                wgPermission -> "Permission granted. In WireGuard: Settings, Advanced, turn on Allow remote control apps. There is no way to check that from here."
                else -> "Grant the control permission, then in WireGuard: Settings, Advanced, turn on Allow remote control apps."
            },
            tone = if (!wgInstalled) Tone.BAD else if (wgPermission) Tone.GOOD else Tone.WARN,
            required = false,
            actionLabel = if (!wgInstalled) null else if (wgPermission) "Open WireGuard" else "Grant",
            action = if (!wgInstalled) null else if (wgPermission) ({
                val intent = wg.launchIntent()
                if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) else actions.requestWireGuard,
        )

        // 7. WireGuard battery: bringing a tunnel up starts a service in WireGuard, and Android
        // refuses that from the background unless the app is exempt from battery optimisation.
        if (wgInstalled) {
            val wgUnrestricted = power.isIgnoringBatteryOptimizations(WireGuardController.PACKAGE)
            checks += SetupCheck(
                id = "wireguard_battery",
                title = "WireGuard battery: unrestricted",
                detail = if (wgUnrestricted) {
                    "Android lets WireGuard start its VPN service from the background, which bringing the tunnel up from here needs."
                } else {
                    "Without it Android refuses to let WireGuard start its VPN service from the background: tunnel down works, tunnel up silently fails. In WireGuard's App info open Battery and choose Unrestricted."
                },
                tone = if (wgUnrestricted) Tone.GOOD else Tone.WARN,
                required = false,
                actionLabel = if (wgUnrestricted) null else "App info",
                action = if (wgUnrestricted) null else ({
                    val intent = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${WireGuardController.PACKAGE}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }),
            )
        }

        // 8. Location (SSID strategy only)
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        checks += SetupCheck(
            id = "location",
            title = "Location (only for SSID matching)",
            detail = if (location) {
                "Granted; the WiFi name is readable. Location services must be on as well."
            } else {
                "Optional. Needed only to read the WiFi name; the LAN probe strategy needs nothing."
            },
            tone = if (location) Tone.GOOD else Tone.OFF,
            required = false,
            actionLabel = if (location) null else "Grant",
            action = if (location) null else actions.requestLocation,
        )

        // 9. Home + tunnel
        val homeOk = settings.hasHomeDefinition
        val tunnelOk = settings.tunnelName.isNotBlank()
        checks += SetupCheck(
            id = "home",
            title = "Home network and tunnel name",
            detail = when {
                homeOk && tunnelOk -> "Home is defined and the tunnel is named \"${settings.tunnelName.trim()}\"."
                homeOk -> "Home is defined; type the tunnel name exactly as WireGuard shows it."
                tunnelOk -> "Mark the current WiFi as home, or give a LAN host that only answers at home."
                else -> "Mark the current WiFi as home or set a probe host, and type the tunnel name."
            },
            tone = if (homeOk && tunnelOk) Tone.GOOD else Tone.WARN,
            required = false,
            actionLabel = if (homeOk && tunnelOk) null else "Set up",
            action = if (homeOk && tunnelOk) null else actions.openSettingsScreen,
        )

        return checks
    }

    fun ready(checks: List<SetupCheck>): Boolean = checks.filter { it.required }.all { it.tone == Tone.GOOD }
}
