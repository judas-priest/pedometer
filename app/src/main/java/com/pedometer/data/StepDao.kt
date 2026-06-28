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
}
