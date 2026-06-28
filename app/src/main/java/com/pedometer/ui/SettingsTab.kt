package com.pedometer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun SettingsTab(
    state: WatchState,
    onAuthKeyChange: (String) -> Unit,
    onMacChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        // Watch section
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

        Spacer(Modifier.height(24.dp))
        Text("О приложении", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Шагомер", style = MaterialTheme.typography.titleSmall)
                Text("Версия 0.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Данные: OPLUS StepProvider, телефонный сенсор, Health Connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
