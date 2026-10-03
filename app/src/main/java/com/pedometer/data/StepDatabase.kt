package com.pedometer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailySteps::class, HourlySteps::class, MinuteSteps::class, StepSnapshot::class, HeartRateRecord::class, HeartRateArchive::class, DailyHealth::class, SleepRecord::class, WorkoutRecord::class, GpsPointRecord::class, Supplement::class, SupplementIntake::class, WeightLog::class],
    version = 16, // keep in sync with VERSION below
    exportSchema = true,
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        /** Mirror of the @Database version. Room needs a literal in the annotation. */
        const val VERSION = 16

        /** Oldest schema version any installed build can still be sitting on. */
        const val OLDEST_SUPPORTED = 7

        /**
         * Every schema change gets a Migration here and a version bump. There is deliberately
         * no destructive fallback: this database holds the only copy of the user's history.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `minute_steps` (" +
                            "`minute` INTEGER NOT NULL, " +
                            "`steps` INTEGER NOT NULL, " +
                            "`source` TEXT NOT NULL, " +
                            "PRIMARY KEY(`minute`, `source`))"
                    )
                }
            },
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `minute_steps` ADD COLUMN `distanceM` INTEGER NOT NULL DEFAULT 0")
                }
            },
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_timestamp` ON `heart_rate` (`timestamp`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_step_snapshots_timestamp` ON `step_snapshots` (`timestamp`)")
                }
            },
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `supplements` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`slot` TEXT NOT NULL, " +
                            "`enabled` INTEGER NOT NULL, " +
                            "`sort` INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `supplement_intakes` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`date` TEXT NOT NULL, " +
                            "`slot` TEXT NOT NULL, " +
                            "`takenAt` INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_supplement_intakes_date_slot` ON `supplement_intakes` (`date`, `slot`)")
                }
            },
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("UPDATE `supplements` SET `slot` = 'dinner' WHERE `slot` = 'flex'")
                    db.execSQL("UPDATE `supplement_intakes` SET `slot` = 'dinner' WHERE `slot` = 'flex'")
                }
            },
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `weight_log` (" +
                            "`date` TEXT NOT NULL, " +
                            "`kg` REAL NOT NULL, " +
                            "`takenAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`date`))"
                    )
                }
            },
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `gps_points` ADD COLUMN `altitude` REAL")
                }
            },
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // DEM cache invalidation: refetch with bilinear interpolation
                    db.execSQL("UPDATE `gps_points` SET `altitude` = NULL")
                }
            },
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `heart_rate_archive` (`day` INTEGER NOT NULL, `data` BLOB NOT NULL, `samples` INTEGER NOT NULL, PRIMARY KEY(`day`))"
                    )
                    val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                    // Backfill: group raw rows older than cutoff by day, compress, insert blobs,
                    // then delete the raw rows. Room runs migrations in a transaction —
                    // a failure here rolls the whole migration back (all-or-nothing).
                    val rows = ArrayList<HrCodec.Sample>(8192)
                    var currentDay = -1L
                    fun flush() {
                        if (rows.isEmpty()) return
                        val blob = HrCodec.compress(rows)
                        val cv = android.content.ContentValues()
                        cv.put("day", currentDay)
                        cv.put("data", blob)
                        cv.put("samples", rows.size)
                        db.insert("heart_rate_archive", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
                        rows.clear()
                    }
                    db.query("SELECT timestamp, bpm FROM heart_rate WHERE timestamp < ? ORDER BY timestamp", arrayOf(cutoff)).use { c ->
                        while (c.moveToNext()) {
                            val ts = c.getLong(0)
                            val bpm = c.getInt(1)
                            val day = ts / 86_400_000L
                            if (day != currentDay) { flush(); currentDay = day }
                            rows.add(HrCodec.Sample(ts, bpm))
                        }
                    }
                    flush()
                    db.execSQL("DELETE FROM heart_rate WHERE timestamp < ?", arrayOf(cutoff))
                }
            },
        )

        @Volatile
        private var INSTANCE: StepDatabase? = null

        fun get(context: Context): StepDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): StepDatabase {
            val gaps = missingMigrationSteps(
                MIGRATIONS.map { it.startVersion to it.endVersion },
                from = OLDEST_SUPPORTED,
                to = VERSION,
            )
            // Fails loudly the moment someone bumps the version without writing the migration,
            // instead of Room silently dropping every table at runtime.
            check(gaps.isEmpty()) { "Missing Room migrations for versions: $gaps" }

            return Room.databaseBuilder(
                context.applicationContext,
                StepDatabase::class.java,
                "pedometer.db",
            ).addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
