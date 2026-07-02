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

        val DEFAULT_WHITELIST = emptySet<String>()

        // Regular phone calls handled by PhoneCallReceiver — skip here
        private val PHONE_CALL_BLACKLIST = setOf(
            "com.android.incallui",
            "com.android.dialer",
            "com.samsung.android.incallui",
            "com.asus.asusincallui",
            "com.oplus.incallui",
        )

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

        // Regular phone calls handled by PhoneCallReceiver
        if (isCall && pkg in PHONE_CALL_BLACKLIST) return

        // Skip media/transport notifications (music player track changes)
        // But allow ongoing calls (VoIP)
        if (!isCall && (
            notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_PROGRESS ||
            sbn.isOngoing)) return

        // Calls always forwarded (VoIP), other apps need whitelist
        if (!isCall && pkg !in getWhitelist(applicationContext)) return
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
            isCall = isCall,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val notification = sbn.notification ?: return
        // VoIP call ended — dismiss call screen on watch
        if (notification.category == Notification.CATEGORY_CALL && sbn.packageName !in PHONE_CALL_BLACKLIST) {
            Log.i(TAG, "VoIP call ended")
            WatchNotificationBridge.sendToWatch(
                id = 0, packageName = "phone", appName = "phone",
                title = "", body = "", isCall = false,
            )
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
