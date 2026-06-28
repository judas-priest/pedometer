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
import com.pedometer.bt.ProtocolHandler
import com.pedometer.bt.SppConnection
import com.pedometer.health.HealthService
import com.pedometer.music.MusicService
import com.pedometer.notification.NotificationService
import com.pedometer.weather.WeatherService
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var connection: SppConnection? = null
    private var protocolHandler: ProtocolHandler? = null
    private var authService: AuthService? = null
    private var healthService: HealthService? = null
    private var musicService: MusicService? = null
    private var weatherService: WeatherService? = null
    private var notificationService: NotificationService? = null

    init {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            authKey = prefs.getString(KEY_AUTH, "") ?: "",
            macAddress = prefs.getString(KEY_MAC, "") ?: "",
        )
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

                val conn = SppConnection(
                    onData = { data -> protocolHandler?.onDataReceived(data) },
                    onDisconnected = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                    }
                )
                connection = conn

                val handler = ProtocolHandler(
                    authService = auth,
                    connection = conn,
                    onAuthenticated = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Connected)
                        requestDeviceInfo()
                        healthService?.startRealtimeStats()
                        musicService?.sendCurrentMusicInfo()
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

                if (!conn.connect(device)) {
                    _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                    return@launch
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
        connection?.disconnect()
        connection = null
        protocolHandler = null
        authService = null
        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
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
        disconnect()
    }
}
