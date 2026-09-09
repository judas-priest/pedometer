package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge

class PhoneCallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneCallReceiver"
        private const val FALLBACK_DELAY_MS = 2_000L

        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callbackRegistered = false
        private var savedNumber: String? = null
        private val handler = Handler(Looper.getMainLooper())
        private var fallbackRunnable: Runnable? = null

        val router = CallRouter(FALLBACK_DELAY_MS)

        fun registerTelephonyCallback(context: Context) {
            if (callbackRegistered) return
            if (Build.VERSION.SDK_INT < 31) return
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) = handleStateChange(context, state)
                    },
                )
                callbackRegistered = true
                Log.i(TAG, "TelephonyCallback registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register TelephonyCallback: ${e.message}")
            }
        }

        /** Applies a router decision to the watch. Called from both entry points. */
        fun apply(action: CallAction) {
            when (action) {
                is CallAction.Show -> WatchNotificationBridge.sendToWatch(
                    id = 99999, packageName = "phone", appName = "phone",
                    title = action.title, body = action.body, isCall = true,
                )
                CallAction.Dismiss -> WatchNotificationBridge.sendToWatch(
                    id = 0, packageName = "phone", appName = "phone",
                    title = "", body = "", isCall = false,
                )
                CallAction.None -> Unit
            }
        }

        private fun handleStateChange(context: Context, state: Int) {
            if (state == lastState) return
            Log.i(TAG, "Telephony state: $lastState -> $state, savedNumber=$savedNumber")
            lastState = state
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val startedAt = System.currentTimeMillis()
                    apply(router.onRinging(startedAt))
                    fallbackRunnable?.let { handler.removeCallbacks(it) }
                    val runnable = Runnable {
                        val lookedUp = resolveContactName(context, savedNumber)
                        val fallback = lookedUp ?: savedNumber ?: "Неизвестный"
                        Log.i(TAG, "Fallback: lookedUp=$lookedUp, sending '$fallback'")
                        apply(router.onTick(System.currentTimeMillis(), fallback))
                    }
                    fallbackRunnable = runnable
                    handler.postDelayed(runnable, FALLBACK_DELAY_MS)
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    fallbackRunnable?.let { handler.removeCallbacks(it) }
                    fallbackRunnable = null
                    savedNumber = null
                    apply(router.onIdle())
                }
            }
        }

        private fun resolveContactName(context: Context, phoneNumber: String?): String? {
            if (phoneNumber.isNullOrBlank()) return null
            return try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber),
                )
                context.contentResolver.query(
                    uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (e: Exception) {
                Log.w(TAG, "Contact lookup failed: ${e.message}")
                null
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.intent.action.PHONE_STATE" -> {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d(TAG, "PHONE_STATE broadcast: $stateStr, number=$number")
                if (number != null) savedNumber = number
                val state = when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                    else -> return
                }
                if (Build.VERSION.SDK_INT < 31) {
                    handleStateChange(context, state)
                }
            }
        }
    }
}
