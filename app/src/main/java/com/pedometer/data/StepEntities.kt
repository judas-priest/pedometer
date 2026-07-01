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

@Entity(tableName = "daily_health")
data class DailyHealth(
    @PrimaryKey val date: String,            // "2026-06-28"
    val hrAvg: Int = 0,
    val hrMin: Int = 0,
    val hrMax: Int = 0,
    val hrResting: Int = 0,
    val spo2Avg: Int = 0,
    val spo2Min: Int = 0,
    val spo2Max: Int = 0,
    val stressAvg: Int = 0,
    val stressMin: Int = 0,
    val stressMax: Int = 0,
    val calories: Int = 0,
    val distanceM: Int = 0,
)

@Entity(tableName = "heart_rate")
data class HeartRateRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,                     // epoch millis
    val bpm: Int,
    val source: String = "watch",            // "watch" or "phone"
)

@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey val bedTime: Long,           // epoch millis
    val wakeupTime: Long,
    val totalMinutes: Int = 0,
    val deepMinutes: Int = 0,
    val lightMinutes: Int = 0,
    val remMinutes: Int = 0,
    val awakeMinutes: Int = 0,
)

@Entity(tableName = "workouts")
data class WorkoutRecord(
    @PrimaryKey val startTime: Long,         // epoch millis
    val endTime: Long,
    val sportType: Int,
    val sportName: String,
    val durationSec: Int = 0,
    val distanceM: Int = 0,
    val calories: Int = 0,
    val hrAvg: Int = 0,
    val hrMax: Int = 0,
    val hrMin: Int = 0,
)

@Entity(
    tableName = "gps_points",
    primaryKeys = ["workoutStart", "timestamp"],
)
data class GpsPointRecord(
    val workoutStart: Long,          // links to WorkoutRecord.startTime
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val speed: Float = 0f,
)

@Entity(tableName = "step_snapshots")
data class StepSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,                     // epoch millis
    val stepsSinceReboot: Long,              // raw TYPE_STEP_COUNTER value
    val source: String,                      // "phone_sensor", "oplus_provider"
)
