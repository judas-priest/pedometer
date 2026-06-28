package com.pedometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.vm.WatchState
import java.time.LocalDate

private val StepGreen = Color(0xFF4CAF50)

@Composable
fun ActivityTab(state: WatchState) {
    val history = state.stepHistory
    val profile = state.profile

    // Weekly summary
    val today = LocalDate.now()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("Активность", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // Weekly summary card
        Text(
            "За неделю",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatItem(label = "Шагов", value = "%,d".format(weekSteps))
                    StatItem(label = "Среднее", value = "%,d".format(avgSteps))
                    StatItem(label = "Цель", value = "$goalDays/7 дн")
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatItem(label = "Дистанция", value = "%.1f км".format(weekDistance))
                    StatItem(label = "Калории", value = "%.0f ккал".format(weekCalories))
                    StatItem(label = "Лучший", value = "%,d".format(weekDays.maxOfOrNull { it.totalSteps } ?: 0))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Monthly summary
        val monthDays = history.filter {
            try {
                val d = LocalDate.parse(it.date)
                !d.isBefore(today.minusDays(29))
            } catch (_: Exception) { false }
        }
        if (monthDays.size > 7) {
            val monthSteps = monthDays.sumOf { it.totalSteps }
            val monthAvg = monthSteps / monthDays.size
            val monthGoalDays = monthDays.count { it.totalSteps >= profile.stepGoal }

            Text(
                "За месяц",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatItem(label = "Шагов", value = "%,d".format(monthSteps))
                        StatItem(label = "Среднее", value = "%,d".format(monthAvg))
                        StatItem(label = "Цель", value = "$monthGoalDays/${monthDays.size} дн")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Streaks
        val streak = calculateStreak(history, profile.stepGoal)
        if (streak > 0) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Серия", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$streak ${daysLabel(streak)} подряд цель выполнена",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StepGreen,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun calculateStreak(
    history: List<com.pedometer.health.DayStepData>,
    goal: Int,
): Int {
    val sorted = history.sortedByDescending { it.date }
    var streak = 0
    for (day in sorted) {
        if (day.totalSteps >= goal) streak++ else break
    }
    return streak
}

private fun daysLabel(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
}
