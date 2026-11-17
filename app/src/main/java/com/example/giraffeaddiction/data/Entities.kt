package com.example.giraffeaddiction.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The states of the per-app machine. [COOLDOWN] and [PAUSED] both mean "suspended right now";
 * the difference is what ends them: a timer for the first, the daily reset for the second.
 */
enum class PauseState { UNPAUSED, IN_SESSION, GRACE, COOLDOWN, PAUSED }

enum class EventKind {
    FOREGROUND, SESSION, PAUSED, UNPAUSED, RESET, PATTERN,
    WIFI_CHANGED, VPN_SENT, VPN_MISMATCH, OVERRIDE,
    SHIZUKU_DOWN, SHIZUKU_UP, SERVICE, ERROR,
}

enum class ShizukuStatus { NOT_RUNNING, NO_PERMISSION, CONNECTING, CONNECTED, DEAD }

/**
 * One row per addictive app. Every policy is an independent switch, so they mix freely:
 *
 *  - openings a day: the app is paused for the rest of the day after its N-th session;
 *  - time a day: the app is paused for the rest of the day once its sessions add up to N minutes;
 *  - cooldown: after every session the app is locked for N seconds before it can be opened again,
 *    like a skill cooldown in a game;
 *  - curfew windows: paused inside fixed daily windows, no sessions involved.
 *
 * Grace and the longest session shape every session whichever of the first three are on.
 */
@Entity(tableName = "app_rule")
data class AppRule(
    @PrimaryKey val packageName: String,
    val label: String,
    val graceSec: Int = 60,
    val maxSessionMin: Int = 20,
    @ColumnInfo(defaultValue = "1") val openingsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val dailyOpenings: Int = 1,
    @ColumnInfo(defaultValue = "0") val timeBudgetEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "30") val dailyMinutes: Int = 30,
    @ColumnInfo(defaultValue = "0") val cooldownEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "120") val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
    /** Encoded `HH:mm-HH:mm;HH:mm-HH:mm`; see [BlockedWindow]. Enforced whatever else is on. */
    val blockedWindows: String = "",
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val windows: List<BlockedWindow> get() = BlockedWindow.parseList(blockedWindows)

    /** Whether foreground events matter at all for this app. */
    val tracksSessions: Boolean get() = openingsEnabled || timeBudgetEnabled || cooldownEnabled

    val budgetMillis: Long get() = dailyMinutes * 60_000L
    val cooldownMillis: Long get() = cooldownSec * 1_000L

    /** Every number pulled back into its allowed range. */
    fun sanitized(): AppRule = copy(
        graceSec = graceSec.coerceIn(0, 300),
        maxSessionMin = maxSessionMin.coerceIn(1, 120),
        dailyOpenings = dailyOpenings.coerceIn(OPENINGS_RANGE),
        dailyMinutes = dailyMinutes.coerceIn(MINUTES_RANGE),
        cooldownSec = cooldownSec.coerceIn(COOLDOWN_RANGE),
    )

    companion object {
        val OPENINGS_RANGE = 1..5
        val MINUTES_RANGE = 10..60
        val COOLDOWN_RANGE = 10..300
        const val DEFAULT_COOLDOWN_SEC = 120
    }
}

/**
 * The state machine's memory. [dayKey] rolls at the reset hour, not at midnight, so the row for a
 * given package and day is the whole story of that day: how many sessions it had, how long they
 * added up to, and where it stands now.
 *
 * [appliedSuspended] is what the engine last successfully told Shizuku; [applyPending] is set when
 * the desired state could not be applied because Shizuku was down (the reset-pending flag), and
 * cleared as soon as it is.
 */
@Entity(tableName = "day_state", primaryKeys = ["packageName", "dayKey"])
data class DayState(
    val packageName: String,
    val dayKey: String,
    val state: PauseState = PauseState.UNPAUSED,
    val sessionStartedAt: Long? = null,
    val leftAt: Long? = null,
    val pausedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val sessionsUsed: Int = 0,
    /** Wall time of the finished sessions, grace included, like the longest-session limit. */
    @ColumnInfo(defaultValue = "0") val usedMillis: Long = 0L,
    val cooldownEndsAt: Long? = null,
    val appliedSuspended: Boolean = false,
    val applyPending: Boolean = false,
    val updatedAt: Long = 0L,
)

/**
 * One stay in the foreground of any app, watched or not. The pattern analyser reads these to
 * spot the reflex checks; nothing else does. Kept for a week.
 */
@Entity(tableName = "app_visit", indices = [Index("startedAt")])
data class AppVisit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    val durationMillis: Long get() = endedAt - startedAt
}

enum class RecommendedPolicy { COOLDOWN }

/**
 * A pattern the analyser found and the one policy it suggests for it. Written by the service,
 * read by the status screen, which pins it until it is applied or put off until the next reset.
 */
@Entity(tableName = "recommendation")
data class Recommendation(
    @PrimaryKey val packageName: String,
    val label: String,
    val policy: RecommendedPolicy = RecommendedPolicy.COOLDOWN,
    /** How many reflex checks were chained together. */
    val count: Int,
    val avgMillis: Long,
    val spanMillis: Long,
    /** Labels of the apps the checks were squeezed between, most frequent first, comma separated. */
    val between: String,
    val firstSeenAt: Long,
    val updatedAt: Long,
    /** The count at which the pattern was last written to the event log. */
    val loggedCount: Int = 0,
    val dismissedUntil: Long = 0L,
) {
    fun dismissed(now: Long): Boolean = now < dismissedUntil
}

/** A saved home WiFi. A blank BSSID matches any access point with that SSID. */
data class HomeNetwork(val ssid: String, val bssid: String) {
    fun encode(): String = "$ssid\t$bssid"

    companion object {
        fun decode(line: String): HomeNetwork? {
            if (line.isBlank()) return null
            val parts = line.split('\t')
            return HomeNetwork(parts[0], parts.getOrElse(1) { "" })
        }

        fun parseList(encoded: String): List<HomeNetwork> =
            encoded.lineSequence().mapNotNull { decode(it) }.toList()

        fun encodeList(list: List<HomeNetwork>): String = list.joinToString("\n") { it.encode() }
    }
}

/** Single row. */
@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey val id: Int = 1,
    val resetHour: Int = 4,
    /** One [HomeNetwork] per line. */
    val homeNetworks: String = "",
    val probeHost: String = "",
    val probePort: Int = 443,
    val tunnelName: String = "",
    val debounceSec: Int = 5,
    val vpnAutomationEnabled: Boolean = true,
    /** 0 = no override; [OVERRIDE_UNTIL_RECONNECT] = until the next WiFi change; else epoch millis. */
    val overrideUntil: Long = 0L,
    val setupSeen: Boolean = false,
) {
    val homeNetworkList: List<HomeNetwork> get() = HomeNetwork.parseList(homeNetworks)
    val hasHomeDefinition: Boolean get() = homeNetworks.isNotBlank() || probeHost.isNotBlank()

    fun overrideActive(now: Long): Boolean =
        overrideUntil == OVERRIDE_UNTIL_RECONNECT || (overrideUntil > 0 && now < overrideUntil)

    companion object {
        const val OVERRIDE_UNTIL_RECONNECT = -1L
    }
}

/** Capped at 2,000 rows; the status screen reads it newest first. */
@Entity(tableName = "event_log")
data class EventLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: EventKind,
    val detail: String,
)

/**
 * What the foreground service currently sees, written by it and read by the UI. Nothing here is a
 * decision; it is the raw picture the status screen shows.
 */
@Entity(tableName = "runtime_state")
data class RuntimeState(
    @PrimaryKey val id: Int = 1,
    val serviceRunning: Boolean = false,
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val observerMode: String = "",
    val foregroundPackage: String? = null,
    val wifiConnected: Boolean = false,
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
    val isHome: Boolean = false,
    val homeReason: String = "",
    val anyInternet: Boolean = false,
    val vpnUp: Boolean = false,
    val lastVpnTarget: String? = null,
    val lastVpnSentAt: Long = 0L,
    val nextResetAt: Long = 0L,
    val updatedAt: Long = 0L,
)
