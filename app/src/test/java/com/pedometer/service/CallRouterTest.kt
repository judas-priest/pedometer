package com.pedometer.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CallRouterTest {

    @Test
    fun `dialer notification within the window wins`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        assertEquals(CallAction.None, router.onRinging(nowMs = 0))
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", "Входящий вызов"),
        )
        assertEquals(CallAction.None, router.onTick(nowMs = 5_000, fallbackTitle = "+79990000000"))
    }

    @Test
    fun `fallback fires when no dialer notification arrives`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        assertEquals(CallAction.None, router.onTick(nowMs = 1_000, fallbackTitle = "+79990000000"))
        assertEquals(
            CallAction.Show("+79990000000", "Входящий вызов"),
            router.onTick(nowMs = 2_000, fallbackTitle = "+79990000000"),
        )
    }

    @Test
    fun `only one show per call`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        assertEquals(CallAction.None, router.onDialerNotification("Мама", null))
    }

    @Test
    fun `idle dismisses only if something was shown`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        assertEquals(CallAction.None, router.onIdle())

        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        assertEquals(CallAction.Dismiss, router.onIdle())
        assertEquals(CallAction.None, router.onIdle())
    }

    @Test
    fun `a dialer notification with no ringing state still shows the call`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", "Входящий вызов"),
        )
    }

    @Test
    fun `blank body falls back to the default subtitle`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", "   "),
        )
    }

    @Test
    fun `a new call after idle can show again`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        router.onIdle()

        router.onRinging(nowMs = 10_000)
        assertEquals(
            CallAction.Show("Папа", "Входящий вызов"),
            router.onDialerNotification("Папа", null),
        )
    }

    @Test
    fun `a late dialer name upgrades a fallback-shown number card`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        assertEquals(
            CallAction.Show("+79990000000", "Входящий вызов"),
            router.onTick(nowMs = 2_000, fallbackTitle = "+79990000000"),
        )
        assertEquals(
            CallAction.Show("Мама", "Входящий вызов"),
            router.onDialerNotification("Мама", null),
        )
        assertEquals(CallAction.None, router.onDialerNotification("Мама", null))
    }

    @Test
    fun `a fallback number cannot overwrite a shown name`() {
        val router = CallRouter(fallbackDelayMs = 2_000)
        router.onRinging(nowMs = 0)
        router.onDialerNotification("Мама", null)
        assertEquals(CallAction.None, router.onTick(nowMs = 3_000, fallbackTitle = "+79990000000"))
    }
}
