# Giraffe Addiction

A sideloaded, single-user Android app that turns the phone back into a tool: every opening of an
"addictive" app is planned, not reflexive. It does two jobs:

- **App pauser.** Each app on the list carries any mix of four policies. When one of them holds,
  the app is closed with Android's own package suspension until it lifts. Pausing runs through
  Shizuku; nothing needs root.
- **Home VPN.** The WireGuard tunnel is brought down within seconds of joining the home WiFi and up
  again on any other network, with a debounce so a flapping WiFi edge does not cause a storm, a
  retry chain if WireGuard does not follow, and a manual override that pauses the automation.

The original plan is in `design/requirements.md`; the policies below superseded its single
"once a day or schedule" mode on 2026-10-17.

## Policies

All four are independent switches on one rule, so they mix freely. Grace (60 s) and the longest
session (20 min) shape every session whichever of the first three are on.

| Policy | Range, default | What it does |
| --- | --- | --- |
| Openings a day | 1 to 5, **1** | After the N-th session the app rests until the reset hour (04:00). |
| Time a day | 10 to 60 min, **30** (off) | Sessions add up, grace included; when the minutes are gone the app rests until the reset hour. |
| Cooldown | 10 s to 5 min, **2 min** (off) | Like a skill cooldown: after every session the app is locked for this long before it can be opened again. With one opening a day it changes nothing; with more openings or a time budget it spaces them out. |
| Curfew | any windows (off) | Paused inside fixed daily windows, for example 21:00 to 07:00, whatever else is on. No sessions involved. |

A new app starts with one opening a day. An app with no policy and no curfew is free all day, and
the list says so.

## Usage patterns and recommendations

The service records every stay in the foreground of every app (a week is kept) and, after each
one, looks for the reflex pattern: an app opened again and again for a few seconds, each time
squeezed between other apps (Gmail, X, Gmail, YouTube, Gmail, a chat). Four such checks within
fifteen minutes of each other make a pattern. It is written to the event log as it grows and
pinned on the Today screen as a recommendation with the one policy it calls for, a two-minute
cooldown. "Add the cooldown" applies exactly that policy and nothing else; "Later" puts it off
until the next reset.

## Layout

| Package | What lives there |
| --- | --- |
| `privileged/` | The AIDL contract and `PauserUserService`, the thin worker Shizuku runs as the shell user. It registers a process observer for foreground changes (polling fallback), runs `cmd package suspend/unsuspend` and force-stop, and can put a package on the device-idle temporary allowlist. |
| `engine/` | `PauseEngine` (the pure, unit-tested state machine: openings, time budget, cooldown, curfew), `ResetClock` (reset-aligned days, next reset, curfew edges), `EngineRunner` (drives the engine from real events, rebuilds timers, reconciles with the package manager), `UsageTracker` and `PatternAnalyzer` (visits and the reflex-check detector), `ShizukuBridge` (bind, reconnect, permission). |
| `vpn/` | `NetworkWatcher` (WiFi, physical internet and VPN callbacks), `HomeDetector` (SSID/BSSID list and/or a LAN probe over the WiFi socket), `WireGuardController` (the explicit broadcast) and `VpnAutomation` (decision rule, debounce, verification with retries, override). |
| `service/` | `PauserForegroundService` (the one always-on process), boot and alarm receivers, alarms, notifications. |
| `data/` | Room: `app_rule`, `day_state`, `app_visit`, `recommendation`, `settings`, `event_log`, `runtime_state`. Schemas are exported to `app/schemas`; migrations live in `AppDatabase`. |
| `ui/` | Compose: setup checklist, Today (status, pinned recommendations, log), Apps (list, picker, rule editor), Settings. The clay colour profile is in `ui/theme`. |

## Building and sideloading

Android Studio with AGP 9.4.1 (built-in Kotlin 2.2.10), or from the command line:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Keep the debug keystore: the Shizuku approval and WireGuard's `CONTROL_TUNNELS` grant are tied to
the signing key.

## First run

The app opens on a checklist. The first three lines must be green for the pause engine to work:
Shizuku running, Shizuku permission granted, notifications allowed. The rest keep it alive and
give the VPN half something to talk to: battery unrestricted for this app, exact alarms,
WireGuard's *Allow remote control apps* plus its permission, **battery unrestricted for WireGuard
too**, location (only for SSID matching), and a home definition with the tunnel name.

The WireGuard battery line matters more than it looks. Bringing a tunnel up makes WireGuard start
its VPN service, and Android refuses that from the background unless the app is exempt from
battery optimisation, so "tunnel down" worked while "tunnel up" silently failed. The service now
also asks the Shizuku worker to put WireGuard on the temporary allowlist just before every
tunnel-up request, and keeps re-sending with a growing delay while the tunnel does not follow.

## Testing on the phone

Set the grace to 10 s, the longest session to 1 min and the cooldown to 10 s on a throwaway app so
a run takes minutes. The event log on the Today screen shows every foreground change of a watched
app, each session start and end with what is left of the day, pauses, unpauses, resets, detected
patterns, WiFi changes, VPN requests and their retries, newest first. The manual checklist is in
`design/requirements.md`.
