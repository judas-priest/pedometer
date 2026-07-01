package com.pedometer.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pedometer.data.DailyHealth
import com.pedometer.data.HourlySteps
import com.pedometer.health.DayStepData

private val HeartRed = Color(0xFFE53935)
private val StepGreen = Color(0xFF4CAF50)
private val SpO2Blue = Color(0xFF42A5F5)
private val StressOrange = Color(0xFFFF9800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDayDetail(
    date: String,
    health: DailyHealth?,
    stepData: DayStepData?,
    hrData: List<Pair<Long, Int>>,
    hourlySteps: List<HourlySteps> = emptyList(),
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatDate(date)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            // Steps card
            if (stepData != null && stepData.totalSteps > 0) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Шаги", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%,d".format(stepData.totalSteps),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = StepGreen,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("Ходьба ${stepData.walkSteps}", style = MaterialTheme.typography.labelSmall)
                            Text("Бег ${stepData.runSteps}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Hourly steps chart
            if (hourlySteps.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Шаги по часам", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        val maxSteps = hourlySteps.maxOfOrNull { it.steps }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                        Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
                            val barW = size.width / 24f
                            for (h in hourlySteps) {
                                val barH = (h.steps / maxSteps) * size.height
                                val x = h.hour * barW
                                drawRect(
                                    StepGreen,
                                    Offset(x + 1f, size.height - barH),
                                    Size(barW - 2f, barH),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            listOf("0", "6", "12", "18", "24").forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Heart rate card
            if (health != null && health.hrAvg > 0) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Пульс", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        // HR chart if data available
                        if (hrData.size > 2) {
                            val downsampled = if (hrData.size <= 150) hrData
                            else {
                                val step = hrData.size / 150
                                hrData.filterIndexed { i, _ -> i % step == 0 }
                            }
                            MiniLineChart(
                                data = downsampled.map { it.second },
                                color = HeartRed,
                                modifier = Modifier.fillMaxWidth().height(80.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MetricColumn("Среднее", "${health.hrAvg}", "уд/мин", HeartRed)
                            MetricColumn("Покой", "${health.hrResting}", "уд/мин")
                            MetricColumn("Мин", "${health.hrMin}", "уд/мин")
                            MetricColumn("Макс", "${health.hrMax}", "уд/мин", HeartRed)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // SpO2 card
            if (health != null && health.spo2Avg > 0) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("SpO2", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MetricColumn("Среднее", "${health.spo2Avg}%", "", SpO2Blue)
                            MetricColumn("Мин", "${health.spo2Min}%", "")
                            MetricColumn("Макс", "${health.spo2Max}%", "")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Stress card
            if (health != null && health.stressAvg > 0) {
                val stressLabel = when {
                    health.stressAvg <= 25 -> "Спокойно"
                    health.stressAvg <= 50 -> "Нормально"
                    health.stressAvg <= 75 -> "Средний"
                    else -> "Высокий"
                }
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Стресс", style = MaterialTheme.typography.titleSmall)
                            Text(stressLabel, style = MaterialTheme.typography.labelSmall, color = StressOrange)
                        }
                        Spacer(Modifier.height(8.dp))
                        // Stress bar
                        LinearProgressIndicator(
                            progress = { health.stressAvg / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = StressOrange,
                            trackColor = StressOrange.copy(alpha = 0.15f),
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
                Spacer(Modifier.height(12.dp))
            }

            // HR zones
            if (hrData.size > 10) {
                val zones = intArrayOf(0, 0, 0, 0, 0) // rest, light, moderate, hard, max
                for ((_, bpm) in hrData) {
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
                    Color(0xFF90CAF9), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFE53935), Color(0xFF9C27B0)
                )

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Зоны пульса", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        // Stacked bar
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Canvas(Modifier.size(8.dp)) { drawCircle(zoneColors[i]) }
                                        Spacer(Modifier.width(2.dp))
                                        Text("${(zones[i] * 100 / total).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            for (i in zones.indices) {
                                if (zones[i] > 0) {
                                    Text(zoneNames[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // No data placeholder
            if (health == null && stepData == null) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Нет данных за этот день", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, unit: String, color: Color = Color.Unspecified) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text("$label $unit".trim(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniLineChart(data: List<Int>, color: Color, modifier: Modifier) {
    val fillColor = color.copy(alpha = 0.15f)
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val minV = (data.min() - 5).coerceAtLeast(0)
        val maxV = (data.max() + 5)
        val range = (maxV - minV).coerceAtLeast(1)
        val w = size.width
        val h = size.height

        val points = data.mapIndexed { i, v ->
            val x = i.toFloat() / (data.size - 1) * w
            val y = h - ((v - minV).toFloat() / range) * h
            Offset(x, y)
        }

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(w, h)
            close()
        }
        drawPath(path, fillColor)

        for (i in 0 until points.size - 1) {
            drawLine(color, points[i], points[i + 1], strokeWidth = 2f, cap = StrokeCap.Round)
        }
    }
}

private fun formatDate(date: String): String {
    return try {
        val d = java.time.LocalDate.parse(date)
        val months = listOf("", "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря")
        "${d.dayOfMonth} ${months[d.monthValue]} ${d.year}"
    } catch (_: Exception) { date }
}
