# Watch Reminders (Events) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full CRUD for watch reminders/events — get, create, edit, delete. UI in Settings. Synced with the "Events" widget on Redmi Watch 5 Lite.

**Architecture:** Extract reminder logic from WatchSettings into dedicated ReminderService. Add reminder state to WatchState. Add UI section in SettingsTab. Protocol: type=17 (Schedule), subtypes: get=14, create=15, edit=17, delete=18.

**Tech Stack:** Kotlin, Protobuf (XiaomiProto.Schedule, Reminders, Reminder, ReminderDetails, ReminderDelete)

---

## File Structure

- Create: `app/src/main/java/com/pedometer/util/ReminderService.kt` — CRUD + protocol handling
- Modify: `app/src/main/java/com/pedometer/util/WatchSettings.kt` — remove old createReminder
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt` — wire ReminderService, add state
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt` — add Reminders UI section

---

### Task 1: Create ReminderService with full CRUD

**Files:**
- Create: `app/src/main/java/com/pedometer/util/ReminderService.kt`

- [ ] **Step 1: Create ReminderService**

```kotlin
package com.pedometer.util

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

data class WatchReminder(
    val id: Int,
    val title: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val repeatMode: Int = 0, // 0=once, 1=daily, 5=weekly, 7=monthly, 8=yearly
)

class ReminderService(private val protocolHandler: ProtocolHandler) {
    companion object {
        private const val TAG = "ReminderService"
        const val COMMAND_TYPE = 17 // Schedule
        const val CMD_GET = 14
        const val CMD_CREATE = 15
        const val CMD_EDIT = 17
        const val CMD_DELETE = 18
    }

    var onRemindersReceived: ((List<WatchReminder>) -> Unit)? = null

    fun getReminders() {
        protocolHandler.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_GET)
                .build()
        )
    }

    fun createReminder(title: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, repeatMode: Int = 0) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_CREATE)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setCreateReminder(XiaomiProto.ReminderDetails.newBuilder()
                    .setTitle(title)
                    .setDate(XiaomiProto.Date.newBuilder().setYear(year).setMonth(month).setDay(day))
                    .setTime(XiaomiProto.Time.newBuilder().setHour(hour).setMinute(minute))
                    .setRepeatMode(repeatMode)
                    .setRepeatFlags(64)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Create reminder: $title $year-$month-$day $hour:$minute repeat=$repeatMode")
        // Refresh list after creation
        getReminders()
    }

    fun editReminder(reminder: WatchReminder) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_EDIT)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setEditReminder(XiaomiProto.Reminder.newBuilder()
                    .setId(reminder.id)
                    .setReminderDetails(XiaomiProto.ReminderDetails.newBuilder()
                        .setTitle(reminder.title)
                        .setDate(XiaomiProto.Date.newBuilder()
                            .setYear(reminder.year).setMonth(reminder.month).setDay(reminder.day))
                        .setTime(XiaomiProto.Time.newBuilder()
                            .setHour(reminder.hour).setMinute(reminder.minute))
                        .setRepeatMode(reminder.repeatMode)
                        .setRepeatFlags(64))))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Edit reminder ${reminder.id}: ${reminder.title}")
        getReminders()
    }

    fun deleteReminder(id: Int) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_DELETE)
            .setSchedule(XiaomiProto.Schedule.newBuilder()
                .setDeleteReminder(XiaomiProto.ReminderDelete.newBuilder()
                    .addId(id)))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Delete reminder $id")
        getReminders()
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_GET -> {
                if (cmd.hasSchedule() && cmd.schedule.hasReminders()) {
                    val reminders = cmd.schedule.reminders.reminderList.map { r ->
                        val d = r.reminderDetails
                        WatchReminder(
                            id = r.id,
                            title = d.title,
                            year = d.date.year,
                            month = d.date.month,
                            day = d.date.day,
                            hour = d.time.hour,
                            minute = d.time.minute,
                            repeatMode = d.repeatMode,
                        )
                    }
                    Log.i(TAG, "Got ${reminders.size} reminders (max=${cmd.schedule.reminders.maxReminders})")
                    onRemindersReceived?.invoke(reminders)
                }
            }
            CMD_CREATE, CMD_EDIT -> {
                val ackId = if (cmd.hasSchedule()) cmd.schedule.ackId else -1
                Log.i(TAG, "Reminder ack: id=$ackId subtype=${cmd.subtype}")
            }
            CMD_DELETE -> {
                Log.i(TAG, "Reminder deleted")
            }
            else -> Log.d(TAG, "Unhandled schedule subtype=${cmd.subtype}")
        }
    }
}
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/util/ReminderService.kt
git commit -m "feat: ReminderService with full CRUD (get/create/edit/delete)"
```

---

### Task 2: Remove old createReminder from WatchSettings

**Files:**
- Modify: `app/src/main/java/com/pedometer/util/WatchSettings.kt:82-98`

- [ ] **Step 1: Remove createReminder method**

Delete lines 82-98 (the `// ── Reminders` section and `createReminder` method) from WatchSettings.kt.

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL (may fail if WatchViewModel still references it — fix in Task 3)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/util/WatchSettings.kt
git commit -m "refactor: remove createReminder from WatchSettings (moved to ReminderService)"
```

---

### Task 3: Wire ReminderService in WatchViewModel + add state

**Files:**
- Modify: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt`

- [ ] **Step 1: Add reminders state to WatchState**

Already exists: `val reminders: List<WatchReminder>` — but it uses `WatchAlarm`. Need to add `WatchReminder` list. Check current state:

The existing `alarms` field uses `WatchAlarm` from AlarmService. Add a new field for reminders:

```kotlin
// In WatchState, add:
val reminders: List<WatchReminder> = emptyList(),
```

Import `com.pedometer.util.WatchReminder`.

- [ ] **Step 2: Add ReminderService field and init**

```kotlin
// Add field:
private var reminderService: ReminderService? = null

// In connect(), after calendarService init (~line 457), add:
val reminders = ReminderService(handler)
reminders.onRemindersReceived = { list ->
    _state.value = _state.value.copy(reminders = list)
}
reminderService = reminders

// In post-auth init, after calendarService.syncCalendar(), add:
reminderService?.getReminders()
Thread.sleep(200)
```

- [ ] **Step 3: Add public methods for UI**

```kotlin
fun getReminders() { reminderService?.getReminders() }
fun createReminder(title: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, repeatMode: Int = 0) {
    reminderService?.createReminder(title, year, month, day, hour, minute, repeatMode)
}
fun deleteReminder(id: Int) { reminderService?.deleteReminder(id) }
```

Remove old `createReminder` delegation to `watchSettings`.

- [ ] **Step 4: Add to handleCommand routing**

```kotlin
// In handleCommand(), add case:
ReminderService.COMMAND_TYPE -> {
    reminderService?.handleCommand(cmd)
    alarmService?.handleCommand(cmd) // alarms share type=17
}
```

Note: Both AlarmService and ReminderService use COMMAND_TYPE=17. Route to both — each handles only its own subtypes.

- [ ] **Step 5: Add to cleanupServices() and disconnect()**

```kotlin
reminderService = null
```

- [ ] **Step 6: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "feat: wire ReminderService in ViewModel, add reminders to state"
```

---

### Task 4: Add Reminders UI in SettingsTab

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt`
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt` (add callback)

- [ ] **Step 1: Add callbacks to SettingsTab signature**

```kotlin
// Add to SettingsTab parameters:
onCreateReminder: (String, Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _, _ -> },
onDeleteReminder: (Int) -> Unit = {},
```

- [ ] **Step 2: Add Reminders section after Alarms**

Add after the Alarms `ElevatedCard` closing brace, inside `if (state.connectionStatus == ConnectionStatus.Connected)`:

```kotlin
// Reminders / Events
Spacer(Modifier.height(16.dp))
Text("События", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
Spacer(Modifier.height(8.dp))

ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
        if (state.reminders.isEmpty()) {
            Text("Нет событий", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.reminders.forEach { reminder ->
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
                if (reminder != state.reminders.last()) HorizontalDivider()
            }
        }
        Spacer(Modifier.height(8.dp))

        // Add reminder
        var newTitle by remember { mutableStateOf("") }
        var newDate by remember { mutableStateOf("") }
        var newTime by remember { mutableStateOf("") }
        OutlinedTextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            label = { Text("Событие") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newDate,
                onValueChange = { newDate = it },
                label = { Text("Дата (ДД.ММ.ГГГГ)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = newTime,
                onValueChange = { newTime = it },
                label = { Text("Время (ЧЧ:ММ)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                val parts = newDate.split(".")
                val timeParts = newTime.split(":")
                if (parts.size == 3 && timeParts.size == 2) {
                    val d = parts[0].toIntOrNull() ?: return@FilledTonalButton
                    val m = parts[1].toIntOrNull() ?: return@FilledTonalButton
                    val y = parts[2].toIntOrNull() ?: return@FilledTonalButton
                    val h = timeParts[0].toIntOrNull() ?: return@FilledTonalButton
                    val min = timeParts[1].toIntOrNull() ?: return@FilledTonalButton
                    onCreateReminder(newTitle, y, m, d, h, min)
                    newTitle = ""
                    newDate = ""
                    newTime = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Добавить событие")
        }
    }
}
```

- [ ] **Step 3: Wire callbacks in MainActivity**

```kotlin
// In SettingsTab call in MainActivity, add:
onCreateReminder = { title, y, m, d, h, min -> vm.createReminder(title, y, m, d, h, min) },
onDeleteReminder = vm::deleteReminder,
```

Note: `deleteReminder` in ViewModel now takes `Int` (reminder id), not `AlarmService.deleteAlarm`. Make sure the method name doesn't clash — rename if needed to `deleteReminderById`.

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleRelease 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/SettingsTab.kt app/src/main/java/com/pedometer/MainActivity.kt
git commit -m "feat: reminders UI in settings — list, create, delete"
```
