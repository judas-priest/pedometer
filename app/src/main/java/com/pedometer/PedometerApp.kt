package com.pedometer

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pedometer.repo.WatchRepository
import com.pedometer.service.PhoneCallReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PedometerApp : Application() {
    companion object {
        private val _foreground = MutableStateFlow(false)
        val foreground: StateFlow<Boolean> = _foreground
        val isInForeground: Boolean get() = _foreground.value

        lateinit var repository: WatchRepository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        repository = WatchRepository.get(this)
        PhoneCallReceiver.registerTelephonyCallback(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _foreground.value = true
                Log.i("PedometerApp", "App → FOREGROUND")
            }

            override fun onStop(owner: LifecycleOwner) {
                _foreground.value = false
                Log.i("PedometerApp", "App → BACKGROUND")
            }
        })
        repository.scope.launch { PedometerApp.foreground.collect { repository.onForegroundChanged(it) } }
    }
}
