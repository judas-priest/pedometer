package com.pedometer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pedometer.MainActivity
import com.pedometer.data.HourlySteps
import com.pedometer.data.StepDatabase
import com.pedometer.data.StepSnapshot
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalDateTime

class StepCounterService : Service(), SensorEventListener {
    companion object {
        private const val TAG = "StepCounterService"
        private const val CHANNEL_ID = "pedometer_steps"
        private const val NOTIFICATION_ID = 2
    }

    private var sensorManager: SensorManager? = null
    private var lastStepCounterValue = -1L
    private var sessionSteps = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: StepDatabase

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        db = StepDatabase.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Подсчёт шагов...")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )

        // Register TYPE_STEP_COUNTER for daily totals + reboot tracking
        val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepCounter != null) {
            sensorManager?.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Step counter sensor registered")
        } else {
            Log.w(TAG, "No step counter sensor available")
        }

        // Register TYPE_STEP_DETECTOR for per-step events (hourly bucketing)
        val stepDetector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            sensorManager?.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Step detector sensor registered")
        } else {
            Log.w(TAG, "No step detector sensor available")
        }

        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> handleStepCounter(event)
            Sensor.TYPE_STEP_DETECTOR -> handleStepDetector()
        }
    }

    private fun handleStepCounter(event: SensorEvent) {
        val total = event.values[0].toLong()
        if (lastStepCounterValue < 0) {
            lastStepCounterValue = total
            scope.launch {
                val dao = db.stepDao()
                val lastSnapshot = dao.getLastSnapshot()
                if (lastSnapshot != null && total < lastSnapshot.stepsSinceReboot) {
                    // Reboot detected: new counter value < last saved value
                    // The steps between last snapshot and reboot are lost,
                    // but we log the gap for awareness
                    Log.i(TAG, "Reboot detected: last=${lastSnapshot.stepsSinceReboot}, now=$total")
                }
                // Save new snapshot
                dao.insertSnapshot(
                    StepSnapshot(
                        timestamp = System.currentTimeMillis(),
                        stepsSinceReboot = total,
                        source = "phone_sensor",
                    )
                )
                // Clean snapshots older than 7 days
                val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                dao.cleanOldSnapshots(weekAgo)
            }
        }
        sessionSteps = total - lastStepCounterValue
        updateNotification("$sessionSteps шагов за сессию")
    }

    private var detectorStepsToday = 0L

    private fun handleStepDetector() {
        // Each call = 1 step detected. Bucket into hourly_steps.
        val now = LocalDateTime.now()
        val date = now.toLocalDate().toString()
        val hour = now.hour
        detectorStepsToday++

        scope.launch {
            val dao = db.stepDao()
            dao.insertHourly(HourlySteps(date = date, hour = hour, steps = 0))
            dao.incrementHourly(date, hour, 1)
        }

        // Update notification every 50 steps to avoid excessive updates
        if (detectorStepsToday % 50 == 0L) {
            updateNotification("$detectorStepsToday шагов сегодня (сервис)")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Шагомер")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Счётчик шагов",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        scope.cancel()
        Log.i(TAG, "Step counter service stopped")
        super.onDestroy()
    }
}
