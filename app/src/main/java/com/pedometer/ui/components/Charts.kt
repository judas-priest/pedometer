package com.pedometer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pedometer.data.HourlySteps
import com.pedometer.health.DayStepData

// ── Shared Colors ──────────────────────────────────────────────────────────────

internal val StepGreen = Color(0xFF4CAF50)
internal val HeartRed = Color(0xFFE53935)
internal val CalorieOrange = Color(0xFFFF9800)
internal val StandBlue = Color(0xFF42A5F5)

// ── StepRing ───────────────────────────────────────────────────────────────────

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

// ── HrChart ────────────────────────────────────────────────────────────────────

@Composable
fun HrChart(data: List<Pair<Long, Int>>, modifier: Modifier = Modifier, onSelect: ((String) -> Unit)? = null) {
    val lineColor = HeartRed
    val fillColor = HeartRed.copy(alpha = 0.15f)
    val gridColor = Color.Gray.copy(alpha = 0.2f)

    var selectedIndex by remember { mutableStateOf(-1) }
    val selectedPoint = if (selectedIndex in data.indices) data[selectedIndex] else null

    Box {
        Canvas(modifier = modifier.pointerInput(data.size) {
            detectTapGestures { offset ->
                if (data.size < 2) return@detectTapGestures
                val minTime = data.minOf { it.first }
                val maxTime = data.maxOf { it.first }
                val timeRange = (maxTime - minTime).coerceAtLeast(1)
                val tapX = offset.x
                val w = size.width.toFloat()

                // Find closest point
                var closest = 0
                var closestDist = Float.MAX_VALUE
                for (i in data.indices) {
                    val x = ((data[i].first - minTime).toFloat() / timeRange) * w
                    val dist = kotlin.math.abs(x - tapX)
                    if (dist < closestDist) {
                        closestDist = dist
                        closest = i
                    }
                }
                selectedIndex = closest
                val pt = data[closest]
                val t = java.time.Instant.ofEpochMilli(pt.first).atZone(java.time.ZoneId.systemDefault())
                onSelect?.invoke("%02d:%02d — %d уд/мин".format(t.hour, t.minute, pt.second))
            }
        }) {
            if (data.size < 2) return@Canvas

            val minTime = data.minOf { it.first }
            val maxTime = data.maxOf { it.first }
            val minHr = (data.minOf { it.second } - 5).coerceAtLeast(40)
            val maxHr = (data.maxOf { it.second } + 5).coerceAtMost(200)
            val timeRange = (maxTime - minTime).coerceAtLeast(1)
            val hrRange = (maxHr - minHr).coerceAtLeast(1)

            val w = size.width
            val h = size.height

            for (hr in listOf(60, 80, 100, 120, 140, 160)) {
                if (hr in minHr..maxHr) {
                    val y = h - (hr - minHr).toFloat() / hrRange * h
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
            }

            val points = data.map { (t, hr) ->
                val x = ((t - minTime).toFloat() / timeRange) * w
                val y = h - ((hr - minHr).toFloat() / hrRange) * h
                Offset(x, y)
            }

            val path = Path().apply {
                moveTo(points.first().x, h)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, h)
                close()
            }
            drawPath(path, fillColor)

            for (i in 0 until points.size - 1) {
                drawLine(lineColor, points[i], points[i + 1], strokeWidth = 2f, cap = StrokeCap.Round)
            }

            // Draw selected point marker
            if (selectedIndex in points.indices) {
                val p = points[selectedIndex]
                drawCircle(lineColor, 6f, p)
                drawCircle(Color.White, 3f, p)
                // Vertical line
                drawLine(gridColor, Offset(p.x, 0f), Offset(p.x, h), strokeWidth = 1f)
            }
        }

    }
}

// ── HourlyStepChart ────────────────────────────────────────────────────────────

@Composable
fun HourlyStepChart(hourlySteps: List<HourlySteps>) {
    val hourMap = hourlySteps.associate { it.hour to it.steps }
    val maxSteps = (hourMap.values.maxOrNull() ?: 1).coerceAtLeast(1)
    var selectedHour by remember { mutableStateOf(-1) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "По часам",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selectedHour >= 0) {
                    val steps = hourMap[selectedHour] ?: 0
                    Text(
                        "%02d:00 — %,d шагов".format(selectedHour, steps),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = StepGreen,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val barCount = 24
                            val gap = 2.dp.toPx()
                            val barWidth = (size.width - gap * (barCount - 1)) / barCount
                            val tappedHour = (offset.x / (barWidth + gap)).toInt().coerceIn(0, 23)
                            selectedHour = tappedHour
                        }
                    }
            ) {
                val barCount = 24
                val gap = 2.dp.toPx()
                val barWidth = (size.width - gap * (barCount - 1)) / barCount

                for (hour in 0 until barCount) {
                    val steps = hourMap[hour] ?: 0
                    val barHeight = if (maxSteps > 0) (steps.toFloat() / maxSteps) * size.height else 0f
                    val x = hour * (barWidth + gap)
                    val isSelected = hour == selectedHour

                    drawRoundRect(
                        color = StepGreen.copy(alpha = when {
                            isSelected -> 1f
                            steps > 0 -> 0.7f
                            else -> 0.15f
                        }),
                        topLeft = Offset(x, size.height - barHeight.coerceAtLeast(if (steps > 0) 2f else 0f)),
                        size = Size(barWidth, barHeight.coerceAtLeast(if (steps > 0) 2f else 0f)),
                        cornerRadius = CornerRadius(4f, 4f),
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

// ── StepHistoryChart ───────────────────────────────────────────────────────────

@Composable
fun StepHistoryChart(history: List<DayStepData>, goal: Int, onDayTap: (DayStepData) -> Unit = {}) {
    val maxSteps = (history.maxOfOrNull { it.totalSteps } ?: goal).coerceAtLeast(goal).coerceAtLeast(1)
    val barColor = StepGreen
    val goalColor = StepGreen.copy(alpha = 0.3f)
    val days = history.reversed().take(7)
    var selectedDay by remember { mutableStateOf<DayStepData?>(null) }

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
                        cornerRadius = CornerRadius(8f, 8f),
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

// ── SleepStagesBar ─────────────────────────────────────────────────────────────

@Composable
fun SleepStagesBar(deep: Int, light: Int, rem: Int, awake: Int, modifier: Modifier = Modifier) {
    val total = (deep + light + rem + awake).toFloat().coerceAtLeast(1f)
    val deepFrac = deep / total
    val lightFrac = light / total
    val remFrac = rem / total
    val awakeFrac = awake / total

    val deepColor = Color(0xFF3F51B5)
    val lightColor = Color(0xFF7986CB)
    val remColor = Color(0xFFFF9800)
    val awakeColor = Color(0xFFEF5350)

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
            val w = size.width
            val h = size.height
            var x = 0f
            drawRect(deepColor, Offset(x, 0f), Size(w * deepFrac, h)); x += w * deepFrac
            drawRect(lightColor, Offset(x, 0f), Size(w * lightFrac, h)); x += w * lightFrac
            drawRect(remColor, Offset(x, 0f), Size(w * remFrac, h)); x += w * remFrac
            drawRect(awakeColor, Offset(x, 0f), Size(w * awakeFrac, h))
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(deepColor) }
                Spacer(Modifier.width(2.dp))
                Text("${deep}м", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(lightColor) }
                Spacer(Modifier.width(2.dp))
                Text("${light}м", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(remColor) }
                Spacer(Modifier.width(2.dp))
                Text("${rem}м", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(awakeColor) }
                Spacer(Modifier.width(2.dp))
                Text("${awake}м", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
