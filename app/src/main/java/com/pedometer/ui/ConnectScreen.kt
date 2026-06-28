package com.pedometer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.vm.ConnectionStatus
import com.pedometer.vm.WatchState

private val StepGreen = Color(0xFF4CAF50)
private val HeartRed = Color(0xFFE53935)
private val CalorieOrange = Color(0xFFFF9800)
private val StandBlue = Color(0xFF42A5F5)

@Composable
fun ConnectScreen(
    state: WatchState,
    onAuthKeyChange: (String) -> Unit,
    onMacChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var selectedDay by remember { mutableStateOf<com.pedometer.health.DayStepData?>(null) }

    if (selectedDay != null) {
        androidx.activity.compose.BackHandler { selectedDay = null }
        DayDetailScreen(day = selectedDay!!, profile = state.profile, onBack = { selectedDay = null })
        return
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // Step ring hero
        val stepGoal = state.profile.stepGoal
        // Priority: Health Connect (accurate daily) > StepProvider > phone sensor session
        val currentSteps = when {
            state.healthConnectSteps > 0 -> state.healthConnectSteps
            state.todayWalkSteps + state.todayRunSteps > 0 -> (state.todayWalkSteps + state.todayRunSteps).toLong()
            else -> state.phoneSteps
        }
        val progress = (currentSteps.toFloat() / stepGoal).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures {
                        // Tap ring → open today detail
                        val todayData = com.pedometer.health.DayStepData(
                            date = java.time.LocalDate.now().toString(),
                            totalSteps = currentSteps.toInt(),
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
            StepRing(progress = progress, color = StepGreen, size = 160f, strokeWidth = 14f)
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
            totalSteps = (state.todayWalkSteps + state.todayRunSteps),
            profile = state.profile,
        )

        // Step history bar chart
        if (state.stepHistory.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "За неделю",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            StepHistoryChart(history = state.stepHistory, goal = stepGoal, onDayTap = { selectedDay = it })
        }

        Spacer(Modifier.height(24.dp))

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
    val maxSteps = (history.maxOfOrNull { it.totalSteps } ?: goal).coerceAtLeast(goal)
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
fun DayDetailScreen(day: com.pedometer.health.DayStepData, profile: com.pedometer.health.UserProfile = com.pedometer.health.UserProfile(), onBack: () -> Unit) {
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
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                )
            }
            Text(
                dateFormatted,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            val progress = (day.totalSteps.toFloat() / profile.stepGoal).coerceIn(0f, 1f)
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
        }
    }
}

