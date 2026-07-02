package com.pedometer.vm

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pedometer.auth.AuthService
import com.pedometer.bt.BleConnection
import com.pedometer.bt.ProtocolHandler
import com.pedometer.bt.SppConnection
import com.pedometer.data.DailyHealth
import com.pedometer.data.GpsPointRecord
import com.pedometer.data.SleepRecord
import com.pedometer.data.DailySteps
import com.pedometer.data.HeartRateRecord
import com.pedometer.data.HourlySteps
import com.pedometer.data.WorkoutRecord
import com.pedometer.data.StepDatabase
import com.pedometer.PedometerApp
import com.pedometer.health.ActivitySync
import com.pedometer.health.HealthService
import com.pedometer.health.DayStepData
import com.pedometer.health.PhoneStepCounter
import com.pedometer.health.HealthConnectReader
import com.pedometer.health.StepProviderReader
import com.pedometer.health.UserProfile
import com.pedometer.music.MusicService
import com.pedometer.notification.NotificationService
import com.pedometer.notification.WatchNotificationBridge
import com.pedometer.util.AlarmService
import com.pedometer.util.WatchSettings
import com.pedometer.util.CalendarService
import com.pedometer.util.WatchAlarm
import com.pedometer.util.UtilityService
import com.pedometer.watchface.DataUploadService
import com.pedometer.watchface.WatchfaceInfo
import com.pedometer.watchface.WatchfaceService
import com.pedometer.weather.WeatherProvider
import com.pedometer.weather.WeatherService
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
)

enum class ConnectionStatus {
    Disconnected, Connecting, Authenticating, Connected
}

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "WatchViewModel"
        private const val PREFS_NAME = "pedometer_prefs"
        private const val KEY_AUTH = "auth_key"
        private const val KEY_MAC = "mac_address"
    }

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    private var bleConnection: BleConnection? = null
    private var sppConnection: SppConnection? = null
    private var protocolHandler: ProtocolHandler? = null
    private var authService: AuthService? = null
    private var healthService: HealthService? = null
    private var musicService: MusicService? = null
    private var weatherService: WeatherService? = null
    private var notificationService: NotificationService? = null
    private var watchfaceService: WatchfaceService? = null
    private var activitySync: ActivitySync? = null
    private var dataUploadService: DataUploadService? = null
    private var utilityService: UtilityService? = null
    private var alarmService: AlarmService? = null
    private var calendarService: CalendarService? = null
    private var watchSettings: WatchSettings? = null
    private val phoneStepCounter = PhoneStepCounter(app)
    private val healthConnectReader = HealthConnectReader(app)
    private var userProfile = UserProfile.load(app)
    private var stepPollingJob: Job? = null
    private var weatherJob: Job? = null
    private var lastWeatherFetchTime = 0L

    init {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            authKey = prefs.getString(KEY_AUTH, "") ?: "",
            macAddress = prefs.getString(KEY_MAC, "") ?: "",
        )

        _state.value = _state.value.copy(profile = userProfile)
        // Auto-connect if MAC and key saved
        val savedMac = _state.value.macAddress
        val savedKey = _state.value.authKey
        if (savedMac.isNotBlank() && savedKey.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(2000) // wait for UI to settle
                Log.i(TAG, "Auto-connecting to $savedMac")
                connect()
            }
        }

        // Lifecycle: step polling + refresh on foreground/background
        PedometerApp.onForegroundChanged = { inForeground ->
            if (inForeground) {
                if (_state.value.connectionStatus == ConnectionStatus.Connected) {
                    healthService?.startRealtimeStats()
                }
                onAppForeground()
            } else {
                healthService?.stopRealtimeStats()
                onAppBackground()
            }
        }
        // App launches in foreground — trigger initial refresh + polling
        onAppForeground()

        // Start phone step counter (event-driven, always active)
        phoneStepCounter.start()
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

    private var lastHrSaveTime = 0L // throttle HR writes to 1 per minute

    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    // Smart step merge: watch priority + phone delta when disconnected
    private var lastKnownWatchSteps = 0
    private var phoneBaselineSteps = 0L  // phone steps at moment watch disconnected

    @SuppressLint("MissingPermission")
    fun connect() {
        val s = _state.value
        if (s.authKey.isBlank() || s.macAddress.isBlank()) return
        if (s.connectionStatus != ConnectionStatus.Disconnected) return

        // Clean up any stale state from previous failed connections
        cleanupServices()

        _state.value = s.copy(connectionStatus = ConnectionStatus.Connecting)

        // Start foreground service to keep connection alive
        val context = getApplication<Application>()
        try {
            context.startForegroundService(
                android.content.Intent(context, com.pedometer.service.WatchConnectionService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service: ${e.message}")
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val btManager = getApplication<Application>()
                    .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter ?: return@launch
                val device: BluetoothDevice = adapter.getRemoteDevice(s.macAddress)

                val auth = AuthService(s.authKey)
                authService = auth

                // Try SPP first (faster, proven to work), fall back to BLE
                val spp = SppConnection(
                    onData = { data -> protocolHandler?.onDataReceived(data) },
                    onDisconnected = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                        // Auto-reconnect
                        if (autoReconnectEnabled && reconnectAttempts < maxReconnectAttempts) {
                            reconnectAttempts++
                            val delayMs = 2000L * reconnectAttempts
                            Log.i(TAG, "Auto-reconnect attempt $reconnectAttempts/$maxReconnectAttempts in ${delayMs}ms")
                            viewModelScope.launch(Dispatchers.IO) {
                                delay(delayMs)
                                if (_state.value.connectionStatus == ConnectionStatus.Disconnected) {
                                    connect()
                                }
                            }
                        } else {
                            // Stop foreground service
                            val ctx = getApplication<Application>()
                            ctx.stopService(android.content.Intent(ctx, com.pedometer.service.WatchConnectionService::class.java))
                        }
                    }
                )
                sppConnection = spp

                val handler = ProtocolHandler(
                    authService = auth,
                    connection = { data -> spp.write(data) },
                    onAuthenticated = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Connected)
                        reconnectAttempts = 0 // reset on successful connect
                        WatchNotificationBridge.protocolHandler = protocolHandler

                        // Update notification text
                        try {
                            val ctx = getApplication<Application>()
                            val svc = android.content.Intent(ctx, com.pedometer.service.WatchConnectionService::class.java)
                            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.let { nm ->
                                val pi = android.app.PendingIntent.getActivity(ctx, 0,
                                    android.content.Intent(ctx, com.pedometer.MainActivity::class.java),
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                                val n = androidx.core.app.NotificationCompat.Builder(ctx, "pedometer_connection")
                                    .setContentTitle("Шагомер")
                                    .setContentText("Часы подключены")
                                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                                    .setContentIntent(pi)
                                    .setOngoing(true)
                                    .setSilent(true)
                                    .build()
                                nm.notify(1, n)
                            }
                        } catch (_: Exception) {}

                        // Periodic weather updates (every 2 hours)
                        weatherJob?.cancel()
                        weatherJob = viewModelScope.launch(Dispatchers.IO) {
                            delay(2 * 60 * 60 * 1000L)
                            while (isActive && _state.value.connectionStatus == ConnectionStatus.Connected) {
                                try {
                                    fetchAndSendWeather()
                                    lastWeatherFetchTime = System.currentTimeMillis()
                                } catch (_: Exception) {}
                                delay(2 * 60 * 60 * 1000L)
                            }
                        }


                        viewModelScope.launch(Dispatchers.IO) {
                            Thread.sleep(500)
                            Log.i(TAG, "POST-AUTH: initializing watch")

                            // 1. getDeviceInfo
                            protocolHandler?.sendCommand(CommandHelper.buildDeviceInfoRequest())
                            Thread.sleep(200)

                            // 2. getBattery
                            protocolHandler?.sendCommand(CommandHelper.buildBatteryRequest())
                            Thread.sleep(200)

                            // 3. setCurrentTime
                            sendCurrentTime()
                            Thread.sleep(200)

                            // 4. setUserInfo
                            sendUserInfo()
                            Thread.sleep(200)

                            // 5. setLocale
                            val localeCmd = XiaomiProto.Command.newBuilder()
                                .setType(CommandHelper.TYPE_SYSTEM)
                                .setSubtype(6)
                                .setSystem(XiaomiProto.System.newBuilder()
                                    .setLanguage(XiaomiProto.Language.newBuilder()
                                        .setCode(java.util.Locale.getDefault().toLanguageTag().replace("-", "_").lowercase())))
                                .build()
                            protocolHandler?.sendCommand(localeCmd)
                            Thread.sleep(200)

                            // 6. Health config init
                            healthService?.initialize()
                            Thread.sleep(300)
                            // Start realtime stats only if app is in foreground
                            if (PedometerApp.isInForeground) {
                                healthService?.startRealtimeStats()
                                Log.i(TAG, "Realtime stats started (foreground)")
                            } else {
                                Log.i(TAG, "Realtime stats skipped (background)")
                            }

                            // 7. Fetch alarms
                            alarmService?.getAlarms()
                            Thread.sleep(200)

                            // 8. Sync contacts
                            watchSettings?.syncContacts()
                            Thread.sleep(200)

                            // 9. Sync calendar
                            calendarService?.syncCalendar()
                            Thread.sleep(200)

                            // 9. Send canned messages for quick reply
                            notificationService?.sendCannedMessages()
                            Thread.sleep(200)

                            // 8. Send weather (activity files fetched by HealthService.initialize())
                            Thread.sleep(500)
                            try { fetchAndSendWeather() } catch (e: Exception) { Log.e(TAG, "Weather init failed", e) }
                            Log.i(TAG, "POST-AUTH: init complete")
                        }
                    },
                    onCommand = { cmd -> handleCommand(cmd) },
                )
                handler.onActivityData = { data ->
                    activitySync?.handleRawData(data)
                }
                protocolHandler = handler

                val health = HealthService(handler) { data ->
                    if (data.steps > 0) {
                        lastKnownWatchSteps = data.steps
                        phoneBaselineSteps = _state.value.phoneStepsSinceBoot
                    }
                    _state.value = _state.value.copy(
                        watchSteps = if (data.steps > 0) data.steps else _state.value.watchSteps,
                        watchCalories = if (data.calories > 0) data.calories else _state.value.watchCalories,
                        heartRate = if (data.heartRate > 0) data.heartRate else _state.value.heartRate,
                        standingHours = if (data.standingHours > 0) data.standingHours else _state.value.standingHours,
                        activeMinutes = if (data.activeMinutes > 0) data.activeMinutes else _state.value.activeMinutes,
                    )
                    // Persist heart rate to Room DB (max once per minute)
                    val now = System.currentTimeMillis()
                    if (data.heartRate > 0 && now - lastHrSaveTime >= 60_000) {
                        lastHrSaveTime = now
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
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
                    if (needed) {
                        startGpsRelay()
                    } else {
                        stopGpsRelay()
                    }
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
                            viewModelScope.launch(Dispatchers.IO) {
                                Thread.sleep(5000) // wait for watch to save files
                                protocolHandler?.sendCommand(CommandHelper.buildActivityFetchToday())
                                Thread.sleep(3000)
                                activitySync?.requestPast()
                            }
                        }
                    }
                }
                healthService = health

                val music = MusicService(getApplication(), handler)
                musicService = music

                val weather = WeatherService(handler)
                weather.onWeatherRequested = {
                    viewModelScope.launch(Dispatchers.IO) { fetchAndSendWeather() }
                }
                weatherService = weather

                val utility = UtilityService(handler) {
                    Log.i(TAG, "FIND PHONE triggered!")
                    startFindPhoneRingtone()
                }

                utilityService = utility

                val alarm = AlarmService(handler)
                alarm.onAlarmsReceived = { alarms ->
                    _state.value = _state.value.copy(alarms = alarms)
                }
                alarmService = alarm

                calendarService = CalendarService(handler, getApplication())
                watchSettings = WatchSettings(handler, getApplication())

                val sync = ActivitySync(handler,
                    onGpsTrack = { workoutStartMs, points ->
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
                                dao.insertGpsPoints(points.map { p ->
                                    GpsPointRecord(workoutStart = workoutStartMs, timestamp = p.timestamp, lat = p.lat, lon = p.lon, speed = p.speed)
                                })
                                Log.i(TAG, "Saved ${points.size} GPS points for workout $workoutStartMs")
                            } catch (e: Exception) { Log.e(TAG, "Save GPS failed", e) }
                        }
                    },
                    onHourlySteps = { date, hourlyList ->
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
                                // Watch data = source of truth, overwrite
                                for ((hour, steps) in hourlyList) {
                                    dao.upsertHourly(HourlySteps(date = date, hour = hour, steps = steps))
                                }
                                Log.i(TAG, "Saved ${hourlyList.size} hourly steps for $date from watch")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save hourly steps", e)
                            }
                        }
                    },
                    onWorkout = { w ->
                        Log.i(TAG, "Workout: ${w.sportName} ${w.durationSec/60}min")
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
                                dao.upsertWorkout(WorkoutRecord(
                                    startTime = w.startTime, endTime = w.endTime,
                                    sportType = w.sportType, sportName = w.sportName,
                                    durationSec = w.durationSec, distanceM = w.distanceM,
                                    calories = w.calories, hrAvg = w.hrAvg, hrMax = w.hrMax, hrMin = w.hrMin,
                                ))
                                val recent = dao.getRecentWorkouts(10)
                                _state.value = _state.value.copy(recentWorkouts = recent)
                            } catch (e: Exception) { Log.e(TAG, "Save workout failed", e) }
                        }
                    },
                    onSleepData = { sleep ->
                        Log.i(TAG, "Sleep: ${sleep.totalMinutes}min deep=${sleep.deepMinutes} light=${sleep.lightMinutes} REM=${sleep.remMinutes}")
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
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
                                _state.value = _state.value.copy(lastSleep = record)
                                Log.i(TAG, "Saved sleep record")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save sleep", e)
                            }
                        }
                    },
                    onDailySummary = { summary ->
                        Log.i(TAG, "Daily summary: HR avg=${summary.hrAvg} rest=${summary.hrResting} " +
                            "SpO2=${summary.spo2Avg} stress=${summary.stressAvg} cal=${summary.calories}")
                        // Update watch calories + distance + active minutes from daily summary
                        _state.value = _state.value.copy(
                            watchCalories = if (summary.calories > 0) summary.calories else _state.value.watchCalories,
                            watchDistanceM = if (summary.distanceM > 0) summary.distanceM else _state.value.watchDistanceM,
                            activeMinutes = if (summary.activeMinutes > 0) summary.activeMinutes else _state.value.activeMinutes,
                            spo2 = if (summary.spo2Avg > 0) summary.spo2Avg else _state.value.spo2,
                            stress = if (summary.stressAvg > 0) summary.stressAvg else _state.value.stress,
                            hrResting = if (summary.hrResting > 0) summary.hrResting else _state.value.hrResting,
                        )
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
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
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save daily health", e)
                            }
                        }
                    },
                    onHeartRateSamples = { samples ->
                        Log.i(TAG, "Got ${samples.size} HR samples from activity sync")
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val dao = StepDatabase.get(getApplication()).stepDao()
                                for (s in samples) {
                                    dao.insertHeartRate(HeartRateRecord(
                                        timestamp = s.timestamp,
                                        bpm = s.bpm,
                                        source = "watch_history",
                                    ))
                                }
                                Log.i(TAG, "Saved ${samples.size} HR samples to DB")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save HR samples", e)
                            }
                        }
                    },
                )
                activitySync = sync

                val upload = DataUploadService(handler)
                upload.onProgress = { progress ->
                    _state.value = _state.value.copy(uploadProgress = progress)
                }
                upload.onComplete = { success ->
                    _state.value = _state.value.copy(uploadProgress = -1)
                    Log.i(TAG, "Watchface upload ${if (success) "SUCCESS" else "FAILED"}")
                    if (success) watchfaceService?.requestWatchfaceList()
                }
                dataUploadService = upload

                val watchface = WatchfaceService(handler) { faces ->
                    _state.value = _state.value.copy(watchfaces = faces)
                }
                watchfaceService = watchface

                val notif = NotificationService(getApplication(), handler)
                notif.onCallAction = { accept ->
                    try {
                        val ctx = getApplication<Application>()
                        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
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

                if (!spp.connect(device)) {
                    Log.w(TAG, "SPP failed, trying BLE...")
                    val ble = BleConnection(
                        context = getApplication(),
                        onData = { data -> protocolHandler?.onDataReceived(data) },
                        onDisconnected = {
                            _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                        }
                    )
                    bleConnection = ble
                    // Swap writer to BLE
                    // TODO: refactor to support dynamic writer swap
                    ble.connect(device)
                    var waitMs = 0
                    while (!ble.isConnected && waitMs < 15000) {
                        Thread.sleep(200)
                        waitMs += 200
                    }
                    if (!ble.isConnected) {
                        Log.e(TAG, "Both SPP and BLE failed")
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                        return@launch
                    }
                }

                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Authenticating)
                handler.start()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
            }
        }
    }

    fun findWatch() {
        // findDevice: 0 = start ringing, 1 = stop ringing
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_SYSTEM)
            .setSubtype(18) // CMD_FIND_WATCH
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(0)) // 0 = START
            .build()
        protocolHandler?.sendCommand(cmd)
        Log.i(TAG, "Find watch triggered (start)")

        // Auto-stop after 10 seconds
        viewModelScope.launch {
            delay(10000)
            val stopCmd = XiaomiProto.Command.newBuilder()
                .setType(CommandHelper.TYPE_SYSTEM)
                .setSubtype(18)
                .setSystem(XiaomiProto.System.newBuilder().setFindDevice(1)) // 1 = STOP
                .build()
            protocolHandler?.sendCommand(stopCmd)
            Log.i(TAG, "Find watch stopped")
        }
    }

    private var findPhoneRingtone: android.media.Ringtone? = null

    private fun startFindPhoneRingtone() {
        stopFindPhoneRingtone()
        val ctx = getApplication<Application>()
        findPhoneRingtone = android.media.RingtoneManager.getRingtone(ctx,
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
        findPhoneRingtone?.play()
        _state.value = _state.value.copy(findPhoneActive = true)
        // Auto-stop after 30 seconds
        viewModelScope.launch {
            delay(30000)
            stopFindPhoneRingtone()
        }
    }

    fun stopFindPhoneRingtone() {
        findPhoneRingtone?.stop()
        findPhoneRingtone = null
        _state.value = _state.value.copy(findPhoneActive = false)
    }

    fun requestWatchfaces() { watchfaceService?.requestWatchfaceList() }
    fun setActiveWatchface(id: String) { watchfaceService?.setActiveWatchface(id) }
    fun deleteWatchface(id: String) { watchfaceService?.deleteWatchface(id) }
    fun uploadWatchface(data: ByteArray) { dataUploadService?.uploadWatchface(data) }
    fun onAppForeground() {
        Log.i(TAG, "App foreground — starting step polling + full refresh")
        refreshData()
        startStepPolling()
    }

    fun onAppBackground() {
        Log.i(TAG, "App background — stopping step polling")
        stopStepPolling()
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
                delay(10_000)
            }
        }
    }

    private fun stopStepPolling() {
        stepPollingJob?.cancel()
        stepPollingJob = null
    }

    fun refreshData() {
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

                // 4. Watch data (only if connected)
                if (_state.value.connectionStatus == ConnectionStatus.Connected) {
                    protocolHandler?.sendCommand(CommandHelper.buildBatteryRequest())
                    protocolHandler?.sendCommand(CommandHelper.buildActivityFetchToday())
                    delay(1000)
                    activitySync?.requestPast()
                    delay(500)

                    // 5. Weather (skip if fetched less than 30 min ago)
                    val now = System.currentTimeMillis()
                    if (now - lastWeatherFetchTime > 30 * 60 * 1000L) {
                        fetchAndSendWeather()
                        lastWeatherFetchTime = now
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun startBreathing() { utilityService?.sendBreathingVibration() }
    fun getAlarms() { alarmService?.getAlarms() }
    fun createAlarm(hour: Int, minute: Int, repeatMode: Int = 0, repeatFlags: Int = 0) {
        alarmService?.createAlarm(hour, minute, repeatMode, repeatFlags)
    }
    fun editAlarm(alarm: WatchAlarm) { alarmService?.editAlarm(alarm) }
    fun deleteAlarm(alarmId: Int) { alarmService?.deleteAlarm(alarmId) }
    fun syncContacts() { watchSettings?.syncContacts() }
    fun setDnd(enabled: Boolean) { watchSettings?.setDnd(enabled) }
    fun setWearingMode(mode: Int) { watchSettings?.setWearingMode(mode) }
    fun createReminder(title: String, y: Int, m: Int, d: Int, h: Int, min: Int) { watchSettings?.createReminder(title, y, m, d, h, min) }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGpsRelay() {
        Log.i(TAG, "Starting GPS relay for workout")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = com.google.android.gms.location.LocationServices
                    .getFusedLocationProviderClient(getApplication<Application>())
                val request = com.google.android.gms.location.LocationRequest.Builder(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000
                ).build()
                val callback = object : com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                        val loc = result.lastLocation ?: return
                        healthService?.sendGpsLocation(
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

    private fun cleanupServices() {
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
        watchSettings = null
        protocolHandler = null
        authService = null
        sppConnection?.disconnect()
        sppConnection = null
        bleConnection?.disconnect()
        bleConnection = null
    }

    fun disconnect() {
        autoReconnectEnabled = false // don't reconnect on manual disconnect
        stopGpsRelay()
        weatherJob?.cancel()
        weatherJob = null
        WatchNotificationBridge.protocolHandler = null
        cleanupServices()
        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
        val ctx = getApplication<Application>()
        ctx.stopService(android.content.Intent(ctx, com.pedometer.service.WatchConnectionService::class.java))
        autoReconnectEnabled = true // re-enable for next connect
        reconnectAttempts = 0
    }

    private fun fetchAndSendWeather() {
        try {
            val app = getApplication<Application>()
            val cityOverride = userProfile.weatherCity
            val coords = if (cityOverride.isNotBlank()) {
                WeatherProvider.geocodeCity(app, cityOverride)
            } else {
                WeatherProvider.getLocation(app)
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
        protocolHandler?.sendCommand(cmd)
    }

    private fun sendUserInfo() {
        val p = userProfile
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
        protocolHandler?.sendCommand(cmd)
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
            AlarmService.COMMAND_TYPE -> alarmService?.handleCommand(cmd)
            else -> Log.d(TAG, "Unhandled command type=${cmd.type}")
        }
    }

    private fun handleSystemCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.SYS_DEVICE_INFO -> {
                if (cmd.hasSystem() && cmd.system.hasDeviceInfo()) {
                    val info = cmd.system.deviceInfo
                    _state.value = _state.value.copy(
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
                    _state.value = _state.value.copy(
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

    override fun onCleared() {
        stopGpsRelay()
        stopStepPolling()
        phoneStepCounter.stop()
        PedometerApp.onForegroundChanged = null
        disconnect()
    }
}
