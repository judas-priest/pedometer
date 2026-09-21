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
import com.pedometer.health.IntensityMinutes
import com.pedometer.health.PhoneStepCounter
import com.pedometer.health.HealthConnectReader
import com.pedometer.health.HealthInsights
import com.pedometer.health.StepProviderReader
import com.pedometer.health.UserProfile
import com.pedometer.health.WalkDetector
import com.pedometer.health.WalkMinute
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
    val intensityToday: IntensityMinutes.Result = IntensityMinutes.Result(0, 0, 0),
    val intensityDay: IntensityMinutes.Result = IntensityMinutes.Result(0, 0, 0),
    val walksDay: String = "",
    val intensityWeek: Int = 0,
    val weekSteps: Int = 0,
    val walksForDay: List<WalkCard> = emptyList(),
    val restingInsight: HealthInsights.RestingHrInsight = HealthInsights.RestingHrInsight(0, 0, 0, false),
    val weekTrimp: Int = 0,
    val prevWeekTrimp: Int = 0,
    val recentPaces: List<Float> = emptyList(), // min/km, last 5 walks, oldest first
)

data class WalkCard(
    val startMinute: Long,
    val endMinute: Long,
    val durationMin: Int,
    val activeMinutes: Int,
    val steps: Int,
    val distanceM: Int,
    val stepsPerMin: Int,
    val hrAvg: Int,
    val hrMax: Int,
    val kcal: Int = 0,
    val trimp: Int = 0,
    val paceMinPerKm: Float = 0f, // 0 = unknown (no distance)
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
        // Explicit user action bypasses the connection policy (quiet hours, home Wi-Fi):
        // connect directly with the manual-override latch instead of relying on the
        // service's AUTO path, which goes through repo.connect() and would be suppressed.
        repo.connectManually()
        // Keep the service for the notification/process lifetime. Its no-action branch
        // calls repo.connect() again — harmless: WatchLink.connect() ignores a call
        // while not Disconnected.
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

                // ── Weekly aggregates + today's intensity ──
                // Week = calendar week starting Monday (user expectation), not a rolling 7-day window.
                val maxHr = IntensityMinutes.maxHrFor(_state.value.profile.age)
                val restingHr = currentRestingHr(dao)
                val dayStart = java.time.LocalDate.parse(todayStr).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val dayEnd = dayStart + 86_400_000L
                val monday = java.time.LocalDate.parse(todayStr).with(java.time.DayOfWeek.MONDAY)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val weekHr = dao.getHeartRateBetween(monday, dayEnd)
                val intensityWeek = IntensityMinutes.compute(weekHr.map { it.timestamp to it.bpm }, maxHr, restingHr).earnedMinutes
                val weekSteps = dao.getRecentDays(7)
                    .filter { it.date >= java.time.LocalDate.parse(todayStr).with(java.time.DayOfWeek.MONDAY).toString() }
                    .sumOf { it.totalSteps }

                _state.value = _state.value.copy(
                    intensityToday = IntensityMinutes.compute(
                        dao.getHeartRateBetween(dayStart, dayEnd).map { it.timestamp to it.bpm }, maxHr, restingHr,
                    ),
                    intensityWeek = intensityWeek,
                    weekSteps = weekSteps,
                )
                loadDayInsights(todayStr)

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

    /** Recent resting HR from the watch's daily summaries; fallback 60 when no data. */
    private suspend fun currentRestingHr(dao: com.pedometer.data.StepDao): Int {
        val recent = dao.getRecentHealth(7).map { it.hrResting }.filter { it in 30..120 }
        return if (recent.isEmpty()) 60 else recent.sum() / recent.size
    }

    /**
     * Computes walks + intensity for one date and stores them under walksDay.
     * Intensity merges two estimators (Google Fit practice): HR-sample zones and walking
     * cadence (>=100 steps/min == moderate); the better of the two counts. HR samples are
     * sparse when the app is backgrounded — cadence keeps the metric honest.
     */
    fun loadDayInsights(dateStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = _state.value.profile
                val maxHr = IntensityMinutes.maxHrFor(profile.age)
                val day = java.time.LocalDate.parse(dateStr)
                val dayStart = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val dayEnd = dayStart + 86_400_000L
                val windowStart = dayStart - 28 * 86_400_000L
                val dao = StepDatabase.get(getApplication()).stepDao()
                val restingHr = currentRestingHr(dao)

                val hr = dao.getHeartRateBetween(windowStart, dayEnd)
                val hrResult = IntensityMinutes.compute(
                    hr.filter { it.timestamp >= dayStart }.map { it.timestamp to it.bpm }, maxHr,
                    restingHr,
                )

                val minuteData = dao.getMinuteStepsBetween(windowStart, dayEnd)
                    .filter { it.source == "phone" }
                    .map { WalkMinute(first = it.minute, steps = it.steps) }
                val walks = WalkDetector.detect(minuteData)

                // Watch-measured distance per minute (meters); phone rows have none.
                val watchDistByMinute = dao.getMinuteStepsBetween(windowStart, dayEnd)
                    .filter { it.source == "watch" && it.distanceM > 0 }
                    .associate { it.minute to it.distanceM }

                val cards = walks.map { seg ->
                    val segHr = hr.filter { it.timestamp in seg.startMinute..seg.endMinute + 60_000L }
                    val hrAvg = if (segHr.isEmpty()) 0 else segHr.map { it.bpm }.average().toInt()
                    val durationMin = ((seg.endMinute - seg.startMinute) / 60_000L).toInt() + 1
                    val watchDist = (seg.startMinute..seg.endMinute step 60_000L).sumOf { m ->
                        watchDistByMinute[m] ?: 0
                    }
                    val distanceM = if (watchDist > 0) watchDist else (seg.steps * profile.stepLengthM).toInt()
                    WalkCard(
                        startMinute = seg.startMinute,
                        endMinute = seg.endMinute,
                        durationMin = durationMin,
                        activeMinutes = seg.activeMinutes,
                        steps = seg.steps,
                        distanceM = distanceM,
                        stepsPerMin = seg.steps / seg.activeMinutes.coerceAtLeast(1),
                        hrAvg = hrAvg,
                        hrMax = segHr.maxOfOrNull { it.bpm } ?: 0,
                        kcal = HealthInsights.keytelKcal(hrAvg, profile.weightKg, profile.age, durationMin, profile.heightCm),
                        trimp = HealthInsights.trimp(hrAvg, durationMin, maxHr, restingHr),
                        paceMinPerKm = if (distanceM > 0) durationMin / (distanceM / 1000f) else 0f,
                    )
                }

                // Selected day's cards + intensity (only segments of that date)
                val dayCards = cards.filter {
                    val d = java.time.Instant.ofEpochMilli(it.startMinute).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    d == day
                }
                val cadenceModerate = dayCards.sumOf { w ->
                    if (w.stepsPerMin >= 100) w.activeMinutes else 0
                }
                val moderate = maxOf(hrResult.moderateMinutes, cadenceModerate)
                val intensity = IntensityMinutes.Result(
                    moderateMinutes = moderate,
                    intenseMinutes = hrResult.intenseMinutes,
                    earnedMinutes = moderate + hrResult.intenseMinutes * 2,
                )

                // TRIMP week-over-week, relative to the selected day
                val inWeek = cards.filter {
                    val d = java.time.Instant.ofEpochMilli(it.startMinute).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    !d.isBefore(day) && d.isBefore(day.plusDays(1))
                }
                val inPrevWeek = cards.filter {
                    val d = java.time.Instant.ofEpochMilli(it.startMinute).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    d.isBefore(day) && !d.isBefore(day.minusDays(6))
                }
                val weekTrimp = inWeek.sumOf { it.trimp }
                val prevWeekTrimp = inPrevWeek.sumOf { it.trimp }

                // Pace trend: last 5 walks BEFORE-or-on the selected day, oldest first
                val paces = cards.filter { it.paceMinPerKm > 0 }
                    .sortedByDescending { it.startMinute }
                    .take(5)
                    .sortedBy { it.startMinute }
                    .map { it.paceMinPerKm }

                // Resting-HR insight for the selected date
                val fromDate = day.minusDays(28).toString()
                val baseline = dao.getRestingHrBetween(fromDate, day.minusDays(1).toString())
                    .map { it.hrResting }
                val todayResting = dao.getRestingHrBetween(dateStr, dateStr)
                    .firstOrNull()?.hrResting ?: 0

                _state.value = _state.value.copy(
                    walksForDay = dayCards,
                    walksDay = dateStr,
                    intensityDay = intensity,
                    restingInsight = HealthInsights.restingHrInsight(baseline, todayResting),
                    weekTrimp = weekTrimp,
                    prevWeekTrimp = prevWeekTrimp,
                    recentPaces = paces,
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadDayInsights($dateStr) failed", e)
            }
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
