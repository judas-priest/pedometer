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
import com.pedometer.util.WatchReminder

@Composable
fun RemindersScreen(
    reminders: List<WatchReminder>,
    onCreateReminder: (String, Int, Int, Int, Int, Int) -> Unit,
    onDeleteReminder: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("События", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (reminders.isEmpty()) {
            Text("Нет событий", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    reminders.forEach { reminder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(reminder.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "%02d.%02d.%04d %02d:%02d".format(reminder.day, reminder.month, reminder.year, reminder.hour, reminder.minute),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDeleteReminder(reminder.id) }) {
                                Text("✕", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (reminder != reminders.last()) HorizontalDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Новое событие", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                var newTitle by remember { mutableStateOf("") }
                var newDate by remember { mutableStateOf("") }
                var newTime by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newDate, onValueChange = { newDate = it },
                        label = { Text("ДД.ММ.ГГГГ") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = newTime, onValueChange = { newTime = it },
                        label = { Text("ЧЧ:ММ") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val parts = newDate.split("."); val timeParts = newTime.split(":")
                        if (parts.size == 3 && timeParts.size == 2 && newTitle.isNotBlank()) {
                            val d = parts[0].toIntOrNull() ?: return@FilledTonalButton
                            val m = parts[1].toIntOrNull() ?: return@FilledTonalButton
                            val y = parts[2].toIntOrNull() ?: return@FilledTonalButton
                            val h = timeParts[0].toIntOrNull() ?: return@FilledTonalButton
                            val min = timeParts[1].toIntOrNull() ?: return@FilledTonalButton
                            onCreateReminder(newTitle, y, m, d, h, min)
                            newTitle = ""; newDate = ""; newTime = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
