package com.pedometer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class OnboardingPage(
    val emoji: String,
    val title: String,
    val description: String,
    val buttonText: String? = null,
    val buttonAction: (() -> Unit)? = null,
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    val pages = listOf(
        OnboardingPage(
            emoji = "⌚",
            title = "Шагомер",
            description = "Полная замена Mi Fitness.\nШаги, пульс, уведомления, музыка, погода — всё в одном приложении.",
        ),
        OnboardingPage(
            emoji = "📱",
            title = "Разрешения",
            description = "Для работы нужны: Bluetooth, геолокация, контакты, активность.",
            buttonText = "Разрешить",
            buttonAction = {
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.READ_CONTACTS,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                    perms.add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            },
        ),
        OnboardingPage(
            emoji = "🔋",
            title = "Батарея",
            description = "Отключите оптимизацию батареи и включите автозапуск для стабильной работы.",
            buttonText = "Настройки",
            buttonAction = {
                context.startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ))
            },
        ),
        OnboardingPage(
            emoji = "🔔",
            title = "Уведомления",
            description = "Разрешите доступ к уведомлениям для пересылки на часы и управления музыкой.",
            buttonText = "Разрешить",
            buttonAction = {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            },
        ),
        OnboardingPage(
            emoji = "✅",
            title = "Готово!",
            description = "Подключите часы в Настройках.\nВведите MAC-адрес и ключ авторизации.",
            buttonText = "Начать",
            buttonAction = onComplete,
        ),
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val p = pages[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(p.emoji, fontSize = 64.sp)
                Spacer(Modifier.height(24.dp))
                Text(
                    p.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    p.description,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.buttonText != null && p.buttonAction != null) {
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = p.buttonAction) {
                        Text(p.buttonText)
                    }
                }
            }
        }

        // Page indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { i ->
                val color = if (i == pagerState.currentPage)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(8.dp)
                        .background(color, MaterialTheme.shapes.small),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Skip / Next buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onComplete) {
                Text("Пропустить")
            }
            if (pagerState.currentPage < pages.size - 1) {
                Button(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }) {
                    Text("Далее")
                }
            }
        }
    }
}

