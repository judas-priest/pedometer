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

class StepCounterService : Service(), SensorEventListener {
    companion object {
        private const val TAG = "StepCounterService"
        private const val CHANNEL_ID = "pedometer_steps"
        private const val NOTIFICATION_ID = 2
    }

    private var sensorManager: SensorManager? = null
    private var initialSteps = -1L
    private var currentSteps = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Counting steps...")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor != null) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Step counter sensor registered")
        } else {
            Log.w(TAG, "No step counter sensor")
        }

        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values[0].toLong()
        if (initialSteps < 0) initialSteps = total
        currentSteps = total - initialSteps
        updateNotification("$total steps today")
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
            .setContentTitle("Pedometer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Step Counter",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        Log.i(TAG, "Step counter service stopped")
        super.onDestroy()
    }
}
