package com.pedometer.music

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge

class MediaListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "MediaListenerService"

        // Whitelist of apps to forward notifications to watch
        private val NOTIFICATION_WHITELIST = setOf(
            "org.telegram.messenger",           // Telegram
            "org.telegram.messenger.web",        // Telegram (web variant)
            "org.telegram.plus",                 // Telegram Plus
            "com.android.mms",                   // SMS
            "com.google.android.apps.messaging", // Google Messages
            "com.oneplus.mms",                   // OnePlus SMS
            "com.coloros.mms",                    // ColorOS SMS
            "com.messanger",                     // Custom messenger
            "com.android.dialer",                // Phone
            "com.google.android.dialer",         // Google Phone
            "com.oplus.dialer",                  // OnePlus/ColorOS Phone
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return

        if (pkg !in NOTIFICATION_WHITELIST) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val appName = getAppName(pkg)

        if (title.isBlank() && text.isBlank()) return

        Log.i(TAG, "Forwarding notification: $pkg - $title: $text")

        WatchNotificationBridge.sendToWatch(
            id = sbn.id,
            packageName = pkg,
            appName = appName,
            title = title,
            body = text,
            isCall = sbn.notification.category == Notification.CATEGORY_CALL,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Could send dismiss to watch
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
