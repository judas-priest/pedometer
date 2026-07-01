package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pedometer.health.UserProfile

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.i("BootReceiver", "Received ${intent.action}")

            val profile = UserProfile.load(context)
            if (profile.backgroundServiceEnabled) {
                Log.i("BootReceiver", "Starting StepCounterService")
                try {
                    context.startForegroundService(Intent(context, StepCounterService::class.java))
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start StepCounterService: ${e.message}")
                }
            }

            // Auto-start watch connection if MAC and key are saved
            val prefs = context.getSharedPreferences("pedometer_prefs", Context.MODE_PRIVATE)
            val mac = prefs.getString("mac_address", "") ?: ""
            val key = prefs.getString("auth_key", "") ?: ""
            if (mac.isNotBlank() && key.isNotBlank()) {
                Log.i("BootReceiver", "Starting WatchConnectionService for auto-connect")
                try {
                    context.startForegroundService(Intent(context, WatchConnectionService::class.java))
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start WatchConnectionService: ${e.message}")
                }
            }
        }
    }
}
