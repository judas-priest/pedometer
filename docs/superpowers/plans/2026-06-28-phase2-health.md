# Phase 2: Health Data — RealTime Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display real-time steps, heart rate, calories, and standing hours from the watch. Add activity sync for historical data.

**Architecture:** After Phase 1 auth, send protobuf command `type=8, subtype=45` to start realtime stats stream. Watch sends periodic `type=8, subtype=47` events with RealTimeStats (steps, calories, heartRate, standingHours). For history, send `type=8, subtype=1` (today) and `subtype=2` (past) to get file IDs, then request each file via `subtype=3`, receive binary data on Activity channel, parse, and ACK via `subtype=5`.

**Tech Stack:** Kotlin, Jetpack Compose, Protobuf Lite, Room DB (for history)

---

## File Structure

```
app/src/main/java/com/pedometer/
├── proto/
│   └── CommandHelper.kt          # Modify: add health commands
├── bt/
│   └── ProtocolHandler.kt        # Modify: route health commands
├── health/
│   └── HealthService.kt          # Create: realtime stats + activity sync
├── vm/
│   └── WatchViewModel.kt         # Modify: add health state
└── ui/
    ├── ConnectScreen.kt           # Modify: show realtime stats
    └── HealthDashboard.kt         # Create: steps/heart rate/calories display
```

---

### Task 1: Add Health Commands to CommandHelper

**Files:**
- Modify: `app/src/main/java/com/pedometer/proto/CommandHelper.kt`

- [ ] **Step 1: Add health command constants and builders**

Add to CommandHelper.kt:

```kotlin
    const val TYPE_HEALTH = 8

    const val HEALTH_SET_USER_INFO = 0
    const val HEALTH_ACTIVITY_FETCH_TODAY = 1
    const val HEALTH_ACTIVITY_FETCH_PAST = 2
    const val HEALTH_ACTIVITY_FETCH_REQUEST = 3
    const val HEALTH_ACTIVITY_FETCH_ACK = 5
    const val HEALTH_REALTIME_STATS_START = 45
    const val HEALTH_REALTIME_STATS_STOP = 46
    const val HEALTH_REALTIME_STATS_EVENT = 47

    fun buildRealtimeStatsStart(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_HEALTH)
            .setSubtype(HEALTH_REALTIME_STATS_START)
            .build()
    }

    fun buildRealtimeStatsStop(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_HEALTH)
            .setSubtype(HEALTH_REALTIME_STATS_STOP)
            .build()
    }

    fun buildActivityFetchToday(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_HEALTH)
            .setSubtype(HEALTH_ACTIVITY_FETCH_TODAY)
            .setHealth(
                XiaomiProto.Health.newBuilder()
                    .setActivitySyncRequestToday(
                        XiaomiProto.ActivitySyncRequestToday.newBuilder().setUnknown1(0)
                    )
            )
            .build()
    }
```

- [ ] **Step 2: Verify build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/proto/CommandHelper.kt
git commit -m "feat: add health protobuf command builders"
```

---

### Task 2: Create HealthService

**Files:**
- Create: `app/src/main/java/com/pedometer/health/HealthService.kt`

- [ ] **Step 1: Create HealthService**

```kotlin
package com.pedometer.health

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto

data class HealthData(
    val steps: Int = 0,
    val calories: Int = 0,
    val heartRate: Int = 0,
    val standingHours: Int = 0,
)

class HealthService(
    private val protocolHandler: ProtocolHandler,
    private val onHealthUpdate: (HealthData) -> Unit,
) {
    companion object {
        private const val TAG = "HealthService"
    }

    private var realtimeStarted = false

    fun startRealtimeStats() {
        if (realtimeStarted) return
        realtimeStarted = true
        Log.i(TAG, "Starting realtime stats")
        protocolHandler.sendCommand(CommandHelper.buildRealtimeStatsStart())
    }

    fun stopRealtimeStats() {
        if (!realtimeStarted) return
        realtimeStarted = false
        Log.i(TAG, "Stopping realtime stats")
        protocolHandler.sendCommand(CommandHelper.buildRealtimeStatsStop())
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.HEALTH_REALTIME_STATS_EVENT -> {
                if (cmd.hasHealth() && cmd.health.hasRealTimeStats()) {
                    val stats = cmd.health.realTimeStats
                    val data = HealthData(
                        steps = stats.steps,
                        calories = stats.calories,
                        heartRate = stats.heartRate,
                        standingHours = stats.standingHours,
                    )
                    Log.d(TAG, "Realtime: steps=${data.steps} hr=${data.heartRate} cal=${data.calories}")
                    onHealthUpdate(data)
                }
            }
            else -> Log.d(TAG, "Unhandled health subtype: ${cmd.subtype}")
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/health/HealthService.kt
git commit -m "feat: implement HealthService with realtime stats"
```

---

### Task 3: Integrate HealthService into ViewModel

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt`

- [ ] **Step 1: Add health data to WatchState**

Add to `WatchState` data class:
```kotlin
    val steps: Int = 0,
    val calories: Int = 0,
    val heartRate: Int = 0,
    val standingHours: Int = 0,
    val realtimeActive: Boolean = false,
```

- [ ] **Step 2: Add HealthService field and wire it up**

Add field:
```kotlin
    private var healthService: HealthService? = null
```

In `connect()`, after creating `ProtocolHandler`, create `HealthService`:
```kotlin
                val health = HealthService(handler) { data ->
                    _state.value = _state.value.copy(
                        steps = data.steps,
                        calories = data.calories,
                        heartRate = data.heartRate,
                        standingHours = data.standingHours,
                    )
                }
                healthService = health
```

In the `onAuthenticated` callback, also start realtime stats:
```kotlin
                    onAuthenticated = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Connected)
                        requestDeviceInfo()
                        health.startRealtimeStats()
                        _state.value = _state.value.copy(realtimeActive = true)
                    },
```

In `handleCommand`, route health commands:
```kotlin
            CommandHelper.TYPE_HEALTH -> healthService?.handleCommand(cmd)
```

In `disconnect()`, stop realtime stats:
```kotlin
        healthService?.stopRealtimeStats()
        healthService = null
```

- [ ] **Step 3: Verify build**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "feat: integrate HealthService into WatchViewModel"
```

---

### Task 4: Create Health Dashboard UI

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/HealthDashboard.kt`
- Modify: `app/src/main/java/com/pedometer/ui/ConnectScreen.kt`

- [ ] **Step 1: Create HealthDashboard.kt**

```kotlin
package com.pedometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.vm.WatchState

@Composable
fun HealthDashboard(state: WatchState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health", style = MaterialTheme.typography.titleLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                title = "Steps",
                value = "${state.steps}",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Heart Rate",
                value = if (state.heartRate > 0) "${state.heartRate} bpm" else "--",
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                title = "Calories",
                value = "${state.calories} kcal",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Standing",
                value = "${state.standingHours} hrs",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
```

- [ ] **Step 2: Add HealthDashboard to ConnectScreen**

In ConnectScreen.kt, after the battery card (inside the `if (state.connectionStatus == ConnectionStatus.Connected)` block), add:

```kotlin
            Spacer(Modifier.height(12.dp))
            HealthDashboard(state = state)
```

- [ ] **Step 3: Verify build**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/HealthDashboard.kt app/src/main/java/com/pedometer/ui/ConnectScreen.kt
git commit -m "feat: add HealthDashboard UI with steps, heart rate, calories"
```

---

### Task 5: Build APK and Test

- [ ] **Step 1: Run all tests**

Run: `cd /home/dima/Projects/pedometer && ./gradlew clean test 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 2: Build APK**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: Phase 2 complete — realtime health stats (steps, HR, calories)"
```
