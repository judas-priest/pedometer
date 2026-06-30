package com.pedometer.notification

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WatchNotificationBridge {
    private const val TAG = "WatchNotificationBridge"
    private const val NOTIFICATION_TYPE = 7
    private const val CMD_NOTIFICATION_SEND = 0

    var protocolHandler: ProtocolHandler? = null

    private val timestampFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)

    fun sendToWatch(
        id: Int,
        packageName: String,
        appName: String,
        title: String,
        body: String,
        isCall: Boolean = false,
    ) {
        val handler = protocolHandler ?: return

        val notification3 = XiaomiProto.Notification3.newBuilder()
            .setId(id)
            .setPackage(if (isCall) "phone" else packageName)
            .setTitle(title)
            .setBody(body)
            .setAppName(if (isCall) "phone" else appName)
            .setTimestamp(timestampFormat.format(Date()))
            .setIsCall(isCall)

        val notification = XiaomiProto.Notification.newBuilder()
            .setNotification2(XiaomiProto.Notification2.newBuilder()
                .setNotification3(notification3))

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(NOTIFICATION_TYPE)
            .setSubtype(CMD_NOTIFICATION_SEND)
            .setNotification(notification)
            .build()

        Log.i(TAG, "Sending ${if (isCall) "call" else "notification"}: $appName - $title")
        handler.sendCommand(cmd)
    }
}
