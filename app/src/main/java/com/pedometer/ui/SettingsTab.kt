package com.pedometer.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.text.input.KeyboardType
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
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    val profile = state.profile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

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

        // Background service
        Text("Фоновый процесс", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Фоновый сбор данных", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Почасовой график, cadence, этажи. Расходует батарею",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = profile.backgroundServiceEnabled,
                        onCheckedChange = {
                            onProfileChange(profile.copy(backgroundServiceEnabled = it))
                        },
                    )
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
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("О приложении", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Шагомер", style = MaterialTheme.typography.titleSmall)
                Text("Версия 0.2.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
