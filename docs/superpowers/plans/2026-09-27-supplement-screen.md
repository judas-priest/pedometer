# Supplement Editor Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the supplements editor out of SettingsTab into a dedicated drill-down screen; empty slots get an explicit «Пока пусто» state.

**Architecture:** Follows the app's existing sub-screen pattern exactly: boolean gate in MainActivity (`showSuppEditor`), `BackHandler`, screen gets `onBack`, SettingsTab gets an `onOpenSupplements` callback (same as its existing `onOpenDebug`/`onOpenNotificationApps`). The editor logic moves from SettingsTab into new `ui/SupplementEditorScreen.kt` unchanged, plus: top bar with back arrow (reference: `DayDetailScreen.kt:65`), empty-slot label, scrollable body.

**Tech Stack:** Kotlin, Compose M3 (BOM 2024.11.00 — FilterChip stable), Room DAO (already has delete/list).

**Worktree:** `/home/dima/.config/superpowers/worktrees/pedometer/health-insights`, branch `health-insights`. NEVER touch `/home/dima/Projects/pedometer`. No APK builds — `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` only.

---

### Task 1: Create SupplementEditorScreen.kt

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/SupplementEditorScreen.kt`

- [ ] **Step 1: Write the screen**

```kotlin
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drill-down screen: supplements grouped by slot, delete per item, add at the bottom. */
@Composable
fun SupplementEditorScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf<List<Supplement>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var selectedSlot by remember { mutableStateOf("breakfast") }

    suspend fun reload() = withContext(Dispatchers.IO) {
        StepDatabase.get(context).stepDao().getAllSupplements()
    }

    LaunchedEffect(Unit) { items = reload() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("БАДы", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SupplementSlot.entries.forEach { slot ->
                val slotItems = items.filter { it.slot == slot.key }.sortedBy { it.sort }
                Text(
                    slot.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SupplementSlot.entries.forEach { slot ->
                FilterChip(
                    selected = selectedSlot == slot.key,
                    onClick = { selectedSlot = slot.key },
                    label = { Text(slot.title) },
                )
            }
        }
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
                        dao.insertSupplement(Supplement(name = name, slot = selectedSlot, sort = sort))
                        items = reload()
                    }
                    newName = ""
                },
            ) { Text("Добавить") }
        }
    }
}
```

- [ ] **Step 2: Verify**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (file is not referenced yet — no call-site changes needed).

- [ ] **Step 3: Commit**

```bash
git add app/src
git commit -m "feat: dedicated supplement editor screen"
```

---

### Task 2: SettingsTab — remove inline editor, add navigation row

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt`

- [ ] **Step 1: Add the callback parameter**

`fun SettingsTab(` (line 30) gains one parameter (next to `onOpenNotificationApps`):

```kotlin
    onOpenSupplements: () -> Unit = {},
```

- [ ] **Step 2: Replace the inline editor call with a navigation row**

Find the two lines (inside the «БАДы» card, after the «Сегодня в офисе» Row):

```kotlin
                Spacer(Modifier.height(4.dp))
                SupplementsEditor()
```

Replace them with:

```kotlin
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSupplements() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Список препаратов", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Добавлять и удалять",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
```

(`clickable` is NOT imported in SettingsTab.kt — verified missing; add `import androidx.compose.foundation.clickable` to the import block.)

- [ ] **Step 3: Delete the old composable**

Remove the entire `private fun SupplementsEditor()` composable from the end of SettingsTab.kt (added in commit 6803461). Nothing else references it.

- [ ] **Step 4: Verify**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 100 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "refactor: settings supplements section navigates to dedicated editor"
```

---

### Task 3: MainActivity — gate and callback

**Files:**
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt`

- [ ] **Step 1: Add the gate state**

Next to the other sub-screen booleans (e.g. `showDayDetail` at :55, or where `showWatchSettings` is declared):

```kotlin
                var showSuppEditor by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Add the screen branch to the Scaffold else-if chain**

The Scaffold content (lines 119–167) is one else-if chain: `if (showAlarms)` :119 → `else if (showReminders)` :132 → `else if (showWatchfaces)` :144 → `else if (showWatchSettings)` :157 → `else` renders the pager :167. Add the supplements branch at the END of the chain, right before the final `else`: find line 167 (the `} else {` that opens the pager branch) and replace it with:

```kotlin
                    } else if (showSuppEditor) {
                        androidx.activity.compose.BackHandler { showSuppEditor = false }
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            SupplementEditorScreen(onBack = { showSuppEditor = false })
                        }
                    } else {
```

(The pager branch below it stays unchanged.)

Add import `com.pedometer.ui.SupplementEditorScreen`.

- [ ] **Step 3: Pass the callback**

`SettingsTab(` call at line 207 — add:

```kotlin
                                onOpenSupplements = { showSuppEditor = true },
```

- [ ] **Step 4: Verify**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 100 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: supplements editor reachable from settings as sub-screen"
```

---

### Task 4: Final verification

- [ ] **Step 1:** `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` — BUILD SUCCESSFUL, 100 tests, `git status` clean.
- [ ] **Step 2:** `git rebase master` before handoff.
