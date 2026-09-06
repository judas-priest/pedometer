package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pedometer.repo.WatchRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i("BootReceiver", "Received ${intent.action}")
        if (!WatchRepository.get(context).hasCredentials) {
            Log.i("BootReceiver", "No credentials — not starting service")
            return
        }
        try {
            context.startForegroundService(Intent(context, WatchConnectionService::class.java))
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start WatchConnectionService: ${e.message}")
        }
    }
}
