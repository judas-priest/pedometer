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

@Composable
fun WatchSettingsScreen(
    onDndChange: (Boolean) -> Unit,
    onWearingModeChange: (Int) -> Unit,
    onSyncContacts: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Настройки часов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var dndEnabled by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Не беспокоить (DND)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = dndEnabled, onCheckedChange = { dndEnabled = it; onDndChange(it) })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Режим ношения", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Браслет" to 0, "Клипса" to 1, "Ожерелье" to 2).forEach { (label, mode) ->
                        FilterChip(selected = false, onClick = { onWearingModeChange(mode) }, label = { Text(label) })
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                OutlinedButton(onClick = onSyncContacts, modifier = Modifier.fillMaxWidth()) {
                    Text("Синхронизировать контакты")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
