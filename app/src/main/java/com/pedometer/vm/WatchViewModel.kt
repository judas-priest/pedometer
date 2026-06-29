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
import com.pedometer.data.DailySteps
import com.pedometer.data.HourlySteps
import com.pedometer.data.StepDatabase
import com.pedometer.health.HealthService
import com.pedometer.health.DayStepData
import com.pedometer.health.PhoneStepCounter
import com.pedometer.health.HealthConnectReader
import com.pedometer.health.StepProviderReader
import com.pedometer.health.UserProfile
import com.pedometer.music.MusicService
import com.pedometer.notification.NotificationService
import com.pedometer.weather.WeatherService
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import kotlinx.coroutines.Dispatchers
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
    val steps: Int = 0,
    val calories: Int = 0,
    val heartRate: Int = 0,
    val standingHours: Int = 0,
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
    private val phoneStepCounter = PhoneStepCounter(app)
    private val healthConnectReader = HealthConnectReader(app)
    private var userProfile = UserProfile.load(app)

    init {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            authKey = prefs.getString(KEY_AUTH, "") ?: "",
            macAddress = prefs.getString(KEY_MAC, "") ?: "",
        )

        _state.value = _state.value.copy(profile = userProfile)

        // Periodically refresh step data from StepProvider (every 10s while active)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val today = StepProviderReader.readToday(app)
                    val history = StepProviderReader.readHistory(app, 30)
                    if (today != null) {
                        _state.value = _state.value.copy(
                            todayWalkSteps = today.walkSteps,
                            todayRunSteps = today.runSteps,
                            todayWalkMinutes = today.walkMinutes,
                            stepHistory = history,
                        )
                        // Persist daily data to Room
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
                } catch (e: Exception) {
                    Log.e(TAG, "StepProvider failed", e)
                }
                delay(10_000)
            }
        }

        // Periodically refresh hourly step data from Room DB (every 10s)
        viewModelScope.launch(Dispatchers.IO) {
            val dao = StepDatabase.get(app).stepDao()
            while (isActive) {
                try {
                    val today = java.time.LocalDate.now().toString()
                    val hourly = dao.getHourlyForDay(today)
                    _state.value = _state.value.copy(todayHourlySteps = hourly)
                } catch (e: Exception) {
                    Log.e(TAG, "Hourly steps read failed", e)
                }
                delay(10_000)
            }
        }

        // Read Health Connect data
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (healthConnectReader.isAvailable()) {
                    val hcSteps = healthConnectReader.readTodaySteps()
                    val hcHR = healthConnectReader.readLatestHeartRate()
                    _state.value = _state.value.copy(
                        healthConnectSteps = hcSteps,
                        healthConnectHR = hcHR,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect failed", e)
            }
        }

        // Start phone step counter
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

        // Start/stop background service
        val context = getApplication<Application>()
        val intent = android.content.Intent(context, com.pedometer.service.StepCounterService::class.java)
        if (profile.backgroundServiceEnabled) {
            context.startForegroundService(intent)
        } else {
            context.stopService(intent)
        }
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

    @SuppressLint("MissingPermission")
    fun connect() {
        val s = _state.value
        if (s.authKey.isBlank() || s.macAddress.isBlank()) return
        if (s.connectionStatus != ConnectionStatus.Disconnected) return

        _state.value = s.copy(connectionStatus = ConnectionStatus.Connecting)

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
                    }
                )
                sppConnection = spp

                val handler = ProtocolHandler(
                    authService = auth,
                    connection = { data -> spp.write(data) },
                    onAuthenticated = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Connected)
                        viewModelScope.launch(Dispatchers.IO) {
                            Thread.sleep(500)
                            // Send time sync first (Gadgetbridge does this)
                            sendCurrentTime()
                            Thread.sleep(300)
                            requestDeviceInfo()
                            Thread.sleep(300)
                            healthService?.startRealtimeStats()
                        }
                    },
                    onCommand = { cmd -> handleCommand(cmd) },
                )
                protocolHandler = handler

                val health = HealthService(handler) { data ->
                    _state.value = _state.value.copy(
                        steps = data.steps,
                        calories = data.calories,
                        heartRate = data.heartRate,
                        standingHours = data.standingHours,
                    )
                }
                healthService = health

                val music = MusicService(getApplication(), handler)
                musicService = music

                val weather = WeatherService(handler)
                weatherService = weather

                val notif = NotificationService(handler)
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

    fun disconnect() {
        healthService?.stopRealtimeStats()
        healthService = null
        musicService = null
        weatherService = null
        notificationService = null
        sppConnection?.disconnect()
        sppConnection = null
        bleConnection?.disconnect()
        bleConnection = null
        protocolHandler = null
        authService = null
        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
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

    private fun requestDeviceInfo() {
        protocolHandler?.sendCommand(CommandHelper.buildDeviceInfoRequest())
        protocolHandler?.sendCommand(CommandHelper.buildBatteryRequest())
    }

    private fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.type) {
            CommandHelper.TYPE_SYSTEM -> handleSystemCommand(cmd)
            CommandHelper.TYPE_HEALTH -> healthService?.handleCommand(cmd)
            MusicService.COMMAND_TYPE -> musicService?.handleCommand(cmd)
            WeatherService.COMMAND_TYPE -> weatherService?.handleCommand(cmd)
            NotificationService.COMMAND_TYPE -> notificationService?.handleCommand(cmd)
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
            else -> Log.d(TAG, "Unhandled system subtype=${cmd.subtype}")
        }
    }

    override fun onCleared() {
        phoneStepCounter.stop()
        disconnect()
    }
}
