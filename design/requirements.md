# App Pauser & Home VPN — Implementation Plan

Oct 11, 2026 · @Mr 🦒

## Goals & scope

One sideloaded Android app, for you only, that does two jobs: it pauses each "addictive" app after its one opening of the day, and it keeps WireGuard off on your home WiFi and on everywhere else. The pausing needs Shizuku (no root); the VPN part is plain Android and works even when Shizuku is down.

**A. Addictive apps — done when:**

- Any installed app can be marked addictive from a list in the app.
- At the daily reset time (default 04:00 local) every addictive app is unpaused.
- The first time an addictive app comes to the foreground that day, a session starts. The session ends when you leave the app for longer than the grace period (default 60 s) or it reaches the max session length (default 20 min). The app is then paused until the next reset.
- A paused app shows Android's own "app paused" screen when tapped, sends no notifications, and its widgets and shortcuts are disabled. This is Android's package suspension, the same mechanism Digital Wellbeing uses, so it cannot be dismissed from the app itself.
- Fallback mode, per app: pause on a fixed schedule (for example 21:00 to 07:00) instead of once a day. Both modes run on the same engine.
- Paused state survives reboots. If Shizuku is not running, apps stay paused (fail-safe toward the goal) and the app shows a notification saying so.

**B. Home VPN — done when:**

- Within about 10 s of connecting to a home WiFi network, the WireGuard tunnel is down.
- Within about 10 s of losing home WiFi and having any other network (other WiFi, cellular), the tunnel is up.
- WiFi that flaps at the edge of range does not cause a toggling storm.
- A manual override pauses the automation for a chosen period, then resumes it.

**Out of scope:** Play Store release, work profiles or multiple users, network-level blocking (DNS or firewall), and any UI beyond what you need to configure and check it.

## Architecture

Two processes are yours: the normal app (UI plus one always-on foreground service that holds all the logic) and a thin Shizuku **UserService** that runs as the shell user and only executes privileged calls and reports foreground changes. Keeping the rules in your own process makes them easy to debug and persist; keeping the UserService thin means it rarely changes.

&#91;embedded content: architecture · 2 processes you write, 3 you talk to\]

The foreground service talks to the UserService over AIDL. Everything on the right half runs as shell (uid 2000) or is a system service; everything on the left is an ordinary app. The VPN path never crosses into Shizuku.

## Feature A — once-a-day pause engine

Detect the first foreground entry with a process observer (event-driven, no polling), suspend the package with Android's built-in suspension when that session ends, and unsuspend at the daily reset. Once-a-day is possible, so schedule mode is a second rule type on the same engine rather than a replacement.

**Privileged mechanics (inside the UserService, running as shell)**

| Need | How | Why this way |
| --- | --- | --- |
| Know when an app is opened | `ActivityManagerApis.registerProcessObserver(...)` from `dev.rikka.hidden:compat`, the same wrapper Shizuku's own server uses; `onForegroundActivitiesChanged(pid, uid, fg)` then `getPackagesForUid(uid)` | Event-driven, no battery cost; shell holds `SET_ACTIVITY_WATCHER`. Fallback if it misbehaves on your ROM: poll `UsageStatsManager.queryEvents` every 5 s while the screen is on |
| Pause | run `cmd package suspend --user 0 <pkg>` via `Runtime.exec` | The CLI has been stable since Android 7; the `IPackageManager.setPackagesSuspendedAsUser` binder signature changed in Android 14 and 15. Switch to the binder only if you want custom dialog text (`SuspendDialogInfo`, plain strings only, because the suspender is `com.android.shell`) |
| End a running session | `am force-stop <pkg>` right after the suspend | Suspension already kills the app on current Android; force-stop makes it certain on every version |
| Unpause | `cmd package unsuspend --user 0 <pkg>` |  |
| Read the real state | `dumpsys package <pkg>` and look for `suspended=true` | Used at boot and on reconnect to reconcile the database with reality |

**Session state machine (in your foreground service, one per addictive app)**

&#91;embedded content: pause engine · 4 states per addictive app\]

The grace state stops a glance at a notification from ending the day's session; the max-session edge stops one "session" from lasting all day. Screen-off counts as leaving. All three numbers are per-app settings.

**Daily reset.** An `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` alarm at the reset time, computed with `ZonedDateTime` in the device zone and re-armed after it fires, on `BOOT_COMPLETED`, and on `TIME_SET` / `TIMEZONE_CHANGED`. The receiver unsuspends every once-a-day app and moves it to *Unpaused*. If Shizuku is down at that moment, the app stays *Paused* with a `resetPending` flag, and the reset runs as soon as `Shizuku.addBinderReceivedListener` fires. The failure direction is always "stays paused".

**Schedule mode (the fallback you asked for, kept as an option).** A rule can carry blocked windows such as 21:00 to 07:00. Two alarms per window: suspend at the start, unsuspend at the end. No foreground detection is involved, so it also works as a belt-and-braces rule on top of once-a-day.

**Lifecycle.** Bind the UserService with `daemon = true` so it outlives your app process, a fixed `tag`, and a `versionCode` you bump whenever its code changes (Shizuku restarts it on a version mismatch). On every `onServiceConnected`, re-register the foreground callback and reconcile each app's real suspended state against the database. On binder death, post a persistent notification ("Shizuku stopped, tap to open it") and keep the database state untouched.

## Feature B — home WiFi ↔ WireGuard

A `NetworkCallback` registered for `TRANSPORT_WIFI` tells the service when WiFi comes and goes; a home check decides whether that WiFi is yours; a debounced decision sends one explicit broadcast to WireGuard. No Shizuku on this path.

**Watching the network.** Register two callbacks on `ConnectivityManager`: one with a `NetworkRequest` for `TRANSPORT_WIFI` (so the VPN being the default network does not hide the WiFi), and the default-network callback to know whether *any* internet is available. On Android 12+ build the WiFi callback with `FLAG_INCLUDE_LOCATION_INFO` so `onCapabilitiesChanged` carries a `WifiInfo` in `transportInfo`.

**Is this WiFi home?** Pick one, or combine both for safety:

| Strategy | Needs | Pros / cons |
| --- | --- | --- |
| SSID and BSSID match against a saved list | `ACCESS_FINE_LOCATION` granted and location services on; otherwise the SSID reads `<unknown ssid>` | Simple to configure from the UI ("mark current network as home"); BSSID guards against a neighbour with the same SSID |
| LAN probe: open a TCP connection with a 1 s timeout to a host that exists only at home (your router's admin page, a NAS, or the WireGuard server's LAN address) | Nothing | Zero permissions, and it proves the thing you care about: the home LAN is reachable directly. Fails if that host is down |
| SSID via Shizuku (`IWifiManager` as shell, which holds `NETWORK_SETTINGS`) | Shizuku running | Avoids the location prompt, but ties the VPN feature to Shizuku. Not recommended |

**Decision rule, evaluated on every callback and after each debounce timer:**

1. If override is active, do nothing.
2. If a WiFi network is connected and passes the home check, the target is *tunnel down*.
3. Otherwise, if any network with `NET_CAPABILITY_INTERNET` exists, the target is *tunnel up*.
4. Otherwise (no network at all), leave the tunnel as it is.
5. If the target differs from the last target sent, start or restart a 5 s debounce timer; when it fires with the same target still true, send the broadcast and log it.

**Talking to WireGuard.** An explicit broadcast, component set so it reaches a manifest receiver on Android 8+:

```kotlin
sendBroadcast(Intent("com.wireguard.android.action.SET_TUNNEL_UP") // or SET_TUNNEL_DOWN
    .setComponent(ComponentName("com.wireguard.android",
        "com.wireguard.android.model.TunnelManager\$IntentReceiver"))
    .putExtra("tunnel", tunnelName))
```

The receiver only acts when WireGuard's *Allow remote control apps* setting is on, and only for senders holding `com.wireguard.android.permission.CONTROL_TUNNELS`, a dangerous-level permission you request at runtime once. The tunnel name must match WireGuard exactly. There is no "query state" intent, so read the actual VPN state from `ConnectivityManager` (a network with `TRANSPORT_VPN`) and re-send if it does not match the target after 10 s.

**Override.** A notification action and a UI button set `overrideUntil` (30 min, 2 h, until reconnect). Manual toggles in WireGuard are otherwise overruled on the next network event, which is the behaviour you asked for.

**Known gap.** Between losing WiFi and WireGuard finishing its handshake, roughly 1 to 3 s of traffic can leave on cellular unprotected. If that matters, the fix is Android's *Block connections without VPN* for WireGuard, but that setting also blocks traffic while the tunnel is down at home, so it conflicts with this feature. Decide which you prefer; the plan assumes the gap is acceptable.

## Project setup

One Kotlin app module, Android Studio on either of your machines, `minSdk` set to your phone's Android version (31 or higher keeps the WiFi code simplest), `targetSdk 35`. Sideload the debug build over USB or wireless debugging; the same wireless-debugging pairing is what starts Shizuku, so set it up once and reuse it.

**Dependencies (`app/build.gradle.kts`)**

| Dependency | Version | Used for |
| --- | --- | --- |
| `dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider` | 13.1.5 | Binder, permission request, `bindUserService`; the provider's manifest merges the Shizuku permission and the `V3_SUPPORT` meta-data for you |
| `dev.rikka.hidden:compat` (implementation), `dev.rikka.hidden:stub` (compileOnly) | 4.4.0 | Typed wrappers for `IActivityManager` / `IPackageManager` hidden APIs, same libraries Shizuku itself builds with |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.1 | Lifts hidden-API restrictions in your own process (call `HiddenApiBypass.addHiddenApiExemptions("")` in `Application.onCreate` on Android 9+) |
| `androidx.room` + KSP | current stable | Rules, per-day state, event log |
| `androidx.lifecycle`, `androidx.work` (optional), `kotlinx-coroutines` | current stable | Service lifecycle, timers |
| Jetpack Compose + Material 3 | current stable | The three screens; keep it minimal |

**Source layout**

- `privileged/` — `IPauserService.aidl` (`suspend`, `unsuspend`, `forceStop`, `isSuspended`, `setForegroundListener`), `IForegroundListener.aidl`, `PauserUserService.kt` (the implementation Shizuku spawns as shell)
- `engine/` — `PauseEngine.kt` (state machine, pure Kotlin, unit-testable), `ResetScheduler.kt`, `ShizukuBridge.kt` (bind, reconnect, reconcile)
- `vpn/` — `NetworkWatcher.kt`, `HomeDetector.kt`, `WireGuardController.kt`
- `service/` — `PauserForegroundService.kt`, `BootReceiver.kt`, `AlarmReceiver.kt`
- `data/` — Room entities and DAOs
- `ui/` — app list, settings, status

**Manifest essentials**

```xml
<uses-permission android:name="com.wireguard.android.permission.CONTROL_TUNNELS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />   <!-- SSID strategy only -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />   <!-- app picker; fine for a sideloaded app -->

<queries><package android:name="com.wireguard.android" /></queries>

<provider android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false" android:enabled="true" android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

<service android:name=".service.PauserForegroundService"
    android:foregroundServiceType="specialUse" android:exported="false">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Watches network and app usage to pause apps and toggle VPN" />
</service>
```

The `specialUse` type is the honest one for a sideloaded personal tool; Play would ask questions, you will not.

## Permissions & first-run checklist

The app's first screen is this checklist with a status dot per line and a button that jumps to the right place; nothing else works until the first three are green.

- [x] **Shizuku installed and running.** Install from the GitHub release, start it via Wireless debugging (Android 11+). On Android 13+ turn on Shizuku's *Start on boot (wireless debugging)* so a reboot does not leave the pause engine dead. If the phone is rooted, use root mode or Sui instead and this problem goes away.
- [x] **Shizuku permission granted to this app.** `Shizuku.requestPermission()`; the approval persists in Shizuku's own list.
- [ ] **Notifications allowed** (`POST_NOTIFICATIONS`) — the foreground service needs its notification, and it is how the app tells you Shizuku has stopped.
- [ ] **Battery: unrestricted.** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; on Samsung, Xiaomi and similar also remove the app from any "sleeping apps" list (see dontkillmyapp.com for the OEM).
- [ ] **Exact alarms** (`SCHEDULE_EXACT_ALARM`, Android 12+): one tap in the system dialog.
- [ ] **WireGuard: Settings, Advanced, *Allow remote control apps*** on. Then grant `CONTROL_TUNNELS` when this app asks.
- [ ] **Location** (`ACCESS_FINE_LOCATION`, only for the SSID strategy) and location services on; otherwise the LAN-probe strategy needs nothing.
- [ ] **Home network saved** ("mark the current WiFi as home", or the probe host) and **tunnel name** typed exactly as in WireGuard.

The Shizuku approval and the `CONTROL_TUNNELS` grant are per-install: a reinstall with a different signing key loses both, so keep the debug keystore or sign release builds with one key.

## Data model

Four Room tables; everything the engine decides is derived from these plus the current clock, so a crash or reboot loses nothing but in-flight timers, which are recomputed on start.

| Table | Columns | Notes |
| --- | --- | --- |
| `app_rule` | `packageName` (PK), `mode` (ONCE\_PER\_DAY or SCHEDULE), `graceSec` (60), `maxSessionMin` (20), `blockedWindows` (JSON list of `start`,`end` local times), `enabled` | One row per addictive app |
| `day_state` | `packageName` + `dayKey` (PK, e.g. 2026-10-11 in reset-aligned days), `state` (UNPAUSED, IN\_SESSION, GRACE, PAUSED), `sessionStartedAt`, `leftAt`, `pausedAt`, `resetPending` | The state machine's memory; `dayKey` rolls at the reset hour, not midnight |
| `settings` | `resetHour` (4), `homeSsids` (list), `homeBssids` (list), `probeHost`, `probePort`, `tunnelName`, `debounceSec` (5), `vpnAutomationEnabled`, `overrideUntil` | Single row |
| `event_log` | `at`, `kind` (FOREGROUND, PAUSED, UNPAUSED, RESET, WIFI\_CHANGED, VPN\_SENT, SHIZUKU\_DOWN, SHIZUKU\_UP, ERROR), `detail` | Capped at 2,000 rows; the status screen reads it newest first |

Timers (grace, max session, debounce) live in memory in the foreground service as coroutine jobs and are rebuilt from `day_state` timestamps whenever the service starts.

## Milestones & build order

Build the risky part first: the spike proves Shizuku can pause an app on your phone before any UI exists. Each milestone is a weekend-sized chunk; the VPN feature comes after the pause engine only because the engine's service is where it lives.

&#91;embedded content: build order · 6 milestones, gate on each\]

1. **M0 Spike.** Empty project with the Shizuku dependencies, the provider in the manifest, a button that requests permission and a second button that runs `cmd package suspend` on a throwaway app through a minimal UserService. Done when tapping that app shows Android's paused screen and `unsuspend` brings it back.
2. **M1 Privileged worker.** The real `IPauserService` AIDL with suspend, unsuspend, forceStop, isSuspended and a foreground-change listener built on `registerProcessObserver`. Done when the app logs every foreground change and can pause any package by name.
3. **M2 Pause engine.** `PauseEngine` as pure Kotlin with unit tests for every transition, the foreground service wiring, Room tables, the daily reset alarm, boot restore and reconcile-on-reconnect. Done when one addictive app follows the once-a-day rule for two consecutive days without you touching anything.
4. **M3 VPN automation.** `NetworkWatcher`, `HomeDetector`, `WireGuardController`, debounce and override. Done when walking out of the house turns the tunnel on and coming home turns it off, with one log line each.
5. **M4 UI.** App picker with icons and search, settings screen, status screen with the event log, first-run checklist. Done when you can add an app or change the home network without editing code.
6. **M5 Hardening.** Battery exemption, OEM sleep lists, the Shizuku-down notification, Android 13+ start-on-boot path, a week of real use. Done when it has survived a reboot and seven days with no manual intervention.

## Testing checklist

Unit-test the state machine with a fake clock (every transition, grace re-entry, max-session cut-off, reset while paused, reset while Shizuku is down). Everything else is a manual pass on the real phone; set `graceSec` to 10 and `maxSessionMin` to 1 while testing so a run takes minutes.

**Pause engine**

- [ ] Open an addictive app, switch away for 5 s, come back: still in session (grace works).
- [ ] Switch away for longer than grace: app is killed and tapping it shows the paused screen; its notifications stop.
- [ ] Stay in the app past max session: it is killed and paused.
- [ ] Reboot overnight: app is still paused in the morning; at the reset hour it is usable again.
- [ ] Stop Shizuku, wait for the reset hour: app stays paused and the notification appears; start Shizuku: the pending reset runs within seconds.
- [ ] Change the reset hour, change time zone: next reset lands at the right local time.
- [ ] Schedule-mode app: pauses at window start, unpauses at window end, including across a reboot inside the window.
- [ ] Remove an app from the list while it is paused: it is unpaused immediately.

**VPN automation**

- [ ] Walk out of WiFi range: tunnel is up within 10 s of cellular being available.
- [ ] Return home: tunnel is down within 10 s of joining home WiFi.
- [ ] Join a non-home WiFi (phone hotspot, café): tunnel stays up.
- [ ] Stand at the edge of range for a minute: at most one toggle per debounce window, nothing flaps.
- [ ] Airplane mode on and off: no broadcast while there is no network; correct target once one returns.
- [ ] Override for 30 min, change networks: nothing happens; after expiry the next event is acted on.
- [ ] Turn off *Allow remote control apps* in WireGuard: the app logs the failed check (VPN state did not match after 10 s) and shows it on the status screen.

**Survival**

- [ ] Swipe the app from recents: the foreground service and its notification remain.
- [ ] Leave the phone idle overnight on battery: the morning event log shows the reset fired on time.

## Risks, edge cases & open decisions

| Risk | Likelihood | What to do |
| --- | --- | --- |
| Shizuku is dead after a reboot on a non-rooted phone, so nothing can be unpaused | High on Android 12 and below, low on 13+ with start-on-boot | Fail-safe is "stays paused"; the notification tells you; on 13+ enable Shizuku's start-on-boot |
| OEM battery manager kills the foreground service or the WireGuard app | Medium on Samsung, Xiaomi, Oppo; low on Pixel | Exempt both apps; after a kill the service restarts via `START_STICKY` and reconciles state |
| Hidden-API signature drift (`setPackagesSuspendedAsUser`, `registerProcessObserver`) on a future Android | Medium per major version | Suspend goes through the `cmd package` CLI, which is stable; the observer is wrapped by `hidden:compat`, which the Shizuku author updates each release |
| You suspend something you need (launcher, keyboard, Shizuku, this app) | Low | The picker hides system apps, Shizuku, WireGuard and itself; the engine refuses those package names anyway |
| Another app also suspends the same package (Digital Wellbeing, a work policy) | Low | Android keeps one suspension per suspender, so both must unsuspend before the app runs; log and move on |
| Neighbour's WiFi with the same SSID, or a captive portal that looks like home | Low | BSSID match, or the LAN probe, or both |
| 1 to 3 s of unprotected traffic during the cellular handoff | Certain | Accepted unless you choose Android's block-without-VPN, which conflicts with tunnel-down at home |
| Suspension is per-user; a second profile is untouched | Certain | Out of scope |

**Open decisions for you**

- [ ] Home detection: SSID+BSSID (needs the location grant) or LAN probe (needs a host that only answers at home)? The plan leans LAN probe.
- [ ] Should the paused dialog carry a custom message? If yes, the suspend call moves from `cmd package` to the `IPackageManager` binder with a string-only `SuspendDialogInfo`.
- [ ] Do you want a deliberate "unpause anyway" path with friction (for example a 10-minute delay), or no escape hatch at all?
- [ ] Reset hour: 04:00 is the default in this plan; confirm or change.

**Sources**

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (reviewed at commits b844bc4 and a27f6e4, v13.1.5)
- [Shizuku-API demo](https://github.com/RikkaApps/Shizuku-API/tree/master/demo) — UserService and `ShizukuBinderWrapper` patterns
- [WireGuard Android manifest](https://github.com/WireGuard/wireguard-android/blob/master/ui/src/main/AndroidManifest.xml) and [TunnelManager.kt](https://github.com/WireGuard/wireguard-android/blob/master/ui/src/main/java/com/wireguard/android/model/TunnelManager.kt) — remote-control actions, `tunnel` extra, `CONTROL_TUNNELS`
- [AOSP Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/Shell/AndroidManifest.xml) — the shell user holds `SUSPEND_APPS`, `FORCE_STOP_PACKAGES`, `SET_ACTIVITY_WATCHER`, `PACKAGE_USAGE_STATS`, `NETWORK_SETTINGS`
