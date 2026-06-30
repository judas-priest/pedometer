package com.pedometer.notification

import android.content.Context
import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

class NotificationService(
    private val context: Context,
    private val protocolHandler: ProtocolHandler,
) {
    companion object {
        private const val TAG = "NotificationService"
        const val COMMAND_TYPE = 7
        const val CMD_NOTIFICATION_SEND = 0
        const val CMD_NOTIFICATION_DISMISS = 1
        const val CMD_OPEN_ON_PHONE = 8
    }

    private var notificationIdCounter = 1

    fun sendNotification(
        packageName: String,
        appName: String,
        title: String,
        body: String,
        isCall: Boolean = false,
    ) {
        val id = notificationIdCounter++

        val notification3 = XiaomiProto.Notification3.newBuilder()
            .setPackage(packageName)
            .setAppName(appName)
            .setTitle(title)
            .setBody(body)
            .setId(id)
            .setIsCall(isCall)
            .setTimestamp(System.currentTimeMillis().toString())

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_NOTIFICATION_SEND)
            .setNotification(
                XiaomiProto.Notification.newBuilder()
                    .setNotification2(
                        XiaomiProto.Notification2.newBuilder()
                            .setNotification3(notification3)
                    )
            )
            .build()

        protocolHandler.sendCommand(cmd)
        Log.d(TAG, "Sent notification: $appName - $title")
    }

    var onCallAction: ((Boolean) -> Unit)? = null // true=accept, false=reject

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_NOTIFICATION_DISMISS -> {
                Log.i(TAG, "Watch dismissed notification")
                if (cmd.hasNotification() && cmd.notification.hasNotificationDismiss()) {
                    val listener = WatchNotificationBridge.notificationListener
                    val ids = cmd.notification.notificationDismiss.notificationIdList
                    for (nid in ids) {
                        Log.d(TAG, "Dismiss ID: ${nid.id}")
                        // Cancel notification on phone
                        try {
                            listener?.activeNotifications?.find { it.id == nid.id }?.let { sbn ->
                                listener.cancelNotification(sbn.key)
                                Log.i(TAG, "Cancelled notification: ${sbn.key}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Cancel failed: ${e.message}")
                        }
                    }
                }
            }
            CMD_OPEN_ON_PHONE -> {
                Log.i(TAG, "Watch requested open on phone")
                if (cmd.hasNotification() && cmd.notification.hasOpenOnPhone()) {
                    // Try to open the source app
                    val listener = WatchNotificationBridge.notificationListener
                    val notifId = cmd.notification.openOnPhone.id
                    val sbn = listener?.activeNotifications?.find { it.id == notifId }
                    if (sbn != null) {
                        try {
                            sbn.notification.contentIntent?.send()
                            Log.i(TAG, "Opened notification on phone: ${sbn.packageName}")
                        } catch (e: Exception) {
                            // Fallback: launch app
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(sbn.packageName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                            }
                        }
                    }
                }
                // Also handle call accept
                onCallAction?.invoke(true)
            }
            else -> Log.d(TAG, "Notification subtype: ${cmd.subtype}")
        }
    }
}
