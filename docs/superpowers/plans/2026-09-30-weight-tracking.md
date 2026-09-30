# Weight Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Weekly weight logging + trend chart (7-day average + alcohol-day markers) in the health screen.

**Architecture:** New Room table `weight_log` (date PK, kg) — migration 12→13. Input: «Занести» button next to the existing weight field in SettingsTab (writes log row + updates profile weight). Card on TodayScreen («Вес», after SupplementsCard) → `showMetric="weight"` detail with a new `WeightChart` line chart (own colors/y-domain — do NOT touch HrChart's HR styling). Alcohol-day detection: pure function in HealthInsights — day tagged if evening-window HR average ≥ personal baseline + 15.

**Worktree:** `/home/dima/.config/superpowers/worktrees/pedometer/health-insights`, branch `health-insights`. NEVER touch `/home/dima/Projects/pedometer`. No APK builds — `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` only.

**Audit references (verified):** navigation/sub-screen pattern `MainActivity.kt:56-60,120-172`; metric-detail pattern `TodayScreen.kt:82-92,281` (`showMetric` state, cards set it at :163-199); line chart base `ui/components/Charts.kt:65` (HrChart — hardcoded HR styling, wrap not modify); card order ends with SupplementsCard (TodayScreen.kt:114-274); profile weight `UserProfile.kt:8,35,48` (prefs "user_profile"/"weight", edited SettingsTab.kt:180-181); Room v12 `StepDatabase.kt:12`, migrations :29-78, tripwire :90-97; day-record pattern `SupplementIntake` `StepEntities.kt:121-127`.

---

### Task 1: Room — WeightLog + migration 13

**Files:** Modify `data/StepEntities.kt` (append), `data/StepDatabase.kt` (version 13, migration), `data/StepDao.kt` (append).

- [ ] Entity:
```kotlin
@Entity(tableName = "weight_log")
data class WeightLog(
    @PrimaryKey val date: String,   // "2026-09-30" local
    val kg: Double,
    val takenAt: Long,
)
```
- [ ] StepDatabase: entities += `WeightLog::class`; version 12→13 (both places); migration:
```kotlin
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `weight_log` (" +
                            "`date` TEXT NOT NULL, " +
                            "`kg` REAL NOT NULL, " +
                            "`takenAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`date`))"
                    )
                }
            },
```
- [ ] DAO:
```kotlin
    @Insert
    suspend fun insertWeightLog(w: WeightLog)

    @Query("SELECT * FROM weight_log ORDER BY date")
    suspend fun getWeightLog(): List<WeightLog>
```
- [ ] `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` green; commit `feat: weight_log table, migration 12->13` (`git add app/src app/schemas`).

---

### Task 2: Alcohol-day detector (pure, TDD)

**Files:** Modify `health/HealthInsights.kt` (add), `app/src/test/java/com/pedometer/health/HealthInsightsTest.kt` (add tests).

- [ ] Add to HealthInsights:
```kotlin
    /** Day is alcohol-tagged when the evening HR window (20:00-24:00) averages
     *  >= baseline + 15 bpm (resting-HR based). baselineHr = user's resting HR. */
    fun isAlcoholDay(eveningHrAvg: Int, restingHr: Int): Boolean =
        eveningHrAvg > 0 && restingHr in 35..120 && eveningHrAvg >= restingHr + 15
```
- [ ] Tests: `isAlcoholDay(100, 58)` true; `isAlcoholDay(90, 58)` false; `isAlcoholDay(100, 0)` false (no baseline); `isAlcoholDay(0, 58)` false.
- [ ] Run green; commit `feat: alcohol-day detector on evening HR baseline`.

---

### Task 3: ViewModel — weight state + save

**Files:** Modify `vm/WatchViewModel.kt`.

- [ ] WatchState fields: `weightHistory: List<WeightLog> = emptyList()`, `alcoholDays: Set<String> = emptySet()` (imports: `com.pedometer.data.WeightLog`).
- [ ] Loader `loadWeight()` (pattern = `loadSupplementsToday()`, `getApplication<Application>()`, `_state.update`): reads `dao.getWeightLog()`; computes alcoholDays by grouping `dao.getHeartRateBetween`-derived per-day evening averages for the last 60 days — evening window = timestamps with hour ≥ 20; per-day avg → `HealthInsights.isAlcoholDay(avg, todayResting)` where resting = latest `daily_health.hrResting` (fallback 60); call from `refreshData()` tail (next to `loadSupplementsToday()`).
- [ ] Save `fun saveWeight(kg: Double)` (pattern = `onSupplementSlotTap`): upsert `WeightLog(LocalDate.now().toString(), kg, now)` + update profile weight via existing `updateProfile(profile.copy(weightKg = kg.roundToInt()))`, then `loadWeight()`.
- [ ] Green; commit `feat: weight history state and save in ViewModel`.

---

### Task 4: WeightChart + card + metric detail

**Files:** Modify `ui/components/Charts.kt` (add composable), `ui/TodayScreen.kt` (card + detail), `MainActivity.kt` (pass callbacks).

- [ ] `Charts.kt` — new composable cloning HrChart's drawing (line + fill + grid) with weight-specific styling:
```kotlin
@Composable
fun WeightChart(data: List<Pair<Long, Double>>, alcoholDays: Set<String>, modifier: Modifier) {
    // y-domain: min/max of data padded ±0.5 kg; x: first..last timestamp.
    // Grey line + fill (colorScheme.primary), red dots (HeartRed) at x where
    // date(data.first.x) is in alcoholDays. Tap optional — omit in v1.
}
```
Implement by copying HrChart's Canvas block (:65-150) and replacing hardcoded HR colors/range/tooltip.
- [ ] TodayScreen: card after SupplementsCard — «Вес»: `state.profile.weightKg` + week delta (last two weight_log entries); tap → `showMetric = "weight"`. In MetricDetailScreen's when (TodayScreen.kt:302/315) — branch "weight": `WeightChart(state.weightHistory.map { it.takenAt to it.kg }, state.alcoholDays)`. NOTE: alcoholDays is Set<String> of ISO dates — inside WeightChart convert each point's timestamp via `java.time.Instant.ofEpochMilli(x).atZone(ZoneId.systemDefault()).toLocalDate().toString()` for the dot check. Also: profile weight is `Int` (UserProfile.kt:8) — `saveWeight(kg: Double)` writes the Double to weight_log but `kg.roundToInt()` into the profile (import `kotlin.math.roundToInt`); keep profile Int, journal Double.
- [ ] MainActivity TodayScreen call: add `onWeightSave: (Double) -> Unit` wired to `vm.saveWeight(it)` only if the card needs it — v1 input lives in Settings (Task 5), card is read-only, so NO signature change unless compiler demands.
- [ ] Green; commit `feat: weight card and trend chart on health screen`.

---

### Task 5: Settings input

**Files:** Modify `ui/SettingsTab.kt`.

- [ ] In the profile card next to the weight OutlinedTextField (:180-181): add Button «Занести» — `enabled = weight text parses to Double in 40..250`; onClick: `onWeightSave(parsed)` (new SettingsTab param `onWeightSave: (Double) -> Unit = {}`, wired in MainActivity to `vm.saveWeight(it)`).
- [ ] Green; commit `feat: weight log entry button in settings`.

---

### Task 6: Final verification

- [ ] `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` — green (~101 tests); `git status` clean; `git rebase master`.
