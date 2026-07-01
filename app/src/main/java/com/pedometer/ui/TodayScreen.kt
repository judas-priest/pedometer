package com.pedometer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.ui.components.*
import com.pedometer.vm.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: WatchState,
    onRefresh: () -> Unit = {},
    onTodayTap: () -> Unit = {},
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Step merge logic
    val phoneSteps = when {
        state.todayWalkSteps + state.todayRunSteps > 0 ->
            (state.todayWalkSteps + state.todayRunSteps).toLong()
        else -> state.phoneSteps
    }
    val currentSteps = if (state.watchSteps > 0) state.watchSteps.toLong() else phoneSteps
    val goal = state.profile.stepGoal
    val targetProgress = (currentSteps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 800),
        label = "stepProgress",
    )

    val walkSteps = state.todayWalkSteps
    val runSteps = state.todayRunSteps
    val totalSteps = currentSteps.toInt()

    // Today's HR data
    val todayStartMillis = LocalDate.now()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    val todayHrFull = remember(state.hrHistory, todayStartMillis) {
        state.hrHistory.filter { it.first >= todayStartMillis }
    }
    val todayHr = remember(todayHrFull) {
        if (todayHrFull.size > 200) {
            val step = todayHrFull.size / 200
            todayHrFull.filterIndexed { i, _ -> i % step == 0 }
        } else {
            todayHrFull
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onRefresh()
            scope.launch {
                delay(2000)
                isRefreshing = false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Title
            Text(
                "Здоровье",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 28.dp),
            )

            // 2. Step ring
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTodayTap() },
                contentAlignment = Alignment.Center,
            ) {
                StepRing(
                    progress = animatedProgress,
                    color = StepGreen,
                    size = 160f,
                    strokeWidth = 14f,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$currentSteps",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = StepGreen,
                    )
                    Text(
                        "/ $goal шагов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 3. Step breakdown
            StepMetricCards(walkSteps, runSteps, totalSteps, state.profile, state.watchCalories, state.watchDistanceM, state.activeMinutes)

            // 4. Live metrics row 1: Pulse | Battery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    title = "Пульс",
                    value = if (state.heartRate > 0) "${state.heartRate}" else "—",
                    unit = if (state.heartRate > 0) "уд/мин" else "",
                    color = HeartRed,
                    modifier = Modifier.weight(1f),
                )
                if (state.batteryLevel >= 0) {
                    MetricCard(
                        title = "Батарея часов",
                        value = "${state.batteryLevel}",
                        unit = "%",
                        color = StandBlue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 5. Live metrics row 2: SpO2 | Stress | Rest HR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    title = "SpO2",
                    value = if (state.spo2 > 0) "${state.spo2}" else "—",
                    unit = if (state.spo2 > 0) "%" else "",
                    color = StandBlue,
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "Стресс",
                    value = if (state.stress > 0) "${state.stress}" else "—",
                    unit = "",
                    color = CalorieOrange,
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "Покой",
                    value = if (state.hrResting > 0) "${state.hrResting}" else "—",
                    unit = if (state.hrResting > 0) "уд/мин" else "",
                    color = HeartRed,
                    modifier = Modifier.weight(1f),
                )
            }

            // 6. Today's HR chart
            if (todayHr.size >= 2) {
                val hrMin = todayHrFull.minOf { it.second }
                val hrMax = todayHrFull.maxOf { it.second }
                val hrAvg = todayHrFull.map { it.second }.average().toInt()

                var hrLabel by remember { mutableStateOf("") }
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Пульс",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hrLabel.isNotEmpty()) {
                                Text(
                                    hrLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = HeartRed,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HrChart(
                            data = todayHr,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            onSelect = { hrLabel = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StatItem("Мин", "$hrMin")
                            StatItem("Средний", "$hrAvg")
                            StatItem("Макс", "$hrMax")
                        }
                    }
                }
            }

            // 7. Last night sleep
            val sleep = state.lastSleep
            if (sleep != null && sleep.totalMinutes > 0) {
                val bedTime = Instant.ofEpochMilli(sleep.bedTime)
                    .atZone(ZoneId.systemDefault())
                val wakeTime = Instant.ofEpochMilli(sleep.wakeupTime)
                    .atZone(ZoneId.systemDefault())
                val bedFmt = "%02d:%02d".format(bedTime.hour, bedTime.minute)
                val wakeFmt = "%02d:%02d".format(wakeTime.hour, wakeTime.minute)
                val hours = sleep.totalMinutes / 60
                val mins = sleep.totalMinutes % 60
                val total = sleep.totalMinutes.coerceAtLeast(1)
                val qualityPct = (sleep.deepMinutes + sleep.remMinutes) * 100 / total
                val qualityLabel = when {
                    qualityPct >= 40 -> "Отличный"
                    qualityPct >= 25 -> "Хороший"
                    qualityPct >= 15 -> "Средний"
                    else -> "Плохой"
                }

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "Сон",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "$bedFmt — $wakeFmt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${hours}ч ${mins}мин",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    qualityLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SleepStagesBar(
                            deep = sleep.deepMinutes,
                            light = sleep.lightMinutes,
                            rem = sleep.remMinutes,
                            awake = sleep.awakeMinutes,
                        )
                    }
                }
            }
        }
    }
}
