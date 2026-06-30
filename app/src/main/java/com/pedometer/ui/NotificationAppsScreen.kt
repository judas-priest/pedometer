package com.pedometer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.pedometer.music.MediaListenerService

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    // Load apps that can receive notifications (have a launcher intent)
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { resolveInfo ->
                AppInfo(
                    packageName = resolveInfo.activityInfo.packageName,
                    appName = resolveInfo.loadLabel(pm).toString(),
                    icon = try { resolveInfo.loadIcon(pm) } catch (_: Exception) { null },
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    var selectedPackages by remember {
        mutableStateOf(MediaListenerService.getWhitelist(context).toMutableSet())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления на часы") },
                navigationIcon = {
                    IconButton(onClick = {
                        MediaListenerService.saveWhitelist(context, selectedPackages)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    "Выберите приложения для пересылки уведомлений на часы",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            items(apps) { app ->
                val checked = app.packageName in selectedPackages

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    app.icon?.let { drawable ->
                        Image(
                            bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            app.appName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            selectedPackages = selectedPackages.toMutableSet().apply {
                                if (isChecked) add(app.packageName) else remove(app.packageName)
                            }
                            MediaListenerService.saveWhitelist(context, selectedPackages)
                        },
                    )
                }
            }
        }
    }
}
