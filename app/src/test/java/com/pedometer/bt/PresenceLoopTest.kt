package com.pedometer.bt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Requirement: «Wi-Fi дома → скан спит. Потеря Wi-Fi → немедленный скан.
 * Вне дома поведение прежнее».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceLoopTest {

    private class Harness(scope: CoroutineScope, var suppressed: Boolean = false) {
        var scans = 0
        var lastPresence: Boolean? = null
        var quietChanged = 0
        var quietHours: QuietHours = QuietHours.DISABLED

        /** Что возвращает каждый скан: true = часы в эфире (каденс 60с), false = нет (бэкофф 5м → 10м). */
        var found: Boolean = false

        val loop = PresenceLoop(
            scope = scope,
            quietHours = { quietHours },
            onQuietChanged = { quietChanged++ },
            onPresenceChanged = { lastPresence = it },
            connected = { false },
            scanSuppressed = { suppressed },
            scanOnce = { scans++; found },
        )
    }

    // 1. Скан спит дома
    @Test
    fun `suppressed loop does not scan`() = runTest {
        val h = Harness(this, suppressed = true)
        h.loop.start()
        advanceTimeBy(10 * 60_000L)
        assertEquals(0, h.scans)
    }

    // 2. Потеря Wi-Fi будит немедленно
    @Test
    fun `scanNow wakes a suppressed loop the moment suppression ends`() = runTest {
        val h = Harness(this, suppressed = true)
        h.loop.start()
        advanceTimeBy(5 * 60_000L)
        assertEquals(0, h.scans)
        h.suppressed = false
        h.loop.scanNow()
        runCurrent()
        assertEquals(1, h.scans)
    }

    // 3. Вне дома, часы в эфире: каденс 60с (интервал минус окно 3с = сканы на 0, 57, 114с)
    @Test
    fun `unsuppressed with watch present scans every minute`() = runTest {
        val h = Harness(this)
        h.found = true
        h.loop.start()
        runCurrent()
        assertEquals(1, h.scans)
        advanceTimeBy(60_000L)
        assertEquals(2, h.scans) // на 57-й секунде
        advanceTimeBy(60_000L)
        assertEquals(3, h.scans) // на 114-й секунде
    }

    // 3b. Часов нет: бэкофф по ScanIntervalPolicy — после первого false интервал 5 минут
    @Test
    fun `absent watch backs off five minutes after first miss`() = runTest {
        val h = Harness(this)
        h.found = false
        h.loop.start()
        runCurrent()
        assertEquals(1, h.scans)
        advanceTimeBy(60_000L)
        assertEquals(1, h.scans) // 60с мало — сон 297с (5м минус окно 3с)
        advanceTimeBy(300_000L)
        assertEquals(2, h.scans) // второй скан на 297-й секунде
        advanceTimeBy(60_000L)
        assertEquals(2, h.scans) // после второго miss бэкофф 10 минут
    }

    // 4. scanNow будит и обычный сон (внутри 297-секундного бэкоффа)
    @Test
    fun `scanNow during backoff sleep triggers an immediate scan`() = runTest {
        val h = Harness(this)
        h.found = false
        h.loop.start()
        runCurrent()
        advanceTimeBy(50_000L)
        assertEquals(1, h.scans)
        h.loop.scanNow()
        runCurrent()
        assertEquals(2, h.scans)
    }

    // 5. Дубликат scanNow — один wake-токен, без спина
    @Test
    fun `repeated scanNow does not cause a spin`() = runTest {
        val h = Harness(this)
        h.found = false
        h.loop.start()
        runCurrent() // scan1
        h.loop.scanNow()
        h.loop.scanNow()
        runCurrent()
        assertEquals(2, h.scans) // оба токена схлопнулись в один скан
        advanceTimeBy(120_000L)
        assertEquals(2, h.scans) // дальше обычный бэкофф, никаких лишних сканов
    }

    // 6. Тихие часы сильнее подавления Wi-Fi
    @Test
    fun `quiet hours block scans even when not suppressed`() = runTest {
        val h = Harness(this)
        h.quietHours = QuietHours(true, 0, 7)
        h.loop.start()
        advanceTimeBy(3 * 60_000L)
        assertEquals(0, h.scans)
        h.quietHours = QuietHours.DISABLED
        h.loop.scanNow()
        runCurrent()
        assertEquals(1, h.scans)
    }
}
