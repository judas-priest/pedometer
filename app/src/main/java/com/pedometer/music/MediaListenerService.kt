package com.pedometer.music

import android.app.Notification
import android.net.Uri
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge

class MediaListenerService : NotificationListenerService() {
    private var callStartTime: Long = 0
    @Volatile private var callTimerRunning = false
    private var callTitle = ""

    companion object {
        private const val TAG = "MediaListenerService"
        const val PREFS_NAME = "notification_whitelist"
        const val KEY_PACKAGES = "packages"

        // No defaults — user controls everything
        val DEFAULT_WHITELIST = emptySet<String>()

        fun getWhitelist(context: android.content.Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_PACKAGES, null) ?: emptySet()
        }

        fun saveWhitelist(context: android.content.Context, packages: Set<String>) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_PACKAGES, packages).apply()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        WatchNotificationBridge.notificationListener = this
        Log.i(TAG, "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        WatchNotificationBridge.notificationListener = null
        Log.i(TAG, "NotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return

        val notification = sbn.notification ?: return
        val isCall = notification.category == Notification.CATEGORY_CALL

        // Skip media/transport notifications (music player track changes)
        // But allow ongoing calls (they become ongoing after answering)
        if (!isCall && (
            notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_PROGRESS ||
            sbn.isOngoing)) return

        // Calls always forwarded, other apps need whitelist
        if (!isCall && pkg !in getWhitelist(applicationContext)) return
        val extras = notification.extras ?: return

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Track active call duration (ongoing = call answered)
        if (isCall && sbn.isOngoing && !callTimerRunning) {
            callStartTime = System.currentTimeMillis()
            callTimerRunning = true
            callTitle = title
            startCallDurationTimer()
        }
        val appName = getAppName(pkg)

        if (title.isBlank() && text.isBlank()) return

        // Resolve contact name from phone number for calls
        if (isCall && title.matches(Regex("^[+\\d\\s\\-()]+$"))) {
            val contactName = resolveContactName(title.replace(Regex("[\\s\\-()]"), ""))
            if (contactName != null) title = contactName
        }

        Log.i(TAG, "Forwarding notification: $pkg - $title: $text")

        WatchNotificationBridge.sendToWatch(
            id = sbn.id,
            packageName = pkg,
            appName = appName,
            title = title,
            body = text,
            isCall = isCall,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val notification = sbn.notification ?: return

        // If a call notification was removed — call ended
        if (notification.category == Notification.CATEGORY_CALL) {
            callTimerRunning = false
            val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0
            callStartTime = 0
            Log.i(TAG, "Call ended (${duration}s)")
            // Send empty call notification to dismiss call screen on watch
            WatchNotificationBridge.sendToWatch(
                id = 0,
                packageName = "phone",
                appName = "phone",
                title = "",
                body = "",
                isCall = false,
            )
        }
    }

    private fun startCallDurationTimer() {
        Thread {
            while (callTimerRunning) {
                Thread.sleep(1000)
                if (!callTimerRunning) break
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                val min = elapsed / 60
                val sec = elapsed % 60
                val durationText = "%d:%02d".format(min, sec)
                WatchNotificationBridge.sendToWatch(
                    id = 88888,
                    packageName = "phone",
                    appName = "Звонок",
                    title = callTitle.ifBlank { "Вызов" },
                    body = durationText,
                    isCall = false, // not isCall so it shows as notification, not call screen
                )
            }
        }.start()
    }

    private fun resolveContactName(phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed: ${e.message}")
            null
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
