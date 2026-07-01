package com.pedometer.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.pedometer.data.StepDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DataExporter {
    private const val TAG = "DataExporter"

    suspend fun exportAll(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val dao = StepDatabase.get(context).stepDao()
            val dir = File(context.cacheDir, "export")
            dir.mkdirs()
            val file = File(dir, "pedometer_export_${System.currentTimeMillis()}.csv")

            file.bufferedWriter().use { w ->
                // Daily steps
                w.write("=== DAILY STEPS ===\n")
                w.write("date,total,walk,run,calories,distance_km\n")
                val days = dao.getRecentDays(365)
                for (d in days) {
                    w.write("${d.date},${d.totalSteps},${d.walkSteps},${d.runSteps},${d.calories},${d.distanceKm}\n")
                }

                // Daily health
                w.write("\n=== DAILY HEALTH ===\n")
                w.write("date,hr_avg,hr_min,hr_max,hr_resting,spo2_avg,spo2_min,spo2_max,stress_avg,stress_min,stress_max\n")
                val health = dao.getRecentHealth(365)
                for (h in health) {
                    w.write("${h.date},${h.hrAvg},${h.hrMin},${h.hrMax},${h.hrResting},${h.spo2Avg},${h.spo2Min},${h.spo2Max},${h.stressAvg},${h.stressMin},${h.stressMax}\n")
                }

                // Heart rate
                w.write("\n=== HEART RATE ===\n")
                w.write("timestamp,bpm,source\n")
                val weekAgo = System.currentTimeMillis() - 7 * 86400_000L
                val hr = dao.getHeartRateSince(weekAgo)
                for (r in hr) {
                    w.write("${r.timestamp},${r.bpm},${r.source}\n")
                }

                // Sleep
                w.write("\n=== SLEEP ===\n")
                w.write("bed_time,wakeup_time,total_min,deep_min,light_min,rem_min,awake_min\n")
                val sleep = dao.getRecentSleep(30)
                for (s in sleep) {
                    w.write("${s.bedTime},${s.wakeupTime},${s.totalMinutes},${s.deepMinutes},${s.lightMinutes},${s.remMinutes},${s.awakeMinutes}\n")
                }

                // Workouts
                w.write("\n=== WORKOUTS ===\n")
                w.write("start,end,sport,name,duration_sec,distance_m,calories,hr_avg,hr_max,hr_min\n")
                val workouts = dao.getRecentWorkouts(100)
                for (wo in workouts) {
                    w.write("${wo.startTime},${wo.endTime},${wo.sportType},${wo.sportName},${wo.durationSec},${wo.distanceM},${wo.calories},${wo.hrAvg},${wo.hrMax},${wo.hrMin}\n")
                }
            }

            Log.i(TAG, "Exported to ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Экспорт данных").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}")
        }
    }
}
