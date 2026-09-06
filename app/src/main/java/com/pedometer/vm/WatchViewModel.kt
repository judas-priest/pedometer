package com.pedometer.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pedometer.data.DailyHealth
import com.pedometer.data.SleepRecord
import com.pedometer.data.DailySteps
import com.pedometer.data.HourlySteps
import com.pedometer.data.WorkoutRecord
import com.pedometer.data.StepDatabase
import com.pedometer.PedometerApp
import com.pedometer.health.DayStepData
import com.pedometer.health.PhoneStepCounter
import com.pedometer.health.HealthConnectReader
import com.pedometer.health.StepProviderReader
import com.pedometer.health.UserProfile
import com.pedometer.repo.withWatchData
import com.pedometer.service.WatchConnectionService
import com.pedometer.util.CalendarService
import com.pedometer.util.WatchAlarm
import com.pedometer.util.WatchReminder
import com.pedometer.watchface.WatchfaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WatchState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val serialNumber: String = "",
    val firmware: String = "",
    val model: String = "",
    val batteryLevel: Int = -1,
    val batteryCharging: Boolean = false,
    val authKey: String = "",
    val macAddress: String = "",
    val watchSteps: Int = 0,        // steps from watch
    val watchCalories: Int = 0,
    val heartRate: Int = 0,         // from watch only
    val standingHours: Int = 0,
    val activeMinutes: Int = 0,
    val watchDistanceM: Int = 0,
    val phoneSteps: Long = 0,
    val phoneStepsSinceBoot: Long = 0,
    val todayWalkSteps: Int = 0,
    val todayRunSteps: Int = 0,
    val todayWalkMinutes: Int = 0,
    val stepHistory: List<DayStepData> = emptyList(),
    val healthConnectSteps: Long = 0,
    val healthConnectHR: Int = 0,
    val profile: UserProfile = UserProfile(),
    val todayHourlySteps: List<HourlySteps> = emptyList(),
    val watchfaces: List<WatchfaceInfo> = emptyList(),
    val spo2: Int = 0,
    val stress: Int = 0,
    val hrResting: Int = 0,
    val lastSleep: SleepRecord? = null,
    val recentWorkouts: List<WorkoutRecord> = emptyList(),
    val findPhoneActive: Boolean = false,
    val hrHistory: List<Pair<Long, Int>> = emptyList(), // timestamp to bpm
    val healthHistory: List<DailyHealth> = emptyList(),
    val uploadProgress: Int = -1, // -1 = not uploading
    val alarms: List<WatchAlarm> = emptyList(),
    val reminders: List<WatchReminder> = emptyList(),
    val calendarEvents: List<com.pedometer.ui.CalendarEventUI> = emptyList(),
)

typealias ConnectionStatus = com.pedometer.bt.ConnectionStatus

/**
 * Phone-side state only. Everything the watch is the source of truth for arrives via
 * [PedometerApp.repository] — the repository owns the connection, the services and the
 * command handling, and survives Activity death; this ViewModel does not.
 */
class WatchViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "WatchViewModel"
        private const val PREFS_NAME = "pedometer_prefs"
        private const val KEY_AUTH = "auth_key"
        private const val KEY_MAC = "mac_address"
    }

    private val repo = PedometerApp.repository

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    private val phoneStepCounter = PhoneStepCounter(app)
    private val healthConnectReader = HealthConnectReader(app)
    private var userProfile = UserProfile.load(app)
    private var stepPollingJob: Job? = null

    init {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            authKey = prefs.getString(KEY_AUTH, "") ?: "",
            macAddress = prefs.getString(KEY_MAC, "") ?: "",
        )

        _state.value = _state.value.copy(profile = userProfile)
        refreshCalendarEvents()

        // Auto-connect is handled by WatchConnectionService, started from MainActivity
        // (and BootReceiver after reboot).

        // Watch data arrives from the repository
        viewModelScope.launch {
            repo.data.collect { data ->
                _state.value = _state.value.withWatchData(data)
            }
        }
        // Room got new health data (sleep, workouts, HR, daily health) — re-read it
        var lastRevision = 0
        viewModelScope.launch {
            repo.data.collect { data ->
                if (data.roomRevision != lastRevision) {
                    lastRevision = data.roomRevision
                    // Room writes originate from the watch — do NOT ask the watch again,
                    // otherwise each answer bumps roomRevision and re-triggers this loop.
                    refreshData(alsoFetchFromWatch = false)
                }
            }
        }
        repo.onGpsRelayNeeded = { needed -> if (needed) startGpsRelay() else stopGpsRelay() }

        viewModelScope.launch {
            PedometerApp.foreground.drop(1).collect { inForeground ->
                if (inForeground) onAppForeground() else onAppBackground()
            }
        }
        // App launches in foreground — ProcessLifecycleOwner doesn't emit for the initial
        // state, so trigger the first refresh + polling + sensor start explicitly.
        onAppForeground()

        viewModelScope.launch {
            phoneStepCounter.stepsSinceStart.collect { steps ->
                _state.value = _state.value.copy(phoneSteps = steps)
            }
        }
        viewModelScope.launch {
            phoneStepCounter.totalStepsSinceBoot.collect { total ->
                _state.value = _state.value.copy(phoneStepsSinceBoot = total)
            }
        }

    }

    fun updateProfile(profile: UserProfile) {
        userProfile = profile
        UserProfile.save(getApplication(), profile)
        _state.value = _state.value.copy(profile = profile)
        repo.onProfileChanged(profile)
    }

    fun updateAuthKey(key: String) {
        _state.value = _state.value.copy(authKey = key)
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTH, key).apply()
    }

    fun updateMacAddress(mac: String) {
        _state.value = _state.value.copy(macAddress = mac)
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAC, mac).apply()
    }

    fun connect() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, WatchConnectionService::class.java))
    }

    fun disconnect() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, WatchConnectionService::class.java)
            .setAction(WatchConnectionService.ACTION_DISCONNECT)
        ctx.startService(intent)
    }

    fun onAppForeground() {
        Log.i(TAG, "App foreground — starting step polling + full refresh")
        phoneStepCounter.start()
        refreshData()
        startStepPolling()
    }

    fun onAppBackground() {
        Log.i(TAG, "App background — stopping step polling + sensor")
        stopStepPolling()
        phoneStepCounter.stop()
    }

    private fun startStepPolling() {
        if (stepPollingJob?.isActive == true) return
        stepPollingJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            while (isActive) {
                try {
                    val today = StepProviderReader.readToday(app)
                    if (today != null) {
                        _state.value = _state.value.copy(
                            todayWalkSteps = today.walkSteps,
                            todayRunSteps = today.runSteps,
                            todayWalkMinutes = today.walkMinutes,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "StepProvider poll failed", e)
                }
                delay(30_000)
            }
        }
    }

    private fun stopStepPolling() {
        stepPollingJob?.cancel()
        stepPollingJob = null
    }

    fun refreshData(alsoFetchFromWatch: Boolean = true) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. StepProvider (OPLUS)
                val today = StepProviderReader.readToday(app)
                val history = StepProviderReader.readHistory(app, 30)
                if (today != null) {
                    _state.value = _state.value.copy(
                        todayWalkSteps = today.walkSteps,
                        todayRunSteps = today.runSteps,
                        todayWalkMinutes = today.walkMinutes,
                        stepHistory = history,
                    )
                } else {
                    // New day — StepProvider has no data yet, reset
                    _state.value = _state.value.copy(
                        todayWalkSteps = 0,
                        todayRunSteps = 0,
                        todayWalkMinutes = 0,
                        stepHistory = history,
                    )
                }
                if (today != null) {
                    val dao = StepDatabase.get(app).stepDao()
                    dao.upsertDaily(DailySteps(
                        date = today.date,
                        totalSteps = today.totalSteps,
                        walkSteps = today.walkSteps,
                        runSteps = today.runSteps,
                        calories = userProfile.calcCalories(today.totalSteps),
                        distanceKm = userProfile.calcDistance(today.totalSteps),
                    ))
                }

                // 2. Room DB — all health data
                val dao = StepDatabase.get(app).stepDao()
                val todayStr = java.time.LocalDate.now().toString()
                val hourly = dao.getHourlyForDay(todayStr)
                val health = dao.getDailyHealth(todayStr)
                val lastSleep = dao.getLastSleep()
                val workouts = dao.getRecentWorkouts(10)
                val weekAgo = java.time.LocalDate.now().minusDays(7)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
                val hrRecords = dao.getHeartRateSince(weekAgo)
                val hrHistory = hrRecords.map { Pair(it.timestamp, it.bpm) }
                _state.value = _state.value.copy(
                    todayHourlySteps = hourly,
                    spo2 = health?.spo2Avg ?: _state.value.spo2,
                    stress = health?.stressAvg ?: _state.value.stress,
                    hrResting = health?.hrResting ?: _state.value.hrResting,
                    lastSleep = lastSleep ?: _state.value.lastSleep,
                    recentWorkouts = workouts,
                    hrHistory = if (hrHistory.isNotEmpty()) hrHistory else _state.value.hrHistory,
                    healthHistory = dao.getRecentHealth(30),
                )

                // 3. Health Connect
                try {
                    if (healthConnectReader.isAvailable()) {
                        val hcSteps = healthConnectReader.readTodaySteps()
                        val hcHR = healthConnectReader.readLatestHeartRate()
                        _state.value = _state.value.copy(
                            healthConnectSteps = hcSteps,
                            healthConnectHR = hcHR,
                        )
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}

            // 4. Watch data — battery, activity files, weather
            if (alsoFetchFromWatch) repo.refreshFromWatch()
        }
    }

    fun createCalendarEvent(title: String, y: Int, m: Int, d: Int, h: Int, min: Int) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Main) {
            val ok = CalendarService.addToSystemCalendar(app, title, y, m, d, h, min)
            if (ok) {
                refreshCalendarEvents()
                repo.syncCalendar()
            }
        }
    }

    fun updateCalendarEvent(eventId: Long, title: String, y: Int, m: Int, d: Int, h: Int, min: Int) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Main) {
            val ok = CalendarService.updateInSystemCalendar(app, eventId, title, y, m, d, h, min)
            if (ok) {
                refreshCalendarEvents()
                repo.syncCalendar()
            }
        }
    }

    fun deleteCalendarEvent(eventId: Long) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            CalendarService.deleteFromSystemCalendar(app, eventId)
            refreshCalendarEvents()
            repo.syncCalendar()
        }
    }

    fun refreshCalendarEvents() {
        val app = getApplication<Application>()
        val events = CalendarService.readUpcomingEventsUI(app)
        _state.value = _state.value.copy(calendarEvents = events)
    }

    fun getAlarms() = repo.getAlarms()

    fun createAlarm(hour: Int, minute: Int) = repo.createAlarm(hour, minute)

    fun editAlarm(alarm: WatchAlarm) = repo.editAlarm(alarm)

    fun deleteAlarm(id: Int) = repo.deleteAlarm(id)

    fun requestWatchfaces() = repo.requestWatchfaces()

    fun setActiveWatchface(id: String) = repo.setActiveWatchface(id)

    fun deleteWatchface(id: String) = repo.deleteWatchface(id)

    fun uploadWatchface(data: ByteArray) = repo.uploadWatchface(data)

    fun findWatch() = repo.findWatch()

    fun setDnd(enabled: Boolean) = repo.setDnd(enabled)

    fun setWearingMode(mode: Int) = repo.setWearingMode(mode)

    fun syncContacts() = repo.syncContacts()

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGpsRelay() {
        Log.i(TAG, "Starting GPS relay for workout")
        try {
            val client = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(getApplication<Application>())
            val request = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 5000
            ).build()
            val callback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val loc = result.lastLocation ?: return
                    repo.sendGpsLocation(
                        loc.latitude, loc.longitude, loc.altitude,
                        loc.speed, loc.bearing
                    )
                }
            }
            gpsCallback = callback
            client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "GPS relay failed: ${e.message}")
        }
    }

    private var gpsCallback: com.google.android.gms.location.LocationCallback? = null

    private fun stopGpsRelay() {
        Log.i(TAG, "Stopping GPS relay")
        gpsCallback?.let {
            try {
                com.google.android.gms.location.LocationServices
                    .getFusedLocationProviderClient(getApplication<Application>())
                    .removeLocationUpdates(it)
            } catch (_: Exception) {}
        }
        gpsCallback = null
    }

    override fun onCleared() {
        stopGpsRelay()
        stopStepPolling()
        phoneStepCounter.stop()
        repo.onGpsRelayNeeded = null
        // Deliberately NOT disconnecting: the connection belongs to the repository/process.
    }
}
