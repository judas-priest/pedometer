package com.pedometer.music

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge

class MediaListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "MediaListenerService"
        const val PREFS_NAME = "notification_whitelist"
        const val KEY_PACKAGES = "packages"

        // Default apps if nothing configured
        val DEFAULT_WHITELIST = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.dialer",
            "com.google.android.dialer",
        )

        fun getWhitelist(context: android.content.Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_PACKAGES, null) ?: DEFAULT_WHITELIST
        }

        fun saveWhitelist(context: android.content.Context, packages: Set<String>) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_PACKAGES, packages).apply()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return

        if (pkg !in getWhitelist(applicationContext)) return

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
