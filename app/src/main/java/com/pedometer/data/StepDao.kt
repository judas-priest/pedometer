package com.pedometer.data

import androidx.room.*

@Dao
interface StepDao {
    // Daily steps
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(daily: DailySteps)

    @Query("SELECT * FROM daily_steps WHERE date = :date")
    suspend fun getDaily(date: String): DailySteps?

    @Query("SELECT * FROM daily_steps ORDER BY date DESC LIMIT :days")
    suspend fun getRecentDays(days: Int): List<DailySteps>

    // Hourly steps
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHourly(hourly: HourlySteps)

    @Query("UPDATE hourly_steps SET steps = steps + :count WHERE date = :date AND hour = :hour")
    suspend fun incrementHourly(date: String, hour: Int, count: Int)

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour")
    suspend fun getHourlyForDay(date: String): List<HourlySteps>

    // Step snapshots (for reboot delta computation)
    @Insert
    suspend fun insertSnapshot(snapshot: StepSnapshot)

    @Query("SELECT * FROM step_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastSnapshot(): StepSnapshot?

    @Query("DELETE FROM step_snapshots WHERE timestamp < :before")
    suspend fun cleanOldSnapshots(before: Long)

    // Daily health (summary from watch)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyHealth(health: DailyHealth)

    @Query("SELECT * FROM daily_health WHERE date = :date")
    suspend fun getDailyHealth(date: String): DailyHealth?

    @Query("SELECT * FROM daily_health ORDER BY date DESC LIMIT :days")
    suspend fun getRecentHealth(days: Int): List<DailyHealth>

    // Workouts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkout(workout: WorkoutRecord)

    @Query("SELECT * FROM workouts ORDER BY startTime DESC LIMIT :count")
    suspend fun getRecentWorkouts(count: Int): List<WorkoutRecord>

    // Sleep
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleep(sleep: SleepRecord)

    @Query("SELECT * FROM sleep_records ORDER BY bedTime DESC LIMIT 1")
    suspend fun getLastSleep(): SleepRecord?

    @Query("SELECT * FROM sleep_records ORDER BY bedTime DESC LIMIT :count")
    suspend fun getRecentSleep(count: Int): List<SleepRecord>

    // Heart rate
    @Insert
    suspend fun insertHeartRate(hr: HeartRateRecord)

    @Query("SELECT * FROM heart_rate WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getHeartRateSince(since: Long): List<HeartRateRecord>

    @Query("SELECT * FROM heart_rate ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastHeartRate(): HeartRateRecord?

    @Query("DELETE FROM heart_rate WHERE timestamp < :before")
    suspend fun cleanOldHeartRate(before: Long)
}
