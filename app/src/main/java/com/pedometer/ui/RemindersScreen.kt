package com.pedometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CalendarEventUI(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    events: List<CalendarEventUI>,
    onCreateEvent: (String, Int, Int, Int, Int, Int) -> Unit,
    onDeleteEvent: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedHour by remember { mutableIntStateOf(12) }
    var selectedMinute by remember { mutableIntStateOf(0) }
    var newTitle by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("События", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (events.isEmpty()) {
            Text("Нет событий на ближайшие 7 дней", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    events.forEach { event ->
                        val start = Instant.ofEpochMilli(event.startMs).atZone(ZoneId.systemDefault())
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    if (event.allDay) "%02d.%02d.%04d (весь день)".format(start.dayOfMonth, start.monthValue, start.year)
                                    else "%02d.%02d.%04d %02d:%02d".format(start.dayOfMonth, start.monthValue, start.year, start.hour, start.minute),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDeleteEvent(event.id) }) {
                                Text("✕", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (event != events.last()) HorizontalDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Новое событие", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(if (selectedDate != null)
                            "%02d.%02d.%04d".format(selectedDate!!.dayOfMonth, selectedDate!!.monthValue, selectedDate!!.year)
                        else "Дата")
                    }
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(if (selectedDate != null) "%02d:%02d".format(selectedHour, selectedMinute) else "Время")
                    }
                }

                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val date = selectedDate ?: return@FilledTonalButton
                        if (newTitle.isBlank()) return@FilledTonalButton
                        System.out.println("REMINDERS_DEBUG: onClick title=$newTitle date=${date.year}-${date.monthValue}-${date.dayOfMonth} $selectedHour:$selectedMinute")
                        onCreateEvent(newTitle, date.year, date.monthValue, date.dayOfMonth, selectedHour, selectedMinute)
                        System.out.println("REMINDERS_DEBUG: onCreateEvent returned")
                        newTitle = ""; selectedDate = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = newTitle.isNotBlank() && selectedDate != null,
                ) { Text("Добавить") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена") } },
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = selectedHour, initialMinute = selectedMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedHour = timePickerState.hour
                    selectedMinute = timePickerState.minute
                    showTimePicker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Отмена") } },
            text = { TimePicker(state = timePickerState) },
        )
    }
}
