package com.pedometer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.data.HourlySteps
import com.pedometer.vm.WatchState

private val StepGreen = Color(0xFF4CAF50)
private val HeartRed = Color(0xFFE53935)
private val CalorieOrange = Color(0xFFFF9800)
private val StandBlue = Color(0xFF42A5F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: WatchState,
) {
    var selectedDay by remember { mutableStateOf<com.pedometer.health.DayStepData?>(null) }

    if (selectedDay != null) {
        androidx.activity.compose.BackHandler { selectedDay = null }
        val isToday = selectedDay!!.date == java.time.LocalDate.now().toString()
        val history = state.stepHistory
        DayDetailScreen(
            day = selectedDay!!,
            profile = state.profile,
            hourlySteps = if (isToday) state.todayHourlySteps else emptyList(),
            onBack = { selectedDay = null },
            onPrevDay = {
                val idx = history.indexOfFirst { it.date == selectedDay!!.date }
                if (idx in 0 until history.size - 1) selectedDay = history[idx + 1]
            },
            onNextDay = {
                val idx = history.indexOfFirst { it.date == selectedDay!!.date }
                if (idx > 0) selectedDay = history[idx - 1]
            },
            hasPrev = history.indexOfFirst { it.date == selectedDay!!.date }.let { it in 0 until history.size - 1 },
            hasNext = history.indexOfFirst { it.date == selectedDay!!.date } > 0,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 28.dp, start = 20.dp, end = 20.dp),
    ) {
        Text(
            "Здоровье",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        // Smart step merge: watch priority, phone delta when disconnected
        val stepGoal = state.profile.stepGoal
        val phoneSteps = when {
            state.todayWalkSteps + state.todayRunSteps > 0 -> (state.todayWalkSteps + state.todayRunSteps).toLong()
            else -> state.phoneSteps
        }
        val currentSteps = if (state.watchSteps > 0) {
            state.watchSteps.toLong() // Watch connected — use watch data
        } else {
            phoneSteps // No watch — use phone
        }
        val totalStepsInt = currentSteps.toInt()
        val progress = if (stepGoal > 0) (currentSteps.toFloat() / stepGoal).coerceIn(0f, 1f) else 0f
        val animProgress by animateFloatAsState(progress, tween(1000, easing = FastOutSlowInEasing), label = "steps")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures {
                        val todayData = com.pedometer.health.DayStepData(
                            date = java.time.LocalDate.now().toString(),
                            totalSteps = totalStepsInt,
                            walkSteps = state.todayWalkSteps,
                            runSteps = state.todayRunSteps,
                            walkMinutes = 0,
                            runMinutes = 0,
                        )
                        selectedDay = todayData
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            StepRing(progress = animProgress, color = StepGreen, size = 160f, strokeWidth = 14f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$currentSteps",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = StepGreen,
                )
                Text(
                    "/ $stepGoal шагов",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        StepMetricCards(
            walkSteps = state.todayWalkSteps,
            runSteps = state.todayRunSteps,
            totalSteps = totalStepsInt,
            profile = state.profile,
        )

        // Heart rate + watch battery (always show if watch connected)
        if (state.connectionStatus == com.pedometer.vm.ConnectionStatus.Connected || state.batteryLevel >= 0) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    title = if (state.heartRate > 0) "Пульс ●" else "Пульс",
                    value = if (state.heartRate > 0) "${state.heartRate}" else "—",
                    unit = "уд/мин",
                    color = HeartRed,
                    modifier = Modifier.weight(1f),
                )
                if (state.batteryLevel >= 0) {
                    MetricCard(
                        title = "Часы",
                        value = "${state.batteryLevel}",
                        unit = "%",
                        color = StandBlue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Step history with period tabs
        if (state.stepHistory.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))

            var selectedPeriod by remember { mutableIntStateOf(0) }
            val periods = listOf("Неделя", "Месяц")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                periods.forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedPeriod == index,
                        onClick = { selectedPeriod = index },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val displayHistory = when (selectedPeriod) {
                0 -> state.stepHistory.reversed().take(7)
                1 -> state.stepHistory.reversed().take(30)
                else -> state.stepHistory.reversed().take(7)
            }
            StepHistoryChart(history = displayHistory, goal = stepGoal, onDayTap = { selectedDay = it })
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun StepRing(progress: Float, color: Color, size: Float, strokeWidth: Float) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val stroke = Stroke(width = strokeWidth.dp.toPx(), cap = StrokeCap.Round)
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        val topLeft = Offset(stroke.width / 2, stroke.width / 2)

        // Track
        drawArc(
            color = color.copy(alpha = 0.15f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // Progress
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}

@Composable
fun ActivityRings(
    stepsProgress: Float,
    caloriesProgress: Float,
    distanceProgress: Float,
    size: Float,
) {
    val colors = listOf(StepGreen, CalorieOrange, StandBlue)
    val progresses = listOf(stepsProgress, caloriesProgress, distanceProgress)

    Canvas(modifier = Modifier.size(size.dp)) {
        val strokeWidth = 12.dp.toPx()
        val gap = 6.dp.toPx()

        progresses.forEachIndexed { index, progress ->
            val inset = (strokeWidth + gap) * index + strokeWidth / 2
            val arcSize = Size(
                this.size.width - inset * 2,
                this.size.height - inset * 2,
            )
            val topLeft = Offset(inset, inset)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            // Track
            drawArc(
                color = colors[index].copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // Progress
            if (progress > 0f) {
                drawArc(
                    color = colors[index],
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun StepMetricCards(
    walkSteps: Int,
    runSteps: Int,
    totalSteps: Int,
    profile: com.pedometer.health.UserProfile,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricCard(title = "Ходьба", value = "$walkSteps", unit = "шагов", color = StepGreen, modifier = Modifier.weight(1f))
        MetricCard(title = "Бег", value = "$runSteps", unit = "шагов", color = CalorieOrange, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricCard(title = "Калории", value = "%.0f".format(profile.calcCalories(totalSteps)), unit = "ккал", color = HeartRed, modifier = Modifier.weight(1f))
        MetricCard(title = "Дистанция", value = "%.2f".format(profile.calcDistance(totalSteps)), unit = "км", color = StandBlue, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StepHistoryChart(history: List<com.pedometer.health.DayStepData>, goal: Int, onDayTap: (com.pedometer.health.DayStepData) -> Unit = {}) {
    val maxSteps = (history.maxOfOrNull { it.totalSteps } ?: goal).coerceAtLeast(goal).coerceAtLeast(1)
    val barColor = StepGreen
    val goalColor = StepGreen.copy(alpha = 0.3f)
    val days = history.reversed().take(7)
    var selectedDay by remember { mutableStateOf<com.pedometer.health.DayStepData?>(null) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .pointerInput(days) {
                        detectTapGestures { offset ->
                            val barCount = days.size
                            if (barCount == 0) return@detectTapGestures
                            val barWidth = size.width / (barCount * 2f)
                            val spacing = barWidth
                            val tappedIndex = ((offset.x - spacing / 2) / (barWidth + spacing)).toInt()
                            if (tappedIndex in days.indices) {
                                selectedDay = days[tappedIndex]
                                onDayTap(days[tappedIndex])
                            }
                        }
                    }
            ) {
                val barCount = days.size
                if (barCount == 0) return@Canvas
                val barWidth = size.width / (barCount * 2f)
                val spacing = barWidth

                val goalY = size.height * (1f - goal.toFloat() / maxSteps)
                drawLine(
                    color = goalColor,
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 2f,
                )

                days.forEachIndexed { i, day ->
                    val barHeight = (day.totalSteps.toFloat() / maxSteps) * size.height
                    val x = i * (barWidth + spacing) + spacing / 2
                    val isSelected = selectedDay?.date == day.date
                    val color = when {
                        isSelected -> barColor
                        day.totalSteps >= goal -> barColor.copy(alpha = 0.8f)
                        else -> barColor.copy(alpha = 0.4f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { day ->
                    val isSelected = selectedDay?.date == day.date
                    Text(
                        day.date.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) StepGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    day: com.pedometer.health.DayStepData,
    profile: com.pedometer.health.UserProfile = com.pedometer.health.UserProfile(),
    hourlySteps: List<HourlySteps> = emptyList(),
    onBack: () -> Unit,
    onPrevDay: () -> Unit = {},
    onNextDay: () -> Unit = {},
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
) {
    val dateFormatted = try {
        val ld = java.time.LocalDate.parse(day.date)
        val months = arrayOf("", "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря")
        val dows = mapOf(
            java.time.DayOfWeek.MONDAY to "пн", java.time.DayOfWeek.TUESDAY to "вт",
            java.time.DayOfWeek.WEDNESDAY to "ср", java.time.DayOfWeek.THURSDAY to "чт",
            java.time.DayOfWeek.FRIDAY to "пт", java.time.DayOfWeek.SATURDAY to "сб",
            java.time.DayOfWeek.SUNDAY to "вс",
        )
        "${ld.dayOfMonth} ${months[ld.monthValue]}, ${dows[ld.dayOfWeek]}"
    } catch (_: Exception) { day.date }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 28.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Активность за день",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Date navigation: < date >
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = onPrevDay,
                enabled = hasPrev,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Предыдущий день",
                    modifier = Modifier.size(18.dp),
                    tint = if (hasPrev) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                dateFormatted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onNextDay,
                enabled = hasNext,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Следующий день",
                    modifier = Modifier.size(18.dp),
                    tint = if (hasNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val progress = if (profile.stepGoal > 0) (day.totalSteps.toFloat() / profile.stepGoal).coerceIn(0f, 1f) else 0f
            Box(contentAlignment = Alignment.Center) {
                StepRing(progress = progress, color = StepGreen, size = 160f, strokeWidth = 14f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${day.totalSteps}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = StepGreen,
                    )
                    Text(
                        "/ ${profile.stepGoal} шагов",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            StepMetricCards(
                walkSteps = day.walkSteps,
                runSteps = day.runSteps,
                totalSteps = day.totalSteps,
                profile = profile,
            )

        if (hourlySteps.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            HourlyStepChart(hourlySteps = hourlySteps)
        }
        Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun HourlyStepChart(hourlySteps: List<HourlySteps>) {
    val hourMap = hourlySteps.associate { it.hour to it.steps }
    val maxSteps = (hourMap.values.maxOrNull() ?: 1).coerceAtLeast(1)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "По часам",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            ) {
                val barCount = 24
                val gap = 2.dp.toPx()
                val barWidth = (size.width - gap * (barCount - 1)) / barCount

                for (hour in 0 until barCount) {
                    val steps = hourMap[hour] ?: 0
                    val barHeight = if (maxSteps > 0) (steps.toFloat() / maxSteps) * size.height else 0f
                    val x = hour * (barWidth + gap)

                    drawRoundRect(
                        color = StepGreen.copy(alpha = if (steps > 0) 0.7f else 0.15f),
                        topLeft = Offset(x, size.height - barHeight.coerceAtLeast(if (steps > 0) 2f else 0f)),
                        size = Size(barWidth, barHeight.coerceAtLeast(if (steps > 0) 2f else 0f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("0", "6", "12", "18", "24").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

