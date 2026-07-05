package com.pedometer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.pedometer.util.WatchAlarm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(
    alarms: List<WatchAlarm>,
    onCreateAlarm: (Int, Int) -> Unit,
    onDeleteAlarm: (Int) -> Unit,
    onToggleAlarm: (WatchAlarm) -> Unit,
    onEditAlarm: (WatchAlarm) -> Unit,
    onBack: () -> Unit,
) {
    var editingAlarm by remember { mutableStateOf<WatchAlarm?>(null) }
    var showEditTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Будильники", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
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
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        editingAlarm = alarm
                                        showEditTimePicker = true
                                    },
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
                        onCreateAlarm(h, m)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить") }
            }
        }
        Spacer(Modifier.height(24.dp))
        } // Column(padding horizontal)
    }

    if (showEditTimePicker && editingAlarm != null) {
        val alarm = editingAlarm!!
        val timePickerState = rememberTimePickerState(initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showEditTimePicker = false; editingAlarm = null },
            confirmButton = {
                TextButton(onClick = {
                    onEditAlarm(alarm.copy(hour = timePickerState.hour, minute = timePickerState.minute))
                    showEditTimePicker = false
                    editingAlarm = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showEditTimePicker = false; editingAlarm = null }) { Text("Отмена") }
            },
            text = { TimePicker(state = timePickerState) },
        )
    }
}
