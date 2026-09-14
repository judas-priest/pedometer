package com.pedometer.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.pedometer.data.HourlySteps
import com.pedometer.data.MinuteSteps
import com.pedometer.data.StepDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Listens to TYPE_STEP_DETECTOR while the foreground service is alive and writes hourly
 * step buckets to Room. This is the only source of hourly data when no watch is syncing.
 *
 * collectEnabled gates collection: while the watch is connected the watch's hourly data
 * is the source of truth (dao.upsertHourly REPLACEs the row) and counting here too would
 * double-count, so the service passes a check against the repository's connection status.
 */
class StepCollector(
    context: Context,
    private val collectEnabled: () -> Boolean = { true },
) : SensorEventListener {

    companion object {
        private const val TAG = "StepCollector"
    }

    private val appContext = context.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var listening = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var minuteBuf: Long = 0L   // minute start, epoch ms
    private var minuteSteps = 0
    private val pendingMinutes = mutableListOf<MinuteSteps>()

    private val accumulator = HourBucketAccumulator { date, hour, steps ->
        scope.launch {
            try {
                val dao = StepDatabase.get(appContext).stepDao()
                // incrementHourly is an UPDATE: the row has to exist first.
                dao.insertHourly(HourlySteps(date = date, hour = hour, steps = 0))
                dao.incrementHourly(date, hour, steps)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist hourly steps", e)
            }
        }
    }

    fun start() {
        if (detector == null) {
            Log.w(TAG, "No TYPE_STEP_DETECTOR on this device")
            return
        }
        if (listening) return
        sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL)
        listening = true
        Log.i(TAG, "Step collector started")
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
        accumulator.flush()
        flushPendingMinutes()
        Log.i(TAG, "Step collector stopped")
    }

    private fun flushPendingMinutes() {
        if (pendingMinutes.isEmpty()) return
        val batch = ArrayList(pendingMinutes)
        pendingMinutes.clear()
        scope.launch {
            try {
                StepDatabase.get(appContext).stepDao().insertMinuteSteps(batch)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist minute steps", e)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return

        val nowMs = System.currentTimeMillis()
        val minuteStart = nowMs - nowMs % 60_000L
        if (minuteBuf != 0L && minuteStart != minuteBuf) {
            pendingMinutes.add(MinuteSteps(minute = minuteBuf, steps = minuteSteps))
            if (pendingMinutes.size >= 5) flushPendingMinutes()
            minuteSteps = 0
        }
        minuteBuf = minuteStart
        minuteSteps += event.values[0].toInt().coerceAtLeast(1)

        if (!collectEnabled()) return
        val now = LocalDateTime.now()
        accumulator.add(now.toLocalDate().toString(), now.hour, event.values[0].toInt().coerceAtLeast(1))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
