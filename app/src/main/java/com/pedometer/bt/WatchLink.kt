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
 *
 * Concurrency: every connect attempt gets a monotonically increasing [attemptId]. All
 * state transitions (status changes, attempt invalidation) happen under [stateLock].
 * A late callback or a zombie thread from an old attempt carries its own epoch and is
 * ignored by the guards once a newer attempt (or disconnect()) has bumped attemptId.
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

    private val stateLock = Any()

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

    @Volatile
    var protocolHandler: ProtocolHandler? = null
        private set

    @Volatile private var spp: SppConnection? = null

    /** Current attempt epoch; bumped to invalidate in-flight/zombie attempts. */
    @Volatile private var attemptId = 0

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
        // Capture the epoch and flip to Connecting atomically. Bumping attemptId here
        // also invalidates any previous attempt still blocked inside SppConnection.connect().
        val myAttempt = synchronized(stateLock) {
            if (_status.value != ConnectionStatus.Disconnected) {
                Log.i(TAG, "connect() ignored — already ${_status.value}")
                return
            }
            _status.value = ConnectionStatus.Connecting
            ++attemptId
        }
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            // Per-attempt handler slot: this connection's read thread only ever feeds
            // THIS attempt's handler, never whatever handler a newer attempt installed.
            var handler: ProtocolHandler? = null
            val connection = SppConnection(
                onData = { data -> handler?.onDataReceived(data) },
                onDisconnected = { handleDrop(myAttempt) },
            )
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null) {
                    Log.e(TAG, "No Bluetooth adapter")
                    fail(myAttempt, "No Bluetooth adapter")
                    return@launch
                }
                val device = adapter.getRemoteDevice(mac)
                val auth = AuthService(authKey)

                // Blocking: up to ~24s. If the user disconnected or a newer attempt started
                // meanwhile, tear down what we just made and bail without touching shared state.
                val ok = connection.connect(device)
                var stale: Boolean
                synchronized(stateLock) {
                    stale = attemptId != myAttempt
                    if (!stale && ok) _status.value = ConnectionStatus.Authenticating
                }
                if (stale) {
                    Log.i(TAG, "Attempt $myAttempt stale after connect() — discarding")
                    try { connection.disconnect() } catch (_: Exception) {}
                    synchronized(stateLock) {
                        if (spp === connection) spp = null
                        if (protocolHandler === handler) protocolHandler = null
                    }
                    return@launch
                }
                if (!ok) {
                    // Close the local connection first (kills its server thread) so a late
                    // accept cannot come back to life after we've reported failure.
                    try { connection.disconnect() } catch (_: Exception) {}
                    Log.w(TAG, "SPP failed for $mac")
                    fail(myAttempt, "SPP connect failed")
                    return@launch
                }

                spp = connection
                val built = ProtocolHandler(
                    authService = auth,
                    connection = { data -> connection.write(data) },
                    onAuthenticated = {
                        // Re-validate the epoch under the lock so a watchdog abort that lands
                        // between the old status check and this write cannot be overwritten:
                        // either we win (Connected) or the abort already bumped attemptId and
                        // we silently do nothing.
                        synchronized(stateLock) {
                            if (attemptId != myAttempt) return@synchronized
                            policy.onConnected()
                            _status.value = ConnectionStatus.Connected
                        }
                        onAuthenticated?.invoke()
                    },
                    onCommand = { cmd -> onCommand?.invoke(cmd) },
                )
                built.onActivityData = { data -> onActivityData?.invoke(data) }
                handler = built
                protocolHandler = built

                built.start()
                startWatchdog(myAttempt)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                if (attemptId == myAttempt) {
                    try { connection.disconnect() } catch (_: Exception) {}
                    fail(myAttempt, "Connect failed")
                }
            }
        }
    }

    /**
     * If auth has not completed in 30s, treat the attempt as failed. Aborting bumps the
     * epoch, so an in-flight auth callback landing afterwards is rejected by its guard.
     */
    private fun startWatchdog(myAttempt: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            delay(30_000)
            if (abortAttempt(myAttempt, "Auth watchdog fired — giving up on this attempt")) {
                closeSockets()
                onDisconnected?.invoke()
                scheduleReconnect()
            }
        }
    }

    /** Returns true if this attempt still owns the connection and the drop was newly observed. */
    private fun markDisconnected(myAttempt: Int, reason: String): Boolean = synchronized(stateLock) {
        if (attemptId != myAttempt) return false
        if (_status.value == ConnectionStatus.Disconnected) return false
        Log.i(TAG, reason)
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Disconnected
        true
    }

    /**
     * Watchdog expiry: like [markDisconnected], but also invalidates the attempt itself
     * (attemptId++) so a late auth-success callback cannot resurrect it as Connected.
     */
    private fun abortAttempt(myAttempt: Int, reason: String): Boolean = synchronized(stateLock) {
        if (attemptId != myAttempt) return false
        if (_status.value != ConnectionStatus.Connecting && _status.value != ConnectionStatus.Authenticating) return false
        Log.w(TAG, reason)
        attemptId++
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Disconnected
        true
    }

    // NOTE on ordering: the status flips to Disconnected under stateLock BEFORE the sockets are
    // closed outside the lock. Closing a socket can make SppConnection fire onDisconnected,
    // which re-enters handleDrop(); the epoch + status guard in markDisconnected stops that
    // from consuming a second reconnect slot or invoking onDisconnected twice. SppConnection.
    // disconnect() sets running=false BEFORE closing the socket, so its readLoop's finally
    // block sees wasRunning=false and does NOT fire the callback. Any callback that does slip
    // through carries its own epoch and is rejected once attemptId has moved on — this is what
    // makes a zombie connectJob (blocked in connect() with no suspension points) harmless.

    private fun fail(myAttempt: Int, reason: String) {
        if (!markDisconnected(myAttempt, reason)) return
        closeSockets()
        onDisconnected?.invoke()
        scheduleReconnect()
    }

    private fun handleDrop(myAttempt: Int) {
        if (!markDisconnected(myAttempt, "Transport dropped")) return
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
        // Invalidate any in-flight attempt first: a zombie still blocked in
        // SppConnection.connect() will hit the epoch guard when it resumes and do nothing.
        synchronized(stateLock) { attemptId++ }
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
