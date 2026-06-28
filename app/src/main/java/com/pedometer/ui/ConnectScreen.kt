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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Step ring hero
        val stepGoal = 6000
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
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            StepRing(progress = progress, color = StepGreen, size = 200f, strokeWidth = 16f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$currentSteps",
                    fontSize = 48.sp,
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

        // Metric cards grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                title = "Ходьба",
                value = "${state.todayWalkSteps}",
                unit = "${state.todayWalkMinutes} мин",
                color = StepGreen,
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "Бег",
                value = "${state.todayRunSteps}",
                unit = "шагов",
                color = CalorieOrange,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            run {
                val hr = when {
                    state.heartRate > 0 -> state.heartRate
                    state.healthConnectHR > 0 -> state.healthConnectHR
                    else -> 0
                }
                MetricCard(
                    title = "Пульс",
                    value = if (hr > 0) "$hr" else "--",
                    unit = "уд/мин",
                    color = HeartRed,
                    modifier = Modifier.weight(1f),
                )
            }
            MetricCard(
                title = "Активность",
                value = "${state.todayWalkMinutes}",
                unit = "мин",
                color = StandBlue,
                modifier = Modifier.weight(1f),
            )
        }

        // Step history bar chart
        if (state.stepHistory.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "За неделю",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            StepHistoryChart(history = state.stepHistory, goal = stepGoal)
        }

        Spacer(Modifier.height(24.dp))

        // Watch connection section
        Text(
            "Часы",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                when (state.connectionStatus) {
                    ConnectionStatus.Disconnected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Redmi Watch 5 Lite", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (state.macAddress.isNotBlank()) state.macAddress else "Не настроено",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    val perms = mutableListOf<String>()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                                        perms.add(Manifest.permission.BLUETOOTH_SCAN)
                                    }
                                    perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
                                    }
                                    permissionLauncher.launch(perms.toTypedArray())
                                },
                                enabled = state.authKey.isNotBlank() && state.macAddress.isNotBlank(),
                            ) {
                                Text("Подключить")
                            }
                        }
                    }
                    ConnectionStatus.Connecting, ConnectionStatus.Authenticating -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (state.connectionStatus == ConnectionStatus.Connecting) "Подключение..." else "Авторизация...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    ConnectionStatus.Connected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (state.model.isNotBlank()) state.model else "Подключено",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (state.batteryLevel >= 0) {
                                    Text(
                                        "Заряд ${state.batteryLevel}%${if (state.batteryCharging) " заряжается" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            ) {
                                Text("Отключить")
                            }
                        }
                    }
                }
            }
        }

        // Settings (collapsed by default)
        Spacer(Modifier.height(8.dp))
        var showSettings by remember { mutableStateOf(state.macAddress.isBlank()) }
        TextButton(onClick = { showSettings = !showSettings }) {
            Text(if (showSettings) "Скрыть настройки" else "Настройки")
        }
        if (showSettings) {
            OutlinedTextField(
                value = state.macAddress,
                onValueChange = onMacChange,
                label = { Text("MAC Address") },
                placeholder = { Text("XX:XX:XX:XX:XX:XX") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.authKey,
                onValueChange = onAuthKeyChange,
                label = { Text("Auth Key") },
                placeholder = { Text("0x...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
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
fun StepHistoryChart(history: List<com.pedometer.health.DayStepData>, goal: Int) {
    val maxSteps = (history.maxOfOrNull { it.totalSteps } ?: goal).coerceAtLeast(goal)
    val barColor = StepGreen
    val goalColor = StepGreen.copy(alpha = 0.3f)
    val days = history.reversed().take(7)
    var selectedDay by remember { mutableStateOf<com.pedometer.health.DayStepData?>(null) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Selected day info
            if (selectedDay != null) {
                val d = selectedDay!!
                Text(
                    "${d.date}: ${d.totalSteps} шагов (${d.walkSteps} ходьба, ${d.runSteps} бег)",
                    style = MaterialTheme.typography.bodySmall,
                    color = StepGreen,
                )
                Spacer(Modifier.height(8.dp))
            }

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
