package com.example.giraffeaddiction.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppRule::class, DayState::class, AppVisit::class, Recommendation::class,
        Settings::class, EventLog::class, RuntimeState::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun dayStateDao(): DayStateDao
    abstract fun appVisitDao(): AppVisitDao
    abstract fun recommendationDao(): RecommendationDao
    abstract fun settingsDao(): SettingsDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun runtimeStateDao(): RuntimeStateDao

    companion object {
        const val NAME = "giraffe.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                // Safety net for a schema bump without a migration: rules and settings are quick to
                // redo, and the per-day state is recomputed anyway.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        /**
         * Version 2 (2026-10-17): the single `mode` became independent policy switches, the day
         * state learned to count sessions and minutes, and two tables arrived for the usage
         * pattern analyser. The rule table is rebuilt because SQLite cannot drop a column in place
         * and Room checks the exact shape; the `CREATE` statements match `schemas/.../2.json`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_rule_new` (`packageName` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`graceSec` INTEGER NOT NULL, `maxSessionMin` INTEGER NOT NULL, " +
                        "`openingsEnabled` INTEGER NOT NULL DEFAULT 1, `dailyOpenings` INTEGER NOT NULL DEFAULT 1, " +
                        "`timeBudgetEnabled` INTEGER NOT NULL DEFAULT 0, `dailyMinutes` INTEGER NOT NULL DEFAULT 30, " +
                        "`cooldownEnabled` INTEGER NOT NULL DEFAULT 0, `cooldownSec` INTEGER NOT NULL DEFAULT 120, " +
                        "`blockedWindows` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`packageName`))"
                )
                db.execSQL(
                    "INSERT INTO `app_rule_new` (`packageName`, `label`, `graceSec`, `maxSessionMin`, `openingsEnabled`, " +
                        "`dailyOpenings`, `timeBudgetEnabled`, `dailyMinutes`, `cooldownEnabled`, `cooldownSec`, " +
                        "`blockedWindows`, `enabled`, `addedAt`) " +
                        "SELECT `packageName`, `label`, `graceSec`, `maxSessionMin`, " +
                        "CASE WHEN `mode` = 'SCHEDULE' THEN 0 ELSE 1 END, 1, 0, 30, 0, 120, " +
                        "`blockedWindows`, `enabled`, `addedAt` FROM `app_rule`"
                )
                db.execSQL("DROP TABLE `app_rule`")
                db.execSQL("ALTER TABLE `app_rule_new` RENAME TO `app_rule`")

                db.execSQL("ALTER TABLE `day_state` ADD COLUMN `sessionsUsed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `day_state` ADD COLUMN `usedMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `day_state` ADD COLUMN `cooldownEndsAt` INTEGER")
                // A day that was paused after its one opening has had that one session.
                db.execSQL("UPDATE `day_state` SET `sessionsUsed` = 1 WHERE `state` = 'PAUSED'")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_visit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_visit_startedAt` ON `app_visit` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recommendation` (`packageName` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`policy` TEXT NOT NULL, `count` INTEGER NOT NULL, `avgMillis` INTEGER NOT NULL, " +
                        "`spanMillis` INTEGER NOT NULL, `between` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `loggedCount` INTEGER NOT NULL, `dismissedUntil` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`packageName`))"
                )
            }
        }
    }
}
