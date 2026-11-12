package com.pedometer.bt

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cheap wear-presence proxy: while started, runs a ~3s BLE scan for the watch's
 * MAC and reports whether the watch is around. This is the same pattern
 * Gadgetbridge calls "Reconnect by BLE scan" — BT Classic watches never re-initiate the
 * connection themselves, so the phone must poll before dialing.
 *
 * Delivery mode: PendingIntent scan, NOT callback scan. On ColorOS (verified 2026-11-04)
 * callback scan results are silently withheld from backgrounded processes even with the
 * FGS alive, battery allowlist and EXEMPTED bucket; PendingIntent delivery is the
 * Google-recommended background path and survives process throttling.
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
        private const val ACTION_SCAN_DELIVERY = "com.pedometer.BLE_SCAN_DELIVERY"
        private const val REQUEST_CODE = 42001
    }

    private val appContext = context.applicationContext
    private val scanner get() =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner

    /** Completed by the delivery receiver: true = watch seen, null = window expired or error. */
    @Volatile private var pendingFound: CompletableDeferred<Boolean?>? = null

    /**
     * RECEIVER_EXPORTED is required because the sender is the Bluetooth stack
     * (com.android.bluetooth, own UID), so RECEIVER_NOT_EXPORTED would silently block
     * delivery. The intent is package-scoped; the only exposure is a spoofed "present"
     * from a hostile local app → worst case a spurious connect attempt. Acceptable for
     * a personal sideloaded app.
     */
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getPackage() != null && intent.getPackage() != appContext.packageName) return
            val error = intent?.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) ?: 0
            if (error != 0) {
                Log.w(TAG, "PendingIntent scan error: $error")
                pendingFound?.complete(null)
                return
            }
            val results =
                intent?.getParcelableArrayListExtra<ScanResult>(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
            if (!results.isNullOrEmpty()) {
                pendingFound?.complete(true)
            }
        }
    }

    /** One PendingIntent reused across scan windows (FLAG_UPDATE_CURRENT keeps it fresh). */
    @Volatile private var scanPendingIntent: PendingIntent? = null

    @Volatile private var started = false

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
        if (!started) {
            ContextCompat.registerReceiver(
                appContext, scanReceiver, IntentFilter(ACTION_SCAN_DELIVERY),
                ContextCompat.RECEIVER_EXPORTED,
            )
            started = true
        }
        loop.start()
    }

    fun stop() {
        loop.stop()
        stopScan(scanPendingIntent)
        if (started) {
            try { appContext.unregisterReceiver(scanReceiver) } catch (_: IllegalArgumentException) {}
            started = false
        }
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
        val pi = scanPendingIntent ?: PendingIntent.getBroadcast(
            appContext, REQUEST_CODE, deliveryIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).also { scanPendingIntent = it }
        val found = CompletableDeferred<Boolean?>()
        pendingFound = found
        return try {
            ble.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(mac).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED) // LP duty-cycle может пропустить 3с окно
                    .build(),
                pi,
            )
            withTimeoutOrNull(SCAN_WINDOW_MS) { found.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scan error: ${e.message}")
            null
        } finally {
            stopScan(pi)
            pendingFound = null
        }
    }

    private fun deliveryIntent(): Intent = Intent(ACTION_SCAN_DELIVERY).setPackage(appContext.packageName)

    @SuppressLint("MissingPermission")
    private fun stopScan(pi: PendingIntent?) {
        try { scanner?.stopScan(pi) } catch (_: Exception) {}
    }
}
