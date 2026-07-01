package com.pedometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pedometer.ui.components.*
import com.pedometer.vm.WatchState
import java.time.LocalDate

@Composable
fun ActivityScreen(
    state: WatchState,
    onDayTap: (LocalDate) -> Unit = {},
) {
    val history = state.stepHistory
    val profile = state.profile
    val today = LocalDate.now()

    // Weekly data
    val weekDays = history.filter {
        try {
            val d = LocalDate.parse(it.date)
            !d.isBefore(today.minusDays(6))
        } catch (_: Exception) { false }
    }
    val weekSteps = weekDays.sumOf { it.totalSteps }
    val weekDistance = profile.calcDistance(weekSteps)
    val weekCalories = profile.calcCalories(weekSteps)
    val avgSteps = if (weekDays.isNotEmpty()) weekSteps / weekDays.size else 0
    val goalDays = weekDays.count { it.totalSteps >= profile.stepGoal }

    // Monthly data
    val monthDays = history.filter {
        try {
            val d = LocalDate.parse(it.date)
            !d.isBefore(today.minusDays(29))
        } catch (_: Exception) { false }
    }

    // Health averages
    val validHealth = state.healthHistory.filter { it.date.startsWith("202") && it.hrAvg > 0 }

    // Period selector
    var selectedPeriod by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            "Активность",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp),
        )

        Spacer(Modifier.height(12.dp))

        // Period tabs
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Неделя", "Месяц").forEachIndexed { i, label ->
                FilterChip(
                    selected = selectedPeriod == i,
                    onClick = { selectedPeriod = i },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Step history chart (tappable → opens day detail)
        if (history.isNotEmpty()) {
            val displayHistory = when (selectedPeriod) {
                0 -> history.reversed().take(7)
                else -> history.reversed().take(30)
            }
            StepHistoryChart(
                history = displayHistory,
                goal = profile.stepGoal,
                onDayTap = { dayData ->
                    try { onDayTap(LocalDate.parse(dayData.date)) } catch (_: Exception) {}
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // Steps summary
        val periodDays = if (selectedPeriod == 0) weekDays else monthDays
        val periodSteps = periodDays.sumOf { it.totalSteps }
        val periodAvg = if (periodDays.isNotEmpty()) periodSteps / periodDays.size else 0
        val periodGoalDays = periodDays.count { it.totalSteps >= profile.stepGoal }
        val periodDistance = profile.calcDistance(periodSteps)
        val periodCalories = profile.calcCalories(periodSteps)
        val periodLabel = if (selectedPeriod == 0) "За неделю" else "За месяц"

        Text(periodLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Шагов", "%,d".format(periodSteps))
                    StatItem("Среднее", "%,d".format(periodAvg))
                    StatItem("Цель", "$periodGoalDays/${periodDays.size} дн")
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Дистанция", "%.1f км".format(periodDistance))
                    StatItem("Калории", "%.0f ккал".format(periodCalories))
                    StatItem("Лучший", "%,d".format(periodDays.maxOfOrNull { it.totalSteps } ?: 0))
                }
            }
        }

        // Streak
        val streak = calculateStreak(history, profile.stepGoal)
        if (streak > 0) {
            Spacer(Modifier.height(12.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("$streak ${daysLabel(streak)} подряд цель выполнена",
                        style = MaterialTheme.typography.bodyMedium, color = StepGreen, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Health summary — filtered by same period as steps
        if (validHealth.isNotEmpty()) {
            val periodDaysCount = if (selectedPeriod == 0) 6 else 29
            val periodHealth = validHealth.filter {
                try {
                    val d = LocalDate.parse(it.date)
                    !d.isBefore(today.minusDays(periodDaysCount.toLong()))
                } catch (_: Exception) { false }
            }
            if (periodHealth.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Здоровье (${periodHealth.size} дн.)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                val avgHr = periodHealth.map { it.hrAvg }.average().toInt()
                val avgResting = periodHealth.filter { it.hrResting > 0 }.let {
                    if (it.isNotEmpty()) it.map { h -> h.hrResting }.average().toInt() else 0
                }
                val avgSpo2 = periodHealth.filter { it.spo2Avg in 1..100 }.let {
                    if (it.isNotEmpty()) it.map { h -> h.spo2Avg }.average().toInt() else 0
                }
                val avgStress = periodHealth.filter { it.stressAvg > 0 }.let {
                    if (it.isNotEmpty()) it.map { h -> h.stressAvg }.average().toInt() else 0
                }
                val maxHr = periodHealth.maxOf { it.hrMax }
                val minHr = periodHealth.filter { it.hrMin > 0 }.minOfOrNull { it.hrMin } ?: 0

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Пульс", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$avgHr", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HeartRed)
                                Text("$minHr–$maxHr", style = MaterialTheme.typography.labelSmall)
                            }
                            if (avgResting > 0) Column {
                                Text("Покой", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$avgResting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            if (avgSpo2 > 0) Column {
                                Text("SpO2", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$avgSpo2%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StandBlue)
                            }
                            if (avgStress > 0) Column {
                                Text("Стресс", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$avgStress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CalorieOrange)
                            }
                        }
                    }
                }
            }
        }

        // Workouts
        if (state.recentWorkouts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Тренировки", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    state.recentWorkouts.take(5).forEachIndexed { i, w ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(w.sportName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                val date = java.time.Instant.ofEpochMilli(w.startTime)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                Text(date.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${w.durationSec / 60} мин", style = MaterialTheme.typography.bodyMedium)
                                if (w.distanceM > 0) Text("%.1f км".format(w.distanceM / 1000.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (i < state.recentWorkouts.take(5).lastIndex) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun calculateStreak(history: List<com.pedometer.health.DayStepData>, goal: Int): Int {
    val sorted = history.sortedByDescending { it.date }
    var streak = 0
    for (day in sorted) { if (day.totalSteps >= goal) streak++ else break }
    return streak
}

private fun daysLabel(n: Int): String {
    val mod10 = n % 10; val mod100 = n % 100
    return when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
}
