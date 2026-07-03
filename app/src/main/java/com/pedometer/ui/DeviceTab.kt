package com.pedometer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pedometer.vm.ConnectionStatus
import com.pedometer.vm.WatchState

@Composable
fun DeviceTab(
    state: WatchState,
    onOpenAlarms: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenWatchfaces: () -> Unit,
    onOpenWatchSettings: () -> Unit,
    onFindWatch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            "Устройство",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp),
        )

        if (state.connectionStatus != ConnectionStatus.Connected) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Часы не подключены",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        state.model.ifBlank { "Redmi Watch 5 Lite" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.batteryLevel >= 0) {
                        Text("Заряд ${state.batteryLevel}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.firmware.isNotBlank()) {
                        Text("Прошивка: ${state.firmware}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceFeatureCard("Будильники", "${state.alarms.size}", Modifier.weight(1f), onOpenAlarms)
                DeviceFeatureCard("События", "${state.reminders.size}", Modifier.weight(1f), onOpenReminders)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceFeatureCard("Циферблаты", "", Modifier.weight(1f), onOpenWatchfaces)
                DeviceFeatureCard("Настройки", "", Modifier.weight(1f), onOpenWatchSettings)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onFindWatch, modifier = Modifier.fillMaxWidth()) {
                Text("Найти часы")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceFeatureCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(modifier = modifier.clickable { onClick() }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
