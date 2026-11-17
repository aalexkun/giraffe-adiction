package com.example.giraffeaddiction.vpn

import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.GiraffeRepository
import com.example.giraffeaddiction.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The decision rule, evaluated on every network callback, settings change and debounce timer:
 *
 *  1. override active: do nothing;
 *  2. home WiFi connected: target is tunnel down;
 *  3. otherwise any physical internet: target is tunnel up;
 *  4. otherwise leave the tunnel alone;
 *  5. a target that differs from the last one sent starts (or restarts) the debounce; when it
 *     fires with the same target still true, one broadcast goes out and is logged.
 *
 * Ten seconds after sending, the real VPN state is compared with the target. If they disagree the
 * request is re-sent with a growing delay, up to [RETRY_DELAYS_MS] times, and every step is
 * logged, which is how a disabled "Allow remote control apps" or a battery-restricted WireGuard
 * shows up on the status screen.
 *
 * [beforeUp] runs just before every tunnel-up request. Bringing a tunnel up makes WireGuard start
 * its VpnService, which Android refuses from the background unless WireGuard is exempt from
 * battery optimisation or on the temporary allowlist; the service uses the hook to put it there
 * through Shizuku when it can. Tunnel down needs no service start, which is why it always worked.
 */
class VpnAutomation(
    private val scope: CoroutineScope,
    private val repo: GiraffeRepository,
    private val watcher: NetworkWatcher,
    private val detector: HomeDetector,
    private val controller: WireGuardController,
    private val beforeUp: suspend () -> String? = { null },
) {
    private val mutex = Mutex()
    private var lastSentTarget: VpnTarget? = null
    private var pendingTarget: VpnTarget? = null
    private var debounceJob: Job? = null
    private var verifyJob: Job? = null
    private var retryJob: Job? = null
    private var overrideJob: Job? = null
    private var lastWifiKey: String? = null
    private var lastSendError: String? = null
    private var probeRetries = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(watcher.snapshot, repo.settings) { snap, settings -> snap to settings }
                .collect { (snap, settings) -> evaluate(snap, settings) }
        }
    }

    private suspend fun evaluate(snap: NetworkWatcher.Snapshot, settings: Settings) = mutex.withLock {
        val now = System.currentTimeMillis()
        val wifi = snap.wifi
        val wifiKey = wifi?.key
        if (wifiKey != lastWifiKey) {
            lastWifiKey = wifiKey
            probeRetries = 0
            repo.log(
                EventKind.WIFI_CHANGED,
                if (wifi == null) "WiFi gone" else "WiFi ${wifi.ssid ?: "(ssid hidden)"} ${wifi.bssid ?: ""}".trim(),
            )
            if (settings.overrideUntil == Settings.OVERRIDE_UNTIL_RECONNECT && wifi != null && wifi.ssid != null) {
                repo.updateSettings { it.copy(overrideUntil = 0L) }
                repo.log(EventKind.OVERRIDE, "override ended: WiFi changed")
                return@withLock // the settings flow re-runs this with the override cleared
            }
        }

        val verdict = if (wifi != null) detector.check(wifi, settings) else null
        val home = verdict?.home == true
        repo.updateRuntime {
            it.copy(
                wifiConnected = wifi != null,
                wifiSsid = wifi?.ssid,
                wifiBssid = wifi?.bssid,
                isHome = home,
                homeReason = verdict?.reason ?: "",
                anyInternet = snap.anyInternet,
                vpnUp = snap.vpnUp,
            )
        }

        // The LAN is often not ready in the first seconds after joining; try the probe again.
        if (wifi != null && !home && settings.probeHost.isNotBlank() && probeRetries < 3) {
            probeRetries++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(4_000L * probeRetries)
                watcher.poke()
            }
        }

        if (!settings.vpnAutomationEnabled) return@withLock
        if (settings.overrideActive(now)) return@withLock

        val target = when {
            wifi != null && home -> VpnTarget.DOWN
            snap.anyInternet -> VpnTarget.UP
            else -> null
        } ?: return@withLock

        if (target == lastSentTarget) {
            // Already asked for; a verification chain may still be running and is left to it.
            debounceJob?.cancel()
            pendingTarget = null
            return@withLock
        }
        if (target == pendingTarget && debounceJob?.isActive == true) return@withLock

        pendingTarget = target
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(settings.debounceSec.coerceAtLeast(1) * 1_000L)
            fire(target)
        }
    }

    private suspend fun fire(target: VpnTarget) = mutex.withLock {
        if (pendingTarget != target) return@withLock
        pendingTarget = null
        val settings = repo.settingsNow()
        if (!settings.vpnAutomationEnabled || settings.overrideActive(System.currentTimeMillis())) return@withLock
        if (dispatch(target, settings, "after ${settings.debounceSec}s debounce")) scheduleVerify(target)
    }

    /** One broadcast, logged. True when it went out; the hook's note, if any, goes in the log. */
    private suspend fun dispatch(target: VpnTarget, settings: Settings, why: String): Boolean {
        val note = if (target == VpnTarget.UP) runCatching { beforeUp() }.getOrNull() else null
        val err = controller.send(target, settings.tunnelName)
        if (err == null) {
            lastSendError = null
            lastSentTarget = target
            repo.log(
                EventKind.VPN_SENT,
                "tunnel ${settings.tunnelName.trim()} ${target.name} ($why)" + (note?.let { "; $it" } ?: ""),
            )
            repo.updateRuntime { it.copy(lastVpnTarget = target.name, lastVpnSentAt = System.currentTimeMillis()) }
            return true
        }
        if (err != lastSendError) {
            // Once per reason, not once per network event: an unconfigured tunnel is not news.
            lastSendError = err
            repo.log(EventKind.ERROR, "VPN broadcast not sent: $err")
        }
        return false
    }

    /**
     * Checks the real tunnel state a little after each request and keeps asking, with a growing
     * delay, while it disagrees. Stops when the state matches, when the target changes, when the
     * override starts, or after the last retry.
     */
    private fun scheduleVerify(target: VpnTarget) {
        verifyJob?.cancel()
        verifyJob = scope.launch {
            var attempt = 0
            while (true) {
                delay(VERIFY_AFTER_MS)
                val up = watcher.snapshot.value.vpnUp
                val wanted = target == VpnTarget.UP
                if (up == wanted) {
                    if (attempt > 0) {
                        repo.log(EventKind.VPN_SENT, "tunnel is ${target.name.lowercase()} after $attempt retr${if (attempt == 1) "y" else "ies"}")
                    }
                    return@launch
                }
                val seen = if (up) "up" else "down"
                if (attempt >= RETRY_DELAYS_MS.size) {
                    repo.log(
                        EventKind.VPN_MISMATCH,
                        "tunnel still $seen after $attempt retries; giving up until the network changes. $HINT",
                    )
                    return@launch
                }
                val wait = RETRY_DELAYS_MS[attempt]
                repo.log(
                    EventKind.VPN_MISMATCH,
                    "tunnel is $seen ${VERIFY_AFTER_MS / 1_000} s after asking for ${target.name}; " +
                        "retry ${attempt + 1} of ${RETRY_DELAYS_MS.size} in ${wait / 1_000} s" +
                        if (attempt == 0) ". $HINT" else "",
                )
                delay(wait)
                attempt++
                mutex.withLock {
                    if (lastSentTarget != target) return@launch
                    val settings = repo.settingsNow()
                    if (!settings.vpnAutomationEnabled || settings.overrideActive(System.currentTimeMillis())) return@launch
                    if (!dispatch(target, settings, "retry $attempt")) return@launch
                }
            }
        }
    }

    /**
     * [until]: 0 resumes automation, [Settings.OVERRIDE_UNTIL_RECONNECT] pauses it until the next
     * WiFi change, anything else is an epoch time at which it resumes by itself.
     */
    suspend fun setOverride(until: Long) {
        mutex.withLock {
            debounceJob?.cancel()
            pendingTarget = null
            overrideJob?.cancel()
            if (until != 0L) verifyJob?.cancel()
            repo.updateSettings { it.copy(overrideUntil = until) }
            when {
                until == 0L -> repo.log(EventKind.OVERRIDE, "automation resumed")
                until == Settings.OVERRIDE_UNTIL_RECONNECT -> repo.log(EventKind.OVERRIDE, "automation paused until the next WiFi change")
                else -> {
                    val minutes = ((until - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
                    repo.log(EventKind.OVERRIDE, "automation paused for $minutes min")
                    overrideJob = scope.launch {
                        delay((until - System.currentTimeMillis()).coerceAtLeast(0))
                        mutex.withLock { lastSentTarget = null }
                        repo.log(EventKind.OVERRIDE, "override expired")
                        watcher.poke()
                    }
                }
            }
            // Whatever was toggled by hand in the meantime is re-asserted on the next evaluation.
            if (until == 0L) lastSentTarget = null
        }
        if (until == 0L) watcher.poke()
    }

    companion object {
        const val VERIFY_AFTER_MS = 10_000L

        /** Waits before each re-send; about eight minutes of trying in all. */
        val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 240_000L)

        const val HINT = "If up never sticks: set WireGuard's battery use to Unrestricted in Android settings " +
            "(it cannot start its VPN service from the background otherwise) and check Allow remote control apps in WireGuard."
    }
}
