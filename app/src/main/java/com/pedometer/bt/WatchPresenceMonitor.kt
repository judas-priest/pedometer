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
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cheap wear-presence proxy: while started, runs a ~3s low-power BLE scan for the watch's
 * MAC and reports whether the watch is around. This is the same pattern
 * Gadgetbridge calls "Reconnect by BLE scan" — BT Classic watches never re-initiate the
 * connection themselves, so the phone must poll before dialing.
 *
 * "Present" means "reachable over the radio", not "worn" — a watch on the nightstand
 * still counts. The watch does not expose wearing state when disconnected.
 *
 * Reports every completed scan window (dedup happens upstream in the repository);
 * the interval backs off the longer the watch stays absent, and pauses entirely
 * during quiet hours.
 */
class WatchPresenceMonitor(
    context: Context,
    private val mac: String,
    private val scope: CoroutineScope,
    private val quietHours: () -> QuietHours = { QuietHours.DISABLED },
    private val onPresenceChanged: (present: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "WatchPresenceMonitor"
        private const val SCAN_WINDOW_MS = 3_000L
        private const val BASE_INTERVAL_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val scanner get() =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner

    private var loopJob: Job? = null
    @Volatile private var callback: ScanCallback? = null
    private val intervalPolicy = ScanIntervalPolicy()

    fun start() {
        if (loopJob != null) return
        if (!hasPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted — presence monitor idle")
            return
        }
        loopJob = scope.launch {
            while (true) {
                val quiet = quietHours().isQuiet(LocalTime.now().hour)
                if (quiet) {
                    // Night window: no scan at all. The repo tore the link down on its own;
                    // presence state simply freezes until morning.
                    delay(BASE_INTERVAL_MS)
                    continue
                }
                val found = scanOnce()
                if (found != null) {
                    intervalPolicy.onScanResult(found)
                    onPresenceChanged(found)
                }
                delay((intervalPolicy.nextIntervalMs() - SCAN_WINDOW_MS).coerceAtLeast(BASE_INTERVAL_MS / 2))
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

    /** @return true if seen, false if the window passed without a match, null if unknown (scan unavailable or failed). */
    @SuppressLint("MissingPermission")
    private suspend fun scanOnce(): Boolean? {
        val ble = scanner ?: return null
        val found = CompletableDeferred<Boolean?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(true)
            }
            override fun onScanFailed(errorCode: Int) {
                // A failed scan is UNKNOWN, not absence — report null so the loop skips it
                // instead of tearing down a connect attempt on an OEM scan hiccup.
                Log.w(TAG, "Scan failed: $errorCode")
                found.complete(null)
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        return try {
            callback = cb
            ble.startScan(listOf(filter), settings, cb)
            withTimeoutOrNull(SCAN_WINDOW_MS) { found.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scan error: ${e.message}")
            null
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
