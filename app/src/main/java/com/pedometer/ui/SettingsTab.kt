package com.pedometer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pedometer.health.UserProfile
import com.pedometer.vm.ConnectionStatus
import com.pedometer.vm.WatchState
import com.pedometer.watchface.WatchfaceInfo

@Composable
fun SettingsTab(
    state: WatchState,
    onAuthKeyChange: (String) -> Unit,
    onMacChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onProfileChange: (UserProfile) -> Unit = {},
    onOpenDebug: () -> Unit = {},
    onOpenNotificationApps: () -> Unit = {},
    onFindWatch: () -> Unit = {},
    onRequestWatchfaces: () -> Unit = {},
    onSetActiveWatchface: (String) -> Unit = {},
    onDeleteWatchface: (String) -> Unit = {},
    onUploadWatchface: (ByteArray) -> Unit = {},
    onCreateAlarm: (Int, Int) -> Unit = { _, _ -> },
    onDeleteAlarm: (Int) -> Unit = {},
    onToggleAlarm: (com.pedometer.util.WatchAlarm) -> Unit = {},
    onDndChange: (Boolean) -> Unit = {},
    onWearingModeChange: (Int) -> Unit = {},
    onSyncContacts: () -> Unit = {},
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onProfileChange(state.profile.copy(backgroundServiceEnabled = true))
        }
    }

    val profile = state.profile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Profile
        Text("Профиль", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var heightText by remember { mutableStateOf(profile.heightCm.toString()) }
                var weightText by remember { mutableStateOf(profile.weightKg.toString()) }
                var goalText by remember { mutableStateOf(profile.stepGoal.toString()) }

                OutlinedTextField(
                    value = heightText,
                    onValueChange = {
                        heightText = it
                        it.toIntOrNull()?.let { h ->
                            onProfileChange(profile.copy(heightCm = h))
                        }
                    },
                    label = { Text("Рост (см)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = weightText,
                    onValueChange = {
                        weightText = it
                        it.toIntOrNull()?.let { w ->
                            onProfileChange(profile.copy(weightKg = w))
                        }
                    },
                    label = { Text("Вес (кг)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Пол:", modifier = Modifier.width(50.dp))
                    FilterChip(
                        selected = profile.isMale,
                        onClick = { onProfileChange(profile.copy(isMale = true)) },
                        label = { Text("М") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !profile.isMale,
                        onClick = { onProfileChange(profile.copy(isMale = false)) },
                        label = { Text("Ж") },
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = goalText,
                    onValueChange = {
                        goalText = it
                        it.toIntOrNull()?.let { g ->
                            onProfileChange(profile.copy(stepGoal = g))
                        }
                    },
                    label = { Text("Цель шагов") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Weather
        Spacer(Modifier.height(16.dp))
        Text("Погода", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var cityText by remember { mutableStateOf(profile.weatherCity) }
                OutlinedTextField(
                    value = cityText,
                    onValueChange = {
                        cityText = it
                        onProfileChange(profile.copy(weatherCity = it))
                    },
                    label = { Text("Город") },
                    placeholder = { Text("Авто (GPS)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "Оставьте пустым для автоопределения по GPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Assistant
        Spacer(Modifier.height(16.dp))
        Text("Ассистент", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var keyText by remember { mutableStateOf(profile.llmApiKey) }
                var urlText by remember { mutableStateOf(profile.llmApiUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        onProfileChange(profile.copy(llmApiUrl = it))
                    },
                    label = { Text("API URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = {
                        keyText = it
                        onProfileChange(profile.copy(llmApiKey = it))
                    },
                    label = { Text("API Key") },
                    placeholder = { Text("Без ключа = локальные ответы") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "OpenAI-совместимый API (RouterAI, DeepSeek и др.)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Battery & ColorOS tips (only when watch connected)
        if (state.connectionStatus == ConnectionStatus.Connected) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            val isWhitelisted = pm.isIgnoringBatteryOptimizations(context.packageName)

            if (!isWhitelisted) {
                Spacer(Modifier.height(8.dp))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Оптимизация батареи", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Отключите оптимизацию чтобы соединение с часами не разрывалось",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = {
                            context.startActivity(Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ))
                        }) {
                            Text("Отключить оптимизацию")
                        }
                    }
                }
            }
        }

        // Notifications & Music
        Spacer(Modifier.height(8.dp))
        val nlEnabled = try {
            val cn = android.content.ComponentName(context, com.pedometer.music.MediaListenerService::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            flat?.contains(cn.flattenToString()) == true
        } catch (_: Exception) { false }

        if (!nlEnabled) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Уведомления и музыка", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Разрешите доступ к уведомлениям для пересылки на часы и управления плеером",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }) {
                        Text("Разрешить")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenNotificationApps() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Уведомления")
                }
                OutlinedButton(
                    onClick = {
                        com.pedometer.notification.WatchNotificationBridge.sendToWatch(
                            id = 12345,
                            packageName = "com.pedometer",
                            appName = "Шагомер",
                            title = "Тест",
                            body = "Тестовое уведомление!",
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Тест")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Watch
        Text("Часы", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.macAddress,
                    onValueChange = onMacChange,
                    label = { Text("MAC-адрес") },
                    placeholder = { Text("XX:XX:XX:XX:XX:XX") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.authKey,
                    onValueChange = onAuthKeyChange,
                    label = { Text("Ключ авторизации") },
                    placeholder = { Text("0x...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))

                when (state.connectionStatus) {
                    ConnectionStatus.Disconnected -> {
                        Button(
                            onClick = {
                                val perms = mutableListOf<String>()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                                    perms.add(Manifest.permission.BLUETOOTH_SCAN)
                                }
                                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                                permissionLauncher.launch(perms.toTypedArray())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.authKey.isNotBlank() && state.macAddress.isNotBlank(),
                        ) {
                            Text("Подключить")
                        }
                    }
                    ConnectionStatus.Connecting, ConnectionStatus.Authenticating -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(if (state.connectionStatus == ConnectionStatus.Connecting) "Подключение..." else "Авторизация...")
                        }
                    }
                    ConnectionStatus.Connected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (state.model.isNotBlank()) state.model else "Подключено", style = MaterialTheme.typography.titleSmall)
                                if (state.batteryLevel >= 0) {
                                    Text("Заряд ${state.batteryLevel}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            FilledTonalButton(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Text("Отключить")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onFindWatch,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("🔔 Найти часы")
                        }
                    }
                }
            }
        }

        // Alarms
        if (state.connectionStatus == ConnectionStatus.Connected) {
            Spacer(Modifier.height(16.dp))
            Text("Будильники", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (state.alarms.isEmpty()) {
                        Text("Нет будильников", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.alarms.forEach { alarm ->
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
                                    1 -> "Ежедневно"
                                    5 -> "По дням"
                                    else -> "Один раз"
                                }
                                Text(repeatLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = alarm.enabled,
                                    onCheckedChange = { onToggleAlarm(alarm.copy(enabled = it)) },
                                )
                                IconButton(onClick = { onDeleteAlarm(alarm.id) }) {
                                    Text("✕", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (alarm != state.alarms.last()) HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // Add alarm
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
                            label = { Text("Ч") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Text(":", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = newMin,
                            onValueChange = { newMin = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("М") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        FilledTonalButton(onClick = {
                            val h = newHour.toIntOrNull()?.coerceIn(0, 23) ?: 7
                            val m = newMin.toIntOrNull()?.coerceIn(0, 59) ?: 0
                            onCreateAlarm(h, m)
                        }) {
                            Text("+")
                        }
                    }
                }
            }
        }

        // Watchfaces
        if (state.connectionStatus == ConnectionStatus.Connected) {
            Spacer(Modifier.height(16.dp))
            Text("Циферблаты", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (state.watchfaces.isEmpty()) {
                        FilledTonalButton(
                            onClick = onRequestWatchfaces,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Загрузить список")
                        }
                    } else {
                        state.watchfaces.forEach { face ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        face.name.ifBlank { face.id },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (face.active) {
                                        Text("Активный", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (!face.active) {
                                    TextButton(onClick = { onSetActiveWatchface(face.id) }) {
                                        Text("Выбрать")
                                    }
                                }
                                if (face.canDelete) {
                                    TextButton(onClick = { onDeleteWatchface(face.id) }) {
                                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (face != state.watchfaces.last()) {
                                HorizontalDivider()
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRequestWatchfaces,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Обновить")
                        }
                    }
                }
            }

            // Upload watchface
            Spacer(Modifier.height(8.dp))
            val filePickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            onUploadWatchface(bytes)
                        }
                    } catch (_: Exception) {}
                }
            }

            if (state.uploadProgress >= 0) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Загрузка циферблата...", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.uploadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.uploadProgress}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Загрузить циферблат (.bin)")
                }
            }
        }

        // Export data
        Spacer(Modifier.height(8.dp))
        val exportScope = rememberCoroutineScope()
        OutlinedButton(
            onClick = {
                exportScope.launch {
                    val file = com.pedometer.util.DataExporter.exportAll(context)
                    if (file != null) {
                        com.pedometer.util.DataExporter.shareFile(context, file)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Экспорт данных (CSV)")
        }

        // Watch settings (DND, wearing mode, contacts)
        if (state.connectionStatus == ConnectionStatus.Connected) {
            Spacer(Modifier.height(16.dp))
            Text("Настройки часов", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // DND sync
                    var dndEnabled by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Не беспокоить (DND)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = dndEnabled, onCheckedChange = {
                            dndEnabled = it
                            onDndChange(it)
                        })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Wearing mode
                    Text("Режим ношения", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Браслет" to 0, "Клипса" to 1, "Ожерелье" to 2).forEach { (label, mode) ->
                            FilterChip(
                                selected = false,
                                onClick = { onWearingModeChange(mode) },
                                label = { Text(label) },
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Sync contacts
                    OutlinedButton(
                        onClick = onSyncContacts,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Синхронизировать контакты")
                    }
                }
            }
        }

        // BLE Debug button
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onOpenDebug() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("BLE Debug")
        }

        Spacer(Modifier.height(16.dp))

        Text("О приложении", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Шагомер", style = MaterialTheme.typography.titleSmall)
                Text("Версия 1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
