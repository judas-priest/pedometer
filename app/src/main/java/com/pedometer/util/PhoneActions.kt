package com.pedometer.util

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.util.Log

object PhoneActions {
    private const val TAG = "PhoneActions"

    fun openCamera(context: Context) {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Camera opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        }
    }

    fun toggleFlashlight(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            // Toggle - we don't track state, so just enable for 5s
            cameraManager.setTorchMode(cameraId, true)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { cameraManager.setTorchMode(cameraId, false) } catch (_: Exception) {}
            }, 5000)
            Log.i(TAG, "Flashlight toggled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight: ${e.message}")
        }
    }

    fun adjustVolume(context: Context, raise: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustVolume(
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        Log.d(TAG, "Volume ${if (raise) "up" else "down"}")
    }
}
