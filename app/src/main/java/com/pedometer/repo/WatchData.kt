package com.pedometer.repo

import com.pedometer.bt.ConnectionStatus
import com.pedometer.util.WatchAlarm
import com.pedometer.util.WatchReminder
import com.pedometer.vm.WatchState
import com.pedometer.watchface.WatchfaceInfo

/**
 * Everything the watch itself is the source of truth for.
 *
 * Owned by WatchRepository, which outlives the Activity. The ViewModel merges it
 * into WatchState for the UI — see withWatchData.
 *
 * Data that lands in Room (sleep, workouts, heart-rate history, daily health) is NOT here:
 * the repository writes it to the database and bumps roomRevision, and the ViewModel
 * re-reads Room in response.
 */
data class WatchData(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val serialNumber: String = "",
    val firmware: String = "",
    val model: String = "",
    val batteryLevel: Int = -1,
    val batteryCharging: Boolean = false,
    val watchSteps: Int = 0,
    val watchCalories: Int = 0,
    val heartRate: Int = 0,
    val standingHours: Int = 0,
    val activeMinutes: Int = 0,
    val watchDistanceM: Int = 0,
    val findPhoneActive: Boolean = false,
    val watchfaces: List<WatchfaceInfo> = emptyList(),
    val uploadProgress: Int = -1,
    val alarms: List<WatchAlarm> = emptyList(),
    val reminders: List<WatchReminder> = emptyList(),
    /** Incremented whenever the repository writes health data to Room. */
    val roomRevision: Int = 0,
)

fun WatchState.withWatchData(d: WatchData): WatchState = copy(
    connectionStatus = d.connectionStatus,
    serialNumber = d.serialNumber,
    firmware = d.firmware,
    model = d.model,
    batteryLevel = d.batteryLevel,
    batteryCharging = d.batteryCharging,
    watchSteps = d.watchSteps,
    watchCalories = d.watchCalories,
    heartRate = d.heartRate,
    standingHours = d.standingHours,
    activeMinutes = d.activeMinutes,
    watchDistanceM = d.watchDistanceM,
    findPhoneActive = d.findPhoneActive,
    watchfaces = d.watchfaces,
    uploadProgress = d.uploadProgress,
    alarms = d.alarms,
    reminders = d.reminders,
)
