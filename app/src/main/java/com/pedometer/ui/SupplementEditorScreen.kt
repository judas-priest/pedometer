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
import androidx.compose.ui.unit.dp
import com.pedometer.data.StepDatabase
import com.pedometer.data.Supplement
import com.pedometer.health.SupplementSlot
import com.pedometer.health.SupplementWindows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drill-down screen: one slot at a time, switched by the tab row. */
@Composable
fun SupplementEditorScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf<List<Supplement>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var selectedSlot by remember { mutableStateOf(SupplementSlot.BREAKFAST) }

    suspend fun reload() = withContext(Dispatchers.IO) {
        StepDatabase.get(context).stepDao().getAllSupplements()
    }

    LaunchedEffect(Unit) { items = reload() }

    val prefs = context.getSharedPreferences("pedometer_prefs", android.content.Context.MODE_PRIVATE)
    var winPrefs by remember {
        mutableStateOf<Map<String, Int>>(
            prefs.all.filterValues { it is Int }.mapValues { it.value as Int }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("БАДы", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SupplementSlot.entries.forEach { slot ->
                FilterChip(
                    selected = selectedSlot == slot,
                    onClick = { selectedSlot = slot },
                    label = { Text(slot.title) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val slot = selectedSlot
        val slotItems = items.filter { it.slot == slot.key }.sortedBy { it.sort }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("home" to "Дома", "office" to "Офис").forEach { (regime, label) ->
                val w = SupplementWindows.windowFor(slot.key, regime, winPrefs)
                var startText by remember(slot.key, regime) { mutableStateOf(SupplementWindows.formatHhMm(w.startMin)) }
                var endText by remember(slot.key, regime) { mutableStateOf(SupplementWindows.formatHhMm(w.endMin)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.5f))
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { v ->
                            startText = v
                            SupplementWindows.parseHhMm(v)?.let { min ->
                                prefs.edit().putInt("supp_win_${slot.key}_${regime}_start", min).apply()
                                winPrefs = winPrefs + ("supp_win_${slot.key}_${regime}_start" to min)
                            }
                        },
                        label = { Text("С") },
                        modifier = Modifier.weight(0.25f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { v ->
                            endText = v
                            SupplementWindows.parseHhMm(v)?.let { min ->
                                prefs.edit().putInt("supp_win_${slot.key}_${regime}_end", min).apply()
                                winPrefs = winPrefs + ("supp_win_${slot.key}_${regime}_end" to min)
                            }
                        },
                        label = { Text("До") },
                        modifier = Modifier.weight(0.25f),
                        singleLine = true,
                    )
                }
            }
            if (slotItems.isEmpty()) {
                Text(
                    "Пока пусто",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            slotItems.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            StepDatabase.get(context).stepDao().deleteSupplement(item)
                            items = reload()
                        }
                    }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Название") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = newName.isNotBlank(),
                onClick = {
                    val name = newName.trim()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val dao = StepDatabase.get(context).stepDao()
                        val sort = dao.getAllSupplements().maxOfOrNull { it.sort }?.plus(1) ?: 0
                        dao.insertSupplement(Supplement(name = name, slot = selectedSlot.key, sort = sort))
                        items = reload()
                    }
                    newName = ""
                },
            ) { Text("Добавить") }
        }
    }
}
