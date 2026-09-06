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
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callbackRegistered = false
        private var savedNumber: String? = null
        private val handler = Handler(Looper.getMainLooper())
        private var fallbackRunnable: Runnable? = null

        // MediaListenerService sets this when it sends call notification
        @Volatile var callHandledByListener = false

        fun registerTelephonyCallback(context: Context) {
            if (callbackRegistered) return
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    tm.registerTelephonyCallback(context.mainExecutor, object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleStateChange(context, state)
                        }
                    })
                    callbackRegistered = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register TelephonyCallback: ${e.message}")
                }
            }
        }

        private fun handleStateChange(context: Context, state: Int) {
            if (state == lastState) return
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    callHandledByListener = false
                    savedNumber = null
                    // Give MediaListenerService 2s to handle with contact name
                    fallbackRunnable?.let { handler.removeCallbacks(it) }
                    fallbackRunnable = Runnable {
                        if (!callHandledByListener) {
                            val title = savedNumber ?: "Входящий вызов"
                            WatchNotificationBridge.sendToWatch(
                                id = 99999, packageName = "phone", appName = "phone",
                                title = title, body = "Входящий вызов", isCall = true,
                            )
                        }
                    }
                    handler.postDelayed(fallbackRunnable!!, 2000)
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    fallbackRunnable?.let { handler.removeCallbacks(it) }
                    fallbackRunnable = null
                    callHandledByListener = false
                    WatchNotificationBridge.sendToWatch(
                        id = 0, packageName = "phone", appName = "phone",
                        title = "", body = "", isCall = false,
                    )
                }
            }
            lastState = state
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.intent.action.PHONE_STATE" -> {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
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
