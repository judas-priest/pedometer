# Supplement Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Manage the supplement list from Settings — view pills grouped by slot, delete, add new with slot picker.

**Architecture:** Pure additive change on top of the shipped supplements feature. Two new DAO methods (`deleteSupplement`, `getAllSupplements`), one new composable `SupplementsEditor` in SettingsTab fed directly by the DAO (this composable has no ViewModel — direct IO + reload matches its self-contained prefs pattern). Scheduler, TodayScreen card and streak logic already read `getEnabledSupplements()` — they pick up edits with zero changes.

**Tech Stack:** Kotlin, Compose M3, Room.

**Worktree:** `/home/dima/.config/superpowers/worktrees/pedometer/health-insights`, branch `health-insights`. NEVER touch `/home/dima/Projects/pedometer`. No APK builds — `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` only.

**Out of scope (agreed with user):** editing time windows — `SupplementLogic.DEFAULTS` stays hardcoded this round.

---

### Task 1: DAO — delete and full list

**Files:**
- Modify: `app/src/main/java/com/pedometer/data/StepDao.kt` (supplements block, after `insertSupplement` at ~line 111)

- [ ] **Step 1: Add two methods**

Inside the `// Supplements` block, right after `suspend fun insertSupplement(s: Supplement)`:

```kotlin
    @Delete
    suspend fun deleteSupplement(s: Supplement)

    @Query("SELECT * FROM supplements ORDER BY sort")
    suspend fun getAllSupplements(): List<Supplement>
```

`@Delete` needs no SQL — Room matches by primary key. Import check: `androidx.room.*` is already wildcard-imported in this file.

- [ ] **Step 2: Verify**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 100 tests green.

- [ ] **Step 3: Commit**

```bash
git add app/src
git commit -m "feat: delete and list DAO methods for supplements"
```

---

### Task 2: Settings editor UI

**Files:**
- Modify: `app/src/main/java/com/pedometer/ui/SettingsTab.kt` (inside the «БАДы» section card, after the «Сегодня в офисе» Row at ~line 412)

- [ ] **Step 1: Add the editor composable**

Append at the end of `SettingsTab.kt` (top-level private composable):

```kotlin
@Composable
private fun SupplementsEditor() {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<com.pedometer.data.Supplement>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var selectedSlot by remember { mutableStateOf("breakfast") }

    suspend fun reload() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.pedometer.data.StepDatabase.get(context).stepDao().getAllSupplements()
    }

    LaunchedEffect(Unit) { items = reload() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.pedometer.health.SupplementSlot.entries.forEach { slot ->
            val slotItems = items.filter { it.slot == slot.key }.sortedBy { it.sort }
            Text(
                slot.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slotItems.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            com.pedometer.data.StepDatabase.get(context).stepDao().deleteSupplement(item)
                            items = reload()
                        }
                    }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.pedometer.health.SupplementSlot.entries.forEach { slot ->
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
                        val dao = com.pedometer.data.StepDatabase.get(context).stepDao()
                        val sort = dao.getAllSupplements().maxOfOrNull { it.sort }?.plus(1) ?: 0
                        dao.insertSupplement(com.pedometer.data.Supplement(name = name, slot = selectedSlot, sort = sort))
                        items = reload()
                    }
                    newName = ""
                },
            ) { Text("Добавить") }
        }
    }
}
```

Imports to add at the top of SettingsTab.kt (verify against existing imports first, skip duplicates):
`androidx.compose.material3.FilterChip`, `androidx.compose.material3.Button`, `androidx.compose.material3.TextButton`, `androidx.compose.ui.platform.LocalContext`, `kotlinx.coroutines.launch`.

If `FilterChip` fails to compile with an opt-in requirement (`@ExperimentalMaterial3Api`), replace the three `FilterChip`s with `TextButton` + border/color emphasis (`colors = ButtonDefaults.textButtonColors(contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)`) — do NOT add new @OptIn annotations to the file.

- [ ] **Step 2: Insert into the «БАДы» settings card**

In the «БАДы» section card: the «Сегодня в офисе» Row closes at line 414, the section `Column` closes at line 415, the `ElevatedCard` closes at line 416. Insert INSIDE the Column — between line 414 (`}` closing the Row) and line 415 (`}` closing the Column):

```kotlin
                Spacer(Modifier.height(4.dp))
                SupplementsEditor()
```

Imports note: `material3.*`, `runtime.*`, `LocalContext` (line 19) and `kotlinx.coroutines.launch` (line 22) are already wildcard/single-imported in this file — only `FilterChip`, `Button`, `TextButton` need nothing extra. No new imports required unless the compiler names one.

- [ ] **Step 3: Verify**

Run: `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 100 tests green.

- [ ] **Step 4: Commit**

```bash
git add app/src
git commit -m "feat: supplement list editor in settings — add, delete, slot picker"
```

---

### Task 3: Final verification

- [ ] **Step 1:** `unset LD_PRELOAD; ./gradlew compileDebugKotlin testDebugUnitTest` — BUILD SUCCESSFUL, 100 tests.
- [ ] **Step 2:** `git status` clean; `git rebase master` before handoff.
