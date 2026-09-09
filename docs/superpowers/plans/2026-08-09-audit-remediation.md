# Audit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the watch connection survive the Activity being destroyed (background, reboot, long-running reconnect), stop the database from being wipeable, restore background step counting and contact names on incoming calls, and clear the release-blocking hygiene issues found in the 2026-08-09 audit.

**Architecture:** Today `WatchViewModel` (1127 lines) owns the Bluetooth socket, the protocol handler, all eleven watch services and all incoming-command handling; `onCleared()` tears the connection down, and `WatchConnectionService` is an empty foreground-notification shell. This plan inverts that: a process-scoped `WatchRepository` (created in `PedometerApp.onCreate`) owns transport + services + command handling and exposes a `StateFlow<WatchData>`; `WatchConnectionService` drives its lifecycle and keeps the process alive; `WatchViewModel` shrinks to phone-side state (steps, history, profile, calendar) plus a merge of `WatchData` into the existing `WatchState`, so **no UI screen changes**. Phone step counting moves into the same foreground service. New logic (reconnect backoff, hour bucketing, call routing, migration coverage) is extracted as pure classes with JUnit tests, because none of it is testable while it lives inside the ViewModel.

**Tech Stack:** Kotlin, Coroutines/StateFlow, Jetpack Compose, Room 2.6.1 + KSP, protobuf-lite, Bluetooth SPP (RFCOMM), JUnit 4.

---

## Ground Rules

**Build and test commands** (run from repo root, `/home/dima/Projects/pedometer`):

```bash
unset LD_PRELOAD                       # required before ANY command that touches the network or adb
./gradlew testDebugUnitTest            # JVM unit tests
./gradlew assembleDebug                # compile check + APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb logcat -s WatchRepository:* WatchLink:* WatchConnectionService:* PhoneCallReceiver:*
```

`--offline` does **not** work in this repo (junit is not in the local Gradle cache). Always run online, always `unset LD_PRELOAD` first.

**Test framework:** JUnit 4 only (`testImplementation("junit:junit:4.13.2")`). There is no Robolectric and no `androidTest` source set, and this plan does not add either. Every test written here is a plain JVM test over pure Kotlin classes with no Android imports. Device behaviour is verified by explicit manual `adb` steps, which are written out as steps — do not skip them and do not claim a task is done without running them.

**Existing tests that must stay green:** `AuthServiceTest` (8), `PacketV1Test` (4), `PacketV2Test` (4).

**Commit style:** conventional commits, no co-authors (`git commit -m "feat: ..."`).

**Never** run `git checkout .`, `git reset --hard`, or delete `pedometer.db` on the phone — the database holds real user history with no backup.

---

## Deliberately Out of Scope

Named here so nobody assumes the audit is fully closed when this plan is:

- **Parser tests** (`health/parsers/`, `ActivitySync`) — these need real captured byte fixtures from the watch, which means a capture session on the device first. Separate plan.
- **Swallowed exceptions** — 19 `catch` blocks in the ViewModel and 13 in `SppConnection` still discard errors. The connection notification now shows the real status (Task 7), which covers the worst case (silent "Connecting" forever); surfacing the rest in the UI is separate work.
- **i18n** — ~200 Russian literals stay hardcoded. Intentional: the app is single-user Russian.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/pedometer/bt/ConnectionStatus.kt` | Create | The connection-state enum, moved out of the `vm` package so `bt` does not depend on `vm`. |
| `app/src/main/java/com/pedometer/bt/ReconnectPolicy.kt` | Create | Pure backoff/attempt-cap decision logic. No Android, no coroutines. |
| `app/src/main/java/com/pedometer/bt/WatchLink.kt` | Create | Owns `SppConnection`/`BleConnection`, `AuthService`, `ProtocolHandler`. Exposes status, `connect`, `send`, `disconnect`, reconnect loop. |
| `app/src/main/java/com/pedometer/repo/WatchData.kt` | Create | Watch-sourced state snapshot + `WatchState.withWatchData()` merge. |
| `app/src/main/java/com/pedometer/repo/WatchRepository.kt` | Create | Process-scoped singleton: owns `WatchLink` + the eleven watch services, post-auth init, incoming-command handling, Room writes. |
| `app/src/main/java/com/pedometer/health/HourBucketAccumulator.kt` | Create | Pure hour-bucketing of step-detector events. |
| `app/src/main/java/com/pedometer/health/StepCollector.kt` | Create | `TYPE_STEP_DETECTOR` listener → `HourBucketAccumulator` → Room. Lives in the foreground service. |
| `app/src/main/java/com/pedometer/service/CallRouter.kt` | Create | Pure arbitration between telephony state and dialer notifications. |
| `app/src/main/java/com/pedometer/data/MigrationCoverage.kt` | Create | Pure check that the migration list covers every version step. |
| `app/src/main/java/com/pedometer/service/WatchConnectionService.kt` | Modify | Drives `WatchRepository` lifecycle, hosts `StepCollector`, notification reflects real status. |
| `app/src/main/java/com/pedometer/vm/WatchViewModel.kt` | Modify | Shrinks to phone-side state + merge + delegation. |
| `app/src/main/java/com/pedometer/PedometerApp.kt` | Modify | Creates the repository singleton; foreground signal becomes a `StateFlow`. |
| `app/src/main/java/com/pedometer/service/BootReceiver.kt` | Modify | Starts a service that now actually connects. |
| `app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt` | Modify | Uses `CallRouter`, restores contact-name lookup. |
| `app/src/main/java/com/pedometer/music/MediaListenerService.kt` | Modify | Feeds `CallRouter` instead of setting a boolean flag. |
| `app/src/main/java/com/pedometer/data/StepDatabase.kt` | Modify | Schema export, real migration list, no destructive fallback. |
| `app/src/main/AndroidManifest.xml` | Modify | Drop `RECORD_AUDIO`, add health foreground-service type. |
| `app/build.gradle.kts` | Modify | Release signing config, `buildConfig = true`, Room schema export dir. |
| `app/src/test/java/com/pedometer/**` | Create | Tests for every pure class above. |

---

## Phase 0 — Start from a clean tree

### Task 0: Commit the pending working-tree changes

The tree has 7 modified files (sensor lifecycle, `Thread.sleep`→`delay`, `TelephonyCallback`, app-name cache). They are good changes but carry two leftovers. Land them first so later diffs are readable.

**Files:**
- Modify: `app/src/main/java/com/pedometer/health/PhoneStepCounter.kt`
- Modify: `app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt`

- [ ] **Step 1: Delete the dead sensor field**

In `PhoneStepCounter.kt`, delete these two lines:

```kotlin
    @Suppress("unused") // reserved for future cadence tracking
    private val stepDetector: Sensor? = null
```

Then delete the now-unused import if `Sensor` is still referenced elsewhere in the file — it is (`stepSensor: Sensor?`), so keep `import android.hardware.Sensor`.

- [ ] **Step 2: Delete the two unused imports in PhoneCallReceiver.kt**

Remove:

```kotlin
import android.net.Uri
import android.provider.ContactsContract
```

(Contact lookup is restored in Task 13 with its own imports.)

- [ ] **Step 3: Verify it compiles**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A app/src/main/java/com/pedometer
git commit -m "perf: sensor lifecycle, coroutine delays, telephony callback, app-name cache"
```

---

## Phase 1 — Connection ownership moves out of the ViewModel

Phase goal: after Task 8, killing the Activity (swipe away from recents) leaves the watch connected and still forwarding notifications, and a reboot reconnects without the app being opened.

### Task 1: ReconnectPolicy (pure, TDD)

Today: `maxReconnectAttempts = 3`, fixed 2s×n delay, and once the counter is exhausted the app never retries until the user taps Connect (`WatchViewModel.kt:204-259`).

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/ReconnectPolicy.kt`
- Test: `app/src/test/java/com/pedometer/bt/ReconnectPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pedometer/bt/ReconnectPolicyTest.kt`:

```kotlin
package com.pedometer.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `delays grow exponentially from the base delay`() {
        val policy = ReconnectPolicy(maxAttempts = 10, baseDelayMs = 2_000L, maxDelayMs = 300_000L)
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
    }

    @Test
    fun `delay is capped at maxDelayMs`() {
        val policy = ReconnectPolicy(maxAttempts = 20, baseDelayMs = 2_000L, maxDelayMs = 10_000L)
        repeat(5) { policy.nextDelayMs() }
        assertEquals(10_000L, policy.nextDelayMs())
    }

    @Test
    fun `gives up after maxAttempts`() {
        val policy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun `onConnected resets the attempt counter`() {
        val policy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.onConnected()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `suspendRetries stops retrying until resumed`() {
        val policy = ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        policy.suspendRetries()
        assertNull(policy.nextDelayMs())
        policy.resumeRetries()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `attempts is observable for logging`() {
        val policy = ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1_000L, maxDelayMs = 10_000L)
        assertEquals(0, policy.attempts)
        policy.nextDelayMs()
        assertEquals(1, policy.attempts)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.bt.ReconnectPolicyTest"`
Expected: compilation failure — `Unresolved reference: ReconnectPolicy`

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/pedometer/bt/ReconnectPolicy.kt`:

```kotlin
package com.pedometer.bt

/**
 * Decides whether and how long to wait before retrying a dropped watch connection.
 *
 * Pure logic: no Android, no coroutines, no clock. The caller owns the waiting.
 */
class ReconnectPolicy(
    private val maxAttempts: Int = 12,
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 5 * 60_000L,
) {
    var attempts: Int = 0
        private set

    private var enabled = true

    /** Delay before the next attempt, or null if we should stop retrying. */
    fun nextDelayMs(): Long? {
        if (!enabled) return null
        if (attempts >= maxAttempts) return null
        attempts++
        val delay = baseDelayMs shl (attempts - 1)
        return if (delay <= 0L || delay > maxDelayMs) maxDelayMs else delay
    }

    /** Successful authentication — the next drop starts from the base delay again. */
    fun onConnected() {
        attempts = 0
        enabled = true
    }

    /** User asked for disconnect — do not fight them. */
    fun suspendRetries() {
        enabled = false
    }

    fun resumeRetries() {
        enabled = true
        attempts = 0
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.bt.ReconnectPolicyTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/ReconnectPolicy.kt app/src/test/java/com/pedometer/bt/ReconnectPolicyTest.kt
git commit -m "feat: ReconnectPolicy with exponential backoff and attempt cap"
```

---

### Task 2: Move ConnectionStatus into the bt package

`ConnectionStatus` is declared at the top level of `WatchViewModel.kt` in package `com.pedometer.vm`. `WatchLink` (package `bt`) needs it, and `bt` must not depend on `vm`. A typealias keeps every existing `com.pedometer.vm.ConnectionStatus` import valid, so no UI file changes.

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/ConnectionStatus.kt`
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt` (lines 96–98)

- [ ] **Step 1: Create the enum in its new home**

Create `app/src/main/java/com/pedometer/bt/ConnectionStatus.kt`:

```kotlin
package com.pedometer.bt

enum class ConnectionStatus {
    Disconnected, Connecting, Authenticating, Connected
}
```

- [ ] **Step 2: Replace the old declaration with a typealias**

In `WatchViewModel.kt`, replace:

```kotlin
enum class ConnectionStatus {
    Disconnected, Connecting, Authenticating, Connected
}
```

with:

```kotlin
typealias ConnectionStatus = com.pedometer.bt.ConnectionStatus
```

- [ ] **Step 3: Verify it compiles**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` — no other file needs editing; `import com.pedometer.vm.ConnectionStatus` resolves through the typealias.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/ConnectionStatus.kt app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "refactor: move ConnectionStatus to bt package behind a typealias"
```

---

### Task 3: WatchData + merge into WatchState (TDD)

`WatchState` mixes watch-sourced fields with phone-sourced ones. Split the watch-sourced subset into `WatchData` so the repository can own it without owning the UI state. `WatchState` itself and every screen stay untouched.

Fields that move to `WatchData`: `connectionStatus`, `serialNumber`, `firmware`, `model`, `batteryLevel`, `batteryCharging`, `watchSteps`, `watchCalories`, `heartRate`, `standingHours`, `activeMinutes`, `watchDistanceM`, `findPhoneActive`, `watchfaces`, `uploadProgress`, `alarms`, `reminders`.

Fields that stay phone-owned in `WatchState`: `authKey`, `macAddress`, `phoneSteps`, `phoneStepsSinceBoot`, `todayWalkSteps`, `todayRunSteps`, `todayWalkMinutes`, `stepHistory`, `healthConnectSteps`, `healthConnectHR`, `profile`, `todayHourlySteps`, `healthHistory`, `calendarEvents`, `lastSleep`, `recentWorkouts`, `hrHistory`, **`spo2`, `stress`, `hrResting`**.

> **Why `spo2`/`stress`/`hrResting` must NOT be in `WatchData`:** `refreshData()` already writes all three into `_state` from Room (`WatchViewModel.kt:806-808`). If they also lived in `WatchData`, every repository emission would overwrite the Room-derived values with `0` — a live regression that only shows up after a reconnect. They arrive via daily summary → written to Room → `bumpRoom()` → the ViewModel re-reads Room. One source of truth.

**Files:**
- Create: `app/src/main/java/com/pedometer/repo/WatchData.kt`
- Test: `app/src/test/java/com/pedometer/repo/WatchDataTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pedometer/repo/WatchDataTest.kt`:

```kotlin
package com.pedometer.repo

import com.pedometer.bt.ConnectionStatus
import com.pedometer.health.UserProfile
import com.pedometer.util.WatchAlarm
import com.pedometer.vm.WatchState
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchDataTest {

    @Test
    fun `watch fields are copied into the ui state`() {
        val data = WatchData(
            connectionStatus = ConnectionStatus.Connected,
            batteryLevel = 42,
            watchSteps = 1234,
            heartRate = 71,
            alarms = listOf(WatchAlarm(id = 1, hour = 7, minute = 30, enabled = true)),
        )

        val merged = WatchState().withWatchData(data)

        assertEquals(ConnectionStatus.Connected, merged.connectionStatus)
        assertEquals(42, merged.batteryLevel)
        assertEquals(1234, merged.watchSteps)
        assertEquals(71, merged.heartRate)
        assertEquals(1, merged.alarms.size)
    }

    @Test
    fun `phone owned fields survive the merge`() {
        val state = WatchState(
            macAddress = "E8:E6:09:31:23:D8",
            authKey = "deadbeef",
            phoneSteps = 900L,
            todayWalkSteps = 800,
            profile = UserProfile(stepGoal = 12000),
        )

        val merged = state.withWatchData(WatchData(watchSteps = 10))

        assertEquals("E8:E6:09:31:23:D8", merged.macAddress)
        assertEquals("deadbeef", merged.authKey)
        assertEquals(900L, merged.phoneSteps)
        assertEquals(800, merged.todayWalkSteps)
        assertEquals(12000, merged.profile.stepGoal)
        assertEquals(10, merged.watchSteps)
    }

    @Test
    fun `default watch data clears live telemetry but not the connection defaults`() {
        val state = WatchState(watchSteps = 500, heartRate = 80)

        val merged = state.withWatchData(WatchData())

        assertEquals(0, merged.watchSteps)
        assertEquals(0, merged.heartRate)
        assertEquals(ConnectionStatus.Disconnected, merged.connectionStatus)
        assertEquals(-1, merged.batteryLevel)
    }

    @Test
    fun `room derived health values are never clobbered by the merge`() {
        // spo2, stress and hrResting come from Room via refreshData() — a repository
        // emission must not reset them. Regression guard for the split above.
        val state = WatchState(spo2 = 97, stress = 34, hrResting = 58, lastSleep = null)

        val merged = state.withWatchData(WatchData())

        assertEquals(97, merged.spo2)
        assertEquals(34, merged.stress)
        assertEquals(58, merged.hrResting)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.repo.WatchDataTest"`
Expected: compilation failure — `Unresolved reference: WatchData`

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/pedometer/repo/WatchData.kt`:

```kotlin
package com.pedometer.repo

import com.pedometer.bt.ConnectionStatus
import com.pedometer.util.WatchAlarm
import com.pedometer.util.WatchReminder
import com.pedometer.vm.WatchState
import com.pedometer.watchface.WatchfaceInfo

/**
 * Everything the watch itself is the source of truth for.
 *
 * Owned by [WatchRepository], which outlives the Activity. The ViewModel merges it into
 * [WatchState] for the UI — see [withWatchData].
 *
 * Data that lands in Room (sleep, workouts, heart-rate history, daily health) is NOT here:
 * the repository writes it to the database and bumps [roomRevision], and the ViewModel
 * re-reads Room in response.
 */
data class WatchData(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val serialNumber: String = "",
    val firmware: String = "",
    val model: String = "",
    val batteryLevel: Int = -1,
    val batteryCharging: Boolean = false,
    val watchSteps: Int = 0,
    val watchCalories: Int = 0,
    val heartRate: Int = 0,
    val standingHours: Int = 0,
    val activeMinutes: Int = 0,
    val watchDistanceM: Int = 0,
    val findPhoneActive: Boolean = false,
    val watchfaces: List<WatchfaceInfo> = emptyList(),
    val uploadProgress: Int = -1,
    val alarms: List<WatchAlarm> = emptyList(),
    val reminders: List<WatchReminder> = emptyList(),
    /** Incremented whenever the repository writes health data to Room. */
    val roomRevision: Int = 0,
)

fun WatchState.withWatchData(d: WatchData): WatchState = copy(
    connectionStatus = d.connectionStatus,
    serialNumber = d.serialNumber,
    firmware = d.firmware,
    model = d.model,
    batteryLevel = d.batteryLevel,
    batteryCharging = d.batteryCharging,
    watchSteps = d.watchSteps,
    watchCalories = d.watchCalories,
    heartRate = d.heartRate,
    standingHours = d.standingHours,
    activeMinutes = d.activeMinutes,
    watchDistanceM = d.watchDistanceM,
    findPhoneActive = d.findPhoneActive,
    watchfaces = d.watchfaces,
    uploadProgress = d.uploadProgress,
    alarms = d.alarms,
    reminders = d.reminders,
)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.repo.WatchDataTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/repo/WatchData.kt app/src/test/java/com/pedometer/repo/WatchDataTest.kt
git commit -m "feat: WatchData snapshot + merge into WatchState"
```

---

### Task 4: WatchLink — transport ownership

Extract the socket/handler/auth lifecycle out of `WatchViewModel.connect()` (`WatchViewModel.kt:211-666`). `WatchLink` owns *only* transport: connect, authenticate, write, reconnect, disconnect. Watch services stay in the ViewModel for now and are re-pointed in Task 5, so this task must keep the app working.

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/WatchLink.kt`

- [ ] **Step 1: Write WatchLink**

Create `app/src/main/java/com/pedometer/bt/WatchLink.kt`:

```kotlin
package com.pedometer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.pedometer.auth.AuthService
import com.pedometer.proto.XiaomiProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Bluetooth transport to the watch: socket, auth handshake, protocol handler,
 * and the reconnect loop. Knows nothing about health data, UI or Room.
 *
 * Lifetime is the process, not the Activity — see [com.pedometer.repo.WatchRepository].
 */
@SuppressLint("MissingPermission")
class WatchLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    companion object {
        private const val TAG = "WatchLink"
    }

    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    /** Fired after a successful auth handshake, on the link's scope. */
    var onAuthenticated: (() -> Unit)? = null

    /** Every decoded protobuf command from the watch. */
    var onCommand: ((XiaomiProto.Command) -> Unit)? = null

    /** Raw activity-file bytes (Channel.Activity). */
    var onActivityData: ((ByteArray) -> Unit)? = null

    /** Fired when the transport drops, before any reconnect attempt. */
    var onDisconnected: (() -> Unit)? = null

    var protocolHandler: ProtocolHandler? = null
        private set

    private var spp: SppConnection? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    private var mac: String = ""
    private var authKey: String = ""

    val isConnected: Boolean get() = _status.value == ConnectionStatus.Connected

    fun connect(macAddress: String, key: String) {
        if (macAddress.isBlank() || key.isBlank()) {
            Log.w(TAG, "connect() ignored — mac or key missing")
            return
        }
        mac = macAddress
        authKey = key
        policy.resumeRetries()
        startConnectAttempt()
    }

    private fun startConnectAttempt() {
        if (_status.value != ConnectionStatus.Disconnected) {
            Log.i(TAG, "connect() ignored — already ${_status.value}")
            return
        }
        _status.value = ConnectionStatus.Connecting
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null) {
                    Log.e(TAG, "No Bluetooth adapter")
                    fail()
                    return@launch
                }
                val device = adapter.getRemoteDevice(mac)
                val auth = AuthService(authKey)

                val connection = SppConnection(
                    onData = { data -> protocolHandler?.onDataReceived(data) },
                    onDisconnected = { handleDrop() },
                )
                spp = connection

                val handler = ProtocolHandler(
                    authService = auth,
                    connection = { data -> connection.write(data) },
                    onAuthenticated = {
                        policy.onConnected()
                        _status.value = ConnectionStatus.Connected
                        onAuthenticated?.invoke()
                    },
                    onCommand = { cmd -> onCommand?.invoke(cmd) },
                )
                handler.onActivityData = { data -> onActivityData?.invoke(data) }
                protocolHandler = handler

                if (!connection.connect(device)) {
                    Log.w(TAG, "SPP failed for $mac")
                    fail()
                    return@launch
                }

                _status.value = ConnectionStatus.Authenticating
                handler.start()
                startWatchdog()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                fail()
            }
        }
    }

    /**
     * The old code could sit in Connecting/Authenticating forever, and because connect()
     * refuses to run unless the status is Disconnected, the Connect button became a no-op.
     * If auth has not completed in 30s, treat it as a failure.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            delay(30_000)
            if (_status.value == ConnectionStatus.Authenticating ||
                _status.value == ConnectionStatus.Connecting
            ) {
                Log.w(TAG, "Auth watchdog fired — giving up on this attempt")
                fail()
            }
        }
    }

    // NOTE on ordering in fail()/handleDrop()/disconnect(): the status is set to Disconnected
    // BEFORE the sockets are closed. Closing a socket makes SppConnection fire onDisconnected,
    // which re-enters handleDrop(); the early return below is what stops that from consuming a
    // second reconnect slot and invoking onDisconnected twice.

    private fun fail() {
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Disconnected
        closeSockets()
        onDisconnected?.invoke()
        scheduleReconnect()
    }

    private fun handleDrop() {
        if (_status.value == ConnectionStatus.Disconnected) return
        Log.i(TAG, "Transport dropped")
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Disconnected
        onDisconnected?.invoke()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delayMs = policy.nextDelayMs()
        if (delayMs == null) {
            Log.w(TAG, "Reconnect exhausted after ${policy.attempts} attempts")
            return
        }
        Log.i(TAG, "Reconnect attempt ${policy.attempts} in ${delayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (_status.value == ConnectionStatus.Disconnected) startConnectAttempt()
        }
    }

    /** Bluetooth came back on, or the user pulled to refresh — retry immediately. */
    fun retryNow() {
        reconnectJob?.cancel()
        policy.resumeRetries()
        if (_status.value == ConnectionStatus.Disconnected) startConnectAttempt()
    }

    fun send(cmd: XiaomiProto.Command) {
        val handler = protocolHandler
        if (handler == null || !isConnected) {
            Log.w(TAG, "send() dropped — not connected")
            return
        }
        handler.sendCommand(cmd)
    }

    /** User-initiated disconnect: no reconnect until [connect] or [retryNow] is called. */
    fun disconnect() {
        policy.suspendRetries()
        watchdogJob?.cancel(); watchdogJob = null
        reconnectJob?.cancel(); reconnectJob = null
        connectJob?.cancel(); connectJob = null
        val wasLive = _status.value != ConnectionStatus.Disconnected
        _status.value = ConnectionStatus.Disconnected
        closeSockets()
        if (wasLive) onDisconnected?.invoke()
    }

    private fun closeSockets() {
        try { spp?.disconnect() } catch (_: Exception) {}
        spp = null
        protocolHandler = null
    }
}
```

Note on the dropped BLE fallback: `WatchViewModel.kt:634-657` had a BLE path that could never work — it created a `BleConnection` but kept writing through the SPP writer (`// TODO: refactor to support dynamic writer swap`), and it blocked the thread for up to 15s. It is not carried over. Verified: `BleConnection` is referenced **only** by `WatchViewModel` — `BleDebugTool` and `DebugScreen` import nothing from `com.pedometer` and talk to the Android BLE API directly. So `bt/BleConnection.kt` becomes unused after Task 6 and is deleted there.

Known warts inherited from `SppConnection` (verified 2026-09-06, deliberately not fixed here — pre-existing, not introduced by this plan): the `serverSocket` in `connect()` is a local variable, so `disconnect()` cannot close it and `accept(30000)` keeps listening for up to 30s after teardown (a server-side accept on the stale socket would start a zombie readLoop feeding whatever `protocolHandler` currently is); and a full failed `connect()` blocks for up to ~24s (client attempt + channel-2 attempt) before returning false, so the 30s auth watchdog only covers the post-connect phase. Revisit if a zombie connection is ever observed in logs.

- [ ] **Step 2: Verify it compiles**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` (nothing uses `WatchLink` yet)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/WatchLink.kt
git commit -m "feat: WatchLink owns transport, auth and reconnect"
```

---

### Task 5: WatchRepository — services and command handling move in

This is the bulk relocation. The code is moved, not rewritten: same callbacks, same protobuf, same Room writes.

**Files:**
- Create: `app/src/main/java/com/pedometer/repo/WatchRepository.kt`
- Source of the moved code: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt`

**Mechanical substitutions to apply to every moved line:**

| In `WatchViewModel` | In `WatchRepository` |
|---|---|
| `viewModelScope` | `scope` |
| `getApplication<Application>()` / `getApplication()` | `context` |
| `_state.value = _state.value.copy(<watchField> = ...)` | `_data.value = _data.value.copy(<watchField> = ...)` |
| `_state.value.<watchField>` | `_data.value.<watchField>` |
| `protocolHandler?.sendCommand(cmd)` | `link.send(cmd)` |
| `_state.value = _state.value.copy(lastSleep = ...)` / `recentWorkouts = ...` | `bumpRoom()` (the ViewModel re-reads Room) |
| `Thread.sleep(n)` | `delay(n)` |

- [ ] **Step 1: Create the repository shell with its public API**

Create `app/src/main/java/com/pedometer/repo/WatchRepository.kt`:

```kotlin
package com.pedometer.repo

import android.content.Context
import android.util.Log
import com.pedometer.bt.ConnectionStatus
import com.pedometer.bt.WatchLink
import com.pedometer.data.StepDatabase
import com.pedometer.health.ActivitySync
import com.pedometer.health.HealthService
import com.pedometer.health.UserProfile
import com.pedometer.music.MusicService
import com.pedometer.notification.NotificationService
import com.pedometer.notification.WatchNotificationBridge
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import com.pedometer.util.AlarmService
import com.pedometer.util.CalendarService
import com.pedometer.util.ReminderService
import com.pedometer.util.UtilityService
import com.pedometer.util.WatchAlarm
import com.pedometer.util.WatchSettings
import com.pedometer.watchface.DataUploadService
import com.pedometer.watchface.WatchfaceService
import com.pedometer.weather.WeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of the watch connection and every watch-facing service.
 *
 * Created once in [com.pedometer.PedometerApp.onCreate] and kept alive by
 * [com.pedometer.service.WatchConnectionService]. Survives Activity death — this is the
 * whole point: notification forwarding, call forwarding and activity sync must keep
 * working with no UI on screen.
 */
class WatchRepository(private val context: Context) {

    companion object {
        private const val TAG = "WatchRepository"
        private const val PREFS_NAME = "pedometer_prefs"
        private const val KEY_AUTH = "auth_key"
        private const val KEY_MAC = "mac_address"

        @Volatile private var INSTANCE: WatchRepository? = null

        fun get(context: Context): WatchRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WatchRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    /** Internal, not private: PedometerApp launches the foreground-signal collector on it. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _data = MutableStateFlow(WatchData())
    val data: StateFlow<WatchData> = _data

    private val link = WatchLink(context, scope)

    // Watch-facing services — recreated on every successful connect.
    private var healthService: HealthService? = null
    private var musicService: MusicService? = null
    private var weatherService: WeatherService? = null
    private var notificationService: NotificationService? = null
    private var watchfaceService: WatchfaceService? = null
    private var activitySync: ActivitySync? = null
    private var dataUploadService: DataUploadService? = null
    private var utilityService: UtilityService? = null
    private var alarmService: AlarmService? = null
    private var calendarService: CalendarService? = null
    private var reminderService: ReminderService? = null

    private var weatherJob: Job? = null
    private var initJob: Job? = null
    private var lastHrSaveTime = 0L
    private var lastWeatherFetchTime = 0L
    private var profile: UserProfile = UserProfile.load(context)

    private val dao get() = StepDatabase.get(context).stepDao()

    init {
        link.onAuthenticated = { onAuthenticated() }
        link.onCommand = { cmd -> handleCommand(cmd) }
        link.onActivityData = { bytes -> activitySync?.handleRawData(bytes) }
        link.onDisconnected = { onLinkDropped() }
        scope.launch {
            link.status.collect { status ->
                _data.value = _data.value.copy(connectionStatus = status)
            }
        }
    }

    val hasCredentials: Boolean
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_MAC, "").isNullOrBlank() &&
                !prefs.getString(KEY_AUTH, "").isNullOrBlank()
        }

    fun connect() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        link.connect(
            prefs.getString(KEY_MAC, "") ?: "",
            prefs.getString(KEY_AUTH, "") ?: "",
        )
    }

    fun disconnect() {
        weatherJob?.cancel(); weatherJob = null
        initJob?.cancel(); initJob = null
        link.disconnect()
    }

    fun onProfileChanged(newProfile: UserProfile) {
        profile = newProfile
    }

    /** Tell the ViewModel that Room has new health data to re-read. */
    private fun bumpRoom() {
        _data.value = _data.value.copy(roomRevision = _data.value.roomRevision + 1)
    }

    private fun onLinkDropped() {
        WatchNotificationBridge.protocolHandler = null
        weatherJob?.cancel(); weatherJob = null
        initJob?.cancel(); initJob = null
        healthService?.stopRealtimeStats()
        healthService = null
        musicService = null
        weatherService = null
        notificationService = null
        watchfaceService = null
        activitySync = null
        dataUploadService = null
        utilityService = null
        alarmService = null
        calendarService = null
        reminderService = null
    }

    private fun onAuthenticated() {
        val handler = link.protocolHandler ?: return
        WatchNotificationBridge.protocolHandler = handler
        buildServices(handler)      // Step 2
        startPostAuthInit()         // Step 3
        startWeatherLoop()          // Step 4
    }
}
```

- [ ] **Step 2: Move service construction**

Add `private fun buildServices(handler: ProtocolHandler)` to `WatchRepository` and move `WatchViewModel.kt:382-632` into it verbatim — from `val health = HealthService(handler) { data ->` through `notificationService = notif` — applying the substitution table. Landmarks in order: `HealthService`, `MusicService`, `WeatherService`, `UtilityService`, `AlarmService`, `CalendarService`, `WatchSettings`, `ReminderService`, `ActivitySync`, `DataUploadService`, `WatchfaceService`, `NotificationService`.

Three call sites need more than a mechanical substitution:

1. GPS relay (`health.onGpsNeeded`, `WatchViewModel.kt:410-416`) — leave `startGpsRelay()`/`stopGpsRelay()` in the ViewModel for now; from the repository, expose them as callbacks.

   Known limit this leaves in place: GPS relay during a watch workout only runs while the Activity is alive, because the fused-location client lives in the ViewModel. That is not a regression — today the entire connection dies with the Activity — but it is the obvious follow-up once this refactor lands. Add:

```kotlin
    /** Set by the ViewModel; the watch asks for phone GPS during workouts. */
    var onGpsRelayNeeded: ((Boolean) -> Unit)? = null
```
and call `onGpsRelayNeeded?.invoke(needed)` inside `health.onGpsNeeded`. The ViewModel wires it in Task 6.

2. Sleep / workout / daily-summary Room writes (`WatchViewModel.kt:499-594`) — keep the `dao.upsert*` calls, and replace **every** `_state.value = _state.value.copy(...)` in this block with `bumpRoom()`. That includes the daily-summary block at `WatchViewModel.kt:541-548`, which currently pushes `watchCalories`, `watchDistanceM`, `activeMinutes`, `spo2`, `stress`, `hrResting` into the UI state directly. Keep the first three as `_data.value.copy(...)` (live telemetry, in `WatchData`); `spo2`/`stress`/`hrResting` are Room-only now — they land in `dao.upsertDailyHealth` a few lines below and reach the UI via `bumpRoom()` → `refreshData()`. Pushing them into `WatchData` too would overwrite the Room values with zeros on the next emission.

3. Watchface upload progress (`WatchViewModel.kt:598-607`) — `_data.value = _data.value.copy(uploadProgress = progress)`.

Delete the moved block from `WatchViewModel.kt` in the same edit; the ViewModel is repaired in Task 6, so **the project will not compile between Step 2 and Task 6 — that is expected, and there is no commit until it does.**

- [ ] **Step 3: Move the post-auth init sequence**

Add to `WatchRepository`:

```kotlin
    private fun startPostAuthInit() {
        initJob?.cancel()
        initJob = scope.launch {
            delay(500)
            Log.i(TAG, "POST-AUTH: initializing watch")
            link.send(CommandHelper.buildDeviceInfoRequest());      delay(200)
            link.send(CommandHelper.buildBatteryRequest());         delay(200)
            sendCurrentTime();                                      delay(200)
            sendUserInfo();                                         delay(200)
            sendLocale();                                           delay(200)
            healthService?.initialize();                            delay(300)
            if (com.pedometer.PedometerApp.isInForeground) {
                healthService?.startRealtimeStats()
            }
            alarmService?.getAlarms();                              delay(200)
            calendarService?.syncCalendar();                        delay(200)
            reminderService?.getReminders();                        delay(200)
            notificationService?.sendCannedMessages();              delay(500)
            try { fetchAndSendWeather() } catch (e: Exception) { Log.e(TAG, "Weather init failed", e) }
            Log.i(TAG, "POST-AUTH: init complete")
        }
    }
```

`watchSettings?.syncContacts()` is intentionally dropped from the automatic sequence — it reads the whole contact book on every connect and the user can trigger it from Watch Settings. Move `sendCurrentTime()`, `sendUserInfo()` and `fetchAndSendWeather()` from the ViewModel into the repository as private methods (search `private fun sendCurrentTime`, `private fun sendUserInfo`, `private fun fetchAndSendWeather` in `WatchViewModel.kt`), and add `sendLocale()` from the inline block at `WatchViewModel.kt:328-335`:

```kotlin
    private fun sendLocale() {
        link.send(
            XiaomiProto.Command.newBuilder()
                .setType(CommandHelper.TYPE_SYSTEM)
                .setSubtype(6)
                .setSystem(
                    XiaomiProto.System.newBuilder().setLanguage(
                        XiaomiProto.Language.newBuilder()
                            .setCode(java.util.Locale.getDefault().toLanguageTag().replace("-", "_").lowercase())
                    )
                )
                .build()
        )
    }
```

- [ ] **Step 4: Move the weather loop**

Add to `WatchRepository`:

```kotlin
    private fun startWeatherLoop() {
        weatherJob?.cancel()
        weatherJob = scope.launch {
            while (true) {
                delay(2 * 60 * 60 * 1000L)
                if (_data.value.connectionStatus != ConnectionStatus.Connected) break
                try { fetchAndSendWeather() } catch (e: Exception) { Log.e(TAG, "Weather refresh failed", e) }
            }
        }
    }
```

- [ ] **Step 5: Move handleCommand**

Move `private fun handleCommand(cmd: XiaomiProto.Command)` and every helper it calls (`handleSystemCommand`, the find-phone ringtone pair, etc. — grep `private fun handle` and `FindPhoneRingtone` in `WatchViewModel.kt`) into `WatchRepository`, applying the substitution table. `startFindPhoneRingtone`/`stopFindPhoneRingtone` use `android.media.RingtoneManager` with a `Context` — `context` works unchanged. `_state.value.copy(findPhoneActive = ...)` becomes `_data.value.copy(findPhoneActive = ...)`.

- [ ] **Step 6: Add the delegating command API**

Add to `WatchRepository` — these are the methods the ViewModel will forward to:

```kotlin
    fun getAlarms() { alarmService?.getAlarms() }
    // Signature mirrors WatchViewModel.createAlarm exactly — MainActivity calls it with two
    // arguments, the defaults must survive the move.
    fun createAlarm(hour: Int, minute: Int, repeatMode: Int = 0, repeatFlags: Int = 0) {
        alarmService?.createAlarm(hour, minute, repeatMode, repeatFlags)
    }
    fun editAlarm(alarm: WatchAlarm) { alarmService?.editAlarm(alarm) }
    fun deleteAlarm(alarmId: Int) { alarmService?.deleteAlarm(alarmId) }

    fun getReminders() { reminderService?.getReminders() }
    fun syncCalendar() { calendarService?.syncCalendar() }
    // WatchSettings is cheap and stateless — built per call rather than held, so it can never
    // outlive the handler it wraps.
    fun syncContacts() { watchSettings()?.syncContacts() }
    fun setDnd(enabled: Boolean) { watchSettings()?.setDnd(enabled) }
    fun setWearingMode(mode: Int) { watchSettings()?.setWearingMode(mode) }
    private fun watchSettings(): WatchSettings? =
        link.protocolHandler?.let { WatchSettings(it, context) }

    /**
     * The "watch" half of WatchViewModel.refreshData() (WatchViewModel.kt:826-841).
     * The ViewModel can no longer reach protocolHandler/activitySync/lastWeatherFetchTime,
     * so it calls this instead.
     */
    fun refreshFromWatch() {
        if (_data.value.connectionStatus != ConnectionStatus.Connected) return
        scope.launch {
            link.send(CommandHelper.buildBatteryRequest())
            link.send(CommandHelper.buildActivityFetchToday())
            delay(1000)
            activitySync?.requestPast()
            delay(500)
            val now = System.currentTimeMillis()
            if (now - lastWeatherFetchTime > 30 * 60 * 1000L) {
                try { fetchAndSendWeather() } catch (e: Exception) { Log.e(TAG, "Weather refresh failed", e) }
                lastWeatherFetchTime = now
            }
        }
    }

    fun requestWatchfaces() { watchfaceService?.requestWatchfaceList() }
    fun setActiveWatchface(id: String) { watchfaceService?.setActiveWatchface(id) }
    fun deleteWatchface(id: String) { watchfaceService?.deleteWatchface(id) }
    fun uploadWatchface(bytes: ByteArray) { dataUploadService?.uploadWatchface(bytes) }

    fun findWatch() {
        fun cmd(state: Int) = XiaomiProto.Command.newBuilder()
            .setType(CommandHelper.TYPE_SYSTEM)
            .setSubtype(18)
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(state))
            .build()
        link.send(cmd(0))
        scope.launch { delay(10_000); link.send(cmd(1)) }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        if (_data.value.connectionStatus != ConnectionStatus.Connected) return
        if (inForeground) healthService?.startRealtimeStats() else healthService?.stopRealtimeStats()
    }
```

Check the exact signatures of `AlarmService`, `WatchSettings`, `ReminderService` and `CalendarService` before writing these — copy the argument lists from the corresponding `WatchViewModel` methods you are deleting, and keep the names identical so Task 6 is a pure delegation.

- [ ] **Step 7: Commit once Task 6 compiles**

Tasks 5 and 6 land in one commit. Continue straight to Task 6.

---

### Task 6: WatchViewModel becomes an observer

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt`
- Modify: `app/src/main/java/com/pedometer/PedometerApp.kt`

- [ ] **Step 1: Turn the foreground callback into something two listeners can share**

`PedometerApp.onForegroundChanged` is a single-slot `var`; the repository and the ViewModel both need it. Replace it with a flow. In `PedometerApp.kt`:

```kotlin
class PedometerApp : Application() {
    companion object {
        private val _foreground = MutableStateFlow(false)
        val foreground: StateFlow<Boolean> = _foreground
        val isInForeground: Boolean get() = _foreground.value

        lateinit var repository: WatchRepository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        repository = WatchRepository.get(this)
        PhoneCallReceiver.registerTelephonyCallback(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _foreground.value = true
                Log.i("PedometerApp", "App → FOREGROUND")
            }

            override fun onStop(owner: LifecycleOwner) {
                _foreground.value = false
                Log.i("PedometerApp", "App → BACKGROUND")
            }
        })
        repository.scope.launch { PedometerApp.foreground.collect { repository.onForegroundChanged(it) } }
    }
}
```

Add the imports `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.launch`, `com.pedometer.repo.WatchRepository`. `repository.scope` is already `internal` from Task 5.

Kotlin 2.0.21 is in use, so `data object` in `CallAction` (Task 11) and `lateinit var` with `private set` in a companion both compile.

- [ ] **Step 2: Delete what moved and wire the repository into the ViewModel**

In `WatchViewModel.kt`, delete the fields `bleConnection`, `sppConnection`, `protocolHandler`, `authService`, `healthService`, `musicService`, `weatherService`, `notificationService`, `watchfaceService`, `activitySync`, `dataUploadService`, `utilityService`, `alarmService`, `calendarService`, `reminderService`, `watchSettings`, `autoReconnectEnabled`, `reconnectAttempts`, `maxReconnectAttempts`, `lastHrSaveTime`, `weatherJob`, `lastWeatherFetchTime`, and the whole `connect()` body, `cleanupServices()`, `disconnect()` body, `handleCommand` and the find-phone helpers.

Add:

```kotlin
    private val repo = PedometerApp.repository

    init {
        // ... existing prefs / profile / calendar init stays ...

        viewModelScope.launch {
            repo.data.collect { data ->
                _state.value = _state.value.withWatchData(data)
            }
        }
        var lastRevision = 0
        viewModelScope.launch {
            repo.data.collect { data ->
                if (data.roomRevision != lastRevision) {
                    lastRevision = data.roomRevision
                    refreshData()
                }
            }
        }
        repo.onGpsRelayNeeded = { needed -> if (needed) startGpsRelay() else stopGpsRelay() }

        viewModelScope.launch {
            PedometerApp.foreground.collect { inForeground ->
                if (inForeground) onAppForeground() else onAppBackground()
            }
        }
    }
```

Add `import com.pedometer.repo.withWatchData`.

Replace the public watch methods with delegation:

```kotlin
    fun connect() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, WatchConnectionService::class.java))
    }

    fun disconnect() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, WatchConnectionService::class.java).setAction(WatchConnectionService.ACTION_DISCONNECT)
        )
    }

    fun getAlarms() = repo.getAlarms()
    fun createAlarm(hour: Int, minute: Int) = repo.createAlarm(hour, minute)
    fun editAlarm(alarm: WatchAlarm) = repo.editAlarm(alarm)
    fun deleteAlarm(id: Int) = repo.deleteAlarm(id)
    fun requestWatchfaces() = repo.requestWatchfaces()
    fun setActiveWatchface(id: String) = repo.setActiveWatchface(id)
    fun deleteWatchface(id: String) = repo.deleteWatchface(id)
    fun uploadWatchface(data: ByteArray) = repo.uploadWatchface(data)
    fun findWatch() = repo.findWatch()
    fun setDnd(enabled: Boolean) = repo.setDnd(enabled)
    fun setWearingMode(mode: Int) = repo.setWearingMode(mode)
    fun syncContacts() = repo.syncContacts()
```

Add the imports `android.content.Intent` and `com.pedometer.service.WatchConnectionService` for the two methods above.

Keep in the ViewModel: `updateProfile` (add `repo.onProfileChanged(profile)`), `updateAuthKey`, `updateMacAddress`, `refreshData`, `startStepPolling`/`stopStepPolling`, `onAppForeground`/`onAppBackground`, `startGpsRelay`/`stopGpsRelay`, the calendar-event CRUD (`createCalendarEvent`, `updateCalendarEvent`, `deleteCalendarEvent`, `refreshCalendarEvents`), and the phone step counter.

**Delete outright — verified unused by any UI file:** `startBreathing()` (line 846), `stopFindPhoneRingtone()` (line 707), `createReminder()` (line 856), `deleteReminderById()` (line 859). The only ViewModel members the UI touches are `state`, `refreshData`, `updateAuthKey`, `updateMacAddress`, `updateProfile`, `connect`, `disconnect`, `getAlarms`, `createAlarm`, `editAlarm`, `deleteAlarm`, `requestWatchfaces`, `setActiveWatchface`, `deleteWatchface`, `uploadWatchface`, `findWatch`, `setDnd`, `setWearingMode`, `syncContacts`, and the four calendar methods. Keep exactly that set public and nothing else; `stopFindPhoneRingtone` moves into the repository as a private helper of the find-phone timer.

Delete the auto-connect block at `WatchViewModel.kt:143-152` — the service owns that now (Task 7).

- [ ] **Step 2b: Repair refreshData**

`refreshData()` ends with a "watch data" section (`WatchViewModel.kt:826-841`) that uses `protocolHandler`, `activitySync` and `lastWeatherFetchTime` — all three are gone. Replace that whole `if (_state.value.connectionStatus == ConnectionStatus.Connected) { ... }` block with a single call:

```kotlin
                repo.refreshFromWatch()
```

Everything above it (StepProvider, Room reads, Health Connect) stays exactly as it is — including the `spo2`/`stress`/`hrResting`/`lastSleep`/`recentWorkouts`/`hrHistory` assignments, which are now the only writers of those fields.

- [ ] **Step 2c: Delete the dead BLE transport**

```bash
git rm app/src/main/java/com/pedometer/bt/BleConnection.kt
```

Verified unused after Step 2: the only references were `WatchViewModel.kt:12,111,636`, all of which this task removes. `BleDebugTool` does not use it.

- [ ] **Step 3: Fix onCleared — it must not kill the connection**

Replace the whole method with:

```kotlin
    override fun onCleared() {
        stopGpsRelay()
        stopStepPolling()
        phoneStepCounter.stop()
        repo.onGpsRelayNeeded = null
        // Deliberately NOT disconnecting: the connection belongs to WatchConnectionService.
    }
```

- [ ] **Step 4: Verify it compiles and the existing tests still pass**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; 6 + 3 + 16 tests pass

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/pedometer
git commit -m "refactor: WatchRepository owns the connection, ViewModel observes it"
```

---

### Task 7: WatchConnectionService drives the connection

**Files:**
- Modify: `app/src/main/java/com/pedometer/service/WatchConnectionService.kt`

- [ ] **Step 1: Rewrite the service**

Replace the file contents with:

```kotlin
package com.pedometer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedometer.MainActivity
import com.pedometer.PedometerApp
import com.pedometer.bt.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive so [com.pedometer.repo.WatchRepository] can hold the watch
 * connection with no Activity on screen, and mirrors the connection status into the
 * ongoing notification.
 */
class WatchConnectionService : Service() {
    companion object {
        private const val TAG = "WatchConnectionService"
        private const val CHANNEL_ID = "pedometer_connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.pedometer.action.DISCONNECT"
    }

    inner class LocalBinder : Binder() {
        val service: WatchConnectionService get() = this@WatchConnectionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo get() = PedometerApp.repository

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch {
            repo.data.collect { data ->
                updateNotification(statusText(data.connectionStatus))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(statusText(repo.data.value.connectionStatus)),
            foregroundTypes(),
        )

        if (intent?.action == ACTION_DISCONNECT) {
            // Disconnect the watch but KEEP the service alive: it also hosts StepCollector
            // (Task 10), and the app is a pedometer with or without a watch.
            Log.i(TAG, "Manual disconnect")
            repo.disconnect()
            return START_STICKY
        }

        if (repo.hasCredentials) {
            Log.i(TAG, "Starting watch connection")
            repo.connect()
        } else {
            Log.w(TAG, "No credentials saved — service idles")
        }
        return START_STICKY
    }

    /**
     * Android 14+ throws SecurityException from startForeground() if a declared type's backing
     * runtime permission is not granted: LOCATION needs ACCESS_FINE_LOCATION, HEALTH needs one
     * of ACTIVITY_RECOGNITION / BODY_SENSORS / HIGH_SAMPLING_RATE_SENSORS. The user can deny
     * either one in Settings, so the mask is built from what is actually granted.
     * CONNECTED_DEVICE has no permission requirement and is always safe.
     */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun statusText(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.Connected -> "Часы подключены"
        ConnectionStatus.Connecting -> "Подключение..."
        ConnectionStatus.Authenticating -> "Авторизация..."
        ConnectionStatus.Disconnected -> "Часы отключены"
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Шагомер")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Watch Connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Start the service on app launch**

In `MainActivity.onCreate`, after the `MediaListenerService` component toggle, add:

```kotlin
        if (PedometerApp.repository.hasCredentials) {
            startForegroundService(Intent(this, WatchConnectionService::class.java))
        }
```

with imports `android.content.Intent`, `com.pedometer.PedometerApp`, `com.pedometer.service.WatchConnectionService`.

- [ ] **Step 3: Add the health foreground-service type to the manifest**

`FOREGROUND_SERVICE_HEALTH` is already declared as a permission. The service currently declares `android:foregroundServiceType="connectedDevice|location"` (AndroidManifest.xml:69-70). Widen it:

```xml
        <service
            android:name=".service.WatchConnectionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice|health|location" />
```

Declaring a type in the manifest is not what throws — only passing its flag to `startForeground()` without the backing runtime permission is, which `foregroundTypes()` above guards. The manifest must list every type the code can pass, so all three go here.

- [ ] **Step 4: Verify it compiles**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Verify on the device — this is the point of the whole phase**

```bash
unset LD_PRELOAD
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# open the app, wait for "Часы подключены" in the notification shade
adb shell am force-stop com.pedometer   # simulate the user swiping the app away is not enough — see note
```

Do NOT use `force-stop` to verify (it kills the process by design). Instead: open the app, swipe it out of Recents, then send a test notification and confirm the watch shows it:

```bash
adb shell "am broadcast -n com.pedometer/.service.CommandReceiver -a com.pedometer.NOTIFY --es title 'Фон' --es body 'После свайпа' --es app 'ADB'"
adb logcat -d -s WatchRepository:* WatchLink:* | tail -20
```
Expected: the notification appears on the watch; log shows no `Transport dropped` around the swipe.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/java/com/pedometer app/src/main/AndroidManifest.xml
git commit -m "feat: WatchConnectionService owns connection lifecycle, survives Activity death"
```

---

### Task 8: BootReceiver actually connects

**Files:**
- Modify: `app/src/main/java/com/pedometer/service/BootReceiver.kt`

- [ ] **Step 1: Use the repository's credential check**

Replace the body of `onReceive` with:

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i("BootReceiver", "Received ${intent.action}")
        if (!WatchRepository.get(context).hasCredentials) {
            Log.i("BootReceiver", "No credentials — not starting service")
            return
        }
        try {
            context.startForegroundService(Intent(context, WatchConnectionService::class.java))
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start WatchConnectionService: ${e.message}")
        }
    }
```

Replace the `com.pedometer.health.UserProfile` import with `com.pedometer.repo.WatchRepository`.

- [ ] **Step 2: Verify on the device**

```bash
unset LD_PRELOAD
adb reboot
# wait ~60s for boot to finish
adb logcat -d -s BootReceiver:* WatchLink:* WatchRepository:* | tail -30
```
Expected: `BootReceiver: Received android.intent.action.BOOT_COMPLETED`, then `WatchLink` progressing to `POST-AUTH: init complete` **without the app having been opened**.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/service/BootReceiver.kt
git commit -m "fix: BootReceiver starts a service that actually connects"
```

---

## Phase 2 — Data safety and background steps

### Task 9: Stop the database from being wipeable (TDD)

`StepDatabase.kt:33-34` calls `.addMigrations(MIGRATION_7_8)` — a no-op migration to a version that does not exist — and then `.fallbackToDestructiveMigration()`, which silently deletes every table on the next schema change. The database holds real step, sleep and workout history.

**Files:**
- Create: `app/src/main/java/com/pedometer/data/MigrationCoverage.kt`
- Modify: `app/src/main/java/com/pedometer/data/StepDatabase.kt`
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore` (no change needed — verify `app/schemas` is not ignored)
- Test: `app/src/test/java/com/pedometer/data/MigrationCoverageTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pedometer/data/MigrationCoverageTest.kt`:

```kotlin
package com.pedometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationCoverageTest {

    @Test
    fun `no gaps when every step is covered`() {
        val steps = listOf(7 to 8, 8 to 9)
        assertEquals(emptyList<Int>(), missingMigrationSteps(steps, from = 7, to = 9))
    }

    @Test
    fun `reports the version that has no migration out of it`() {
        val steps = listOf(7 to 8)
        assertEquals(listOf(8), missingMigrationSteps(steps, from = 7, to = 9))
    }

    @Test
    fun `reports every missing step`() {
        assertEquals(listOf(7, 8), missingMigrationSteps(emptyList(), from = 7, to = 9))
    }

    @Test
    fun `multi version migrations cover the range they span`() {
        val steps = listOf(7 to 9)
        assertEquals(emptyList<Int>(), missingMigrationSteps(steps, from = 7, to = 9))
    }

    @Test
    fun `same version needs no migrations`() {
        assertEquals(emptyList<Int>(), missingMigrationSteps(emptyList(), from = 7, to = 7))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.data.MigrationCoverageTest"`
Expected: compilation failure — `Unresolved reference: missingMigrationSteps`

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/pedometer/data/MigrationCoverage.kt`:

```kotlin
package com.pedometer.data

/**
 * Returns every version step in [from, to) that no migration spans — i.e. the points where
 * an upgrading user's database would be destroyed if destructive fallback were on.
 *
 * A migration (start, end) covers every step v where start <= v < end, so a single 7→9
 * migration covers both 7 and 8.
 *
 * Pure function so it can be unit-tested without Room or a device.
 */
fun missingMigrationSteps(
    steps: List<Pair<Int, Int>>,
    from: Int,
    to: Int,
): List<Int> = (from until to).filter { version ->
    steps.none { (start, end) -> start <= version && version < end }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.data.MigrationCoverageTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests pass

- [ ] **Step 5: Enable Room schema export**

In `app/build.gradle.kts`, inside the `android { ... }` block after `kotlinOptions`, add:

```kotlin
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
```

`ksp { }` at the module level requires the KSP plugin, which is already applied. If Gradle rejects `ksp { }` inside `android { }`, move it to the top level of the file, after the `android { }` block.

- [ ] **Step 6: Remove the destructive fallback and the fake migration**

Replace `app/src/main/java/com/pedometer/data/StepDatabase.kt` with:

```kotlin
package com.pedometer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [DailySteps::class, HourlySteps::class, StepSnapshot::class, HeartRateRecord::class, DailyHealth::class, SleepRecord::class, WorkoutRecord::class, GpsPointRecord::class],
    version = 7, // keep in sync with VERSION below
    exportSchema = true,
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        /** Mirror of the @Database version. Room needs a literal in the annotation. */
        const val VERSION = 7

        /** Oldest schema version any installed build can still be sitting on. */
        const val OLDEST_SUPPORTED = 7

        /**
         * Every schema change gets a Migration here and a version bump. There is deliberately
         * no destructive fallback: this database holds the only copy of the user's history.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var INSTANCE: StepDatabase? = null

        fun get(context: Context): StepDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): StepDatabase {
            val gaps = missingMigrationSteps(
                MIGRATIONS.map { it.startVersion to it.endVersion },
                from = OLDEST_SUPPORTED,
                to = VERSION,
            )
            // Fails loudly the moment someone bumps the version without writing the migration,
            // instead of Room silently dropping every table at runtime.
            check(gaps.isEmpty()) { "Missing Room migrations for versions: $gaps" }

            return Room.databaseBuilder(
                context.applicationContext,
                StepDatabase::class.java,
                "pedometer.db",
            ).addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
```

Caveat to accept knowingly: an install still sitting on a schema older than 7 will now throw `IllegalStateException: A migration from N to 7 was required but not found` instead of silently wiping. The developer's phone is on 7 (the current shipped version), and a loud failure is the correct trade — the old behaviour destroyed data without asking. If an older install turns up, write the missing migration and raise nothing.

- [ ] **Step 7: Verify the schema is exported and the app still opens the database**

```bash
unset LD_PRELOAD
./gradlew assembleDebug
ls app/schemas/com.pedometer.data.StepDatabase/7.json
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# open the app, go to Активность and confirm the 30-day chart still has history
adb logcat -d | grep -i "sqlite\|Room" | tail -20
```
Expected: `7.json` exists; no `IllegalStateException: Migration didn't properly handle` in the log; the chart still shows past days.

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts app/schemas app/src/main/java/com/pedometer/data app/src/test/java/com/pedometer/data
git commit -m "fix: remove destructive Room fallback, export schema, add migration coverage check"
```

---

### Task 10: Background step counting (TDD)

`StepCounterService` was deleted in `6284dc2`, and the step sensor now only runs while the app is on screen. Hourly buckets are only ever written from watch data (`WatchViewModel.kt:485-498`), so the hourly chart is empty for anyone whose watch is not syncing. Put the sensor back in the foreground service that already exists.

**Files:**
- Create: `app/src/main/java/com/pedometer/health/HourBucketAccumulator.kt`
- Create: `app/src/main/java/com/pedometer/health/StepCollector.kt`
- Modify: `app/src/main/java/com/pedometer/service/WatchConnectionService.kt`
- Test: `app/src/test/java/com/pedometer/health/HourBucketAccumulatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pedometer/health/HourBucketAccumulatorTest.kt`:

```kotlin
package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HourBucketAccumulatorTest {

    private data class Flushed(val date: String, val hour: Int, val steps: Int)

    @Test
    fun `events in the same hour accumulate without flushing`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)

        assertEquals(emptyList<Flushed>(), out)
    }

    @Test
    fun `crossing into a new hour flushes the previous bucket`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 11)

        assertEquals(listOf(Flushed("2026-08-09", 10, 2)), out)
    }

    @Test
    fun `crossing midnight flushes the previous day`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 23)
        acc.add("2026-08-10", 0)

        assertEquals(listOf(Flushed("2026-08-09", 23, 1)), out)
    }

    @Test
    fun `explicit flush emits and resets`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.flush()
        acc.flush()

        assertEquals(listOf(Flushed("2026-08-09", 10, 1)), out)
    }

    @Test
    fun `flush with nothing pending emits nothing`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.flush()

        assertEquals(emptyList<Flushed>(), out)
    }

    @Test
    fun `flushEvery forces a flush once the threshold is reached`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator(flushEvery = 2) { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)

        assertEquals(listOf(Flushed("2026-08-09", 10, 2)), out)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.health.HourBucketAccumulatorTest"`
Expected: compilation failure — `Unresolved reference: HourBucketAccumulator`

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/pedometer/health/HourBucketAccumulator.kt`:

```kotlin
package com.pedometer.health

/**
 * Buckets step-detector events into (date, hour) counts, flushing when the hour rolls over,
 * when [flushEvery] events have piled up, or on demand. Pure — the caller supplies the clock
 * and does the persisting.
 */
class HourBucketAccumulator(
    private val flushEvery: Int = 25,
    private val onFlush: (date: String, hour: Int, steps: Int) -> Unit,
) {
    private var date: String? = null
    private var hour: Int = -1
    private var count: Int = 0

    fun add(eventDate: String, eventHour: Int, steps: Int = 1) {
        if (date != null && (eventDate != date || eventHour != hour)) flush()
        date = eventDate
        hour = eventHour
        count += steps
        if (count >= flushEvery) flush()
    }

    fun flush() {
        val d = date
        if (d != null && count > 0) onFlush(d, hour, count)
        count = 0
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.health.HourBucketAccumulatorTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests pass

- [ ] **Step 5: Write StepCollector**

Create `app/src/main/java/com/pedometer/health/StepCollector.kt`:

```kotlin
package com.pedometer.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.pedometer.data.HourlySteps
import com.pedometer.data.StepDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Listens to TYPE_STEP_DETECTOR while the foreground service is alive and writes hourly
 * step buckets to Room. This is the only source of hourly data when no watch is syncing.
 */
class StepCollector(
    context: Context,
    private val scope: CoroutineScope,
    /** When false, events are dropped: watch hourly data is the source of truth
     *  (dao.upsertHourly REPLACES the row, WatchViewModel.kt:491), and phone increments
     *  would sit on top of the watch value until the next sync — double counting. */
    private val collectEnabled: () -> Boolean = { true },
) : SensorEventListener {

    companion object {
        private const val TAG = "StepCollector"
    }

    private val appContext = context.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var listening = false

    private val accumulator = HourBucketAccumulator { date, hour, steps ->
        scope.launch {
            try {
                val dao = StepDatabase.get(appContext).stepDao()
                // incrementHourly is an UPDATE: the row has to exist first.
                dao.insertHourly(HourlySteps(date = date, hour = hour, steps = 0))
                dao.incrementHourly(date, hour, steps)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist hourly steps", e)
            }
        }
    }

    fun start() {
        if (detector == null) {
            Log.w(TAG, "No TYPE_STEP_DETECTOR on this device")
            return
        }
        if (listening) return
        sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL)
        listening = true
        Log.i(TAG, "Step collector started")
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
        accumulator.flush()
        Log.i(TAG, "Step collector stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
        if (!collectEnabled()) return
        val now = LocalDateTime.now()
        accumulator.add(now.toLocalDate().toString(), now.hour, event.values[0].toInt().coerceAtLeast(1))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
```

- [ ] **Step 6: Host it in the foreground service**

In `WatchConnectionService`, add the field and lifecycle calls:

```kotlin
    // Phone detector only counts while the watch is NOT syncing — see the
    // double-counting note on StepCollector.collectEnabled.
    private val stepCollector by lazy {
        StepCollector(
            this,
            collectEnabled = {
                PedometerApp.repository.data.value.connectionStatus != ConnectionStatus.Connected
            },
        )
    }
```

`StepCollector` owns its own `Dispatchers.IO` scope rather than borrowing the service's: `stop()` flushes the pending bucket by launching a coroutine, and the service's scope is cancelled in `onDestroy`, which would swallow that final write. Change the class signature from Step 5 accordingly — drop the `scope` parameter, keep `collectEnabled`:

```kotlin
class StepCollector(
    context: Context,
    private val collectEnabled: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Known limit, accept it: if the process is killed outright the pending bucket (at most `flushEvery - 1` = 24 steps) is lost. Lowering `flushEvery` trades database writes for precision.

Call `stepCollector.start()` at the end of `onCreate()` and `stepCollector.stop()` at the start of `onDestroy()`. Add `import com.pedometer.health.StepCollector`.

- [ ] **Step 7: Verify on the device**

```bash
unset LD_PRELOAD
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# open the app so the service starts, then lock the screen and walk ~50 steps
adb logcat -d -s StepCollector:* | tail
```
Expected: `Step collector started`; after walking, open the app → Здоровье → tap today → the hourly chart shows a bar for the current hour.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/pedometer/health app/src/main/java/com/pedometer/service/WatchConnectionService.kt app/src/test/java/com/pedometer/health
git commit -m "feat: background hourly step collection in the foreground service"
```

---

## Phase 3 — Incoming-call regression

### Task 11: CallRouter (TDD)

The current arbitration is a `@Volatile` boolean plus a 2s `Handler` timer split across `PhoneCallReceiver` and `MediaListenerService`, and contact-name lookup was deleted, so the fallback path shows a bare number or nothing.

**Files:**
- Create: `app/src/main/java/com/pedometer/service/CallRouter.kt`
- Test: `app/src/test/java/com/pedometer/service/CallRouterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pedometer/service/CallRouterTest.kt`:

```kotlin
package com.pedometer.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CallRouterTest {

    @Test
    fun `dialer notification within the window wins`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        assertEquals(CallAction.None, router.onRinging(nowMs = 0))
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", "Входящий вызов"),
        )
        assertEquals(CallAction.None, router.onTick(nowMs = 5_000, fallbackTitle = "+79990000000"))
    }

    @Test
    fun `fallback fires when no dialer notification arrives`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        assertEquals(CallAction.None, router.onTick(nowMs = 1_000, fallbackTitle = "+79990000000"))
        assertEquals(
            CallAction.Show("+79990000000", "Входящий вызов"),
            router.onTick(nowMs = 2_000, fallbackTitle = "+79990000000"),
        )
    }

    @Test
    fun `only one show per call`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        assertEquals(CallAction.None, router.onDialerNotification("Мама", null))
    }

    @Test
    fun `idle dismisses only if something was shown`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        assertEquals(CallAction.None, router.onIdle())

        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        assertEquals(CallAction.Dismiss, router.onIdle())
        assertEquals(CallAction.None, router.onIdle())
    }

    @Test
    fun `a dialer notification with no ringing state still shows the call`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", "Входящий вызов"),
        )
    }

    @Test
    fun `blank body falls back to the default subtitle`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", "   "),
        )
    }

    @Test
    fun `a new call after idle can show again`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        router.onIdle()

        router.onRinging(nowMs = 10_000)
        assertEquals(
            CallAction.Show("Папа", "Входящий вызов"),
            router.onDialerNotification("Папа", null),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.service.CallRouterTest"`
Expected: compilation failure — `Unresolved reference: CallRouter`

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/pedometer/service/CallRouter.kt`:

```kotlin
package com.pedometer.service

/** What the watch should be told, decided by [CallRouter]. */
sealed interface CallAction {
    data class Show(val title: String, val body: String) : CallAction
    data object Dismiss : CallAction
    data object None : CallAction
}

/**
 * Arbitrates between two racing sources of "there is an incoming call":
 * telephony state (fast, but only has a number) and the dialer's notification
 * (slower, but carries the resolved contact name).
 *
 * Pure: the caller supplies the clock and performs the sending.
 */
class CallRouter(private val fallbackDelayMs: Long = 2_000L) {

    private companion object {
        const val DEFAULT_BODY = "Входящий вызов"
    }

    private var ringingAtMs: Long? = null
    private var shown = false

    fun onRinging(nowMs: Long): CallAction {
        ringingAtMs = nowMs
        shown = false
        return CallAction.None
    }

    fun onDialerNotification(title: String, text: String?): CallAction {
        if (shown) return CallAction.None
        if (title.isBlank()) return CallAction.None
        shown = true
        val body = text?.takeIf { it.isNotBlank() } ?: DEFAULT_BODY
        return CallAction.Show(title, body)
    }

    /** Called periodically while ringing; emits the fallback once the window expires. */
    fun onTick(nowMs: Long, fallbackTitle: String): CallAction {
        if (shown) return CallAction.None
        val started = ringingAtMs ?: return CallAction.None
        if (nowMs - started < fallbackDelayMs) return CallAction.None
        shown = true
        return CallAction.Show(fallbackTitle, DEFAULT_BODY)
    }

    fun onIdle(): CallAction {
        val wasShown = shown
        shown = false
        ringingAtMs = null
        return if (wasShown) CallAction.Dismiss else CallAction.None
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.service.CallRouterTest"`
Expected: `BUILD SUCCESSFUL`, 7 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/service/CallRouter.kt app/src/test/java/com/pedometer/service/CallRouterTest.kt
git commit -m "feat: CallRouter arbitrates telephony state and dialer notifications"
```

---

### Task 12: Wire CallRouter in and restore contact names

**Files:**
- Modify: `app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt`
- Modify: `app/src/main/java/com/pedometer/music/MediaListenerService.kt`

- [ ] **Step 1: Rewrite PhoneCallReceiver's companion to use the router**

Replace the `companion object` body in `PhoneCallReceiver.kt` with:

```kotlin
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
            lastState = state
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val startedAt = System.currentTimeMillis()
                    apply(router.onRinging(startedAt))
                    fallbackRunnable?.let { handler.removeCallbacks(it) }
                    val runnable = Runnable {
                        val fallback = resolveContactName(context, savedNumber)
                            ?: savedNumber
                            ?: "Неизвестный"
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
```

Re-add the imports removed in Task 0:

```kotlin
import android.net.Uri
import android.provider.ContactsContract
```

Keep the existing `onReceive` (it feeds `savedNumber` and calls `handleStateChange` on API < 31) — but it must set `savedNumber` **before** calling `handleStateChange`, which it already does.

- [ ] **Step 2: Feed the router from MediaListenerService**

In `MediaListenerService.onNotificationPosted`, replace the whole call-handling block (the `if (isCall || pkg.contains("incall") || ...)` branch) with:

```kotlin
        if (isCall || pkg in PHONE_CALL_PACKAGES) {
            val extras = notification.extras
            val callerName = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val callerText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (!callerName.isNullOrBlank()) {
                PhoneCallReceiver.apply(PhoneCallReceiver.router.onDialerNotification(callerName, callerText))
                return
            }
        }
```

and in `onNotificationRemoved`:

```kotlin
        if (notification.category == Notification.CATEGORY_CALL) {
            PhoneCallReceiver.apply(PhoneCallReceiver.router.onIdle())
        }
```

Delete the `callHandledByListener` references. Restore `PHONE_CALL_PACKAGES` as the membership test instead of the `pkg.contains(...)` chain — substring matching on `"contacts"` also catches unrelated packages.

**Before switching, the set must be extended — the current one misses the ColorOS dialer and this is the exact cause of "number instead of contact name":** the dialer on the OnePlus Ace 5 Ultra (ColorOS 16) is ODialer, package **`com.oplus.dialer`** (confirmed via Play Store listing). Only `com.oplus.incallui` is in the set today (`MediaListenerService.kt:18-26`); today's `contains("dialer")` chain catches the dialer by accident, exact membership would not. Add to `PHONE_CALL_PACKAGES`:

```kotlin
            "com.oplus.dialer",
            "com.oplus.contacts",
```

Belt-and-braces once ADB is available: `adb shell pm list packages | grep -iE 'dial|incall'` and confirm the actual dialer package on the device.

- [ ] **Step 3: Verify on the device**

```bash
unset LD_PRELOAD
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# have someone call the phone from a number saved in Contacts
adb logcat -d -s PhoneCallReceiver:* WatchNotificationBridge:* | tail -20
```
Expected: the watch shows the **contact name** (not the number), exactly one call screen appears, and it clears when the call ends. Repeat with a number not in Contacts: expect the bare number, still exactly once.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pedometer/service/PhoneCallReceiver.kt app/src/main/java/com/pedometer/music/MediaListenerService.kt
git commit -m "fix: restore contact names on incoming calls, single-source call routing"
```

---

## Phase 4 — Release hygiene and documentation

### Task 13: Real release signing

`app/build.gradle.kts:27` signs release builds with the debug keystore, so a release APK can never be upgraded in place.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore`
- Create (locally, never committed): `keystore.properties`, `pedometer-release.jks`

- [ ] **Step 1: Generate the keystore**

Run (interactive — it prompts for passwords and a name):

```bash
keytool -genkeypair -v -keystore pedometer-release.jks -alias pedometer \
  -keyalg RSA -keysize 4096 -validity 10000
```

- [ ] **Step 2: Create keystore.properties**

Create `keystore.properties` in the repo root with the values just chosen:

```properties
storeFile=../pedometer-release.jks
storePassword=CHANGE_ME
keyAlias=pedometer
keyPassword=CHANGE_ME
```

- [ ] **Step 3: Keep both files out of git**

Append to `.gitignore`:

```
keystore.properties
*.jks
```

- [ ] **Step 4: Wire it into Gradle**

In `app/build.gradle.kts`, above the `android { }` block:

```kotlin
import java.util.Properties

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}
```

Inside `android { }`, before `buildTypes`:

```kotlin
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
```

And in `buildTypes.release`, replace `signingConfig = signingConfigs.getByName("debug")` with:

```kotlin
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release") else null
```

- [ ] **Step 5: Verify the release build is signed with the new key**

```bash
unset LD_PRELOAD
./gradlew assembleRelease
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk | head -5
```
Expected: `BUILD SUCCESSFUL`, and the certificate owner is the name entered in Step 1 — **not** `CN=Android Debug`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts .gitignore
git commit -m "build: sign release with a real keystore"
```

---

### Task 14: Keep the debug tools out of release builds

`DebugScreen` + `BleDebugTool` (419 lines, raw BLE writes) ship in release and are reachable from Settings.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt`
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt`

- [ ] **Step 1: Enable BuildConfig generation**

In `app/build.gradle.kts`, extend the `buildFeatures` block:

```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

- [ ] **Step 2: Hide the entry point in release**

In `SettingsTab.kt`, wrap the debug button (currently `OutlinedButton(onClick = onOpenDebug, ...) { Text("Отладка") }`):

```kotlin
        if (com.pedometer.BuildConfig.DEBUG) {
            OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) { Text("Отладка") }
        }
```

- [ ] **Step 3: Guard the screen itself**

In `MainActivity.kt`, change the debug branch condition:

```kotlin
                if (showDebug && BuildConfig.DEBUG) {
```

with `import com.pedometer.BuildConfig`.

- [ ] **Step 4: Verify both variants build**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug assembleRelease`
Expected: `BUILD SUCCESSFUL`. Install the release APK and confirm Settings has no "Отладка" button.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/pedometer/MainActivity.kt app/src/main/java/com/pedometer/ui/SettingsTab.kt
git commit -m "chore: gate debug tools behind BuildConfig.DEBUG"
```

---

### Task 15: Drop the dead permission

`RECORD_AUDIO` was added for the voice assistant, which was deleted in `6284dc2`. `MODIFY_AUDIO_SETTINGS` stays — `MusicService` and `PhoneActions` still adjust volume.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Confirm nothing records audio**

Run: `grep -rn "AudioRecord\|MediaRecorder\|SpeechRecognizer" app/src/main --include="*.kt"`
Expected: no output.

- [ ] **Step 2: Remove the permission**

Delete this line from `app/src/main/AndroidManifest.xml` (line 26):

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 3: Verify**

Run: `unset LD_PRELOAD; ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "chore: drop RECORD_AUDIO, unused since the voice assistant was removed"
```

---

### Task 16: Make the docs match the code

Four documents claim features that no longer exist or were never built.

**Files:**
- Modify: `docs/MASTER_PLAN.md`
- Modify: `docs/AUDIT_AND_PLAN.md`
- Modify: `docs/REFACTORING.md`
- Modify: `README.md`

- [ ] **Step 1: MASTER_PLAN.md — Phase 9**

Replace every `- [x]` under `## Phase 9: Voice Assistant (Bonus)` with `- [-]` and append this line under the heading:

```markdown
> **Removed 2026-07-02 (commit 6284dc2):** `VoiceAssistant.kt`, `LlmClient.kt` and `WhisperClient.kt`
> were deleted as dead code — watch mic audio is not reachable over SPP without HFP. Kept in
> `## Tech Debt` for anyone who wants to retry via SCO.
```

- [ ] **Step 2: AUDIT_AND_PLAN.md — StepCounterService references**

Under `### P1` and `### P2`, the entries mentioning `StepCounterService` describe a file deleted in `6284dc2`. Replace those two bullet texts with:

```markdown
- [x] Bucket TYPE_STEP_DETECTOR events by hour into hourly_steps — `StepCollector`, hosted by `WatchConnectionService` (2026-08-09)
- [ ] TYPE_STEP_COUNTER reboot snapshots — `StepSnapshot` table exists, nothing writes to it since StepCounterService was removed
```

- [ ] **Step 3: REFACTORING.md — reopen what was closed prematurely**

Change the two `[x]` lines under `## Medium — Code Quality` to:

```markdown
- [x] Room migrations — destructive fallback removed 2026-08-09, `MIGRATIONS` list + `missingMigrationSteps` coverage check, schema exported to `app/schemas/`
- [ ] Unit tests — pure logic is covered (auth, packets, reconnect, hour buckets, call routing, state merge); the activity/health parsers in `health/parsers/` are still untested
```

And under `## High — Refactoring`, replace the WatchViewModel line with:

```markdown
- [x] WatchViewModel — connection, watch services and command handling extracted to `repo/WatchRepository` (2026-08-09); the ViewModel now holds phone-side state only
```

- [ ] **Step 4: README.md — Features**

Under `## Features`, change the Steps bullet to state what is true:

```markdown
- **Steps** — daily count from the phone's step sensor (background) and the OPLUS step provider, walk/run breakdown from the watch, daily goal ring
```

- [ ] **Step 5: Commit**

```bash
git add docs README.md
git commit -m "docs: sync plans and README with the actual code"
```

---

## Verification Checklist

Run before declaring the plan complete. Every line needs an observed result, not an assumption.

- [ ] `unset LD_PRELOAD; ./gradlew testDebugUnitTest` — all tests pass (16 pre-existing + 6 ReconnectPolicy + 4 WatchData + 5 MigrationCoverage + 6 HourBucket + 7 CallRouter = 44)
- [ ] `./gradlew assembleDebug assembleRelease` — both succeed
- [ ] Release APK certificate is not `CN=Android Debug`
- [ ] Swipe the app out of Recents → ADB test notification still reaches the watch
- [ ] `adb reboot` → watch reconnects with the app never opened
- [ ] Incoming call from a saved contact → the watch shows the contact name, once, and clears on hangup
- [ ] Walk with the screen locked → the hourly chart in day detail shows the current hour
- [ ] The 30-day chart still shows pre-upgrade history (no data loss from the Room change)
- [ ] Settings in a release build has no "Отладка" button

---

## Appendix — Claims Verified Against the Code (2026-08-09)

Every API this plan calls was read out of the source, not remembered. If any of these is false when you get there, stop and re-check rather than adapting silently.

| Claim | Verified |
|---|---|
| `SppConnection(onData, onDisconnected)`, `.connect(device): Boolean`, `.write`, `.disconnect()` | `bt/SppConnection.kt:14,36,167,177` |
| `ProtocolHandler(authService, connection, onAuthenticated, onCommand)` + `var onActivityData`, `.start()`, `.onDataReceived()`, `.sendCommand()` | `bt/ProtocolHandler.kt:18-23,35,48,235` |
| `HealthService(handler, onHealthUpdate)` + `onWorkoutEvent`, `onGpsNeeded`, `initialize()`, `start/stopRealtimeStats()`, `handleCommand()` | `health/HealthService.kt:19-23,78,105,112,119` |
| `MusicService(context, handler)`, `NotificationService(context, handler)`, `WatchSettings(handler, context)`, `CalendarService(handler, context)` | respective class headers |
| `UtilityService(handler, onFindPhone)`, `WatchfaceService(handler, onWatchfaceList)`, `DataUploadService(handler)`, `AlarmService(handler)`, `ReminderService(handler)` | respective class headers |
| `ActivitySync(handler, onDailySummary, onHeartRateSamples, onSleepData, onWorkout, onHourlySteps, onGpsTrack)` — all named with defaults | `health/ActivitySync.kt:21-29` |
| `AlarmService.createAlarm(hour, minute, repeatMode=0, repeatFlags=0, smart=2)` | `util/AlarmService.kt:39` |
| `StepDao.incrementHourly` is an `UPDATE` — the row must be inserted first | `data/StepDao.kt:23-24` |
| `StepDao.insertHourly` is `@Insert(onConflict = IGNORE)`, safe as a pre-insert | `data/StepDao.kt:17-18` |
| `ConnectionStatus` is imported as `com.pedometer.vm.ConnectionStatus` by `DeviceTab` and `SettingsTab` only, and used in a non-exhaustive `when` — the typealias is transparent to both | `ui/DeviceTab.kt:12`, `ui/SettingsTab.kt:25,77-103` |
| `BleConnection` is referenced only by `WatchViewModel`; `BleDebugTool`/`DebugScreen` import nothing from `com.pedometer` | grep over `app/src/main` |
| Nothing writes `StepSnapshot` — only `StepDao.insertSnapshot` exists | grep over `app/src/main` |
| No `AudioRecord`/`MediaRecorder`/`SpeechRecognizer` anywhere, so `RECORD_AUDIO` is dead; `MODIFY_AUDIO_SETTINGS` is still used by `MusicService`/`PhoneActions` | grep over `app/src/main` |
| The UI touches only the 23 ViewModel members listed in Task 6; `startBreathing`, `stopFindPhoneRingtone`, `createReminder`, `deleteReminderById` are unreferenced | grep of `vm\.` over `ui/` + `MainActivity.kt` |
| `PedometerApp.isInForeground` / `onForegroundChanged` are used only by `PedometerApp` and `WatchViewModel` | grep over `app/src/main` |
| Kotlin 2.0.21, AGP 8.7.3, KSP 2.0.21-1.0.27 — `data object` and `lateinit` in a companion are available | `build.gradle.kts:2-6` |
| Service currently declares `foregroundServiceType="connectedDevice|location"` | `AndroidManifest.xml:69-70` |
| `ACTIVITY_RECOGNITION` is requested at runtime from `SettingsTab` (Connect button) and `OnboardingScreen` — but can be denied, hence the dynamic type mask | `ui/SettingsTab.kt:87`, `ui/OnboardingScreen.kt:57` |
| `refreshData()` is the only writer of `spo2`/`stress`/`hrResting`/`lastSleep`/`recentWorkouts`/`hrHistory` besides the moved callbacks | `vm/WatchViewModel.kt:804-813` |
| `refreshData()`'s tail uses `protocolHandler`, `activitySync`, `lastWeatherFetchTime` — all move, hence `refreshFromWatch()` | `vm/WatchViewModel.kt:826-841` |
| `handleCommand` dispatches by `cmd.type` to health/activitySync/music/weather/notification/watchface/dataUpload/alarm/reminder | `vm/WatchViewModel.kt:1053-1071` |
