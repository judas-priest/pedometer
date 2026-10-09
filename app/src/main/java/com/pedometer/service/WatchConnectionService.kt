package com.pedometer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedometer.MainActivity
import com.pedometer.PedometerApp
import com.pedometer.bt.ConnectionStatus
import com.pedometer.bt.WatchPresenceMonitor
import com.pedometer.health.StepCollector
import com.pedometer.health.SupplementScheduler
import com.pedometer.health.UserProfile
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
        private const val DISCONNECTED_TEXT = "Часы отключены"
        const val ACTION_DISCONNECT = "com.pedometer.action.DISCONNECT"
    }

    inner class LocalBinder : Binder() {
        val service: WatchConnectionService get() = this@WatchConnectionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo get() = PedometerApp.repository

    // Phone detector only counts while the watch is NOT syncing — see the
    // double-counting note on StepCollector.collectEnabled.
    private val stepCollector by lazy {
        StepCollector(
            this,
            collectEnabled = {
                PedometerApp.repository.data.value.connectionStatus != ConnectionStatus.Connected
            },
        )
    }

    private var presenceMonitor: WatchPresenceMonitor? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED, // системные broadc'ы доставляются и так
        )
        repo.onWorkoutVibrate = { vibrateTriole() }
        scope.launch {
            repo.data.collect { data -> renderNotification(data) }
        }
        stepCollector.start()
        SupplementScheduler.start(this, scope)
        // Poll for the watch over BLE and drive connect/disconnect from its presence:
        // no watch in range → no reconnect attempts, no wasted battery.
        repo.configuredMac?.let { mac ->
            presenceMonitor = WatchPresenceMonitor(
                this, mac, repo.scope,
                quietHours = repo::currentQuietHours,
                onQuietChanged = repo::onQuietHoursChanged,
                onPresenceChanged = repo::onWatchPresence,
                connected = { repo.data.value.connectionStatus == com.pedometer.vm.ConnectionStatus.Connected },
                scanSuppressed = { repo.isScanSuppressed() },
            )
            presenceMonitor?.start()
        }

        registerWifiGate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID,
                buildNotification(statusText(repo.data.value.connectionStatus) ?: DISCONNECTED_TEXT, capsule = false, daySteps = -1, goal = UserProfile.load(this).stepGoal),
                foregroundTypes(),
            )
        } catch (e: SecurityException) {
            // Defense in depth: foregroundTypes() should never produce an unsafe mask, but if it
            // does (or the OS adds a new check), degrade to the always-permitted CONNECTED_DEVICE.
            Log.e(TAG, "startForeground rejected full mask, retrying with CONNECTED_DEVICE", e)
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    buildNotification(statusText(repo.data.value.connectionStatus) ?: DISCONNECTED_TEXT, capsule = false, daySteps = -1, goal = UserProfile.load(this).stepGoal),
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
                    this, NOTIFICATION_ID,
                    buildNotification(statusText(repo.data.value.connectionStatus) ?: DISCONNECTED_TEXT, capsule = false, daySteps = -1, goal = UserProfile.load(this).stepGoal),
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

    /**
     * Notification text: only the two stable states. Connecting/Authenticating are
     * transient (seconds per attempt, and reconnect backoff cycles through them), and
     * showing "Подключение..." for each attempt just makes the notification flap.
     * A hung attempt is capped by WatchLink's 30s watchdog, which lands on Disconnected.
     */
    private var screenOn = true
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action == Intent.ACTION_SCREEN_ON
            if (screenOn) renderNotification(repo.data.value) // доехать накопленное при включении
        }
    }

    private fun renderNotification(data: com.pedometer.repo.WatchData) {
        if (!screenOn) return // выключенный экран — шторку не дёргаем; SCREEN_ON доедет ресивером
        val enabled = repo.isWorkoutStatusEnabled()
        val w = data.workout
        val ui = WorkoutStatusUi.resolve(
            event = null,
            sport = w?.sport ?: "",
            paused = w?.paused ?: false,
            connected = data.connectionStatus == com.pedometer.vm.ConnectionStatus.Connected,
            active = w != null,
            enabled = enabled,
        )
        // Текст: workout-текст или дефолт статуса линка.
        // Транзиентные состояния (Connecting/Authenticating) НЕ постим — анти-флаттер как было.
        val text = ui.notificationText ?: statusText(data.connectionStatus) ?: return
        val goal = UserProfile.load(this).stepGoal
        val capsuleVisible = ui.showLiveUpdate &&
            WorkoutStatusUi.shouldShowCapsule(data.daySteps, goal) &&
            notificationManagerCanPromote()
        Log.d(TAG, "Render: text='$text' capsule=$capsuleVisible daySteps=${data.daySteps} " +
            "workout=${w?.sport} status=${data.connectionStatus}")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, capsuleVisible, data.daySteps, goal))
    }

    /** NotificationManager.canPostPromotedNotifications() — платформенный API 36, прямой вызов (compileSdk 36). */
    private fun notificationManagerCanPromote(): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        } else false

    private fun statusText(status: ConnectionStatus): String? = when (status) {
        ConnectionStatus.Connected -> "Часы подключены"
        ConnectionStatus.Disconnected -> "Часы отключены"
        ConnectionStatus.Connecting, ConnectionStatus.Authenticating -> null
    }

    private fun buildNotification(text: String, capsule: Boolean, daySteps: Int, goal: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Шагомер")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (capsule) {
            // Promoted ongoing (Android 16): капелька с дневным прогрессом шагов.
            // MetricStyle — это API 37 (Android 17), на 36 его нет; ProgressStyle — штатный
            // promoted-стиль API 36 (NotificationCompat, core 1.17).
            val safeGoal = goal.coerceAtLeast(1)
            b.setRequestPromotedOngoing(true)
                .setStyle(
                    NotificationCompat.ProgressStyle()
                        // Compat-стиль не имеет 3-арг setProgress: max задаётся суммой сегментов.
                        .addProgressSegment(NotificationCompat.ProgressStyle.Segment(safeGoal))
                        .setProgress(daySteps.coerceIn(0, safeGoal))
                        .setStyledByProgress(true),
                )
        }
        return b.build()
    }

    private fun vibrateTriole() {
        Log.d(TAG, "Workout triole vibration")
        val effect = VibrationEffect.createWaveform(WorkoutStatusUi.TRIOLE_TIMINGS, -1)
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(effect)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Watch Connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Home scenario: report Wi-Fi transport changes to the repo. The callback is registered
     * unconditionally — the on/off decision lives in the repo (settings flag), so toggling
     * the setting never requires re-registering.
     */
    private fun registerWifiGate() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi available")
                repo.onHomeWifiChanged(true)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi lost")
                repo.onHomeWifiChanged(false)
                presenceMonitor?.scanNow()  // gate just opened — scan at once, don't wait a tick
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            cm.registerNetworkCallback(request, cb)
            wifiCallback = cb
            // Initial state — callbacks only fire on transitions.
            val wifiUp = cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            repo.onHomeWifiChanged(wifiUp)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi gate registration failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        presenceMonitor?.stop()
        presenceMonitor = null
        SupplementScheduler.stop()
        stepCollector.stop()
        scope.cancel()
        wifiCallback?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        wifiCallback = null
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }
}
