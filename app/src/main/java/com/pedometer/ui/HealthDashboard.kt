package com.pedometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedometer.vm.WatchState

@Composable
fun HealthDashboard(state: WatchState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Phone Sensors", style = MaterialTheme.typography.titleLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                title = "Steps (phone)",
                value = "${state.phoneStepsSinceBoot}",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Session",
                value = "${state.phoneSteps}",
                modifier = Modifier.weight(1f),
            )
        }

        if (state.connectionStatus == com.pedometer.vm.ConnectionStatus.Connected) {
            Text("Watch", style = MaterialTheme.typography.titleLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(title = "Steps", value = "${state.watchSteps}", modifier = Modifier.weight(1f))
                StatCard(
                    title = "Heart Rate",
                    value = if (state.heartRate > 0) "${state.heartRate} bpm" else "--",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(title = "Calories", value = "${state.watchCalories} kcal", modifier = Modifier.weight(1f))
                StatCard(title = "Standing", value = "${state.standingHours} hrs", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
