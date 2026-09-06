package com.pedometer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedometer.MainActivity
import com.pedometer.PedometerApp
import com.pedometer.bt.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive so WatchRepository can hold the watch connection with no Activity
 * on screen, and mirrors the connection status into the ongoing notification.
 */
class WatchConnectionService : Service() {
    companion object {
        private const val TAG = "WatchConnectionService"
        private const val CHANNEL_ID = "pedometer_connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.pedometer.action.DISCONNECT"
    }

    inner class LocalBinder : Binder() {
        val service: WatchConnectionService get() = this@WatchConnectionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo get() = PedometerApp.repository

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch {
            repo.data.collect { data ->
                updateNotification(statusText(data.connectionStatus))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(statusText(repo.data.value.connectionStatus)),
                foregroundTypes(),
            )
        } catch (e: SecurityException) {
            // Defense in depth: foregroundTypes() should never produce an unsafe mask, but if it
            // does (or the OS adds a new check), degrade to the always-permitted CONNECTED_DEVICE.
            Log.e(TAG, "startForeground rejected full mask, retrying with CONNECTED_DEVICE", e)
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(statusText(repo.data.value.connectionStatus)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } catch (retry: SecurityException) {
                Log.e(TAG, "startForeground rejected even CONNECTED_DEVICE", retry)
                throw retry
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "startForeground rejected full mask, retrying with CONNECTED_DEVICE", e)
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(statusText(repo.data.value.connectionStatus)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } catch (retry: IllegalArgumentException) {
                Log.e(TAG, "startForeground rejected even CONNECTED_DEVICE", retry)
                throw retry
            }
        }

        if (intent?.action == ACTION_DISCONNECT) {
            // Disconnect the watch but KEEP the service alive: it also hosts StepCollector
            // (a later task), and the app is a pedometer with or without a watch.
            Log.i(TAG, "Manual disconnect")
            repo.disconnect()
            return START_STICKY
        }

        if (repo.hasCredentials) {
            Log.i(TAG, "Starting watch connection")
            repo.connect()
        } else {
            Log.w(TAG, "No credentials saved — service idles")
        }
        return START_STICKY
    }

    /**
     * Android 14+ throws SecurityException from startForeground() if a declared type's backing
     * runtime permission is not granted. The user can deny any of them in Settings, so the mask
     * is built from what is actually granted.
     *
     * LOCATION additionally requires ACCESS_BACKGROUND_LOCATION when the service is started
     * FROM THE BACKGROUND (e.g. the BootReceiver path after reboot). A user who granted location
     * "while in use" only would otherwise hit a SecurityException in onStartCommand on every
     * reboot, so the LOCATION bit is only set when background location is granted too. This is
     * behaviorally harmless: the GPS relay for workouts only starts from the foreground Activity.
     *
     * HEALTH needs one of ACTIVITY_RECOGNITION / BODY_SENSORS / HIGH_SAMPLING_RATE_SENSORS.
     * CONNECTED_DEVICE has no permission requirement and is always safe.
     */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun statusText(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.Connected -> "Часы подключены"
        ConnectionStatus.Connecting -> "Подключение..."
        ConnectionStatus.Authenticating -> "Авторизация..."
        ConnectionStatus.Disconnected -> "Часы отключены"
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Шагомер")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Watch Connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
