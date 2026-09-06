package com.pedometer.music

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge
import com.pedometer.service.PhoneCallReceiver

class MediaListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "MediaListenerService"
        const val PREFS_NAME = "notification_whitelist"
        const val KEY_PACKAGES = "packages"

        val DEFAULT_WHITELIST = emptySet<String>()

        // Phone dialer packages — use their notification for caller name
        private val PHONE_CALL_PACKAGES = setOf(
            "com.android.incallui",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.asus.asusincallui",
            "com.oplus.incallui",
            "com.oplus.dialer",
            "com.oplus.contacts",
        )

        /** Titles like "+7 999 123-45-67" — system call notifications never resolve contacts. */
        private val NUMBER_ONLY_TITLE = Regex("^[+0-9][0-9 ()\\-.]{4,}$")

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

        // Phone calls: use notification title (has contact name)
        if (isCall || pkg in PHONE_CALL_PACKAGES) {
            val extras = notification.extras
            val callerName = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val callerText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (!callerName.isNullOrBlank()) {
                if (callerName.matches(NUMBER_ONLY_TITLE)) {
                    // System call notifications (com.android.server.telecom and friends) carry
                    // the RAW number in EXTRA_TITLE — they never resolve contacts. Do not claim
                    // the router's "shown" slot with it: the 2s telephony fallback does the
                    // contact lookup and shows the name (or the number if not in contacts).
                    return
                }
                PhoneCallReceiver.apply(PhoneCallReceiver.router.onDialerNotification(callerName, callerText))
                return
            }
        }

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
        // Call ended (phone or VoIP) — dismiss call screen on watch
        if (notification.category == Notification.CATEGORY_CALL) {
            Log.i(TAG, "VoIP call ended")
            PhoneCallReceiver.apply(PhoneCallReceiver.router.onIdle())
        }
    }

    private val appNameCache = HashMap<String, String>()

    private fun getAppName(packageName: String): String {
        appNameCache[packageName]?.let { return it }
        val name = try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
        appNameCache[packageName] = name
        return name
    }
}
