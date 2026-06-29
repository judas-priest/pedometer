package com.pedometer.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DebugScreen(mac: String) {
    val context = LocalContext.current
    val tool = remember { BleDebugTool(context) }
    val log by tool.log.collectAsState()
    val services by tool.services.collectAsState()
    val connected by tool.connected.collectAsState()
    val listState = rememberLazyListState()

    var writeService by remember { mutableStateOf("") }
    var writeChar by remember { mutableStateOf("") }
    var writeHex by remember { mutableStateOf("") }

    // Auto-scroll log
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        // Header + Connect
        Text("BLE Debug: $mac", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(
                onClick = { tool.connect(mac) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        } else {
            Button(
                onClick = { tool.disconnect() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Disconnect")
            }
        }

        Spacer(Modifier.height(4.dp))

        // Quick actions
        if (connected && services.isNotEmpty()) {
            Text("Quick", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                // DLVP: enable debug UART (mode 0x2F4 on PA18/PA19)
                AssistChip(
                    onClick = {
                        // handle 0xA407 → write mode bytes for UART
                        // UUID pattern: 0000a407-0000-1000-8000-00805f9b34fb
                        tool.writeChar("0000a407", "0000a407",
                            byteArrayOf(0xF4.toByte(), 0x02))
                    },
                    label = { Text("UART on") },
                )
                // DLVP: enable JTAG/SWD (mode 0x2D2)
                AssistChip(
                    onClick = {
                        tool.writeChar("0000a407", "0000a407",
                            byteArrayOf(0xD2.toByte(), 0x02))
                    },
                    label = { Text("JTAG on") },
                )
                // Read all characteristics
                AssistChip(
                    onClick = {
                        for (entry in services) {
                            if (entry.properties and 0x02 != 0) { // PROPERTY_READ
                                tool.readChar(entry.serviceUuid, entry.charUuid)
                            }
                        }
                    },
                    label = { Text("Read all") },
                )
                AssistChip(
                    onClick = { tool.clearLog() },
                    label = { Text("Clear") },
                )
            }

            Spacer(Modifier.height(4.dp))

            // GATT table
            Text("GATT (${services.size} chars)", style = MaterialTheme.typography.labelMedium)
            LazyColumn(modifier = Modifier.height(120.dp)) {
                items(services) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${entry.charUuid} [${entry.propsStr}]",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (entry.properties and 0x02 != 0) {
                                TextButton(
                                    onClick = { tool.readChar(entry.serviceUuid, entry.charUuid) },
                                    contentPadding = PaddingValues(4.dp),
                                    modifier = Modifier.height(24.dp),
                                ) { Text("R", fontSize = 10.sp) }
                            }
                            if (entry.properties and 0x10 != 0) {
                                TextButton(
                                    onClick = { tool.enableNotify(entry.serviceUuid, entry.charUuid) },
                                    contentPadding = PaddingValues(4.dp),
                                    modifier = Modifier.height(24.dp),
                                ) { Text("N", fontSize = 10.sp) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Manual write
            Text("Write", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = writeChar,
                    onValueChange = { writeChar = it },
                    label = { Text("UUID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = writeHex,
                    onValueChange = { writeHex = it },
                    label = { Text("Hex") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                )
                FilledTonalButton(
                    onClick = {
                        val bytes = writeHex.replace(" ", "")
                            .chunked(2)
                            .map { it.toInt(16).toByte() }
                            .toByteArray()
                        val svc = if (writeService.isNotBlank()) writeService else writeChar
                        tool.writeChar(svc, writeChar, bytes)
                    },
                    contentPadding = PaddingValues(8.dp),
                ) { Text("Send") }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Log
        Text("Log (${log.size})", style = MaterialTheme.typography.labelMedium)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1A1A1A)),
        ) {
            items(log) { line ->
                val color = when {
                    line.contains("FAILED") || line.contains("error", ignoreCase = true) -> Color(0xFFFF5252)
                    line.contains("OK") || line.contains("enabled") || line.contains("Connected") -> Color(0xFF69F0AE)
                    line.contains("NOTIFY") || line.contains("READ") -> Color(0xFF40C4FF)
                    line.contains("WRITE") -> Color(0xFFFFD740)
                    else -> Color(0xFFBBBBBB)
                }
                Text(
                    line,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}
