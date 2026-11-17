package com.example.giraffeaddiction.engine

import com.example.giraffeaddiction.data.AppVisit
import com.example.giraffeaddiction.data.Recommendation

/**
 * Reads the day's foreground visits for the reflex pattern: an app opened again and again for a
 * few seconds, each time squeezed between other apps. Gmail, X, Gmail, YouTube, Gmail, a chat,
 * Gmail: the inbox is not being read, it is being checked. Pure Kotlin, unit-tested.
 */
object PatternAnalyzer {

    data class Params(
        /** A visit no longer than this is a check, not a read. */
        val shortVisitMillis: Long = 15_000L,
        /** Anything shorter is a transition artefact and is ignored altogether. */
        val minVisitMillis: Long = 1_000L,
        /** Two checks further apart than this belong to different chains. */
        val maxGapMillis: Long = 15 * 60_000L,
        /** A chain must be at least this long to be reported. */
        val minBounces: Int = 4,
    )

    /** One app's chain of checks. */
    data class Bounce(
        val packageName: String,
        val count: Int,
        val avgMillis: Long,
        val spanMillis: Long,
        val firstAt: Long,
        val lastAt: Long,
        /** The apps the checks were squeezed between, most frequent first. */
        val between: List<String>,
    )

    fun bounces(visits: List<AppVisit>, params: Params = Params()): List<Bounce> {
        val v = visits.filter { it.durationMillis >= params.minVisitMillis }.sortedBy { it.startedAt }

        class Hit(val visit: AppVisit, val neighbours: List<String>)

        val hits = LinkedHashMap<String, MutableList<Hit>>()
        for (i in v.indices) {
            val cur = v[i]
            if (cur.durationMillis > params.shortVisitMillis) continue
            // A check is short and comes from somewhere else; the next app, if any, is noted too.
            val prev = v.getOrNull(i - 1) ?: continue
            if (prev.packageName == cur.packageName) continue
            val next = v.getOrNull(i + 1)?.takeIf { it.packageName != cur.packageName }
            hits.getOrPut(cur.packageName) { ArrayList() } += Hit(cur, listOfNotNull(prev.packageName, next?.packageName))
        }

        val result = ArrayList<Bounce>()
        for ((pkg, list) in hits) {
            // The longest chain of checks with no gap wider than maxGap; ties go to the latest.
            var best: List<Hit> = emptyList()
            var chain = ArrayList<Hit>()
            for (hit in list) {
                val last = chain.lastOrNull()
                if (last != null && hit.visit.startedAt - last.visit.endedAt > params.maxGapMillis) {
                    if (chain.size >= best.size) best = chain
                    chain = ArrayList()
                }
                chain += hit
            }
            if (chain.size >= best.size) best = chain
            if (best.size < params.minBounces) continue

            val between = best.flatMap { it.neighbours }
                .groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            result += Bounce(
                packageName = pkg,
                count = best.size,
                avgMillis = best.map { it.visit.durationMillis }.average().toLong(),
                spanMillis = best.last().visit.endedAt - best.first().visit.startedAt,
                firstAt = best.first().visit.startedAt,
                lastAt = best.last().visit.endedAt,
                between = between,
            )
        }
        return result.sortedWith(compareByDescending<Bounce> { it.count }.thenByDescending { it.lastAt })
    }
}

/** The one sentence the log and the status card both show. */
fun Recommendation.summary(): String {
    val span = (spanMillis / 60_000L).coerceAtLeast(1)
    val each = (avgMillis / 1_000L).coerceAtLeast(1)
    val apps = between.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val betweenText = when (apps.size) {
        0 -> ""
        1 -> ", between ${apps[0]} and the home screen"
        2 -> ", between ${apps[0]} and ${apps[1]}"
        else -> ", between ${apps.dropLast(1).joinToString(", ")} and ${apps.last()}"
    }
    return "Checked $count times in $span min, about $each s each$betweenText."
}

/** What the recommended policy would change, in one line. */
fun Recommendation.pitch(): String = when (policy) {
    com.example.giraffeaddiction.data.RecommendedPolicy.COOLDOWN ->
        "A 2 min cooldown after each visit turns the reflex into a decision: read it once, properly."
}
