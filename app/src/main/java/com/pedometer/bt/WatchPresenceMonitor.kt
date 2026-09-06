package com.pedometer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cheap wear-presence proxy: while started, runs a ~3s low-power BLE scan for the watch's
 * MAC once a minute and reports whether the watch is around. This is the same pattern
 * Gadgetbridge calls "Reconnect by BLE scan" — BT Classic watches never re-initiate the
 * connection themselves, so the phone must poll before dialing.
 *
 * "Present" means "reachable over the radio", not "worn" — a watch on the nightstand
 * still counts. The watch does not expose wearing state when disconnected.
 *
 * Reports nothing until the first scan window completes.
 */
class WatchPresenceMonitor(
    context: Context,
    private val mac: String,
    private val scope: CoroutineScope,
    private val onPresenceChanged: (present: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "WatchPresenceMonitor"
        private const val SCAN_WINDOW_MS = 3_000L
        private const val SCAN_INTERVAL_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val scanner get() =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner

    private var loopJob: Job? = null
    private var callback: ScanCallback? = null

    @Volatile private var lastPresent = false

    fun start() {
        if (loopJob != null) return
        if (!hasPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted — presence monitor idle")
            return
        }
        loopJob = scope.launch {
            while (true) {
                val found = scanOnce()
                if (found != null && found != lastPresent) {
                    Log.i(TAG, "Watch present: $found")
                    lastPresent = found
                    onPresenceChanged(found)
                }
                delay(SCAN_INTERVAL_MS - SCAN_WINDOW_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        stopScan()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    /** @return true if seen, false if the window passed without a match, null if scanning unavailable. */
    @SuppressLint("MissingPermission")
    private suspend fun scanOnce(): Boolean? {
        val ble = scanner ?: return null
        val found = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(true)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                found.complete(false)
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        return try {
            callback = cb
            ble.startScan(listOf(filter), settings, cb)
            val r = kotlinx.coroutines.withTimeoutOrNull(SCAN_WINDOW_MS) { found.await() }
            r ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Scan error: ${e.message}")
            false
        } finally {
            stopScan()
            callback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = callback ?: return
        try { scanner?.stopScan(cb) } catch (_: Exception) {}
    }
}
