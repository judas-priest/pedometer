package com.pedometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.ComponentName
import android.content.pm.PackageManager
import com.pedometer.debug.DebugScreen
import com.pedometer.music.MediaListenerService
import com.pedometer.ui.ConnectScreen
import com.pedometer.ui.NotificationAppsScreen
import com.pedometer.ui.OnboardingScreen
import com.pedometer.ui.theme.PedometerTheme
import com.pedometer.assistant.VoiceAssistant
import com.pedometer.vm.WatchViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Force restart NotificationListenerService (needed after APK update)
        val cn = ComponentName(this, MediaListenerService::class.java)
        packageManager.setComponentEnabledSetting(cn,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        packageManager.setComponentEnabledSetting(cn,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        setContent {
            PedometerTheme {
                val vm: WatchViewModel = viewModel()
                val state by vm.state.collectAsState()
                val voiceAssistant = remember { VoiceAssistant(this@MainActivity) }
                val micPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) voiceAssistant.startListening()
                }
                var showDebug by remember { mutableStateOf(false) }
                var showNotificationApps by remember { mutableStateOf(false) }

                // Onboarding on first launch
                val prefs = remember { getSharedPreferences("app_prefs", MODE_PRIVATE) }
                var showOnboarding by remember { mutableStateOf(!prefs.getBoolean("onboarding_done", false)) }

                if (showOnboarding) {
                    OnboardingScreen(onComplete = {
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                        showOnboarding = false
                    })
                    return@PedometerTheme
                }

                if (showDebug) {
                    androidx.activity.compose.BackHandler { showDebug = false }
                    DebugScreen(mac = state.macAddress.ifBlank { "E8:E6:09:31:23:D8" })
                    return@PedometerTheme
                }

                if (showNotificationApps) {
                    androidx.activity.compose.BackHandler { showNotificationApps = false }
                    NotificationAppsScreen(onBack = { showNotificationApps = false })
                    return@PedometerTheme
                }

                val pagerState = rememberPagerState(pageCount = { 3 })
                val scope = rememberCoroutineScope()

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = pagerState.currentPage == 0,
                                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                                icon = { Icon(Icons.Default.FavoriteBorder, contentDescription = null) },
                                label = { Text("Здоровье") },
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 1,
                                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                                icon = { Icon(Icons.Default.DirectionsRun, contentDescription = null) },
                                label = { Text("Активность") },
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 2,
                                onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Настройки") },
                            )
                        }
                    },
                ) { padding ->
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) { page ->
                        when (page) {
                            0 -> ConnectScreen(state = state)
                            1 -> com.pedometer.ui.ActivityTab(
                                state = state,
                                onCamera = { com.pedometer.util.PhoneActions.openCamera(this@MainActivity) },
                                onFlashlight = { com.pedometer.util.PhoneActions.toggleFlashlight(this@MainActivity) },
                                onFindWatch = { vm.findWatch() },
                                onBreathing = { vm.startBreathing() },
                                onScoTest = { voiceAssistant.testSco() },
                                onVoiceAssistant = {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                                            this@MainActivity, android.Manifest.permission.RECORD_AUDIO
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        voiceAssistant.startListening()
                                    } else {
                                        micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                            )
                            2 -> com.pedometer.ui.SettingsTab(
                                state = state,
                                onAuthKeyChange = vm::updateAuthKey,
                                onMacChange = vm::updateMacAddress,
                                onConnect = vm::connect,
                                onDisconnect = vm::disconnect,
                                onProfileChange = vm::updateProfile,
                                onOpenDebug = { showDebug = true },
                                onOpenNotificationApps = { showNotificationApps = true },
                                onFindWatch = { vm.findWatch() },
                                onRequestWatchfaces = { vm.requestWatchfaces() },
                                onSetActiveWatchface = { vm.setActiveWatchface(it) },
                                onDeleteWatchface = { vm.deleteWatchface(it) },
                                onUploadWatchface = { vm.uploadWatchface(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
