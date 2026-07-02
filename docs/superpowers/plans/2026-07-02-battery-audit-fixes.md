# Battery Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 5 confirmed battery drain issues found by audit agents and verified manually.

**Architecture:** All fixes are in WatchViewModel.kt — step polling interval, SPP cleanup, weather job lifecycle, weather caching, ringtone dedup.

**Tech Stack:** Kotlin, Coroutines (Job), Android Bluetooth SPP

---

## File Structure

- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt` — all 5 fixes

---

### Task 1: Step polling 1с → 10с

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt:741`

- [ ] **Step 1: Change delay from 1000 to 10000**

```kotlin
// In startStepPolling(), line 741, change:
delay(1_000)
// to:
delay(10_000)
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "perf: step polling 1s → 10s (reduce CPU/recomposition)"
```

---

### Task 2: cleanupServices() — close SPP/BLE before nulling

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt:877-891`

- [ ] **Step 1: Add disconnect calls to cleanupServices()**

```kotlin
// Replace cleanupServices() (lines 877-891) with:
private fun cleanupServices() {
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
    watchSettings = null
    protocolHandler = null
    authService = null
    sppConnection?.disconnect()
    sppConnection = null
    bleConnection?.disconnect()
    bleConnection = null
}
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "fix: cleanupServices() closes SPP/BLE sockets (prevent leak on reconnect)"
```

---

### Task 3: Weather loop — store Job, cancel on disconnect

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt:125,294-301,893-921`

- [ ] **Step 1: Add weatherJob field**

```kotlin
// After line 126 (private var stepPollingJob: Job? = null), add:
private var weatherJob: Job? = null
```

- [ ] **Step 2: Store weather loop Job**

```kotlin
// Lines 294-301, change:
                        // Periodic weather updates (every 2 hours)
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(2 * 60 * 60 * 1000L)
                            while (isActive && _state.value.connectionStatus == ConnectionStatus.Connected) {
                                try { fetchAndSendWeather() } catch (_: Exception) {}
                                delay(2 * 60 * 60 * 1000L)
                            }
                        }
// to:
                        // Periodic weather updates (every 2 hours)
                        weatherJob?.cancel()
                        weatherJob = viewModelScope.launch(Dispatchers.IO) {
                            delay(2 * 60 * 60 * 1000L)
                            while (isActive && _state.value.connectionStatus == ConnectionStatus.Connected) {
                                try { fetchAndSendWeather() } catch (_: Exception) {}
                                delay(2 * 60 * 60 * 1000L)
                            }
                        }
```

- [ ] **Step 3: Cancel weatherJob in disconnect()**

```kotlin
// In disconnect(), after stopGpsRelay() (line 895), add:
        weatherJob?.cancel()
        weatherJob = null
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "fix: cancel weather coroutine on disconnect (prevent accumulation)"
```

---

### Task 4: Weather cache — don't fetch on every foreground

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt:125,819,923-949`

- [ ] **Step 1: Add lastWeatherFetchTime field**

```kotlin
// After weatherJob field, add:
private var lastWeatherFetchTime = 0L
```

- [ ] **Step 2: Add TTL check in refreshData()**

```kotlin
// Line 819, change:
                // 5. Weather
                fetchAndSendWeather()
// to:
                // 5. Weather (skip if fetched less than 30 min ago)
                val now = System.currentTimeMillis()
                if (now - lastWeatherFetchTime > 30 * 60 * 1000L) {
                    fetchAndSendWeather()
                    lastWeatherFetchTime = now
                }
```

- [ ] **Step 3: Also update timestamp in weather loop**

```kotlin
// In the weather loop (around line 298), change:
                                try { fetchAndSendWeather() } catch (_: Exception) {}
// to:
                                try {
                                    fetchAndSendWeather()
                                    lastWeatherFetchTime = System.currentTimeMillis()
                                } catch (_: Exception) {}
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "perf: weather cache 30min TTL (skip GPS+HTTP on frequent foreground)"
```

---

### Task 5: Remove duplicate ringtone in UtilityService callback

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt:444-456`

- [ ] **Step 1: Replace local ringtone with existing startFindPhoneRingtone()**

```kotlin
// Lines 444-456, change:
                val utility = UtilityService(handler) {
                    // Find Phone: play loud ringtone
                    Log.i(TAG, "FIND PHONE triggered!")
                    val ctx = getApplication<Application>()
                    val ringtone = android.media.RingtoneManager.getRingtone(ctx,
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
                    ringtone?.play()
                    // Stop after 10 seconds
                    viewModelScope.launch {
                        delay(10000)
                        ringtone?.stop()
                    }
                }
// to:
                val utility = UtilityService(handler) {
                    Log.i(TAG, "FIND PHONE triggered!")
                    startFindPhoneRingtone()
                }
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "fix: remove duplicate ringtone, reuse startFindPhoneRingtone()"
```
