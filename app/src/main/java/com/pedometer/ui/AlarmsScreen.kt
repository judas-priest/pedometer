package com.pedometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pedometer.util.WatchAlarm

@Composable
fun AlarmsScreen(
    alarms: List<WatchAlarm>,
    onCreateAlarm: (Int, Int) -> Unit,
    onDeleteAlarm: (Int) -> Unit,
    onToggleAlarm: (WatchAlarm) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Будильники", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (alarms.isEmpty()) {
            Text("Нет будильников", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    alarms.forEach { alarm ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "%02d:%02d".format(alarm.hour, alarm.minute),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            val repeatLabel = when (alarm.repeatMode) {
                                1 -> "Ежедневно"; 5 -> "По дням"; else -> "Один раз"
                            }
                            Text(repeatLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = alarm.enabled, onCheckedChange = { onToggleAlarm(alarm.copy(enabled = it)) })
                            IconButton(onClick = { onDeleteAlarm(alarm.id) }) {
                                Text("✕", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (alarm != alarms.last()) HorizontalDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Новый будильник", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                var newHour by remember { mutableStateOf("7") }
                var newMin by remember { mutableStateOf("00") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newHour,
                        onValueChange = { newHour = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Часы") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = newMin,
                        onValueChange = { newMin = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Минуты") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val h = newHour.toIntOrNull()?.coerceIn(0, 23) ?: 7
                        val m = newMin.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        android.util.Log.i("AlarmsScreen", "Creating alarm $h:$m")
                        onCreateAlarm(h, m)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
