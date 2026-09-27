# Supplements (БАДы) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Supplement tracking with meal-slot reminders (watch buzz + swipe-to-log notification), streak stats, full settings toggle.

**Architecture:** Pure logic in `health/SupplementLogic.kt` (windows, regime, streak, dismiss guard — unit-testable). Persistence: two Room tables (`supplements`, `supplement_intakes`), migration 10→11. Reminders: minute-tick scheduler owned by `WatchConnectionService`, posts Android notifications with `DeleteIntent` (swipe = intake, 30 s accidental-dismiss guard) and mirrors a text notification to the watch via `WatchNotificationBridge.sendToWatch`. UI: card in TodayScreen, history section in DayDetailScreen, settings section in SettingsTab — all cloned from existing card/section patterns.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room (manual Migrations, no fallback), kotlinx.coroutines, NotificationCompat (InboxStyle).

**Worktree:** `/home/dima/.config/superpowers/worktrees/pedometer/health-insights`, branch `health-insights`. NEVER touch `/home/dima/Projects/pedometer`. Do NOT build the APK — `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` only. Commit with `git add app` (NOT `app/src` — manifest must be included).

**Key existing references (read before editing):**
- `data/StepDatabase.kt` — VERSION=10, MIGRATIONS array, migration tripwire at build()
- `ui/SettingsTab.kt:279` — prefs name `"pedometer_prefs"`, quiet-hours section pattern (Row+Column+Switch at :285-305)
- `ui/TodayScreen.kt:223` — ElevatedCard pattern
- `ui/DayDetailScreen.kt:293` — section header pattern («Прогулки»: `Text(titleMedium, onSurfaceVariant)` + `Spacer(8.dp)`)
- `notification/WatchNotificationBridge.sendToWatch(id, packageName, appName, title, body)` — static watch push
- `repo/WatchRepository.kt:108` — `@Volatile private var homeWifiConnected`; `:222` — `onHomeWifiChanged(connected)`
- `vm/WatchViewModel.kt` — `_state.update { it.copy(...) }` atomic pattern (MANDATORY), `dao = StepDatabase.get(app).stepDao()`

---

### Task 1: Pure logic — slots, windows, dismiss guard

**Files:**
- Create: `app/src/main/java/com/pedometer/health/SupplementLogic.kt`
- Test: `app/src/test/java/com/pedometer/health/SupplementLogicTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.pedometer.health

import org.junit.Assert.*
import org.junit.Test

class SupplementLogicTest {

    @Test
    fun `home windows are correct`() {
        assertEquals(Window(720, 780), SupplementWindows.windowFor("fasting", office = false))
        assertEquals(Window(780, 960), SupplementWindows.windowFor("breakfast", office = false))
        assertEquals(Window(780, 1320), SupplementWindows.windowFor("flex", office = false))
    }

    @Test
    fun `office windows are correct`() {
        assertEquals(Window(420, 480), SupplementWindows.windowFor("fasting", office = true))
        assertEquals(Window(480, 570), SupplementWindows.windowFor("breakfast", office = true))
        assertEquals(Window(780, 1320), SupplementWindows.windowFor("flex", office = true))
    }

    @Test
    fun `prefs override home window`() {
        val prefs = mapOf("supp_win_breakfast_start" to 800, "supp_win_breakfast_end" to 900)
        assertEquals(Window(800, 900), SupplementWindows.windowFor("breakfast", office = false, prefs = prefs))
    }

    @Test
    fun `prefs override does not affect office`() {
        val prefs = mapOf("supp_win_breakfast_start" to 800, "supp_win_breakfast_end" to 900)
        assertEquals(Window(480, 570), SupplementWindows.windowFor("breakfast", office = true, prefs = prefs))
    }

    @Test
    fun `dismissal within 30s is accidental`() {
        assertTrue(SupplementWindows.isAccidentalDismissal(postedAtMs = 1_000_000L, dismissedAtMs = 1_020_000L))
    }

    @Test
    fun `dismissal after 30s counts`() {
        assertFalse(SupplementWindows.isAccidentalDismissal(postedAtMs = 1_000_000L, dismissedAtMs = 1_031_000L))
    }

    @Test
    fun `office override expires next day`() {
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertTrue(SupplementWindows.officeOverrideActive("2026-09-26", today))
        assertFalse(SupplementWindows.officeOverrideActive("2026-09-25", today))
        assertFalse(SupplementWindows.officeOverrideActive(null, today))
    }

    @Test
    fun `streak counts back from yesterday when today incomplete`() {
        val required = setOf("breakfast", "flex")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast"),                        // today: incomplete → not counted
            "2026-09-25" to setOf("breakfast", "flex"),
            "2026-09-24" to setOf("breakfast", "flex"),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(2, SupplementStreak.streak(logged, required, today))
    }

    @Test
    fun `streak counts today when complete`() {
        val required = setOf("breakfast", "flex")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast", "flex"),
            "2026-09-25" to setOf("breakfast", "flex"),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(2, SupplementStreak.streak(logged, required, today))
    }

    @Test
    fun `streak is zero when yesterday missed`() {
        val required = setOf("breakfast", "flex")
        val logged = mapOf(
            "2026-09-26" to setOf("breakfast", "flex"),
            "2026-09-25" to emptySet(),
        )
        val today = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(0, SupplementStreak.streak(logged, required, today))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.health.SupplementLogicTest"`
Expected: FAIL — unresolved reference `SupplementWindows` / `SupplementStreak` / `Window`

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/pedometer/health/SupplementLogic.kt`:

```kotlin
package com.pedometer.health

import java.time.LocalDate

/** Supplement intake slot. key is stored in DB. */
enum class SupplementSlot(val key: String, val title: String) {
    FASTING("fasting", "Натощак"),
    BREAKFAST("breakfast", "Завтрак"),
    FLEX("flex", "Обед или ужин");

    companion object {
        fun byKey(key: String): SupplementSlot = entries.first { it.key == key }
    }
}

/** Window of day in minutes, [startMin, endMin). */
data class Window(val startMin: Int, val endMin: Int) {
    fun contains(minOfDay: Int): Boolean = minOfDay in startMin until endMin
}

object SupplementWindows {
    // Defaults: HOME breakfast 13:00–16:00, fasting 12:00–13:00;
    // OFFICE breakfast 08:00–09:30, fasting 07:00–08:00; flex 13:00–22:00 in both regimes.
    private val DEFAULTS = mapOf(
        false to mapOf( // false = home
            "fasting" to Window(720, 780),
            "breakfast" to Window(780, 960),
            "flex" to Window(780, 1320),
        ),
        true to mapOf(
            "fasting" to Window(420, 480),
            "breakfast" to Window(480, 570),
            "flex" to Window(780, 1320),
        ),
    )

    /** Effective window for a slot. Prefs (minutes of day) override the HOME regime only. */
    fun windowFor(slot: String, office: Boolean, prefs: Map<String, Int> = emptyMap()): Window {
        val def = DEFAULTS.getValue(office).getValue(slot)
        if (office) return def
        val start = prefs["supp_win_${slot}_start"] ?: def.startMin
        val end = prefs["supp_win_${slot}_end"] ?: def.endMin
        return Window(start, end)
    }

    /** Swipe within 30 s of posting is accidental (shade clear-all protection). */
    fun isAccidentalDismissal(postedAtMs: Long, dismissedAtMs: Long, thresholdMs: Long = 30_000L): Boolean =
        dismissedAtMs - postedAtMs < thresholdMs

    /** Manual «Сегодня в офисе» toggle: stored as ISO date string, expires next day. */
    fun officeOverrideActive(storedDate: String?, today: LocalDate): Boolean =
        storedDate == today.toString()
}

object SupplementStreak {
    /**
     * Consecutive days ending today (or yesterday if today is not yet fully logged)
     * where every required slot has an intake.
     */
    fun streak(loggedByDate: Map<String, Set<String>>, requiredSlots: Set<String>, today: LocalDate): Int {
        if (requiredSlots.isEmpty()) return 0
        var day = today
        if (loggedByDate[day.toString()]?.containsAll(requiredSlots) != true) day = day.minusDays(1)
        var count = 0
        while (loggedByDate[day.toString()]?.containsAll(requiredSlots) == true) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `unset LD_PRELOAD; ./gradlew testDebugUnitTest --tests "com.pedometer.health.SupplementLogicTest"`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: supplement slot windows, dismiss guard, streak logic"
```

---

### Task 2: Room entities, migration 10→11, DAO

**Files:**
- Modify: `app/src/main/java/com/pedometer/data/StepEntities.kt` (append at end)
- Modify: `app/src/main/java/com/pedometer/data/StepDatabase.kt:10-12,20,29-52`
- Modify: `app/src/main/java/com/podemeter/data/StepDao.kt` (append at end)

- [ ] **Step 1: Add entities to StepEntities.kt**

Append at the end of the file:

```kotlin
@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                // "ALA 600"
    val slot: String,                // SupplementSlot.key: "fasting" | "breakfast" | "flex"
    val enabled: Boolean = true,
    val sort: Int = 0,
)

@Entity(tableName = "supplement_intakes", indices = [Index(value = ["date", "slot"])])
data class SupplementIntake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,                // "2026-09-26" local
    val slot: String,
    val takenAt: Long,               // epoch millis of the swipe
)
```

- [ ] **Step 2: Bump DB version and add migration in StepDatabase.kt**

1. Line 11: append `Supplement::class, SupplementIntake::class` to the `entities = [...]` array.
2. Line 12: `version = 10` → `version = 11`.
3. Line 20: `const val VERSION = 10` → `const val VERSION = 11`.
4. Append to the MIGRATIONS array (after the 9,10 entry):

```kotlin
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `supplements` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`slot` TEXT NOT NULL, " +
                            "`enabled` INTEGER NOT NULL, " +
                            "`sort` INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `supplement_intakes` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`date` TEXT NOT NULL, " +
                            "`slot` TEXT NOT NULL, " +
                            "`takenAt` INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_supplement_intakes_date_slot` ON `supplement_intakes` (`date`, `slot`)")
                }
            },
```

The `missingMigrationSteps` tripwire in `build()` will fail the build if version and migration diverge — run tests to confirm it passes.

- [ ] **Step 3: Add DAO methods to StepDao.kt**

Append before the closing brace of the interface:

```kotlin
    // Supplements
    @Insert
    suspend fun insertSupplement(s: Supplement)

    @Query("SELECT * FROM supplements WHERE enabled = 1 ORDER BY sort")
    suspend fun getEnabledSupplements(): List<Supplement>

    @Query("SELECT * FROM supplement_intakes WHERE date = :date")
    suspend fun getIntakesForDate(date: String): List<SupplementIntake>

    @Query("SELECT * FROM supplement_intakes WHERE date >= :fromDate")
    suspend fun getIntakesSince(fromDate: String): List<SupplementIntake>

    @Query("SELECT COUNT(*) FROM supplement_intakes WHERE date = :date AND slot = :slot")
    suspend fun countIntakes(date: String, slot: String): Int

    @Insert
    suspend fun insertSupplementIntake(intake: SupplementIntake)
```

- [ ] **Step 4: Verify compile + tests**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL (schema JSON `11.json` appears under `app/schemas/`)

- [ ] **Step 5: Commit**

```bash
git add app/src app/schemas
git commit -m "feat: supplements + intakes tables, migration 10->11"
```

---

### Task 3: Seed + home/office regime accessor

**Files:**
- Modify: `app/src/main/java/com/pedometer/repo/WatchRepository.kt` (near `currentQuietHours()` at :127)
- Create: `app/src/main/java/com/pedometer/health/SupplementSeed.kt`

- [ ] **Step 1: Expose regime accessor in WatchRepository**

Add after `fun currentQuietHours()` (line ~127):

```kotlin
    /** True when the phone sits on the home Wi-Fi — regime detector for supplement slots. */
    fun isAtHome(): Boolean = homeWifiConnected
```

- [ ] **Step 2: Create seeder**

`app/src/main/java/com/pedometer/health/SupplementSeed.kt`:

```kotlin
package com.pedometer.health

import android.content.Context
import android.util.Log
import com.pedometer.data.StepDatabase
import com.pedometer.data.Supplement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SupplementSeed {
    private const val TAG = "SupplementSeed"
    private const val KEY_SEEDED = "supplements_seeded"

    /** One-time seed of the user's stack. Fasting stays empty until ALA arrives. */
    suspend fun seedIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("pedometer_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return@withContext
        val dao = StepDatabase.get(context).stepDao()
        val items = listOf(
            Supplement(name = "B1 100 мг", slot = "breakfast", sort = 0),
            Supplement(name = "B12 10 мкг", slot = "breakfast", sort = 1),
            Supplement(name = "D3 250 МЕ", slot = "breakfast", sort = 2),
            Supplement(name = "Астаксантин 4.8 мг", slot = "breakfast", sort = 3),
            Supplement(name = "Хонда №1", slot = "breakfast", sort = 4),
            Supplement(name = "Уридин", slot = "breakfast", sort = 5),
            Supplement(name = "Бенфотиамин №1", slot = "breakfast", sort = 6),
            Supplement(name = "Хонда №2", slot = "flex", sort = 7),
            Supplement(name = "Бенфотиамин №2", slot = "flex", sort = 8),
        )
        items.forEach { dao.insertSupplement(it) }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "Seeded ${items.size} supplements")
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src
git commit -m "feat: supplement seed data and home-regime accessor"
```

---

### Task 4: Android notification + dismiss receiver

**Files:**
- Create: `app/src/main/java/com/pedometer/service/SupplementDismissReceiver.kt`
- Create: `app/src/main/java/com/pedometer/health/SupplementNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml` (inside `<application>`, next to the other receivers)

- [ ] **Step 1: Create the dismiss receiver**

`app/src/main/java/com/pedometer/service/SupplementDismissReceiver.kt`:

```kotlin
package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pedometer.health.SupplementScheduler

/** Fires when the supplement notification is swiped away — swipe means "taken". */
class SupplementDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(SupplementNotifier.EXTRA_SLOT) ?: return
        val postedAt = intent.getLongExtra(SupplementNotifier.EXTRA_POSTED_AT, 0L)
        SupplementScheduler.onDismissed(context.applicationContext, slot, postedAt)
    }
}
```

- [ ] **Step 2: Create the notifier**

`app/src/main/java/com/pedometer/health/SupplementNotifier.kt`:

```kotlin
package com.pedometer.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pedometer.MainActivity
import com.pedometer.R
import com.pedometer.notification.WatchNotificationBridge
import com.pedometer.service.SupplementDismissReceiver

/** Posts swipe-to-log supplement notifications and mirrors them to the watch. */
object SupplementNotifier {
    const val EXTRA_SLOT = "supp_slot"
    const val EXTRA_POSTED_AT = "supp_posted_at"
    private const val CHANNEL_ID = "supplements"
    private const val NOTIF_BASE = 9000
    private const val WATCH_ID_BASE = 9100

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "БАДы", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /**
     * Posts the swipe-to-log notification. Lines are rendered via InboxStyle
     * (one pill per line, up to 7 visible). Swipe-away fires SupplementDismissReceiver.
     */
    fun post(context: Context, slot: SupplementSlot, lines: List<String>) {
        ensureChannel(context)
        val postedAt = System.currentTimeMillis()
        val dismissIntent = Intent(context, SupplementDismissReceiver::class.java).apply {
            putExtra(EXTRA_SLOT, slot.key)
            putExtra(EXTRA_POSTED_AT, postedAt)
        }
        val deletePending = PendingIntent.getBroadcast(
            context,
            (slot.key.hashCode() + (postedAt / 1000).toInt()) and 0x7FFFFFFF,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            slot.key.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val inbox = NotificationCompat.InboxStyle()
        lines.take(7).forEach { inbox.addLine(it) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("БАДы: ${slot.title}")
            .setStyle(inbox)
            // AutoCancel must stay FALSE: a tap removes the notification, which fires
            // the DeleteIntent and would falsely log an intake. Swipe-only logging.
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deletePending)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_BASE + slot.ordinal, notification)

        // Mirror to the watch (text-only there — the buzz is the point).
        WatchNotificationBridge.sendToWatch(
            id = WATCH_ID_BASE + slot.ordinal,
            packageName = "com.pedometer",
            appName = "БАДы",
            title = slot.title,
            body = lines.joinToString(" · "),
        )
    }
}
```

- [ ] **Step 3: Register receiver in the manifest**

Inside `<application>` in `app/src/main/AndroidManifest.xml`, next to the existing receivers:

```xml
        <receiver
            android:name=".service.SupplementDismissReceiver"
            android:exported="false" />
```

- [ ] **Step 4: Verify compile**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`R.drawable.ic_launcher_foreground` verified to exist in `app/src/main/res/drawable/`.)

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat: supplement swipe-to-log notification with dismiss receiver"
```

---

### Task 5: Scheduler — minute tick, windows, guard

**Files:**
- Create: `app/src/main/java/com/pedometer/health/SupplementScheduler.kt`
- Modify: `app/src/main/java/com/pedometer/service/WatchConnectionService.kt` (`onCreate`, near `stepCollector.start()` at :79)
- Modify: `app/src/main/java/com/pedometer/service/WatchConnectionService.kt` (`onDestroy` / cleanup where `presenceMonitor` is torn down)

- [ ] **Step 1: Create the scheduler**

`app/src/main/java/com/pedometer/health/SupplementScheduler.kt`:

```kotlin
package com.pedometer.health

import android.content.Context
import android.util.Log
import com.pedometer.PedometerApp
import com.pedometer.data.StepDatabase
import com.pedometer.data.SupplementIntake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * Minute-tick scheduler for supplement slot reminders.
 * Flow: window start → swipe-to-log notification (mirror on watch);
 * notification dismissed → intake logged (30 s guard);
 * window end, still not logged → one nudge, then silence for the day.
 */
object SupplementScheduler {
    private const val TAG = "SupplementScheduler"
    private const val KEY_ENABLED = "supplements_enabled"
    private const val KEY_OFFICE_DATE = "supp_office_date"
    private const val END_NUDGE_SUFFIX = ":end"

    private var job: Job? = null
    private var appContext: Context? = null
    private var lastTickDate: String? = null

    /** slot key → millis when the current window notification was posted. */
    private val postedAt = mutableMapOf<String, Long>()

    /** Dedup keys already fired today: "2026-09-26|breakfast" and "2026-09-26|breakfast:end". */
    private val firedKeys = mutableSetOf<String>()

    fun start(context: Context, scope: CoroutineScope) {
        if (job != null) return
        appContext = context.applicationContext
        job = scope.launch {
            SupplementSeed.seedIfNeeded(context)
            while (true) {
                try {
                    tick(context.applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "tick failed", e)
                }
                delay(60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        postedAt.clear()
        firedKeys.clear()
    }

    /** Called from SupplementDismissReceiver when the notification is swiped. */
    fun onDismissed(context: Context, slotKey: String, postedAtMs: Long) {
        val now = System.currentTimeMillis()
        if (SupplementWindows.isAccidentalDismissal(postedAtMs, now)) {
            Log.i(TAG, "Dismiss of $slotKey within 30s — accidental, ignoring")
            return
        }
        if (runCatching { SupplementSlot.byKey(slotKey) }.getOrNull() == null) return
        postedAt.remove(slotKey)
        Log.i(TAG, "Supplement intake: $slotKey at $now")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val dao = StepDatabase.get(context).stepDao()
            val today = LocalDate.now().toString()
            // DeleteIntent can also fire from app-side cancel() — never double-log.
            if (dao.countIntakes(today, slotKey) > 0) return@launch
            dao.insertSupplementIntake(SupplementIntake(date = today, slot = slotKey, takenAt = now))
        }
    }

    private suspend fun tick(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("pedometer_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return@withContext

        val today = LocalDate.now()
        // Daily state resets — dedup keys must not leak across days.
        if (today.toString() != lastTickDate) {
            firedKeys.clear()
            postedAt.clear()
            lastTickDate = today.toString()
        }
        val dao = StepDatabase.get(context).stepDao()
        val pills = dao.getEnabledSupplements().groupBy { it.slot }
        val requiredSlots = pills.keys
        if (requiredSlots.isEmpty()) return@withContext

        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val prefsMap = prefs.all.filterValues { it is Int }.mapValues { it.value as Int }
        val office = SupplementWindows.officeOverrideActive(prefs.getString(KEY_OFFICE_DATE, null), today) ||
            !(PedometerApp.repository.isAtHome())

        for ((slotKey, items) in pills) {
            val alreadyLogged = dao.countIntakes(today.toString(), slotKey) > 0
            if (alreadyLogged) continue
            val w = SupplementWindows.windowFor(slotKey, office, prefsMap)
            val startKey = "$today|$slotKey"
            val endKey = "$today|$slotKey$END_NUDGE_SUFFIX"

            if (w.contains(nowMin) && startKey !in firedKeys && !postedAt.containsKey(slotKey)) {
                firedKeys.add(startKey)
                postedAt[slotKey] = System.currentTimeMillis()
                val slot = SupplementSlot.byKey(slotKey)
                SupplementNotifier.post(context, slot, items.sortedBy { it.sort }.map { it.name })
                Log.i(TAG, "Window opened: $slotKey (${items.size} items)")
            }
            if (nowMin >= w.endMin && nowMin < w.endMin + 3 && endKey !in firedKeys) {
                firedKeys.add(endKey)
                val slot = SupplementSlot.byKey(slotKey)
                SupplementNotifier.post(context, slot, items.sortedBy { it.sort }.map { it.name })
                Log.i(TAG, "End-of-window nudge: $slotKey")
            }
        }
    }
}
```

- [ ] **Step 2: Wire into WatchConnectionService**

In `onCreate` (after `stepCollector.start()` at line ~79):

```kotlin
        SupplementScheduler.start(this, scope)
```

Add import `com.pedometer.health.SupplementScheduler`.

In `onDestroy` (lines 256–259, where `presenceMonitor?.stop()` / `presenceMonitor = null` live), add alongside:

```kotlin
        SupplementScheduler.stop()
```

- [ ] **Step 3: Verify compile + tests**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green

- [ ] **Step 4: Commit**

```bash
git add app
git commit -m "feat: supplement reminder scheduler with swipe guard and end-of-window nudge"
```

---

### Task 6: ViewModel state + tap handler

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt` (WatchState data class + refreshData + tap handler)

- [ ] **Step 1: Add state fields**

In the `WatchState` data class add (next to the walk/detail fields):

```kotlin
    val supplementSlots: List<SupplementSlotUi> = emptyList(),
    val supplementStreak: Int = 0,
    val supplementsForDay: List<SupplementIntake> = emptyList(),
```

And next to it the UI model:

```kotlin
data class SupplementSlotUi(
    val slot: String,
    val title: String,
    val taken: Boolean,
    val takenAtText: String,
)
```

Imports needed: `com.pedometer.data.SupplementIntake`, `com.pedometer.health.SupplementSlot`, `com.pedometer.health.SupplementStreak`, `android.app.Application` (likely already imported — verify).

- [ ] **Step 2: Loader in refreshData**

In `WatchViewModel.refreshData()` (the function that already queries `dao` and updates state with `_state.update { ... }`), append at the end of the function body:

```kotlin
                loadSupplementsToday()
```

And add the method to the class:

```kotlin
    private fun loadSupplementsToday() {
        scope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val prefs = app.getSharedPreferences("pedometer_prefs", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("supplements_enabled", false)) {
                _state.update { it.copy(supplementSlots = emptyList(), supplementStreak = 0) }
                return@launch
            }
            val dao = StepDatabase.get(app).stepDao()
            val today = java.time.LocalDate.now().toString()
            val pills = dao.getEnabledSupplements().groupBy { it.slot }
            val intakes = dao.getIntakesSince(today.minusDays(30).toString())
            val loggedByDate = intakes.groupBy { it.date }
                .mapValues { (_, v) -> v.map { it.slot }.toSet() }
            val slots = pills.keys
                .map { SupplementSlot.byKey(it) }
                .sortedBy { it.ordinal }
                .map { slot ->
                    val intake = intakes.filter { it.date == today && it.slot == slot.key }.maxByOrNull { it.takenAt }
                    SupplementSlotUi(
                        slot = slot.key,
                        title = slot.title,
                        taken = intake != null,
                        takenAtText = intake?.let {
                            java.time.Instant.ofEpochMilli(it.takenAt).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        } ?: "",
                    )
                }
            val streak = SupplementStreak.streak(
                loggedByDate,
                pills.keys,
                java.time.LocalDate.now(),
            )
            _state.update { it.copy(supplementSlots = slots, supplementStreak = streak) }
        }
    }
```

- [ ] **Step 3: Day-detail loader**

Find where `walksForDay` is written into state in `WatchViewModel.kt` (search `_state.update` blocks containing `walksForDay`). Immediately after that update block, add:

```kotlin
                scope.launch(Dispatchers.IO) {
                    val dao = StepDatabase.get(app).stepDao()
                    val intakes = dao.getIntakesForDate(date.toString())
                    _state.update { it.copy(supplementsForDay = intakes) }
                }
```

(`date` is the existing `LocalDate` variable in that scope; use the same variable name the surrounding code uses.)

- [ ] **Step 4: Tap handler**

Add to the ViewModel:

```kotlin
    fun onSupplementSlotTap(slotKey: String) {
        scope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val dao = StepDatabase.get(app).stepDao()
            val today = java.time.LocalDate.now().toString()
            if (dao.countIntakes(today, slotKey) > 0) return@launch
            dao.insertSupplementIntake(
                SupplementIntake(date = today, slot = slotKey, takenAt = System.currentTimeMillis())
            )
            loadSupplementsToday()
        }
    }
```

- [ ] **Step 5: Verify compile + tests**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: supplement state, streak loader, tap handler in ViewModel"
```

---

### Task 7: TodayScreen card

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/TodayScreen.kt`

- [ ] **Step 1: Add the card composable**

Append at the end of the file (top-level private composable, same pattern as other cards):

```kotlin
@Composable
private fun SupplementsCard(
    slots: List<SupplementSlotUi>,
    streak: Int,
    onSlotTap: (String) -> Unit,
) {
    if (slots.isEmpty()) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("БАДы", style = MaterialTheme.typography.titleSmall)
            slots.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !s.taken) { onSlotTap(s.slot) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (s.taken) "✓ ${s.takenAtText}" else "✗",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.taken) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (streak > 0) {
                Text(
                    "$streak дн. подряд",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

Import `com.pedometer.vm.SupplementSlotUi`.

- [ ] **Step 2: Insert the card into the screen body**

In `TodayScreen`'s scrollable `Column`, insert AFTER the sleep card block (the `ElevatedCard` starting at line 223 with the «Сон» title, ending with the `SleepStagesBar(...)` call and its three closing braces just before the `} // else (not showing metric detail)` comment around line 267):

```kotlin
                Spacer(Modifier.height(12.dp))
                SupplementsCard(
                    slots = state.supplementSlots,
                    streak = state.supplementStreak,
                    onSlotTap = { onSupplementSlotTap(it) },
                )
```

`TodayScreen` needs a new parameter — update its signature and the call site in `MainActivity.kt` (search `TodayScreen(`):

```kotlin
fun TodayScreen(
    state: WatchState,
    onRefresh: () -> Unit = {},
    onTodayTap: () -> Unit = {},
    onSupplementSlotTap: (String) -> Unit = {},
)
```

At the call site pass `onSupplementSlotTap = { viewModel.onSupplementSlotTap(it) }` (mirror how `onRefresh` is passed there).

- [ ] **Step 3: Verify compile**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src
git commit -m "feat: supplements card on health screen"
```

---

### Task 8: DayDetail history section

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/DayDetailScreen.kt` (after the "Тренировки" section, around line 490)

- [ ] **Step 1: Add the section**

After the workouts section block (`if (dayWorkouts.isNotEmpty()) { ... }`), add:

```kotlin
        // 11. Supplements history
        if (state.supplementsForDay.isNotEmpty()) {
            Text("БАДы", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.supplementsForDay
                        .sortedBy { it.takenAt }
                        .forEach { intake ->
                            val time = java.time.Instant.ofEpochMilli(intake.takenAt)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    runCatching { SupplementSlot.byKey(intake.slot).title }.getOrDefault(intake.slot),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "✓ $time",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
```

Import `com.pedometer.health.SupplementSlot` in the file.

- [ ] **Step 2: Verify compile**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src
git commit -m "feat: supplement history in day detail"
```

---

### Task 9: Settings section

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt` (after the «Энергосбережение» section)

- [ ] **Step 1: Add the section**

After the quiet-hours / energy-saving card block (find the end of the `wifiGate` Switch card), add a new section mirroring the quiet-hours pattern:

```kotlin
        // ── Supplements ──────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Text("БАДы", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        var supplementsEnabled by remember { mutableStateOf(prefs.getBoolean("supplements_enabled", false)) }
        var officeOverride by remember {
            mutableStateOf(
                com.pedometer.health.SupplementWindows.officeOverrideActive(
                    prefs.getString("supp_office_date", null),
                    java.time.LocalDate.now(),
                )
            )
        }

        ElevatedCard {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Отслеживание БАДов", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Напоминания по приёмам пищи, смахни уведомление = выпил",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = supplementsEnabled, onCheckedChange = { on ->
                        supplementsEnabled = on
                        prefs.edit().putBoolean("supplements_enabled", on).apply()
                    })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Сегодня в офисе", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Офисные окна вместо домашних (сбросится завтра)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = officeOverride, onCheckedChange = { on ->
                        officeOverride = on
                        prefs.edit()
                            .putString("supp_office_date", if (on) java.time.LocalDate.now().toString() else "")
                            .apply()
                    })
                }
            }
        }
```

(`prefs` variable already exists at :279 in this composable. Window editing UI is intentionally NOT in v1 — defaults live in `SupplementWindows`; windows are editable only in code. Report this as a known limitation.)

- [ ] **Step 2: Verify compile**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src
git commit -m "feat: supplements settings section with master switch and office override"
```

---

### Task 10: Final verification

- [ ] **Step 1: Full test suite**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 90+ tests green (10 new in SupplementLogicTest)

- [ ] **Step 2: Self-review checklist**

- Migration 10→11 present, `missingMigrationSteps` tripwire passes (build proves it)
- Master switch OFF → scheduler tick returns early, card hidden, DayDetail section hidden by empty list
- No `_state.value = _state.value.copy` introduced — only `_state.update {}`
- `git status` clean

- [ ] **Step 3: Final commit (if anything pending) and push-ready state**

```bash
git add app
git commit -m "feat: supplements tracking — final polish"
```

Leave the branch rebased on master: `git rebase master` before handing back.
