package com.pedometer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pedometer.health.SupplementNotifier
import com.pedometer.health.SupplementScheduler

/** Fires when the supplement notification is swiped away — swipe means "taken". */
class SupplementDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(SupplementNotifier.EXTRA_SLOT) ?: return
        val postedAt = intent.getLongExtra(SupplementNotifier.EXTRA_POSTED_AT, 0L)
        SupplementScheduler.onDismissed(context.applicationContext, slot, postedAt)
    }
}
