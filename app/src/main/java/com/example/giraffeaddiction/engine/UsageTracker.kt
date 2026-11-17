package com.example.giraffeaddiction.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.giraffeaddiction.data.AppRule
import com.example.giraffeaddiction.data.AppVisit
import com.example.giraffeaddiction.data.EventKind
import com.example.giraffeaddiction.data.GiraffeRepository
import com.example.giraffeaddiction.data.Recommendation
import com.example.giraffeaddiction.data.RecommendedPolicy
import com.example.giraffeaddiction.ui.AppCatalog
import java.time.ZoneId

/**
 * Turns the stream of foreground changes into visits (one row per stay in front, any app), and
 * after each visit runs [PatternAnalyzer] over the day so far. A pattern becomes a
 * [Recommendation] row for the status screen and, as it grows, a line in the event log.
 *
 * Launchers, system UI and the permission dialogs are not visits: they are the gaps between apps.
 * Called only under the engine's mutex.
 */
class UsageTracker(
    context: Context,
    private val repo: GiraffeRepository,
    private val rules: () -> Map<String, AppRule>,
) {
    private val appContext = context.applicationContext
    private val ignored: Set<String> = buildSet {
        add(context.packageName)
        addAll(AppCatalog.forbidden)
        addAll(launchers(context))
        add("com.google.android.permissioncontroller")
        add("com.android.permissioncontroller")
        add("com.android.intentresolver")
        add("com.google.android.apps.wellbeing")
    }

    private var current: Pair<String, Long>? = null

    suspend fun onForeground(packageName: String, now: Long) {
        if (packageName in ignored) {
            close(now)
            return
        }
        if (current?.first == packageName) return
        close(now)
        current = packageName to now
    }

    suspend fun onBackground(packageName: String, now: Long) {
        if (current?.first == packageName) close(now)
    }

    suspend fun onScreenOff(now: Long) = close(now)

    private suspend fun close(now: Long) {
        val (pkg, started) = current ?: return
        current = null
        if (now - started < MIN_VISIT_MS) return
        repo.insertVisit(AppVisit(packageName = pkg, startedAt = started, endedAt = now))
        analyze(now)
    }

    /** Re-reads today's visits and lines the recommendation table up with what they show. */
    suspend fun analyze(now: Long) {
        val settings = repo.settingsNow()
        val dayStart = ResetClock.dayStartMillis(now, settings.resetHour, ZoneId.systemDefault())
        val bounces = PatternAnalyzer.bounces(repo.visitsSinceNow(dayStart)).associateBy { it.packageName }
        val ruleMap = rules()
        val existing = repo.allRecommendations().associateBy { it.packageName }

        for (pkg in existing.keys) {
            if (pkg !in bounces) repo.deleteRecommendation(pkg)
        }
        for ((pkg, bounce) in bounces) {
            if (ruleMap[pkg]?.cooldownEnabled == true) {
                // Already has the policy the pattern calls for: nothing to recommend.
                if (pkg in existing) repo.deleteRecommendation(pkg)
                continue
            }
            val prev = existing[pkg]
            var rec = Recommendation(
                packageName = pkg,
                label = AppCatalog.label(appContext, pkg),
                policy = RecommendedPolicy.COOLDOWN,
                count = bounce.count,
                avgMillis = bounce.avgMillis,
                spanMillis = bounce.spanMillis,
                between = bounce.between.take(3).joinToString(", ") { AppCatalog.label(appContext, it) },
                firstSeenAt = prev?.firstSeenAt ?: now,
                updatedAt = now,
                loggedCount = prev?.loggedCount ?: 0,
                dismissedUntil = prev?.dismissedUntil ?: 0L,
            )
            if (rec.count >= rec.loggedCount + LOG_EVERY) {
                repo.log(EventKind.PATTERN, "${rec.label}: ${rec.summary()} Suggested: a 2 min cooldown.")
                rec = rec.copy(loggedCount = rec.count)
            }
            repo.upsertRecommendation(rec)
        }
    }

    companion object {
        /** Shorter stays are the observer settling, not a visit. */
        const val MIN_VISIT_MS = 1_000L

        /** A pattern is logged when first found and again every time it grows by this much. */
        const val LOG_EVERY = 4

        private fun launchers(context: Context): Set<String> = runCatching {
            context.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.ResolveInfoFlags.of(0L),
                )
                .map { it.activityInfo.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }
}
