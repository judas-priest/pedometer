package com.pedometer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailySteps::class, HourlySteps::class, MinuteSteps::class, StepSnapshot::class, HeartRateRecord::class, DailyHealth::class, SleepRecord::class, WorkoutRecord::class, GpsPointRecord::class, Supplement::class, SupplementIntake::class],
    version = 12, // keep in sync with VERSION below
    exportSchema = true,
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        /** Mirror of the @Database version. Room needs a literal in the annotation. */
        const val VERSION = 12

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
