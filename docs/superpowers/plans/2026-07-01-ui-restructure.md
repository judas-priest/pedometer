# UI Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure 3-tab UI so Tab 1 (Здоровье) = today's snapshot, Tab 2 (Активность) = day-by-day history with navigation, Tab 3 (Настройки) = clean settings.

**Architecture:** Replace the monolithic 1028-line ConnectScreen.kt with focused composables. Extract reusable chart components to shared files. Tab 1 = clean "today only" dashboard. Tab 2 = day navigator with all metrics for selected day. Keep existing conditional screens (Onboarding, Debug, NotificationApps) untouched.

**Tech Stack:** Jetpack Compose, Material 3, Room DB (existing data layer unchanged)

**Data layer gaps to fill first:** DAO needs `getHourlyForDay(date)` (exists), `getSleepByDate(date)` (missing), workouts filtered by date (missing in query, can filter client-side).

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `ui/components/Charts.kt` | **Create** | Reusable: StepRing, HrChart, HourlyStepChart, StepHistoryChart, SleepStagesBar |
| `ui/components/MetricCards.kt` | **Create** | Reusable: MetricCard, StepMetricCards, QuickActionCard |
| `ui/TodayScreen.kt` | **Create** | Tab 1: today's dashboard |
| `ui/ActivityScreen.kt` | **Create** | Tab 2: day navigator with all metrics |
| `data/StepDao.kt` | **Modify** | Add getSleepForDate query |
| `ui/SettingsTab.kt` | **Modify** | Remove SCO test button |
| `MainActivity.kt` | **Modify** | Wire new screens, keep conditional screens |
| `ui/ConnectScreen.kt` | **Delete** | Replaced by TodayScreen.kt |
| `ui/ActivityTab.kt` | **Delete** | Replaced by ActivityScreen.kt |
| `ui/HealthDayDetail.kt` | **Delete** | Merged into ActivityScreen.kt |

**NOT touched:** OnboardingScreen.kt, NotificationAppsScreen.kt, DebugScreen.kt, WatchViewModel.kt, all services.

**IMPORTANT:** Before implementing each task, do a `WebSearch` for best practices. Examples:
- Task 0: "android room dao query by date sqlite localdate"
- Task 1: "jetpack compose extract reusable composable component best practices 2025"
- Task 2: "jetpack compose material 3 card component reusable pattern"
- Task 3: "jetpack compose pull to refresh health dashboard design Material 3 2025"
- Task 4: "jetpack compose date navigation day picker arrows calendar material 3"
- Task 5: "jetpack compose pager tab migration refactor best practices"

---

### Task 0: Add missing DAO queries

**Files:**
- Modify: `app/src/main/java/com/pedometer/data/StepDao.kt`

- [ ] **Step 1: Add sleep-by-date query**

```kotlin
// In StepDao.kt, add after getRecentSleep:
@Query("SELECT * FROM sleep_records WHERE date(bedTime/1000, 'unixepoch', 'localtime') = :date OR date(wakeupTime/1000, 'unixepoch', 'localtime') = :date LIMIT 1")
suspend fun getSleepForDate(date: String): SleepRecord?
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/data/StepDao.kt
git commit -m "feat: add getSleepForDate DAO query"
```

---

### Task 1: Extract reusable chart components

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/components/Charts.kt`

- [ ] **Step 1: Create Charts.kt**

Extract from ConnectScreen.kt into new file. Every composable must be `internal` or public, no private (they're shared now). Colors at top of file.

Contents to extract (copy verbatim, change `private` to `internal`):
- Colors: `StepGreen`, `HeartRed`, `CalorieOrange`, `StandBlue` (from ConnectScreen.kt lines 35-38)
- `StepRing` (ConnectScreen.kt lines 494-513)
- `HrChart` with interactive tap (ConnectScreen.kt lines 560-620)
- `HourlyStepChart` with `HourlyStepChart` composable (ConnectScreen.kt lines 792-850)
- `StepHistoryChart` (ConnectScreen.kt lines 625-660)
- New `SleepStagesBar` — extract sleep bar Canvas + legend from ConnectScreen.kt lines 332-376 into standalone composable:

```kotlin
@Composable
fun SleepStagesBar(deep: Int, light: Int, rem: Int, awake: Int, modifier: Modifier = Modifier)
```

Package: `com.pedometer.ui.components`

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (new file compiles, old files still exist and work)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/components/Charts.kt
git commit -m "refactor: extract chart composables to shared Charts.kt"
```

---

### Task 2: Extract reusable metric cards

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/components/MetricCards.kt`

- [ ] **Step 1: Create MetricCards.kt**

Extract from ConnectScreen.kt and ActivityTab.kt:
- `MetricCard` (ConnectScreen.kt lines 525-545) — title, value, unit, color, modifier
- `StepMetricCards` (ConnectScreen.kt lines 375-395) — walk/run/calories/distance row
- `QuickActionCard` (ActivityTab.kt lines 215-235) — emoji + label card
- `StatItem` (ActivityTab.kt lines 237-250) — label + value column

Package: `com.pedometer.ui.components`

All composables `internal` visibility.

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/components/MetricCards.kt
git commit -m "refactor: extract metric card composables to MetricCards.kt"
```

---

### Task 3: Create TodayScreen (Tab 1 — Здоровье)

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/TodayScreen.kt`

- [ ] **Step 1: Create TodayScreen.kt**

```kotlin
package com.pedometer.ui

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: WatchState,
    onRefresh: () -> Unit = {},
    onTodayTap: () -> Unit = {},
)
```

Wrap in `PullToRefreshBox`. Content in scrollable Column, top to bottom:

**Section 1 — Steps (lines ~20-50):**
- Title "Здоровье" (headlineLarge, Bold)
- Step ring (animated progress, tap → onTodayTap)
- Steps number + "/ goal шагов"

**Section 2 — Step breakdown (lines ~50-60):**
- `StepMetricCards(walk, run, total, profile)` from MetricCards.kt

**Section 3 — Live metrics (lines ~60-90):**
- Row: Пульс (live ● if > 0, else "—") | Батарея (if >= 0)
- Row: SpO2 | Стресс | Покой (show "—" if 0)
- All via `MetricCard` from MetricCards.kt

**Section 4 — Today's HR chart (lines ~90-120):**
- Show only if hrHistory has data for today
- Filter: `state.hrHistory.filter { it.first >= todayStartMillis }`
- Downsample to 200 points
- `HrChart` from Charts.kt with min/avg/max labels

**Section 5 — Last night sleep (lines ~120-160):**
- Show only if `state.lastSleep != null && totalMinutes > 0`
- Compact card: "Сон" | time range | duration | quality label
- `SleepStagesBar` from Charts.kt
- Legend row with colored dots

**Step logic** (same as current ConnectScreen):
```kotlin
val phoneSteps = when {
    state.todayWalkSteps + state.todayRunSteps > 0 -> (state.todayWalkSteps + state.todayRunSteps).toLong()
    else -> state.phoneSteps
}
val currentSteps = if (state.watchSteps > 0) state.watchSteps.toLong() else phoneSteps
```

No: weekly summaries, per-day cards, step history chart, health-for-N-days card. Those are Tab 2.

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (TodayScreen compiles standalone, not wired yet)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/TodayScreen.kt
git commit -m "feat: create TodayScreen — clean today-only health dashboard"
```

---

### Task 4: Create ActivityScreen — scaffolding + day navigation

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/ActivityScreen.kt`

- [ ] **Step 1: Create ActivityScreen.kt with day navigation**

```kotlin
package com.pedometer.ui

@Composable
fun ActivityScreen(
    state: WatchState,
    onFindWatch: () -> Unit = {},
    onBreathing: () -> Unit = {},
)
```

Internal state:
```kotlin
var selectedDate by remember { mutableStateOf(LocalDate.now()) }
```

Header: Row with ← arrow, formatted date ("1 июля, вт"), → arrow. → disabled if selectedDate == today.

Data filtering helpers (private functions):
```kotlin
private fun filterHrForDay(hrHistory: List<Pair<Long, Int>>, date: LocalDate): List<Pair<Long, Int>>
private fun filterWorkoutsForDay(workouts: List<WorkoutRecord>, date: LocalDate): List<WorkoutRecord>
```

Content in scrollable Column:

1. Date navigation header
2. Step ring for selected day (from `state.stepHistory.find { it.date == selectedDate.toString() }`)
3. StepMetricCards (walk/run/calories/distance)
4. HourlyStepChart (only if selectedDate == today, using `state.todayHourlySteps`)
5. HR chart for selected day (filterHrForDay, downsample, HrChart)
6. HR summary row: avg | resting | min | max (from `state.healthHistory.find { it.date == selectedDate.toString() }`)
7. HR zones bar (from filtered HR data)
8. SpO2 card (from healthHistory for date)
9. Stress card with LinearProgressIndicator (from healthHistory for date)
10. Sleep card (filter `state.lastSleep` — show only if sleep wakeupTime falls on selectedDate)
11. Workouts for date (filterWorkoutsForDay)
12. Step history bar chart (week view) — tap bar → change selectedDate
13. Quick actions: Найти часы | Дыхание (2 cards)

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/ActivityScreen.kt
git commit -m "feat: create ActivityScreen — day-by-day health navigator"
```

---

### Task 5: Wire new screens in MainActivity + delete old files

**Files:**
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt`
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt`
- Delete: `app/src/main/java/com/pedometer/ui/ConnectScreen.kt`
- Delete: `app/src/main/java/com/pedometer/ui/ActivityTab.kt`
- Delete: `app/src/main/java/com/pedometer/ui/HealthDayDetail.kt`

- [ ] **Step 1: Update SettingsTab — remove SCO test**

Remove `onScoTest` parameter and SCO test QuickActionCard. Keep everything else.

- [ ] **Step 2: Update MainActivity — replace screen references**

```kotlin
when (page) {
    0 -> TodayScreen(
        state = state,
        onRefresh = { vm.refreshData() },
        onTodayTap = { scope.launch { pagerState.animateScrollToPage(1) } },
    )
    1 -> ActivityScreen(
        state = state,
        onFindWatch = { vm.findWatch() },
        onBreathing = { vm.startBreathing() },
    )
    2 -> SettingsTab(
        state = state,
        onAuthKeyChange = vm::updateAuthKey,
        onMacChange = vm::updateMacAddress,
        onConnect = vm::connect,
        onDisconnect = vm::disconnect,
        onProfileChange = vm::updateProfile,
        onOpenDebug = { showDebug = true },
        onOpenNotificationApps = { showNotificationApps = true },
        onFindWatch = { vm.findWatch() },
        onRequestWatchfaces = { vm.requestWatchfaces() },
        onSetActiveWatchface = { vm.setActiveWatchface(it) },
        onDeleteWatchface = { vm.deleteWatchface(it) },
        onUploadWatchface = { vm.uploadWatchface(it) },
    )
}
```

Remove: `VoiceAssistant` instance, `micPermLauncher`, `onCamera`, `onFlashlight`, `onScoTest`, `onVoiceAssistant` callbacks.

Keep: `showDebug`, `showNotificationApps`, `showOnboarding` conditional screens — they stay exactly as they are.

Remove imports: `ConnectScreen`, `ActivityTab`, `HealthDayDetail`, `VoiceAssistant`, `PhoneActions`.

- [ ] **Step 3: Delete old files**

```bash
rm app/src/main/java/com/pedometer/ui/ConnectScreen.kt
rm app/src/main/java/com/pedometer/ui/ActivityTab.kt
rm app/src/main/java/com/pedometer/ui/HealthDayDetail.kt
```

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL with no dangling references.

If build fails — fix missing imports in TodayScreen/ActivityScreen referencing old files.

- [ ] **Step 5: Install and verify**

```bash
unset LD_PRELOAD && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Take screenshots of all 3 tabs:
- Tab 1: Only today's data — steps ring, live HR, battery, SpO2, stress, sleep, HR chart
- Tab 2: Day navigator — arrows work, shows metrics for selected day, step history at bottom
- Tab 3: Settings without SCO test

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: complete UI restructure — TodayScreen + ActivityScreen, delete old files"
```
