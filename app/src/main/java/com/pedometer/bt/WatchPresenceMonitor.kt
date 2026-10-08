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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
 * during quiet hours. When the home-Wi-Fi gate is active the loop sleeps;
 * [scanNow] resumes it the moment Wi-Fi drops.
 */
class WatchPresenceMonitor(
    context: Context,
    private val mac: String,
    private val scope: CoroutineScope,
    quietHours: () -> QuietHours = { QuietHours.DISABLED },
    onQuietChanged: () -> Unit = {},
    onPresenceChanged: (present: Boolean) -> Unit,
    connected: () -> Boolean = { false },
    private val scanSuppressed: () -> Boolean = { false },
) {
    companion object {
        private const val TAG = "WatchPresenceMonitor"
        private const val SCAN_WINDOW_MS = 3_000L
    }

    private val appContext = context.applicationContext
    private val scanner get() =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner

    @Volatile private var callback: ScanCallback? = null

    private val loop = PresenceLoop(
        scope = scope,
        quietHours = quietHours,
        onQuietChanged = onQuietChanged,
        onPresenceChanged = onPresenceChanged,
        connected = connected,
        scanSuppressed = scanSuppressed,
        scanOnce = { scanOnce() },
    )

    fun start() {
        if (!hasPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted — presence monitor idle")
            return
        }
        loop.start()
    }

    fun stop() {
        loop.stop()
        stopScan()
    }

    /** Wi-Fi gate just opened (onLost) — scan at once instead of waiting out the tick. */
    fun scanNow() = loop.scanNow()

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
