package com.pedometer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pedometer.vm.ConnectionStatus
import com.pedometer.vm.WatchState

@Composable
fun ConnectScreen(
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pedometer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.macAddress,
            onValueChange = onMacChange,
            label = { Text("MAC Address") },
            placeholder = { Text("XX:XX:XX:XX:XX:XX") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.authKey,
            onValueChange = onAuthKeyChange,
            label = { Text("Auth Key") },
            placeholder = { Text("0x...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))

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
                        permissionLauncher.launch(perms.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.authKey.isNotBlank() && state.macAddress.isNotBlank(),
                ) {
                    Text("Connect")
                }
            }
            ConnectionStatus.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Connecting...")
            }
            ConnectionStatus.Authenticating -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Authenticating...")
            }
            ConnectionStatus.Connected -> {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Disconnect")
                }
            }
        }

        if (state.connectionStatus == ConnectionStatus.Connected) {
            Spacer(Modifier.height(24.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Device Info", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.model.isNotBlank()) Text("Model: ${state.model}")
                    if (state.firmware.isNotBlank()) Text("Firmware: ${state.firmware}")
                    if (state.serialNumber.isNotBlank()) Text("S/N: ${state.serialNumber}")
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Battery", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.batteryLevel >= 0) {
                        Text("${state.batteryLevel}%${if (state.batteryCharging) " (charging)" else ""}")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.batteryLevel / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Loading...")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HealthDashboard(state = state)
        }
    }
}
