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
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pedometer.data.DailyHealth
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

    var showMetric by remember { mutableStateOf("") }

    if (showMetric.isNotEmpty()) {
        androidx.activity.compose.BackHandler { showMetric = "" }
        MetricDetailScreen(
            metric = showMetric,
            hrData = todayHr,
            hrFull = todayHrFull,
            healthHistory = state.healthHistory,
            onBack = { showMetric = "" },
        )
        return
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

            // 4. Tappable metric cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    title = "Пульс",
                    value = if (state.heartRate > 0) "${state.heartRate}" else "—",
                    unit = if (state.heartRate > 0) "уд/мин" else "",
                    color = HeartRed,
                    modifier = Modifier.weight(1f).clickable { showMetric = "hr" },
                )
                if (state.batteryLevel >= 0) {
                    MetricCard(
                        title = "Батарея",
                        value = "${state.batteryLevel}",
                        unit = "%",
                        color = StandBlue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    title = "SpO2",
                    value = if (state.spo2 > 0) "${state.spo2}" else "—",
                    unit = if (state.spo2 > 0) "%" else "",
                    color = StandBlue,
                    modifier = Modifier.weight(1f).clickable { showMetric = "spo2" },
                )
                MetricCard(
                    title = "Стресс",
                    value = if (state.stress > 0) "${state.stress}" else "—",
                    unit = "",
                    color = CalorieOrange,
                    modifier = Modifier.weight(1f).clickable { showMetric = "stress" },
                )
                MetricCard(
                    title = "Покой",
                    value = if (state.hrResting > 0) "${state.hrResting}" else "—",
                    unit = if (state.hrResting > 0) "уд/мин" else "",
                    color = HeartRed,
                    modifier = Modifier.weight(1f).clickable { showMetric = "resting" },
                )
            }

            // (metric detail opens as full screen via showMetric state above)

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

@Composable
private fun MetricDetailScreen(
    metric: String,
    hrData: List<Pair<Long, Int>>,
    hrFull: List<Pair<Long, Int>>,
    healthHistory: List<DailyHealth>,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Back + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
            }
            Text(
                when (metric) {
                    "hr" -> "Пульс"
                    "spo2" -> "SpO2"
                    "stress" -> "Стресс"
                    "resting" -> "Пульс покоя"
                    else -> ""
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            when (metric) {
                "hr" -> {
                    // Today's HR chart
                    if (hrData.size >= 2) {
                        Spacer(Modifier.height(16.dp))
                        Text("Сегодня", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        var label by remember { mutableStateOf("") }
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                if (label.isNotEmpty()) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = HeartRed)
                                    Spacer(Modifier.height(4.dp))
                                }
                                HrChart(data = hrData, modifier = Modifier.fillMaxWidth().height(200.dp), onSelect = { label = it })
                                Spacer(Modifier.height(8.dp))
                                val min = hrFull.minOf { it.second }
                                val max = hrFull.maxOf { it.second }
                                val avg = hrFull.map { it.second }.average().toInt()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    StatItem("Мин", "$min")
                                    StatItem("Средний", "$avg")
                                    StatItem("Макс", "$max")
                                }
                            }
                        }
                    }
                    // Weekly HR trend
                    val hrHistory = healthHistory.filter { it.hrAvg > 0 }.sortedBy { it.date }.takeLast(7)
                    if (hrHistory.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("За неделю", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        WeeklyBarChartFull(hrHistory, { it.hrAvg }, HeartRed, "уд/мин")
                    }
                }
                "spo2" -> {
                    val spo2Data = healthHistory.filter { it.spo2Avg in 1..100 }.sortedBy { it.date }.takeLast(14)
                    Spacer(Modifier.height(16.dp))
                    if (spo2Data.isNotEmpty()) {
                        WeeklyBarChartFull(spo2Data, { it.spo2Avg }, StandBlue, "%")
                    } else {
                        Text("Нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "stress" -> {
                    val stressData = healthHistory.filter { it.stressAvg > 0 }.sortedBy { it.date }.takeLast(14)
                    Spacer(Modifier.height(16.dp))
                    if (stressData.isNotEmpty()) {
                        WeeklyBarChartFull(stressData, { it.stressAvg }, CalorieOrange, "")
                    } else {
                        Text("Нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "resting" -> {
                    val restData = healthHistory.filter { it.hrResting > 0 }.sortedBy { it.date }.takeLast(14)
                    Spacer(Modifier.height(16.dp))
                    if (restData.isNotEmpty()) {
                        WeeklyBarChartFull(restData, { it.hrResting }, HeartRed, "уд/мин")
                    } else {
                        Text("Нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WeeklyBarChartFull(
    data: List<DailyHealth>,
    getValue: (DailyHealth) -> Int,
    color: Color,
    unit: String,
) {
    val values = data.map { getValue(it) }
    val maxVal = values.max().toFloat().coerceAtLeast(1f)
    val minVal = values.min()
    val avg = values.average().toInt()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Мин", "$minVal")
                StatItem("Среднее", "$avg")
                StatItem("Макс", "${values.max()}")
            }
            Spacer(Modifier.height(12.dp))

            Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                val barW = (size.width / data.size) - 6f
                data.forEachIndexed { i, _ ->
                    val v = values[i]
                    val h = (v / maxVal) * size.height * 0.85f
                    val x = i * (barW + 6f) + 3f
                    drawRect(color.copy(alpha = 0.8f), Offset(x, size.height - h), Size(barW, h))
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                data.forEach { h ->
                    val dayLabel = try {
                        val d = java.time.LocalDate.parse(h.date)
                        "%d.%02d".format(d.dayOfMonth, d.monthValue)
                    } catch (_: Exception) { "" }
                    Text(dayLabel, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}
