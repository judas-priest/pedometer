package com.pedometer.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pedometer.MainActivity
import com.pedometer.R
import com.pedometer.notification.WatchNotificationBridge
import com.pedometer.service.SupplementDismissReceiver

/** Posts swipe-to-log supplement notifications and mirrors them to the watch. */
object SupplementNotifier {
    const val EXTRA_SLOT = "supp_slot"
    const val EXTRA_POSTED_AT = "supp_posted_at"
    private const val CHANNEL_ID = "supplements"
    private const val NOTIF_BASE = 9000
    private const val WATCH_ID_BASE = 9100

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "БАДы", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /**
     * Posts the swipe-to-log notification. Lines are rendered via InboxStyle
     * (one pill per line, up to 7 visible). Swipe-away fires SupplementDismissReceiver.
     */
    fun post(context: Context, slot: SupplementSlot, lines: List<String>) {
        ensureChannel(context)
        val postedAt = System.currentTimeMillis()
        val dismissIntent = Intent(context, SupplementDismissReceiver::class.java).apply {
            putExtra(EXTRA_SLOT, slot.key)
            putExtra(EXTRA_POSTED_AT, postedAt)
        }
        val deletePending = PendingIntent.getBroadcast(
            context,
            (slot.key.hashCode() + (postedAt / 1000).toInt()) and 0x7FFFFFFF,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            slot.key.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val inbox = NotificationCompat.InboxStyle()
        lines.take(7).forEach { inbox.addLine(it) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("БАДы: ${slot.title}")
            .setStyle(inbox)
            // AutoCancel must stay FALSE: a tap removes the notification, which fires
            // the DeleteIntent and would falsely log an intake. Swipe-only logging.
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deletePending)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_BASE + slot.ordinal, notification)

        // Mirror to the watch (text-only there — the buzz is the point).
        WatchNotificationBridge.sendToWatch(
            id = WATCH_ID_BASE + slot.ordinal,
            packageName = "com.pedometer",
            appName = "БАДы",
            title = slot.title,
            body = lines.joinToString(" · "),
        )
    }
}
