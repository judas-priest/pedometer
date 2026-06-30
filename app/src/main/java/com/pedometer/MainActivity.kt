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
import com.pedometer.debug.DebugScreen
import com.pedometer.ui.ConnectScreen
import com.pedometer.ui.theme.PedometerTheme
import com.pedometer.vm.WatchViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PedometerTheme {
                val vm: WatchViewModel = viewModel()
                val state by vm.state.collectAsState()
                var showDebug by remember { mutableStateOf(false) }

                if (showDebug) {
                    androidx.activity.compose.BackHandler { showDebug = false }
                    DebugScreen(mac = state.macAddress.ifBlank { "E8:E6:09:31:23:D8" })
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
                            1 -> com.pedometer.ui.ActivityTab(state = state)
                            2 -> com.pedometer.ui.SettingsTab(
                                state = state,
                                onAuthKeyChange = vm::updateAuthKey,
                                onMacChange = vm::updateMacAddress,
                                onConnect = vm::connect,
                                onDisconnect = vm::disconnect,
                                onProfileChange = vm::updateProfile,
                                onOpenDebug = { showDebug = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
