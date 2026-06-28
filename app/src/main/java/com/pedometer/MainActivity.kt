package com.pedometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedometer.ui.ConnectScreen
import com.pedometer.ui.theme.PedometerTheme
import com.pedometer.vm.WatchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Test OPLUS StepProvider
        com.pedometer.health.StepProviderReader.tryPaths(this)
        setContent {
            PedometerTheme {
                val vm: WatchViewModel = viewModel()
                val state by vm.state.collectAsState()
                ConnectScreen(
                    state = state,
                    onAuthKeyChange = vm::updateAuthKey,
                    onMacChange = vm::updateMacAddress,
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                )
            }
        }
    }
}
