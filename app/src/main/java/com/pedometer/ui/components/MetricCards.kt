package com.pedometer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.health.UserProfile

// ── MetricCard ─────────────────────────────────────────────────────────────────

@Composable
internal fun MetricCard(
    title: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
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

// ── StepMetricCards ────────────────────────────────────────────────────────────

@Composable
internal fun StepMetricCards(
    walkSteps: Int,
    runSteps: Int,
    totalSteps: Int,
    profile: UserProfile,
    watchCalories: Int = 0,
    watchDistanceM: Int = 0,
    activeMinutes: Int = 0,
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
        val cal = if (watchCalories > 0) watchCalories.toDouble() else profile.calcCalories(totalSteps)
        MetricCard(title = "Калории", value = "%.0f".format(cal), unit = "ккал", color = HeartRed, modifier = Modifier.weight(1f))
        val dist = if (watchDistanceM > 0) watchDistanceM / 1000.0 else profile.calcDistance(totalSteps)
        MetricCard(title = "Дистанция", value = "%.2f".format(dist), unit = "км", color = StandBlue, modifier = Modifier.weight(1f))
    }
    if (activeMinutes > 0) {
        Spacer(Modifier.height(12.dp))
        MetricCard(title = "Активность", value = "$activeMinutes", unit = "/ 30 мин", color = CalorieOrange, modifier = Modifier.fillMaxWidth())
    }
}

// ── QuickActionCard ────────────────────────────────────────────────────────────

@Composable
internal fun QuickActionCard(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier.clickable { onClick() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── StatItem ───────────────────────────────────────────────────────────────────

@Composable
internal fun StatItem(label: String, value: String) {
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
