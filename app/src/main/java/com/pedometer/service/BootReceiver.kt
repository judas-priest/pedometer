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

            val profile = UserProfile.load(context)
            if (profile.backgroundServiceEnabled) {
                Log.i("BootReceiver", "Starting StepCounterService after ${intent.action}")
                val serviceIntent = Intent(context, StepCounterService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
