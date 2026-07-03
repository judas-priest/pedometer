# Device Tab — UI Restructure Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 4th tab "Устройство" between Активность and Настройки. Move alarms, events, watchfaces, watch settings (DND/contacts/wearing) to separate screens accessible from Device tab. Settings keeps only: connection, profile, weather, notifications, battery optimization, export, debug.

**Architecture:** New DeviceTab composable with grid of feature cards (like Mi Fitness Device page). Each card opens full-screen overlay (same pattern as DayDetailScreen/DebugScreen). SettingsTab shrinks to actual settings only. HorizontalPager changes from 3 to 4 pages.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3

---

## File Structure

- Create: `app/src/main/java/com/pedometer/ui/DeviceTab.kt` — grid of device features
- Create: `app/src/main/java/com/pedometer/ui/AlarmsScreen.kt` — full alarm CRUD screen
- Create: `app/src/main/java/com/pedometer/ui/RemindersScreen.kt` — full reminders CRUD screen
- Create: `app/src/main/java/com/pedometer/ui/WatchfacesScreen.kt` — watchface management screen
- Create: `app/src/main/java/com/pedometer/ui/WatchSettingsScreen.kt` — DND, wearing mode, contacts
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt` — remove device features, keep settings only
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt` — add 4th tab, wire screens

---

### Task 1: Create AlarmsScreen

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/AlarmsScreen.kt`

- [ ] **Step 1: Create AlarmsScreen**

Extract alarm UI from SettingsTab into standalone screen with back button:

```kotlin
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
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
                        onCreateAlarm(h, m)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
```

- [ ] **Step 2: Build**
Run: `./gradlew assembleRelease 2>&1 | tail -3`

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/pedometer/ui/AlarmsScreen.kt
git commit -m "feat: standalone AlarmsScreen"
```

---

### Task 2: Create RemindersScreen

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/RemindersScreen.kt`

- [ ] **Step 1: Create RemindersScreen**

```kotlin
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
```

- [ ] **Step 2: Build and commit**
```bash
git add app/src/main/java/com/pedometer/ui/RemindersScreen.kt
git commit -m "feat: standalone RemindersScreen"
```

---

### Task 3: Create WatchfacesScreen and WatchSettingsScreen

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/WatchfacesScreen.kt`
- Create: `app/src/main/java/com/pedometer/ui/WatchSettingsScreen.kt`

- [ ] **Step 1: Create WatchfacesScreen**

Full code — extract from SettingsTab sections 5 (Циферблаты + upload). Back button + title row. Params:

```kotlin
@Composable
fun WatchfacesScreen(
    watchfaces: List<com.pedometer.watchface.WatchfaceInfo>,
    uploadProgress: Int,
    onRequestWatchfaces: () -> Unit,
    onSetActiveWatchface: (String) -> Unit,
    onDeleteWatchface: (String) -> Unit,
    onUploadWatchface: (ByteArray) -> Unit,
    onBack: () -> Unit,
)
```

Body: same as current SettingsTab watchface section (lines ~320-400 of current SettingsTab) wrapped in Column with back button header. `filePickerLauncher` stays inside this composable. Upload progress card stays.

- [ ] **Step 2: Create WatchSettingsScreen**

Full code — extract from SettingsTab section 4 (Настройки часов). Params:

```kotlin
@Composable
fun WatchSettingsScreen(
    onDndChange: (Boolean) -> Unit,
    onWearingModeChange: (Int) -> Unit,
    onSyncContacts: () -> Unit,
    onBack: () -> Unit,
)
```

Body: same as current SettingsTab DND/wearing/contacts section wrapped in Column with back button header.

- [ ] **Step 3: Build and commit**
```bash
git add app/src/main/java/com/pedometer/ui/WatchfacesScreen.kt app/src/main/java/com/pedometer/ui/WatchSettingsScreen.kt
git commit -m "feat: standalone WatchfacesScreen and WatchSettingsScreen"
```

---

### Task 4: Create DeviceTab

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/DeviceTab.kt`

- [ ] **Step 1: Create DeviceTab with feature grid**

```kotlin
package com.pedometer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
            // Watch info card
            Spacer(Modifier.height(12.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        state.model.ifBlank { "Redmi Watch 5 Lite" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.batteryLevel >= 0) {
                        Text(
                            "Заряд ${state.batteryLevel}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.firmware.isNotBlank()) {
                        Text(
                            "Прошивка: ${state.firmware}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Feature grid
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
```

- [ ] **Step 2: Build and commit**
```bash
git add app/src/main/java/com/pedometer/ui/DeviceTab.kt
git commit -m "feat: DeviceTab with feature grid"
```

---

### Task 5: Strip SettingsTab — remove device features

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt`

- [ ] **Step 1: Remove from SettingsTab**

Remove these sections entirely:
- Alarms (section 2)
- Reminders/Events (section 3)
- Watch Settings — DND, wearing mode, contacts (section 4)
- Watchfaces + upload (section 5)
- Find watch button from connection card

Remove unused callback params:
- `onFindWatch`, `onRequestWatchfaces`, `onSetActiveWatchface`, `onDeleteWatchface`, `onUploadWatchface`
- `onCreateAlarm`, `onDeleteAlarm`, `onToggleAlarm`
- `onDndChange`, `onWearingModeChange`, `onSyncContacts`
- `onCreateReminder`, `onDeleteReminder`

Keep:
- Connection (MAC/key/connect/disconnect)
- Notifications
- Profile (height/weight/gender/goal)
- Weather (city)
- Battery optimization
- Export / Debug / About

- [ ] **Step 2: Build and commit**
```bash
git add app/src/main/java/com/pedometer/ui/SettingsTab.kt
git commit -m "refactor: strip device features from SettingsTab"
```

---

### Task 6: Wire everything in MainActivity

**Files:**
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt`

- [ ] **Step 1: Add 4th tab and screen state**

Change `pageCount = { 3 }` to `pageCount = { 4 }`.

Add state vars:
```kotlin
var showAlarms by remember { mutableStateOf(false) }
var showReminders by remember { mutableStateOf(false) }
var showWatchfaces by remember { mutableStateOf(false) }
var showWatchSettings by remember { mutableStateOf(false) }
```

Add BackHandlers and screen rendering for each (same pattern as showDebug/showNotificationApps).

- [ ] **Step 2: Update NavigationBar**

Add 4th item between Активность and Настройки:
```kotlin
NavigationBarItem(
    selected = pagerState.currentPage == 2,
    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
    icon = { Icon(Icons.Default.Watch, contentDescription = null) },
    label = { Text("Устройство") },
)
```

Shift Settings to page 3: change `pagerState.currentPage == 2` to `== 3` and `animateScrollToPage(2)` to `(3)` for the Settings NavigationBarItem.

- [ ] **Step 3: Add page 2 = DeviceTab, shift Settings to page 3**

In the HorizontalPager `when(page)` block:

```kotlin
2 -> DeviceTab(
    state = state,
    onOpenAlarms = { showAlarms = true },
    onOpenReminders = { showReminders = true },
    onOpenWatchfaces = { showWatchfaces = true },
    onOpenWatchSettings = { showWatchSettings = true },
    onFindWatch = { vm.findWatch() },
)
```

- [ ] **Step 4: Add overlay screens**

Before the Scaffold, add:
```kotlin
if (showAlarms) {
    BackHandler { showAlarms = false }
    AlarmsScreen(
        alarms = state.alarms,
        onCreateAlarm = { h, m -> vm.createAlarm(h, m) },
        onDeleteAlarm = { vm.deleteAlarm(it) },
        onToggleAlarm = { vm.editAlarm(it) },
        onBack = { showAlarms = false },
    )
    return@PedometerTheme
}
if (showReminders) {
    BackHandler { showReminders = false }
    RemindersScreen(
        reminders = state.reminders,
        onCreateReminder = { title, y, m, d, h, min -> vm.createReminder(title, y, m, d, h, min) },
        onDeleteReminder = { vm.deleteReminderById(it) },
        onBack = { showReminders = false },
    )
    return@PedometerTheme
}
if (showWatchfaces) {
    BackHandler { showWatchfaces = false }
    WatchfacesScreen(
        watchfaces = state.watchfaces,
        uploadProgress = state.uploadProgress,
        onRequestWatchfaces = { vm.requestWatchfaces() },
        onSetActiveWatchface = { vm.setActiveWatchface(it) },
        onDeleteWatchface = { vm.deleteWatchface(it) },
        onUploadWatchface = { vm.uploadWatchface(it) },
        onBack = { showWatchfaces = false },
    )
    return@PedometerTheme
}
if (showWatchSettings) {
    BackHandler { showWatchSettings = false }
    WatchSettingsScreen(
        onDndChange = { vm.setDnd(it) },
        onWearingModeChange = { vm.setWearingMode(it) },
        onSyncContacts = { vm.syncContacts() },
        onBack = { showWatchSettings = false },
    )
    return@PedometerTheme
}
```

- [ ] **Step 5: Slim down SettingsTab call**

Remove these callbacks from SettingsTab invocation:
```kotlin
// REMOVE all of these:
onFindWatch, onRequestWatchfaces, onSetActiveWatchface, onDeleteWatchface, onUploadWatchface,
onCreateAlarm, onDeleteAlarm, onToggleAlarm,
onDndChange, onWearingModeChange, onSyncContacts,
onCreateReminder, onDeleteReminder,
```

Keep only: `state`, `onAuthKeyChange`, `onMacChange`, `onConnect`, `onDisconnect`, `onProfileChange`, `onOpenDebug`, `onOpenNotificationApps`.

- [ ] **Step 6: Build and verify**
Run: `./gradlew assembleRelease 2>&1 | tail -3`

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/pedometer/MainActivity.kt
git commit -m "feat: 4-tab navigation — Здоровье/Активность/Устройство/Настройки"
```
