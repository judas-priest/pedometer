package com.pedometer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [DailySteps::class, HourlySteps::class, StepSnapshot::class, HeartRateRecord::class, DailyHealth::class, SleepRecord::class, WorkoutRecord::class, GpsPointRecord::class],
    version = 7, // keep in sync with VERSION below
    exportSchema = true,
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        /** Mirror of the @Database version. Room needs a literal in the annotation. */
        const val VERSION = 7

        /** Oldest schema version any installed build can still be sitting on. */
        const val OLDEST_SUPPORTED = 7

        /**
         * Every schema change gets a Migration here and a version bump. There is deliberately
         * no destructive fallback: this database holds the only copy of the user's history.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

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
