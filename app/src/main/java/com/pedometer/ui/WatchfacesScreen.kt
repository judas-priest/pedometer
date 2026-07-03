package com.pedometer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pedometer.watchface.WatchfaceInfo

@Composable
fun WatchfacesScreen(
    watchfaces: List<WatchfaceInfo>,
    uploadProgress: Int,
    onRequestWatchfaces: () -> Unit,
    onSetActiveWatchface: (String) -> Unit,
    onDeleteWatchface: (String) -> Unit,
    onUploadWatchface: (ByteArray) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Циферблаты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (watchfaces.isEmpty()) {
                    FilledTonalButton(onClick = onRequestWatchfaces, modifier = Modifier.fillMaxWidth()) {
                        Text("Загрузить список")
                    }
                } else {
                    watchfaces.forEach { face ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(face.name.ifBlank { face.id }, style = MaterialTheme.typography.bodyMedium)
                                if (face.active) {
                                    Text("Активный", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (!face.active) {
                                TextButton(onClick = { onSetActiveWatchface(face.id) }) { Text("Выбрать") }
                            }
                            if (face.canDelete) {
                                TextButton(onClick = { onDeleteWatchface(face.id) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        if (face != watchfaces.last()) HorizontalDivider()
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRequestWatchfaces, modifier = Modifier.fillMaxWidth()) { Text("Обновить") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    if (bytes != null && bytes.isNotEmpty()) onUploadWatchface(bytes)
                } catch (_: Exception) {}
            }
        }

        if (uploadProgress >= 0) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Загрузка циферблата...", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { uploadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${uploadProgress}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            FilledTonalButton(onClick = { filePickerLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Text("Загрузить циферблат (.bin)")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
