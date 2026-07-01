package com.pedometer

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class PedometerApp : Application() {
    companion object {
        var isInForeground = false
            private set
        var onForegroundChanged: ((Boolean) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
                Log.i("PedometerApp", "App → FOREGROUND")
                onForegroundChanged?.invoke(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
                Log.i("PedometerApp", "App → BACKGROUND")
                onForegroundChanged?.invoke(false)
            }
        })
    }
}
