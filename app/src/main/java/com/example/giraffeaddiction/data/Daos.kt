package com.example.giraffeaddiction.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rule ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<AppRule>>

    @Query("SELECT * FROM app_rule")
    suspend fun all(): List<AppRule>

    @Query("SELECT * FROM app_rule WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppRule?

    @Query("SELECT * FROM app_rule WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<AppRule?>

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Query("DELETE FROM app_rule WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface DayStateDao {
    @Query("SELECT * FROM day_state WHERE packageName = :packageName ORDER BY dayKey DESC LIMIT 1")
    suspend fun latest(packageName: String): DayState?

    @Query(
        "SELECT * FROM day_state d WHERE dayKey = " +
            "(SELECT MAX(dayKey) FROM day_state WHERE packageName = d.packageName)"
    )
    fun observeLatest(): Flow<List<DayState>>

    @Upsert
    suspend fun upsert(state: DayState)

    @Query("DELETE FROM day_state WHERE packageName = :packageName")
    suspend fun deleteFor(packageName: String)

    @Query("DELETE FROM day_state WHERE dayKey < :minDayKey")
    suspend fun prune(minDayKey: String)
}

@Dao
interface AppVisitDao {
    @Insert
    suspend fun insert(visit: AppVisit)

    @Query("SELECT * FROM app_visit WHERE startedAt >= :since ORDER BY startedAt")
    suspend fun since(since: Long): List<AppVisit>

    @Query("SELECT * FROM app_visit WHERE startedAt >= :since ORDER BY startedAt")
    fun observeSince(since: Long): Flow<List<AppVisit>>

    @Query("DELETE FROM app_visit WHERE startedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface RecommendationDao {
    @Query("SELECT * FROM recommendation ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Recommendation>>

    @Query("SELECT * FROM recommendation")
    suspend fun all(): List<Recommendation>

    @Query("SELECT * FROM recommendation WHERE packageName = :packageName")
    suspend fun get(packageName: String): Recommendation?

    @Upsert
    suspend fun upsert(recommendation: Recommendation)

    @Query("DELETE FROM recommendation WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("UPDATE recommendation SET dismissedUntil = :until WHERE packageName = :packageName")
    suspend fun dismiss(packageName: String, until: Long)

    @Query("DELETE FROM recommendation WHERE updatedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<Settings?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): Settings?

    @Upsert
    suspend fun upsert(settings: Settings)
}

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(event: EventLog)

    @Query("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY id DESC LIMIT 2000)")
    suspend fun trim()

    @Query("SELECT * FROM event_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventLog>>

    @Query("DELETE FROM event_log")
    suspend fun clear()
}

@Dao
interface RuntimeStateDao {
    @Query("SELECT * FROM runtime_state WHERE id = 1")
    fun observe(): Flow<RuntimeState?>

    @Query("SELECT * FROM runtime_state WHERE id = 1")
    suspend fun get(): RuntimeState?

    @Upsert
    suspend fun upsert(state: RuntimeState)
}
