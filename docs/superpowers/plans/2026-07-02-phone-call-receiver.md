# Phone Call Receiver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Handle incoming/outgoing phone calls via TelephonyManager BroadcastReceiver (like Gadgetbridge), not via NotificationListener. Fixes: contact name shows as phone number on watch.

**Architecture:** New `PhoneCallReceiver` BroadcastReceiver listens for `PHONE_STATE` and `NEW_OUTGOING_CALL`. Resolves contact name via `ContactsContract.PhoneLookup`. Sends call state to watch via `WatchNotificationBridge`. NotificationListener ignores `com.android.incallui` calls (blacklist). VoIP calls still handled by NotificationListener.

**Tech Stack:** Kotlin, TelephonyManager, ContactsContract, BroadcastReceiver

---

## File Structure

- Create: `app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt` — BroadcastReceiver for PHONE_STATE
- Modify: `app/src/main/java/com/pedometer/music/MediaListenerService.kt` — blacklist incallui, remove call-specific code
- Modify: `app/src/main/AndroidManifest.xml` — register PhoneCallReceiver

---

### Task 1: Create PhoneCallReceiver

**Files:**
- Create: `app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt`

- [ ] **Step 1: Create PhoneCallReceiver**

```kotlin
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
                // EXTRA_INCOMING_NUMBER only present in one of two broadcasts
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
                    // Call answered
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
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt
git commit -m "feat: PhoneCallReceiver via TelephonyManager for reliable call handling"
```

---

### Task 2: Register PhoneCallReceiver in manifest + add READ_CALL_LOG permission

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add READ_CALL_LOG permission**

Add after existing `READ_PHONE_STATE` permission line:

```xml
    <uses-permission android:name="android.permission.READ_CALL_LOG" />
```

- [ ] **Step 2: Add receiver after BootReceiver**

Find the closing `</receiver>` tag of BootReceiver and add after it:

```xml
        <receiver
            android:name=".service.PhoneCallReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.PHONE_STATE" />
                <action android:name="android.intent.action.NEW_OUTGOING_CALL" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: register PhoneCallReceiver for PHONE_STATE broadcast"
```

---

### Task 3: Blacklist incallui in MediaListenerService + remove call timer

**Files:**
- Modify: `app/src/main/java/com/pedometer/music/MediaListenerService.kt`

- [ ] **Step 1: Add PHONE_CALL_BLACKLIST and skip regular calls**

Add after `companion object {` opening:

```kotlin
        private val PHONE_CALL_BLACKLIST = setOf(
            "com.android.incallui",
            "com.android.dialer",
            "com.samsung.android.incallui",
            "com.asus.asusincallui",
            "com.oplus.incallui",
        )
```

- [ ] **Step 2: Skip blacklisted call notifications**

Change lines 58-59 from:

```kotlin
        val isCall = notification.category == Notification.CATEGORY_CALL
```

to:

```kotlin
        val isCall = notification.category == Notification.CATEGORY_CALL
        // Regular phone calls handled by PhoneCallReceiver — skip here
        if (isCall && pkg in PHONE_CALL_BLACKLIST) return
```

- [ ] **Step 3: Remove call duration timer and related fields**

Remove fields (lines 12-14):
```kotlin
    private var callStartTime: Long = 0
    @Volatile private var callTimerRunning = false
    private var callTitle = ""
```

Remove call timer tracking in onNotificationPosted (lines 75-81):
```kotlin
        // Track active call duration (ongoing = call answered)
        if (isCall && sbn.isOngoing && !callTimerRunning) {
            callStartTime = System.currentTimeMillis()
            callTimerRunning = true
            callTitle = title
            startCallDurationTimer()
        }
```

Remove startCallDurationTimer method entirely (lines 126-145).

Remove resolveContactName method (lines 147-159) — now in PhoneCallReceiver.

In onNotificationRemoved — remove `callTimerRunning = false` and `callStartTime` references. Keep the block that sends empty notification to dismiss call screen on watch (needed for VoIP). Replace with:

```kotlin
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val notification = sbn.notification ?: return
        if (notification.category == Notification.CATEGORY_CALL && sbn.packageName !in PHONE_CALL_BLACKLIST) {
            Log.i(TAG, "VoIP call ended")
            WatchNotificationBridge.sendToWatch(
                id = 0, packageName = "phone", appName = "phone",
                title = "", body = "", isCall = false,
            )
        }
    }
```

Update onListenerDisconnected — remove `callTimerRunning = false`.
Update onDestroy — remove `callTimerRunning = false`.

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/music/MediaListenerService.kt
git commit -m "refactor: blacklist incallui in NotificationListener, remove call timer (handled by PhoneCallReceiver)"
```
