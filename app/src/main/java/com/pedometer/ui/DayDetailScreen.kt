package com.pedometer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.data.DailyHealth
import com.pedometer.data.HourlySteps
import com.pedometer.data.WorkoutRecord
import com.pedometer.health.DayStepData
import com.pedometer.health.UserProfile
import com.pedometer.ui.components.*
import com.pedometer.vm.WatchState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DayDetailScreen(
    state: WatchState,
    initialDate: LocalDate = LocalDate.now(),
    onBack: () -> Unit = {},
) {
    var selectedDate by remember { mutableStateOf(initialDate) }

    val today = LocalDate.now()
    val oldestDate = state.stepHistory.minByOrNull { it.date }?.date?.let {
        try { LocalDate.parse(it) } catch (_: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Back button + title (no horizontal padding — flush left)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Активность за день",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        // Content with padding
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {

        // Date navigation with chevrons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(
                onClick = { selectedDate = selectedDate.minusDays(1) },
                enabled = oldestDate == null || selectedDate > oldestDate,
                modifier = Modifier.size(36.dp),
            ) {
                Text("‹", fontSize = 24.sp, color = if (oldestDate == null || selectedDate > oldestDate) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                formatDate(selectedDate),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.width(16.dp))
            IconButton(
                onClick = { selectedDate = selectedDate.plusDays(1) },
                enabled = selectedDate < today,
                modifier = Modifier.size(36.dp),
            ) {
                Text("›", fontSize = 24.sp, color = if (selectedDate < today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        }

        Spacer(Modifier.height(8.dp))

        // 2. Step ring for selected day
        val daySteps = state.stepHistory.find { it.date == selectedDate.toString() }
        val steps = daySteps?.totalSteps ?: 0
        val progress = (steps.toFloat() / state.profile.stepGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            StepRing(progress = progress, color = StepGreen, size = 160f, strokeWidth = 12f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%,d".format(steps),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = StepGreen,
                )
                Text(
                    "/ ${state.profile.stepGoal}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 3. Step breakdown
        val walk = daySteps?.walkSteps ?: 0
        val run = daySteps?.runSteps ?: 0
        StepMetricCards(walk, run, steps, state.profile)

        Spacer(Modifier.height(16.dp))

        // 4. Hourly step chart (today only)
        if (selectedDate == today && state.todayHourlySteps.isNotEmpty()) {
            HourlyStepChart(state.todayHourlySteps)
            Spacer(Modifier.height(16.dp))
        }

        // 5. HR chart for selected day
        val dayHr = filterHrForDay(state.hrHistory, selectedDate)
        if (dayHr.size > 2) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Пульс", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val downsampled = if (dayHr.size <= 150) dayHr
                    else {
                        val step = dayHr.size / 150
                        dayHr.filterIndexed { i, _ -> i % step == 0 }
                    }
                    HrChart(
                        data = downsampled,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    val minHr = dayHr.minOf { it.second }
                    val avgHr = dayHr.map { it.second }.average().toInt()
                    val maxHr = dayHr.maxOf { it.second }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        HrLabel("Мин", minHr)
                        HrLabel("Среднее", avgHr)
                        HrLabel("Макс", maxHr)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 6. HR summary + zones
        val health = state.healthHistory.find { it.date == selectedDate.toString() }
        if (health != null && health.hrAvg > 0) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Пульс (сводка)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        HrLabel("Среднее", health.hrAvg)
                        HrLabel("Покой", health.hrResting)
                        HrLabel("Мин", health.hrMin)
                        HrLabel("Макс", health.hrMax)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // HR zones
            if (dayHr.size > 2) {
                val zones = intArrayOf(0, 0, 0, 0, 0)
                for ((_, bpm) in dayHr) {
                    when {
                        bpm < 60 -> zones[0]++
                        bpm < 100 -> zones[1]++
                        bpm < 140 -> zones[2]++
                        bpm < 170 -> zones[3]++
                        else -> zones[4]++
                    }
                }
                val total = zones.sum().toFloat().coerceAtLeast(1f)
                val zoneNames = listOf("Покой", "Лёгкая", "Жиросж.", "Кардио", "Пиковая")
                val zoneColors = listOf(
                    Color(0xFF90CAF9), Color(0xFF4CAF50), Color(0xFFFF9800),
                    Color(0xFFE53935), Color(0xFF9C27B0),
                )

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Зоны пульса", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                            var x = 0f
                            for (i in zones.indices) {
                                val w = (zones[i] / total) * size.width
                                if (w > 0) {
                                    drawRect(zoneColors[i], Offset(x, 0f), Size(w, size.height))
                                    x += w
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            for (i in zones.indices) {
                                if (zones[i] > 0) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Canvas(Modifier.size(8.dp)) { drawCircle(zoneColors[i]) }
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                "${(zones[i] * 100 / total).toInt()}%",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        Text(
                                            zoneNames[i],
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // 7. SpO2 card
        if (health != null && health.spo2Avg > 0) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SpO2", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "${health.spo2Avg}%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = StandBlue,
                            )
                            Text("Среднее", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${health.spo2Min}% — ${health.spo2Max}%", style = MaterialTheme.typography.bodyMedium)
                            Text("Мин — Макс", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 8. Stress card
        if (health != null && health.stressAvg > 0) {
            val stressLabel = when {
                health.stressAvg <= 25 -> "Спокойно"
                health.stressAvg <= 50 -> "Нормально"
                health.stressAvg <= 75 -> "Средний"
                else -> "Высокий"
            }
            val stressColor = Color(0xFFFF9800)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Стресс", style = MaterialTheme.typography.titleSmall)
                        Text(stressLabel, style = MaterialTheme.typography.labelSmall, color = stressColor)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { health.stressAvg / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = stressColor,
                        trackColor = stressColor.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Мин ${health.stressMin}", style = MaterialTheme.typography.labelSmall)
                        Text("Среднее ${health.stressAvg}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("Макс ${health.stressMax}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 9. Sleep card
        val sleepDate = state.lastSleep?.let {
            Instant.ofEpochMilli(it.wakeupTime).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        if (sleepDate == selectedDate && state.lastSleep != null && state.lastSleep.totalMinutes > 0) {
            val sleep = state.lastSleep
            val bedTime = Instant.ofEpochMilli(sleep.bedTime).atZone(ZoneId.systemDefault())
            val wakeTime = Instant.ofEpochMilli(sleep.wakeupTime).atZone(ZoneId.systemDefault())
            val timeRange = "%02d:%02d — %02d:%02d".format(
                bedTime.hour, bedTime.minute, wakeTime.hour, wakeTime.minute,
            )
            val hours = sleep.totalMinutes / 60
            val mins = sleep.totalMinutes % 60

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Сон", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(timeRange, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${hours}ч ${mins}м",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SleepStagesBar(
                        deep = sleep.deepMinutes,
                        light = sleep.lightMinutes,
                        rem = sleep.remMinutes,
                        awake = sleep.awakeMinutes,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 10. Workouts
        val dayWorkouts = filterWorkoutsForDay(state.recentWorkouts, selectedDate)
        if (dayWorkouts.isNotEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Тренировки", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    dayWorkouts.forEachIndexed { index, w ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(w.sportName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${w.durationSec / 60} мин", style = MaterialTheme.typography.bodyMedium)
                                if (w.distanceM > 0) {
                                    Text("%.1f км".format(w.distanceM / 1000.0), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (index < dayWorkouts.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(24.dp))
        } // inner Column with padding
    }
}

@Composable
private fun HrLabel(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = HeartRed,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun filterHrForDay(hrHistory: List<Pair<Long, Int>>, date: LocalDate): List<Pair<Long, Int>> {
    val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val dayEnd = dayStart + 86400_000L
    return hrHistory.filter { it.first in dayStart until dayEnd }
}

private fun filterWorkoutsForDay(workouts: List<WorkoutRecord>, date: LocalDate): List<WorkoutRecord> {
    val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val dayEnd = dayStart + 86400_000L
    return workouts.filter { it.startTime in dayStart until dayEnd }
}

private fun formatDate(date: LocalDate): String {
    val months = arrayOf(
        "", "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    val dows = mapOf(
        java.time.DayOfWeek.MONDAY to "пн", java.time.DayOfWeek.TUESDAY to "вт",
        java.time.DayOfWeek.WEDNESDAY to "ср", java.time.DayOfWeek.THURSDAY to "чт",
        java.time.DayOfWeek.FRIDAY to "пт", java.time.DayOfWeek.SATURDAY to "сб",
        java.time.DayOfWeek.SUNDAY to "вс",
    )
    return "${date.dayOfMonth} ${months[date.monthValue]}, ${dows[date.dayOfWeek]}"
}
