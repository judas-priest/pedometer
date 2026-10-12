package com.pedometer.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * Триоль старта/финиша тренировки. Весь код в try/catch с честным логом:
 * ColorOS может душить вибро молча — без лога не отличить «не вызвано» от «съедено».
 */
object TriolePlayer {
    private const val TAG = "TriolePlayer"

    fun play(context: Context) {
        try {
            val effect = VibrationEffect.createWaveform(WorkoutStatusUi.TRIOLE_TIMINGS, -1)
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(VibratorManager::class.java)
                Log.d(TAG, "vibrate: manager=$vm vibrator=${vm?.defaultVibrator}")
                vm?.defaultVibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
                    .vibrate(effect)
            }
            Log.d(TAG, "vibrate: submitted")
        } catch (e: Exception) {
            Log.d(TAG, "vibrate FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
