package com.pedometer.data

import androidx.room.*

@Entity(tableName = "daily_steps")
data class DailySteps(
    @PrimaryKey val date: String,           // "2026-06-28"
    val totalSteps: Int = 0,
    val walkSteps: Int = 0,
    val runSteps: Int = 0,
    val calories: Double = 0.0,
    val distanceKm: Double = 0.0,
)

@Entity(
    tableName = "hourly_steps",
    primaryKeys = ["date", "hour"],
)
data class HourlySteps(
    val date: String,                        // "2026-06-28"
    val hour: Int,                           // 0-23
    val steps: Int = 0,
)

@Entity(tableName = "step_snapshots")
data class StepSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,                     // epoch millis
    val stepsSinceReboot: Long,              // raw TYPE_STEP_COUNTER value
    val source: String,                      // "phone_sensor", "oplus_provider"
)
