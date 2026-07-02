package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge

class PhoneCallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneCallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var savedNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.intent.action.NEW_OUTGOING_CALL" -> {
                savedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                Log.i(TAG, "Outgoing call to $savedNumber")
            }
            "android.intent.action.PHONE_STATE" -> {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val number = if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) else null
                val state = when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                    else -> return
                }
                onCallStateChanged(context, state, number)
            }
        }
    }

    private fun onCallStateChanged(context: Context, state: Int, number: String?) {
        if (state == lastState) return

        Log.i(TAG, "Call state: $lastState → $state, number=$number")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (number != null) savedNumber = number
                val displayName = resolveContactName(context, savedNumber) ?: savedNumber ?: "Неизвестный"
                Log.i(TAG, "Incoming call: $displayName")
                WatchNotificationBridge.sendToWatch(
                    id = 99999,
                    packageName = "phone",
                    appName = "phone",
                    title = displayName,
                    body = "Входящий вызов",
                    isCall = true,
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    val displayName = resolveContactName(context, savedNumber) ?: savedNumber ?: "Вызов"
                    Log.i(TAG, "Call answered: $displayName")
                    WatchNotificationBridge.sendToWatch(
                        id = 99999,
                        packageName = "phone",
                        appName = "Звонок",
                        title = displayName,
                        body = "Идёт вызов",
                        isCall = false,
                    )
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "Call ended")
                WatchNotificationBridge.sendToWatch(
                    id = 0,
                    packageName = "phone",
                    appName = "phone",
                    title = "",
                    body = "",
                    isCall = false,
                )
                savedNumber = null
            }
        }
        lastState = state
    }

    private fun resolveContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed: ${e.message}")
            null
        }
    }
}
