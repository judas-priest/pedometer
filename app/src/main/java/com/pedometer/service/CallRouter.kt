package com.pedometer.service

/** What the watch should be told, decided by CallRouter. */
sealed interface CallAction {
    data class Show(val title: String, val body: String) : CallAction
    data object Dismiss : CallAction
    data object None : CallAction
}

/**
 * Arbitrates between two racing sources of "there is an incoming call":
 * telephony state (fast, but only has a number) and the dialer's notification
 * (slower, but carries the resolved contact name).
 *
 * Pure: the caller supplies the clock and performs the sending.
 */
class CallRouter(private val fallbackDelayMs: Long = 2_000L) {

    private companion object {
        const val DEFAULT_BODY = "Входящий вызов"
    }

    private var ringingAtMs: Long? = null
    private var shown = false

    @Synchronized
    fun onRinging(nowMs: Long): CallAction {
        ringingAtMs = nowMs
        shown = false
        return CallAction.None
    }

    @Synchronized
    fun onDialerNotification(title: String, text: String?): CallAction {
        if (shown) return CallAction.None
        if (title.isBlank()) return CallAction.None
        shown = true
        val body = text?.takeIf { it.isNotBlank() } ?: DEFAULT_BODY
        return CallAction.Show(title, body)
    }

    /** Called periodically while ringing; emits the fallback once the window expires. */
    @Synchronized
    fun onTick(nowMs: Long, fallbackTitle: String): CallAction {
        if (shown) return CallAction.None
        val started = ringingAtMs ?: return CallAction.None
        if (nowMs - started < fallbackDelayMs) return CallAction.None
        shown = true
        return CallAction.Show(fallbackTitle, DEFAULT_BODY)
    }

    @Synchronized
    fun onIdle(): CallAction {
        val wasShown = shown
        shown = false
        ringingAtMs = null
        return if (wasShown) CallAction.Dismiss else CallAction.None
    }
}
