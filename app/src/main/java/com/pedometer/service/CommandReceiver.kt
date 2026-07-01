package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge

/**
 * Receive commands via ADB broadcast for testing.
 *
 * Send notification:
 *   adb shell am broadcast -a com.pedometer.NOTIFY --es title "Title" --es body "Text" --es app "PC" -n com.pedometer/.service.CommandReceiver
 *
 * Find watch:
 *   adb shell am broadcast -a com.pedometer.FIND -n com.pedometer/.service.CommandReceiver
 *
 * Refresh data:
 *   adb shell am broadcast -a com.pedometer.REFRESH -n com.pedometer/.service.CommandReceiver
 */
class CommandReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CommandReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received: ${intent.action}")
        when (intent.action) {
            "com.pedometer.NOTIFY" -> {
                val title = intent.getStringExtra("title") ?: "Test"
                val body = intent.getStringExtra("body") ?: ""
                val app = intent.getStringExtra("app") ?: "ADB"
                val pkg = intent.getStringExtra("package") ?: "com.pedometer"
                WatchNotificationBridge.sendToWatch(
                    id = (System.currentTimeMillis() % 100000).toInt(),
                    packageName = pkg,
                    appName = app,
                    title = title,
                    body = body,
                )
                Log.i(TAG, "Sent notification: [$app] $title: $body")
            }
            "com.pedometer.FIND" -> {
                Log.i(TAG, "Find watch triggered via ADB")
                // Will be handled if someone wires it
            }
            "com.pedometer.REFRESH" -> {
                Log.i(TAG, "Refresh triggered via ADB")
            }
        }
    }
}
