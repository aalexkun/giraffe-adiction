package com.example.giraffeaddiction.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one door to the database. The UI writes rules and settings through it; the service writes
 * day state, visits, recommendations, runtime state and the log through it; both read with flows.
 */
class GiraffeRepository(private val db: AppDatabase) {

    val rules: Flow<List<AppRule>> = db.appRuleDao().observeAll()
    val dayStates: Flow<List<DayState>> = db.dayStateDao().observeLatest()
    val recommendations: Flow<List<Recommendation>> = db.recommendationDao().observeAll()
    val settings: Flow<Settings> = db.settingsDao().observe().map { it ?: Settings() }
    val runtime: Flow<RuntimeState> = db.runtimeStateDao().observe().map { it ?: RuntimeState() }

    fun events(limit: Int = 200): Flow<List<EventLog>> = db.eventLogDao().observeRecent(limit)
    fun rule(packageName: String): Flow<AppRule?> = db.appRuleDao().observe(packageName)
    fun visitsSince(since: Long): Flow<List<AppVisit>> = db.appVisitDao().observeSince(since)

    suspend fun allRules(): List<AppRule> = db.appRuleDao().all()
    suspend fun getRule(packageName: String): AppRule? = db.appRuleDao().get(packageName)
    suspend fun upsertRule(rule: AppRule) = db.appRuleDao().upsert(rule.sanitized())
    suspend fun deleteRule(packageName: String) = db.appRuleDao().delete(packageName)

    suspend fun latestDayState(packageName: String): DayState? = db.dayStateDao().latest(packageName)
    suspend fun upsertDayState(state: DayState) = db.dayStateDao().upsert(state)
    suspend fun deleteDayStates(packageName: String) = db.dayStateDao().deleteFor(packageName)
    suspend fun pruneDayStates(minDayKey: String) = db.dayStateDao().prune(minDayKey)

    suspend fun insertVisit(visit: AppVisit) = db.appVisitDao().insert(visit)
    suspend fun visitsSinceNow(since: Long): List<AppVisit> = db.appVisitDao().since(since)
    suspend fun pruneVisits(before: Long) = db.appVisitDao().prune(before)

    suspend fun recommendation(packageName: String): Recommendation? = db.recommendationDao().get(packageName)
    suspend fun allRecommendations(): List<Recommendation> = db.recommendationDao().all()
    suspend fun upsertRecommendation(recommendation: Recommendation) = db.recommendationDao().upsert(recommendation)
    suspend fun deleteRecommendation(packageName: String) = db.recommendationDao().delete(packageName)
    suspend fun dismissRecommendation(packageName: String, until: Long) = db.recommendationDao().dismiss(packageName, until)
    suspend fun pruneRecommendations(before: Long) = db.recommendationDao().prune(before)

    suspend fun settingsNow(): Settings = db.settingsDao().get() ?: Settings()
    suspend fun updateSettings(transform: (Settings) -> Settings) = settingsLock.withLock {
        db.settingsDao().upsert(transform(settingsNow()))
    }

    suspend fun runtimeNow(): RuntimeState = db.runtimeStateDao().get() ?: RuntimeState()

    /**
     * Read-modify-write under a lock: the service, the bridge and the VPN automation all update
     * different fields of the same row from different coroutines, and without the lock one of
     * them overwrites the others' fields with stale values.
     */
    suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState) = runtimeLock.withLock {
        val next = transform(runtimeNow()).copy(updatedAt = System.currentTimeMillis())
        db.runtimeStateDao().upsert(next)
    }

    private val runtimeLock = Mutex()
    private val settingsLock = Mutex()

    suspend fun log(kind: EventKind, detail: String) {
        db.eventLogDao().insert(EventLog(at = System.currentTimeMillis(), kind = kind, detail = detail))
        db.eventLogDao().trim()
    }

    suspend fun clearLog() = db.eventLogDao().clear()
}
