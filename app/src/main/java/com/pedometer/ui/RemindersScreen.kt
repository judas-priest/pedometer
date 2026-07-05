package com.pedometer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
    onEditEvent: (Long, String, Int, Int, Int, Int, Int) -> Unit,
    onDeleteEvent: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedHour by remember { mutableIntStateOf(12) }
    var selectedMinute by remember { mutableIntStateOf(0) }
    var newTitle by remember { mutableStateOf("") }

    // Edit state
    var editingEvent by remember { mutableStateOf<CalendarEventUI?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editDate by remember { mutableStateOf<LocalDate?>(null) }
    var editHour by remember { mutableIntStateOf(0) }
    var editMinute by remember { mutableIntStateOf(0) }
    var showEditDatePicker by remember { mutableStateOf(false) }
    var showEditTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("События", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))

        if (events.isEmpty()) {
            Text("Нет событий на ближайшие 30 дней", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    events.forEach { event ->
                        val start = Instant.ofEpochMilli(event.startMs).atZone(ZoneId.systemDefault())
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingEvent = event
                                    editTitle = event.title
                                    val zdt = Instant.ofEpochMilli(event.startMs).atZone(ZoneId.systemDefault())
                                    editDate = zdt.toLocalDate()
                                    editHour = zdt.hour
                                    editMinute = zdt.minute
                                }
                                .padding(vertical = 4.dp),
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
                        onCreateEvent(newTitle, date.year, date.monthValue, date.dayOfMonth, selectedHour, selectedMinute)
                        newTitle = ""; selectedDate = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = newTitle.isNotBlank() && selectedDate != null,
                ) { Text("Добавить") }
            }
        }
        Spacer(Modifier.height(24.dp))
        } // Column(padding horizontal)
    }

    // Create date picker
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

    // Create time picker
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

    // Edit dialog
    if (editingEvent != null) {
        AlertDialog(
            onDismissRequest = { editingEvent = null },
            title = { Text("Редактировать") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editTitle, onValueChange = { editTitle = it },
                        label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showEditDatePicker = true }, modifier = Modifier.weight(1f)) {
                            Text(editDate?.let { "%02d.%02d.%04d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "Дата")
                        }
                        OutlinedButton(onClick = { showEditTimePicker = true }, modifier = Modifier.weight(1f)) {
                            Text("%02d:%02d".format(editHour, editMinute))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ev = editingEvent ?: return@TextButton
                    val d = editDate ?: return@TextButton
                    if (editTitle.isNotBlank()) {
                        onEditEvent(ev.id, editTitle, d.year, d.monthValue, d.dayOfMonth, editHour, editMinute)
                    }
                    editingEvent = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editingEvent = null }) { Text("Отмена") } },
        )
    }

    // Edit date picker
    if (showEditDatePicker) {
        val initMs = editDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initMs)
        DatePickerDialog(
            onDismissRequest = { showEditDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        editDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showEditDatePicker = false
                    showEditTimePicker = true
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = { showEditDatePicker = false }) { Text("Отмена") } },
        ) { DatePicker(state = datePickerState) }
    }

    // Edit time picker
    if (showEditTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = editHour, initialMinute = editMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showEditTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    editHour = timePickerState.hour
                    editMinute = timePickerState.minute
                    showEditTimePicker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showEditTimePicker = false }) { Text("Отмена") } },
            text = { TimePicker(state = timePickerState) },
        )
    }
}
