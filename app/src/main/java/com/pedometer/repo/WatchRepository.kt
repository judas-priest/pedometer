package com.pedometer.repo

import android.content.Context
import android.util.Log
import com.pedometer.bt.ConnectionStatus
import com.pedometer.bt.ProtocolHandler
import com.pedometer.bt.WatchLink
import com.pedometer.data.DailyHealth
import com.pedometer.data.GpsPointRecord
import com.pedometer.data.HeartRateRecord
import com.pedometer.data.HourlySteps
import com.pedometer.data.SleepRecord
import com.pedometer.data.StepDatabase
import com.pedometer.data.WorkoutRecord
import com.pedometer.health.ActivitySync
import com.pedometer.health.HealthService
import com.pedometer.health.UserProfile
import com.pedometer.music.MusicService
import com.pedometer.notification.NotificationService
import com.pedometer.notification.WatchNotificationBridge
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import com.pedometer.util.AlarmService
import com.pedometer.util.CalendarService
import com.pedometer.util.ReminderService
import com.pedometer.util.UtilityService
import com.pedometer.util.WatchAlarm
import com.pedometer.util.WatchSettings
import com.pedometer.watchface.DataUploadService
import com.pedometer.watchface.WatchfaceService
import com.pedometer.weather.WeatherProvider
import com.pedometer.weather.WeatherService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of the watch connection and every watch-facing service.
 *
 * Created once in PedometerApp.onCreate and kept alive by WatchConnectionService.
 * Survives Activity death — notification forwarding, call forwarding and activity sync
 * keep working with no UI on screen.
 */
class WatchRepository(private val context: Context) {

    companion object {
        private const val TAG = "WatchRepository"
        private const val PREFS_NAME = "pedometer_prefs"
        private const val KEY_AUTH = "auth_key"
        private const val KEY_MAC = "mac_address"

        @Volatile private var INSTANCE: WatchRepository? = null

        fun get(context: Context): WatchRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WatchRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    /** Internal, not private: PedometerApp launches the foreground-signal collector on it. */
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Uncaught in repository scope", e)
        }
    )
    private val _data = MutableStateFlow(WatchData())
    val data: StateFlow<WatchData> = _data

    private val link = WatchLink(context, scope)

    // Watch-facing services — recreated on every successful connect. Written from the
    // BT read/auth threads, read from main/IO — hence @Volatile.
    @Volatile private var healthService: HealthService? = null
    @Volatile private var musicService: MusicService? = null
    @Volatile private var weatherService: WeatherService? = null
    @Volatile private var notificationService: NotificationService? = null
    @Volatile private var watchfaceService: WatchfaceService? = null
    @Volatile private var activitySync: ActivitySync? = null
    @Volatile private var dataUploadService: DataUploadService? = null
    @Volatile private var utilityService: UtilityService? = null
    @Volatile private var alarmService: AlarmService? = null
    @Volatile private var calendarService: CalendarService? = null
    @Volatile private var reminderService: ReminderService? = null

    private var weatherJob: Job? = null
    private var initJob: Job? = null
    @Volatile private var lastHrSaveTime = 0L
    @Volatile private var lastWeatherFetchTime = 0L
    @Volatile private var profile: UserProfile = UserProfile.load(context)

    private val dao get() = StepDatabase.get(context).stepDao()

    /** Set by the ViewModel; the watch asks for phone GPS during workouts. */
    var onGpsRelayNeeded: ((Boolean) -> Unit)? = null

    /** ViewModel relays fused-location fixes here while the watch workout requests GPS. */
    fun sendGpsLocation(lat: Double, lon: Double, alt: Double, speed: Float, bearing: Float) {
        healthService?.sendGpsLocation(lat, lon, alt, speed, bearing)
    }

    init {
        link.onAuthenticated = { onAuthenticated() }
        link.onCommand = { cmd -> handleCommand(cmd) }
        link.onActivityData = { bytes -> activitySync?.handleRawData(bytes) }
        link.onDisconnected = { onLinkDropped() }
        scope.launch {
            link.status.collect { status ->
                _data.value = _data.value.copy(connectionStatus = status)
            }
        }
    }

    val hasCredentials: Boolean
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_MAC, "").isNullOrBlank() &&
                !prefs.getString(KEY_AUTH, "").isNullOrBlank()
        }

    fun connect() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        link.connect(
            prefs.getString(KEY_MAC, "") ?: "",
            prefs.getString(KEY_AUTH, "") ?: "",
        )
    }

    fun disconnect() {
        weatherJob?.cancel(); weatherJob = null
        initJob?.cancel(); initJob = null
        link.disconnect()
        stopGpsRelayIfActive()
    }

    /** Ask the consumer (ViewModel) to stop fused-location updates — idempotent on its side. */
    private fun stopGpsRelayIfActive() {
        val cb = onGpsRelayNeeded
        cb?.invoke(false)
    }

    fun onProfileChanged(newProfile: UserProfile) {
        profile = newProfile
    }

    /** Tell the ViewModel that Room has new health data to re-read. */
    private fun bumpRoom() {
        _data.value = _data.value.copy(roomRevision = _data.value.roomRevision + 1)
    }

    private fun onLinkDropped() {
        WatchNotificationBridge.protocolHandler = null
        weatherJob?.cancel(); weatherJob = null
        initJob?.cancel(); initJob = null
        healthService?.stopRealtimeStats()
        healthService = null
        musicService = null
        weatherService = null
        notificationService = null
        watchfaceService = null
        activitySync = null
        dataUploadService = null
        utilityService = null
        alarmService = null
        calendarService = null
        reminderService = null
        stopGpsRelayIfActive()
    }

    private fun onAuthenticated() {
        val handler = link.protocolHandler ?: return
        WatchNotificationBridge.protocolHandler = handler
        buildServices(handler)
        startPostAuthInit()
        startWeatherLoop()
    }

    private fun buildServices(handler: ProtocolHandler) {
        val health = HealthService(handler) { data ->
            _data.value = _data.value.copy(
                watchSteps = if (data.steps > 0) data.steps else _data.value.watchSteps,
                watchCalories = if (data.calories > 0) data.calories else _data.value.watchCalories,
                heartRate = if (data.heartRate > 0) data.heartRate else _data.value.heartRate,
                standingHours = if (data.standingHours > 0) data.standingHours else _data.value.standingHours,
                activeMinutes = if (data.activeMinutes > 0) data.activeMinutes else _data.value.activeMinutes,
            )
            // Persist heart rate to Room DB (max once per minute)
            val now = System.currentTimeMillis()
            if (data.heartRate > 0 && now - lastHrSaveTime >= 60_000) {
                lastHrSaveTime = now
                scope.launch(Dispatchers.IO) {
                    try {
                        dao.insertHeartRate(HeartRateRecord(
                            timestamp = now,
                            bpm = data.heartRate,
                            source = "watch",
                        ))
                    } catch (_: Exception) {}
                }
            }
        }
        health.onGpsNeeded = { needed ->
            onGpsRelayNeeded?.invoke(needed)
        }
        health.onWorkoutEvent = { event ->
            Log.i(TAG, "Workout event: ${event.sportName} status=${event.status}")
            when (event.status) {
                0 -> { // started
                    WatchNotificationBridge.sendToWatch(
                        id = 77777,
                        packageName = "com.pedometer",
                        appName = "Тренировка",
                        title = event.sportName,
                        body = "Начата",
                    )
                }
                3 -> { // finished
                    // Fetch activity data to get workout results + GPS track
                    scope.launch(Dispatchers.IO) {
                        delay(5000) // wait for watch to save files
                        link.send(CommandHelper.buildActivityFetchToday())
                        delay(3000)
                        activitySync?.requestPast()
                    }
                }
            }
        }
        healthService = health

        val music = MusicService(context, handler)
        musicService = music

        val weather = WeatherService(handler)
        weather.onWeatherRequested = {
            scope.launch(Dispatchers.IO) { fetchAndSendWeather() }
        }
        weatherService = weather

        val utility = UtilityService(handler) {
            Log.i(TAG, "FIND PHONE triggered!")
            startFindPhoneRingtone()
        }

        utilityService = utility

        val alarm = AlarmService(handler)
        alarm.onAlarmsReceived = { alarms ->
            _data.value = _data.value.copy(alarms = alarms)
        }
        alarmService = alarm

        calendarService = CalendarService(handler, context)

        val remService = ReminderService(handler)
        remService.onRemindersReceived = { list ->
            _data.value = _data.value.copy(reminders = list)
        }
        reminderService = remService

        val sync = ActivitySync(handler,
            onGpsTrack = { workoutStartMs, points ->
                scope.launch(Dispatchers.IO) {
                    try {
                        dao.insertGpsPoints(points.map { p ->
                            GpsPointRecord(workoutStart = workoutStartMs, timestamp = p.timestamp, lat = p.lat, lon = p.lon, speed = p.speed)
                        })
                        Log.i(TAG, "Saved ${points.size} GPS points for workout $workoutStartMs")
                        bumpRoom()
                    } catch (e: Exception) { Log.e(TAG, "Save GPS failed", e) }
                }
            },
            onHourlySteps = { date, hourlyList ->
                scope.launch(Dispatchers.IO) {
                    try {
                        // Watch data = source of truth, overwrite
                        for ((hour, steps) in hourlyList) {
                            dao.upsertHourly(HourlySteps(date = date, hour = hour, steps = steps))
                        }
                        Log.i(TAG, "Saved ${hourlyList.size} hourly steps for $date from watch")
                        bumpRoom()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save hourly steps", e)
                    }
                }
            },
            onWorkout = { w ->
                Log.i(TAG, "Workout: ${w.sportName} ${w.durationSec/60}min")
                scope.launch(Dispatchers.IO) {
                    try {
                        dao.upsertWorkout(WorkoutRecord(
                            startTime = w.startTime, endTime = w.endTime,
                            sportType = w.sportType, sportName = w.sportName,
                            durationSec = w.durationSec, distanceM = w.distanceM,
                            calories = w.calories, hrAvg = w.hrAvg, hrMax = w.hrMax, hrMin = w.hrMin,
                        ))
                        bumpRoom()
                    } catch (e: Exception) { Log.e(TAG, "Save workout failed", e) }
                }
            },
            onSleepData = { sleep ->
                Log.i(TAG, "Sleep: ${sleep.totalMinutes}min deep=${sleep.deepMinutes} light=${sleep.lightMinutes} REM=${sleep.remMinutes}")
                scope.launch(Dispatchers.IO) {
                    try {
                        val record = SleepRecord(
                            bedTime = sleep.bedTime,
                            wakeupTime = sleep.wakeupTime,
                            totalMinutes = sleep.totalMinutes,
                            deepMinutes = sleep.deepMinutes,
                            lightMinutes = sleep.lightMinutes,
                            remMinutes = sleep.remMinutes,
                            awakeMinutes = sleep.awakeMinutes,
                        )
                        dao.upsertSleep(record)
                        bumpRoom()
                        Log.i(TAG, "Saved sleep record")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save sleep", e)
                    }
                }
            },
            onDailySummary = { summary ->
                Log.i(TAG, "Daily summary: HR avg=${summary.hrAvg} rest=${summary.hrResting} " +
                    "SpO2=${summary.spo2Avg} stress=${summary.stressAvg} cal=${summary.calories}")
                // Update watch calories + distance + active minutes from daily summary.
                // SpO2/stress/resting HR are NOT pushed here: they land in upsertDailyHealth
                // below and reach the UI via bumpRoom() — pushing them would overwrite
                // Room values with zeros on the next emission.
                _data.value = _data.value.copy(
                    watchCalories = if (summary.calories > 0) summary.calories else _data.value.watchCalories,
                    watchDistanceM = if (summary.distanceM > 0) summary.distanceM else _data.value.watchDistanceM,
                    activeMinutes = if (summary.activeMinutes > 0) summary.activeMinutes else _data.value.activeMinutes,
                )
                scope.launch(Dispatchers.IO) {
                    try {
                        val dateStr = java.time.Instant.ofEpochSecond(summary.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate().toString()
                        val existing = dao.getDailyHealth(dateStr)
                        dao.upsertDailyHealth(DailyHealth(
                            date = dateStr,
                            hrAvg = if (summary.hrAvg > 0) summary.hrAvg else existing?.hrAvg ?: 0,
                            hrMin = if (summary.hrMin > 0) summary.hrMin else existing?.hrMin ?: 0,
                            hrMax = if (summary.hrMax > 0) summary.hrMax else existing?.hrMax ?: 0,
                            hrResting = if (summary.hrResting > 0) summary.hrResting else existing?.hrResting ?: 0,
                            spo2Avg = if (summary.spo2Avg > 0) summary.spo2Avg else existing?.spo2Avg ?: 0,
                            spo2Min = if (summary.spo2Min > 0) summary.spo2Min else existing?.spo2Min ?: 0,
                            spo2Max = if (summary.spo2Max > 0) summary.spo2Max else existing?.spo2Max ?: 0,
                            stressAvg = if (summary.stressAvg > 0) summary.stressAvg else existing?.stressAvg ?: 0,
                            stressMin = if (summary.stressMin > 0) summary.stressMin else existing?.stressMin ?: 0,
                            stressMax = if (summary.stressMax > 0) summary.stressMax else existing?.stressMax ?: 0,
                            calories = if (summary.calories > 0) summary.calories else existing?.calories ?: 0,
                            distanceM = if (summary.distanceM > 0) summary.distanceM else existing?.distanceM ?: 0,
                        ))
                        Log.i(TAG, "Saved daily health for $dateStr")
                        bumpRoom()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save daily health", e)
                    }
                }
            },
            onHeartRateSamples = { samples ->
                Log.i(TAG, "Got ${samples.size} HR samples from activity sync")
                scope.launch(Dispatchers.IO) {
                    try {
                        for (s in samples) {
                            dao.insertHeartRate(HeartRateRecord(
                                timestamp = s.timestamp,
                                bpm = s.bpm,
                                source = "watch_history",
                            ))
                        }
                        Log.i(TAG, "Saved ${samples.size} HR samples to DB")
                        bumpRoom()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save HR samples", e)
                    }
                }
            },
        )
        activitySync = sync

        val upload = DataUploadService(handler)
        upload.onProgress = { progress ->
            _data.value = _data.value.copy(uploadProgress = progress)
        }
        upload.onComplete = { success ->
            _data.value = _data.value.copy(uploadProgress = -1)
            Log.i(TAG, "Watchface upload ${if (success) "SUCCESS" else "FAILED"}")
            if (success) watchfaceService?.requestWatchfaceList()
        }
        dataUploadService = upload

        val watchface = WatchfaceService(handler) { faces ->
            _data.value = _data.value.copy(watchfaces = faces)
        }
        watchfaceService = watchface

        val notif = NotificationService(context, handler)
        notif.onCallAction = { accept ->
            try {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                if (accept) {
                    @Suppress("MissingPermission")
                    telecom.acceptRingingCall()
                    Log.i(TAG, "Call ACCEPTED from watch")
                } else {
                    @Suppress("MissingPermission")
                    telecom.endCall()
                    Log.i(TAG, "Call REJECTED from watch")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Call action failed: ${e.message}")
            }
        }
        notificationService = notif
    }

    private fun startPostAuthInit() {
        initJob?.cancel()
        initJob = scope.launch {
            delay(500)
            Log.i(TAG, "POST-AUTH: initializing watch")

            // 1. getDeviceInfo
            link.send(CommandHelper.buildDeviceInfoRequest())
            delay(200)

            // 2. getBattery
            link.send(CommandHelper.buildBatteryRequest())
            delay(200)

            // 3. setCurrentTime
            sendCurrentTime()
            delay(200)

            // 4. setUserInfo
            sendUserInfo()
            delay(200)

            // 5. setLocale
            sendLocale()
            delay(200)

            // 6. Health config init
            healthService?.initialize()
            delay(300)
            // Start realtime stats only if app is in foreground
            if (com.pedometer.PedometerApp.isInForeground) {
                healthService?.startRealtimeStats()
                Log.i(TAG, "Realtime stats started (foreground)")
            } else {
                Log.i(TAG, "Realtime stats skipped (background)")
            }

            // 7. Fetch alarms
            alarmService?.getAlarms()
            delay(200)

            // 8. Sync calendar
            calendarService?.syncCalendar()
            delay(200)

            // 9. Fetch reminders
            reminderService?.getReminders()
            delay(200)

            // 10. Send canned messages for quick reply
            notificationService?.sendCannedMessages()
            delay(500)

            // 11. Send weather (activity files fetched by HealthService.initialize())
            try { fetchAndSendWeather() } catch (e: Exception) { Log.e(TAG, "Weather init failed", e) }
            Log.i(TAG, "POST-AUTH: init complete")
        }
    }

    private fun startWeatherLoop() {
        weatherJob?.cancel()
        weatherJob = scope.launch {
            while (true) {
                delay(2 * 60 * 60 * 1000L)
                if (_data.value.connectionStatus != ConnectionStatus.Connected) break
                try {
                    fetchAndSendWeather()
                    lastWeatherFetchTime = System.currentTimeMillis()
                } catch (e: Exception) { Log.e(TAG, "Weather refresh failed", e) }
            }
        }
    }

    fun getAlarms() { alarmService?.getAlarms() }
    // Signature mirrors WatchViewModel.createAlarm exactly — MainActivity calls it with two
    // arguments, the defaults must survive the move.
    fun createAlarm(hour: Int, minute: Int, repeatMode: Int = 0, repeatFlags: Int = 0) {
        alarmService?.createAlarm(hour, minute, repeatMode, repeatFlags)
    }
    fun editAlarm(alarm: WatchAlarm) { alarmService?.editAlarm(alarm) }
    fun deleteAlarm(alarmId: Int) { alarmService?.deleteAlarm(alarmId) }

    fun getReminders() { reminderService?.getReminders() }
    fun syncCalendar() { calendarService?.syncCalendar() }
    // WatchSettings is cheap and stateless — built per call rather than held.
    fun syncContacts() { watchSettings()?.syncContacts() }
    fun setDnd(enabled: Boolean) { watchSettings()?.setDnd(enabled) }
    fun setWearingMode(mode: Int) { watchSettings()?.setWearingMode(mode) }
    private fun watchSettings(): WatchSettings? =
        link.protocolHandler?.let { WatchSettings(it, context) }

    /** The "watch" half of the ViewModel's refreshData(). */
    fun refreshFromWatch() {
        if (_data.value.connectionStatus != ConnectionStatus.Connected) return
        scope.launch {
            link.send(CommandHelper.buildBatteryRequest())
            link.send(CommandHelper.buildActivityFetchToday())
            delay(1000)
            activitySync?.requestPast()
            delay(500)
            val now = System.currentTimeMillis()
            if (now - lastWeatherFetchTime > 30 * 60 * 1000L) {
                try { fetchAndSendWeather() } catch (e: Exception) { Log.e(TAG, "Weather refresh failed", e) }
                lastWeatherFetchTime = now
            }
        }
    }

    fun requestWatchfaces() { watchfaceService?.requestWatchfaceList() }
    fun setActiveWatchface(id: String) { watchfaceService?.setActiveWatchface(id) }
    fun deleteWatchface(id: String) { watchfaceService?.deleteWatchface(id) }
    fun uploadWatchface(bytes: ByteArray) { dataUploadService?.uploadWatchface(bytes) }

    fun findWatch() {
        // findDevice: 0 = start ringing, 1 = stop ringing
        fun cmd(state: Int) = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_SYSTEM)
            .setSubtype(18) // CMD_FIND_WATCH
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(state))
            .build()
        link.send(cmd(0))
        Log.i(TAG, "Find watch triggered (start)")

        // Auto-stop after 10 seconds
        scope.launch {
            delay(10_000)
            link.send(cmd(1))
            Log.i(TAG, "Find watch stopped")
        }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        if (_data.value.connectionStatus != ConnectionStatus.Connected) return
        if (inForeground) healthService?.startRealtimeStats() else healthService?.stopRealtimeStats()
    }

    private var findPhoneRingtone: android.media.Ringtone? = null

    private fun startFindPhoneRingtone() {
        stopFindPhoneRingtone()
        findPhoneRingtone = android.media.RingtoneManager.getRingtone(context,
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
        findPhoneRingtone?.play()
        _data.value = _data.value.copy(findPhoneActive = true)
        // Auto-stop after 30 seconds
        scope.launch {
            delay(30000)
            stopFindPhoneRingtone()
        }
    }

    private fun stopFindPhoneRingtone() {
        findPhoneRingtone?.stop()
        findPhoneRingtone = null
        _data.value = _data.value.copy(findPhoneActive = false)
    }

    private fun fetchAndSendWeather() {
        try {
            val cityOverride = profile.weatherCity
            val coords = if (cityOverride.isNotBlank()) {
                WeatherProvider.geocodeCity(context, cityOverride)
            } else {
                WeatherProvider.getLocation(context)
            }
            val lat = coords?.first ?: 55.75
            val lon = coords?.second ?: 37.62
            val data = WeatherProvider.fetch(lat, lon, coords?.third ?: "Москва")
            if (data != null) {
                weatherService?.setLocation(data.cityName)
                weatherService?.sendWeather(data)

                val forecasts = WeatherProvider.fetchForecast(lat, lon)
                if (forecasts.isNotEmpty()) {
                    weatherService?.sendForecast(data.cityName, forecasts)
                }
            } else {
                Log.w(TAG, "Weather fetch returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather failed", e)
        }
    }

    private fun sendCurrentTime() {
        val cal = java.util.GregorianCalendar.getInstance()
        val tz = cal.timeZone
        val offset = tz.getOffset(cal.timeInMillis)
        val quarterHourOffset = (offset / 1000 / 60 / 15)
        val dst = if (tz.inDaylightTime(cal.time)) (tz.dstSavings / 1000 / 60 / 15) else 0

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_SYSTEM)
            .setSubtype(CommandHelper.SYS_CLOCK)
            .setSystem(XiaomiProto.System.newBuilder()
                .setClock(XiaomiProto.Clock.newBuilder()
                    .setTime(XiaomiProto.Time.newBuilder()
                        .setHour(cal.get(java.util.Calendar.HOUR_OF_DAY))
                        .setMinute(cal.get(java.util.Calendar.MINUTE))
                        .setSecond(cal.get(java.util.Calendar.SECOND))
                        .setMillisecond(cal.get(java.util.Calendar.MILLISECOND)))
                    .setDate(XiaomiProto.Date.newBuilder()
                        .setYear(cal.get(java.util.Calendar.YEAR))
                        .setMonth(cal.get(java.util.Calendar.MONTH) + 1)
                        .setDay(cal.get(java.util.Calendar.DAY_OF_MONTH)))
                    .setTimezone(XiaomiProto.TimeZone.newBuilder()
                        .setZoneOffset(quarterHourOffset)
                        .setDstOffset(dst)
                        .setName(tz.id))))
            .build()
        Log.i(TAG, "Sending current time: ${cal.get(java.util.Calendar.HOUR_OF_DAY)}:${cal.get(java.util.Calendar.MINUTE)} tz=${tz.id}")
        link.send(cmd)
    }

    private fun sendUserInfo() {
        val p = profile
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_HEALTH)
            .setSubtype(0) // CMD_USER_INFO_SET
            .setHealth(XiaomiProto.Health.newBuilder()
                .setUserInfo(XiaomiProto.UserInfo.newBuilder()
                    .setHeight(p.heightCm)
                    .setWeight(p.weightKg.toFloat())
                    .setBirthday(19900101)
                    .setGender(if (p.isMale) 1 else 2)
                    .setGoalSteps(p.stepGoal)
                    .setGoalCalories(300)
                    .setGoalStanding(12)
                    .setGoalMoving(30)))
            .build()
        Log.i(TAG, "Sending user info: height=${p.heightCm} weight=${p.weightKg} goal=${p.stepGoal}")
        link.send(cmd)
    }

    private fun sendLocale() {
        link.send(
            XiaomiProto.Command.newBuilder()
                .setType(CommandHelper.TYPE_SYSTEM)
                .setSubtype(6)
                .setSystem(
                    XiaomiProto.System.newBuilder().setLanguage(
                        XiaomiProto.Language.newBuilder()
                            .setCode(java.util.Locale.getDefault().toLanguageTag().replace("-", "_").lowercase())
                    )
                )
                .build()
        )
    }

    private fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.type) {
            CommandHelper.TYPE_SYSTEM -> handleSystemCommand(cmd)
            CommandHelper.TYPE_HEALTH -> {
                healthService?.handleCommand(cmd)
                activitySync?.handleCommand(cmd)
            }
            MusicService.COMMAND_TYPE -> musicService?.handleCommand(cmd)
            WeatherService.COMMAND_TYPE -> weatherService?.handleCommand(cmd)
            NotificationService.COMMAND_TYPE -> notificationService?.handleCommand(cmd)
            WatchfaceService.COMMAND_TYPE -> watchfaceService?.handleCommand(cmd)
            DataUploadService.COMMAND_TYPE -> dataUploadService?.handleCommand(cmd)
            AlarmService.COMMAND_TYPE -> {
                alarmService?.handleCommand(cmd)
                reminderService?.handleCommand(cmd)
            }
            else -> Log.d(TAG, "Unhandled command type=${cmd.type}")
        }
    }

    private fun handleSystemCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.SYS_DEVICE_INFO -> {
                if (cmd.hasSystem() && cmd.system.hasDeviceInfo()) {
                    val info = cmd.system.deviceInfo
                    _data.value = _data.value.copy(
                        serialNumber = info.serialNumber,
                        firmware = info.firmware,
                        model = info.model,
                    )
                    Log.i(TAG, "Device: ${info.model} FW:${info.firmware} SN:${info.serialNumber}")
                }
            }
            CommandHelper.SYS_BATTERY -> {
                if (cmd.hasSystem() && cmd.system.hasPower() && cmd.system.power.hasBattery()) {
                    val bat = cmd.system.power.battery
                    _data.value = _data.value.copy(
                        batteryLevel = bat.level,
                        batteryCharging = bat.state == 1,
                    )
                    Log.i(TAG, "Battery: ${bat.level}% charging=${bat.state}")
                }
            }
            17 -> {
                // Find phone from watch
                if (cmd.hasSystem()) {
                    val action = cmd.system.findDevice
                    Log.i(TAG, "FIND PHONE: action=$action")
                    if (action == 0) {
                        // Start ringing
                        startFindPhoneRingtone()
                    } else {
                        // Stop ringing (watch cancelled)
                        stopFindPhoneRingtone()
                    }
                } else {
                    startFindPhoneRingtone()
                }
            }
            18 -> {
                // Find device (our find watch command echo)
                Log.d(TAG, "Find device echo subtype=18")
            }
            else -> Log.d(TAG, "Unhandled system subtype=${cmd.subtype}")
        }
    }
}
