package com.pedometer.ui

import android.Manifest
import android.content.Intent
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
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.pedometer.health.UserProfile
import com.pedometer.vm.ConnectionStatus
import com.pedometer.vm.WatchState

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
) {
    val context = LocalContext.current
    val profile = state.profile
    val connected = state.connectionStatus == ConnectionStatus.Connected

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // ── Connection ───────────────────────────────────────────────────
        Text("Подключение", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.macAddress, onValueChange = onMacChange,
                    label = { Text("MAC-адрес") }, placeholder = { Text("XX:XX:XX:XX:XX:XX") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.authKey, onValueChange = onAuthKeyChange,
                    label = { Text("Ключ авторизации") }, placeholder = { Text("0x...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
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
                                perms.add(Manifest.permission.READ_CALL_LOG)
                                permissionLauncher.launch(perms.toTypedArray())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.authKey.isNotBlank() && state.macAddress.isNotBlank(),
                        ) { Text("Подключить") }
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
                            ) { Text("Отключить") }
                        }
                    }
                }
            }
        }

        // ── Notifications ────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Text("Уведомления", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        val nlEnabled = try {
            val cn = android.content.ComponentName(context, com.pedometer.music.MediaListenerService::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            flat?.contains(cn.flattenToString()) == true
        } catch (_: Exception) { false }

        if (!nlEnabled) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Доступ к уведомлениям", style = MaterialTheme.typography.titleSmall)
                    Text("Разрешите для пересылки на часы и управления плеером", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }) { Text("Разрешить") }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenNotificationApps() }, modifier = Modifier.weight(1f)) { Text("Приложения") }
                OutlinedButton(
                    onClick = {
                        com.pedometer.notification.WatchNotificationBridge.sendToWatch(
                            id = 12345, packageName = "com.pedometer", appName = "Шагомер",
                            title = "Тест", body = "Тестовое уведомление!",
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Тест") }
            }
        }

        // ── Profile ──────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Text("Профиль", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var heightText by remember { mutableStateOf(profile.heightCm.toString()) }
                var weightText by remember { mutableStateOf(profile.weightKg.toString()) }
                var goalText by remember { mutableStateOf(profile.stepGoal.toString()) }

                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it; it.toIntOrNull()?.let { h -> onProfileChange(profile.copy(heightCm = h)) } },
                    label = { Text("Рост (см)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it; it.toIntOrNull()?.let { w -> onProfileChange(profile.copy(weightKg = w)) } },
                    label = { Text("Вес (кг)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Пол:", modifier = Modifier.width(50.dp))
                    FilterChip(selected = profile.isMale, onClick = { onProfileChange(profile.copy(isMale = true)) }, label = { Text("М") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !profile.isMale, onClick = { onProfileChange(profile.copy(isMale = false)) }, label = { Text("Ж") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it; it.toIntOrNull()?.let { g -> onProfileChange(profile.copy(stepGoal = g)) } },
                    label = { Text("Цель шагов") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        // ── Weather ──────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Text("Погода", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var cityText by remember { mutableStateOf(profile.weatherCity) }
                OutlinedTextField(
                    value = cityText,
                    onValueChange = { cityText = it; onProfileChange(profile.copy(weatherCity = it)) },
                    label = { Text("Город") }, placeholder = { Text("Авто (GPS)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Text("Пусто = автоопределение по GPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Battery optimization ─────────────────────────────────────────
        if (connected) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Spacer(Modifier.height(16.dp))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Оптимизация батареи", style = MaterialTheme.typography.titleSmall)
                        Text("Отключите чтобы соединение с часами не разрывалось", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                        }) { Text("Отключить оптимизацию") }
                    }
                }
            }
        }

        // ── Export / Debug / About ───────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        val exportScope = rememberCoroutineScope()
        OutlinedButton(
            onClick = {
                exportScope.launch {
                    val file = com.pedometer.util.DataExporter.exportAll(context)
                    if (file != null) com.pedometer.util.DataExporter.shareFile(context, file)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Экспорт данных (CSV)") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) { Text("Отладка") }

        Spacer(Modifier.height(16.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Шагомер", style = MaterialTheme.typography.titleSmall)
                Text("Версия 1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
