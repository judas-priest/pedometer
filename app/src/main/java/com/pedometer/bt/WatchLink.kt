package com.pedometer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.pedometer.auth.AuthService
import com.pedometer.proto.XiaomiProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Bluetooth transport to the watch: socket, auth handshake, protocol handler,
 * and the reconnect loop. Knows nothing about health data, UI or Room.
 *
 * Lifetime is the process, not the Activity.
 */
@SuppressLint("MissingPermission")
class WatchLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    companion object {
        private const val TAG = "WatchLink"
    }

    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    /** Fired after a successful auth handshake, on the link's scope. */
    var onAuthenticated: (() -> Unit)? = null

    /** Every decoded protobuf command from the watch. */
    var onCommand: ((XiaomiProto.Command) -> Unit)? = null

    /** Raw activity-file bytes (Channel.Activity). */
    var onActivityData: ((ByteArray) -> Unit)? = null

    /** Fired when the transport drops, before any reconnect attempt. */
    var onDisconnected: (() -> Unit)? = null

    var protocolHandler: ProtocolHandler? = null
        private set

    private var spp: SppConnection? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    private var mac: String = ""
    private var authKey: String = ""

    val isConnected: Boolean get() = _status.value == ConnectionStatus.Connected

    fun connect(macAddress: String, key: String) {
        if (macAddress.isBlank() || key.isBlank()) {
            Log.w(TAG, "connect() ignored — mac or key missing")
            return
        }
        mac = macAddress
        authKey = key
        policy.resumeRetries()
        startConnectAttempt()
    }

    private fun startConnectAttempt() {
        if (_status.value != ConnectionStatus.Disconnected) {
            Log.i(TAG, "connect() ignored — already ${_status.value}")
            return
        }
        _status.value = ConnectionStatus.Connecting
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null) {
                    Log.e(TAG, "No Bluetooth adapter")
                    fail()
                    return@launch
                }
                val device = adapter.getRemoteDevice(mac)
                val auth = AuthService(authKey)

                val connection = SppConnection(
                    onData = { data -> protocolHandler?.onDataReceived(data) },
                    onDisconnected = { handleDrop() },
                )
                spp = connection

                val handler = ProtocolHandler(
                    authService = auth,
                    connection = { data -> connection.write(data) },
                    onAuthenticated = {
                        policy.onConnected()
                        _status.value = ConnectionStatus.Connected
                        onAuthenticated?.invoke()
                    },
                    onCommand = { cmd -> onCommand?.invoke(cmd) },
                )
                handler.onActivityData = { data -> onActivityData?.invoke(data) }
                protocolHandler = handler

                if (!connection.connect(device)) {
                    Log.w(TAG, "SPP failed for $mac")
                    fail()
                    return@launch
                }

                _status.value = ConnectionStatus.Authenticating
                handler.start()
                startWatchdog()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                fail()
            }
        }
    }

    /**
     * The old code could sit in Connecting/Authenticating forever, and because connect()
     * refuses to run unless the status is Disconnected, the Connect button became a no-op.
     * If auth has not completed in 30s, treat it as a failure.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            delay(30_000)
            if (_status.value == ConnectionStatus.Authenticating ||
                _status.value == ConnectionStatus.Connecting
            ) {
                Log.w(TAG, "Auth watchdog fired — giving up on this attempt")
                fail()
            }
        }
    }

    // NOTE on ordering in fail()/handleDrop()/disconnect(): the status is set to Disconnected
    // BEFORE the sockets are closed. Closing a socket makes SppConnection fire onDisconnected,
    // which re-enters handleDrop(); the early return below is what stops that from consuming a
    // second reconnect slot and invoking onDisconnected twice. SppConnection.disconnect() sets
    // running=false BEFORE closing the socket, so its readLoop's finally-block sees
    // wasRunning=false and does NOT fire the callback — late callbacks cannot hit a new attempt.

    private fun fail() {
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Disconnected
        closeSockets()
        onDisconnected?.invoke()
        scheduleReconnect()
    }

    private fun handleDrop() {
        if (_status.value == ConnectionStatus.Disconnected) return
        Log.i(TAG, "Transport dropped")
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Disconnected
        onDisconnected?.invoke()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delayMs = policy.nextDelayMs()
        if (delayMs == null) {
            Log.w(TAG, "Reconnect exhausted after ${policy.attempts} attempts")
            return
        }
        Log.i(TAG, "Reconnect attempt ${policy.attempts} in ${delayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (_status.value == ConnectionStatus.Disconnected) startConnectAttempt()
        }
    }

    /** Bluetooth came back on, or the user pulled to refresh — retry immediately. */
    fun retryNow() {
        reconnectJob?.cancel()
        policy.resumeRetries()
        if (_status.value == ConnectionStatus.Disconnected) startConnectAttempt()
    }

    fun send(cmd: XiaomiProto.Command) {
        val handler = protocolHandler
        if (handler == null || !isConnected) {
            Log.w(TAG, "send() dropped — not connected")
            return
        }
        handler.sendCommand(cmd)
    }

    /** User-initiated disconnect: no reconnect until connect() or retryNow() is called. */
    fun disconnect() {
        policy.suspendRetries()
        watchdogJob?.cancel(); watchdogJob = null
        reconnectJob?.cancel(); reconnectJob = null
        connectJob?.cancel(); connectJob = null
        val wasLive = _status.value != ConnectionStatus.Disconnected
        _status.value = ConnectionStatus.Disconnected
        closeSockets()
        if (wasLive) onDisconnected?.invoke()
    }

    private fun closeSockets() {
        try { spp?.disconnect() } catch (_: Exception) {}
        spp = null
        protocolHandler = null
    }
}
