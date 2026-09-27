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
