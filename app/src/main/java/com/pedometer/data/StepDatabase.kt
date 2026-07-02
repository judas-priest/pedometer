package com.pedometer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailySteps::class, HourlySteps::class, StepSnapshot::class, HeartRateRecord::class, DailyHealth::class, SleepRecord::class, WorkoutRecord::class, GpsPointRecord::class],
    version = 7,
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        // Future migrations go here — never lose data again
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Example: db.execSQL("ALTER TABLE daily_health ADD COLUMN newField INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile
        private var INSTANCE: StepDatabase? = null

        fun get(context: Context): StepDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    "pedometer.db",
                ).addMigrations(MIGRATION_7_8)
                 .fallbackToDestructiveMigration()
                 .build().also { INSTANCE = it }
            }
        }
    }
}
