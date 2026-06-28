package com.pedometer.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PhoneStepCounter(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "PhoneStepCounter"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var initialSteps: Long = -1L

    private val _stepsSinceStart = MutableStateFlow(0L)
    val stepsSinceStart: StateFlow<Long> = _stepsSinceStart

    private val _totalStepsSinceBoot = MutableStateFlow(0L)
    val totalStepsSinceBoot: StateFlow<Long> = _totalStepsSinceBoot

    val isAvailable: Boolean get() = stepSensor != null

    fun start() {
        if (stepSensor == null) {
            Log.w(TAG, "Step counter sensor not available")
            return
        }
        sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        Log.i(TAG, "Step counter started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "Step counter stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val totalSteps = event.values[0].toLong()
            _totalStepsSinceBoot.value = totalSteps

            if (initialSteps < 0) {
                initialSteps = totalSteps
            }
            _stepsSinceStart.value = totalSteps - initialSteps
            Log.d(TAG, "Steps: total=$totalSteps sinceStart=${_stepsSinceStart.value}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
