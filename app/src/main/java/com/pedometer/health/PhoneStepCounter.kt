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

    private var initialSteps: Long = -1L
    private var startDay: Int = java.time.LocalDate.now().dayOfYear

    private val _stepsSinceStart = MutableStateFlow(0L)
    val stepsSinceStart: StateFlow<Long> = _stepsSinceStart

    private val _totalStepsSinceBoot = MutableStateFlow(0L)
    val totalStepsSinceBoot: StateFlow<Long> = _totalStepsSinceBoot

    val isAvailable: Boolean get() = stepSensor != null
    private var listening = false

    fun start() {
        if (stepSensor == null || listening) return
        sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        listening = true
        Log.i(TAG, "Step counter started")
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
        Log.i(TAG, "Step counter stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val totalSteps = event.values[0].toLong()
            _totalStepsSinceBoot.value = totalSteps

            val today = java.time.LocalDate.now().dayOfYear
            if (initialSteps < 0 || today != startDay) {
                initialSteps = totalSteps
                startDay = today
            }
            _stepsSinceStart.value = totalSteps - initialSteps
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
