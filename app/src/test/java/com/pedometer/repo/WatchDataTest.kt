package com.pedometer.repo

import com.pedometer.bt.ConnectionStatus
import com.pedometer.health.UserProfile
import com.pedometer.util.WatchAlarm
import com.pedometer.vm.WatchState
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchDataTest {

    @Test
    fun `watch fields are copied into the ui state`() {
        val data = WatchData(
            connectionStatus = ConnectionStatus.Connected,
            batteryLevel = 42,
            watchSteps = 1234,
            heartRate = 71,
            alarms = listOf(WatchAlarm(id = 1, hour = 7, minute = 30, enabled = true)),
        )

        val merged = WatchState().withWatchData(data)

        assertEquals(ConnectionStatus.Connected, merged.connectionStatus)
        assertEquals(42, merged.batteryLevel)
        assertEquals(1234, merged.watchSteps)
        assertEquals(71, merged.heartRate)
        assertEquals(1, merged.alarms.size)
    }

    @Test
    fun `phone owned fields survive the merge`() {
        val state = WatchState(
            macAddress = "E8:E6:09:31:23:D8",
            authKey = "deadbeef",
            phoneSteps = 900L,
            todayWalkSteps = 800,
            profile = UserProfile(stepGoal = 12000),
        )

        val merged = state.withWatchData(WatchData(watchSteps = 10))

        assertEquals("E8:E6:09:31:23:D8", merged.macAddress)
        assertEquals("deadbeef", merged.authKey)
        assertEquals(900L, merged.phoneSteps)
        assertEquals(800, merged.todayWalkSteps)
        assertEquals(12000, merged.profile.stepGoal)
        assertEquals(10, merged.watchSteps)
    }

    @Test
    fun `default watch data clears live telemetry but not the connection defaults`() {
        val state = WatchState(watchSteps = 500, heartRate = 80)

        val merged = state.withWatchData(WatchData())

        assertEquals(0, merged.watchSteps)
        assertEquals(0, merged.heartRate)
        assertEquals(ConnectionStatus.Disconnected, merged.connectionStatus)
        assertEquals(-1, merged.batteryLevel)
    }

    @Test
    fun `room derived health values are never clobbered by the merge`() {
        // spo2, stress and hrResting come from Room via refreshData() — a repository
        // emission must not reset them. Regression guard for the split above.
        val state = WatchState(spo2 = 97, stress = 34, hrResting = 58, lastSleep = null)

        val merged = state.withWatchData(WatchData())

        assertEquals(97, merged.spo2)
        assertEquals(34, merged.stress)
        assertEquals(58, merged.hrResting)
    }
}
