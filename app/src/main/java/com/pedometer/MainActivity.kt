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
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.ComponentName
import android.content.pm.PackageManager
import com.pedometer.debug.DebugScreen
import com.pedometer.music.MediaListenerService
import com.pedometer.ui.*
import com.pedometer.ui.theme.PedometerTheme
import com.pedometer.vm.WatchViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cn = ComponentName(this, MediaListenerService::class.java)
        packageManager.setComponentEnabledSetting(cn,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        packageManager.setComponentEnabledSetting(cn,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        setContent {
            PedometerTheme {
                val vm: WatchViewModel = viewModel()
                val state by vm.state.collectAsState()
                var showDebug by remember { mutableStateOf(false) }
                var showNotificationApps by remember { mutableStateOf(false) }
                var showDayDetail by remember { mutableStateOf<java.time.LocalDate?>(null) }
                var showAlarms by remember { mutableStateOf(false) }
                var showReminders by remember { mutableStateOf(false) }
                var showWatchfaces by remember { mutableStateOf(false) }
                var showWatchSettings by remember { mutableStateOf(false) }

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

                val pagerState = rememberPagerState(pageCount = { 4 })
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
                                icon = { Icon(Icons.Default.Watch, contentDescription = null) },
                                label = { Text("Устройство") },
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 3,
                                onClick = { scope.launch { pagerState.animateScrollToPage(3) } },
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
                            0 -> if (showDayDetail != null) {
                                androidx.activity.compose.BackHandler { showDayDetail = null }
                                DayDetailScreen(
                                    state = state,
                                    initialDate = showDayDetail!!,
                                    onBack = { showDayDetail = null },
                                )
                            } else {
                                TodayScreen(
                                    state = state,
                                    onRefresh = { vm.refreshData() },
                                    onTodayTap = { showDayDetail = java.time.LocalDate.now() },
                                )
                            }
                            1 -> ActivityScreen(
                                state = state,
                                onDayTap = {
                                    showDayDetail = it
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                },
                                onRefresh = { vm.refreshData() },
                            )
                            2 -> when {
                                showAlarms -> {
                                    LaunchedEffect(Unit) { vm.getAlarms() }
                                    androidx.activity.compose.BackHandler { showAlarms = false }
                                    AlarmsScreen(
                                        alarms = state.alarms,
                                        onCreateAlarm = { h, m -> vm.createAlarm(h, m) },
                                        onDeleteAlarm = { vm.deleteAlarm(it) },
                                        onToggleAlarm = { vm.editAlarm(it) },
                                        onBack = { showAlarms = false },
                                    )
                                }
                                showReminders -> {
                                    LaunchedEffect(Unit) { vm.refreshCalendarEvents() }
                                    androidx.activity.compose.BackHandler { showReminders = false }
                                    RemindersScreen(
                                        events = state.calendarEvents,
                                        onCreateEvent = { title, y, m, d, h, min -> vm.createCalendarEvent(title, y, m, d, h, min) },
                                        onDeleteEvent = { vm.deleteCalendarEvent(it) },
                                        onBack = { showReminders = false },
                                    )
                                }
                                showWatchfaces -> {
                                    androidx.activity.compose.BackHandler { showWatchfaces = false }
                                    WatchfacesScreen(
                                        watchfaces = state.watchfaces,
                                        uploadProgress = state.uploadProgress,
                                        onRequestWatchfaces = { vm.requestWatchfaces() },
                                        onSetActiveWatchface = { vm.setActiveWatchface(it) },
                                        onDeleteWatchface = { vm.deleteWatchface(it) },
                                        onUploadWatchface = { vm.uploadWatchface(it) },
                                        onBack = { showWatchfaces = false },
                                    )
                                }
                                showWatchSettings -> {
                                    androidx.activity.compose.BackHandler { showWatchSettings = false }
                                    WatchSettingsScreen(
                                        onDndChange = { vm.setDnd(it) },
                                        onWearingModeChange = { vm.setWearingMode(it) },
                                        onSyncContacts = { vm.syncContacts() },
                                        onBack = { showWatchSettings = false },
                                    )
                                }
                                else -> DeviceTab(
                                    state = state,
                                    onOpenAlarms = { showAlarms = true },
                                    onOpenReminders = { showReminders = true },
                                    onOpenWatchfaces = { showWatchfaces = true },
                                    onOpenWatchSettings = { showWatchSettings = true },
                                    onFindWatch = { vm.findWatch() },
                                )
                            }
                            3 -> SettingsTab(
                                state = state,
                                onAuthKeyChange = vm::updateAuthKey,
                                onMacChange = vm::updateMacAddress,
                                onConnect = vm::connect,
                                onDisconnect = vm::disconnect,
                                onProfileChange = vm::updateProfile,
                                onOpenDebug = { showDebug = true },
                                onOpenNotificationApps = { showNotificationApps = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
