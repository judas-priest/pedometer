package com.pedometer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Требование: «{имя тренировки} обычно; {имя} · Пауза при паузе; {имя} · нет связи при обрыве;
 * на финише — дефолтный статус линка; триоль на старте и финише; капелька = дневные шаги к цели;
 * выключатель возвращает старое поведение».
 */
class WorkoutStatusUiTest {

    // 1. Старт, связь жива
    @Test
    fun `start shows sport name with capsule and triole`() {
        val ui = WorkoutStatusUi.resolve(event = 0, sport = "Ходьба", paused = false,
            connected = true, active = true, enabled = true)
        assertEquals("Ходьба", ui.notificationText)
        assertTrue(ui.showLiveUpdate)
        assertEquals(VibrationKind.TRIOLE, ui.vibration)
    }

    // 2. Продолжение — без вибро
    @Test
    fun `resume shows sport name without vibration`() {
        val ui = WorkoutStatusUi.resolve(event = 1, sport = "Ходьба", paused = false,
            connected = true, active = true, enabled = true)
        assertEquals("Ходьба", ui.notificationText)
        assertTrue(ui.showLiveUpdate)
        assertNull(ui.vibration)
    }

    // 3. Пауза
    @Test
    fun `pause appends pause suffix`() {
        val ui = WorkoutStatusUi.resolve(event = 2, sport = "Ходьба", paused = true,
            connected = true, active = true, enabled = true)
        assertEquals("Ходьба · Пауза", ui.notificationText)
        assertTrue(ui.showLiveUpdate)
        assertNull(ui.vibration)
    }

    // 4. Финиш — дефолт, триоль
    @Test
    fun `finish clears text and capsule with triole`() {
        val ui = WorkoutStatusUi.resolve(event = 3, sport = "Ходьба", paused = false,
            connected = true, active = false, enabled = true)
        assertNull(ui.notificationText)
        assertFalse(ui.showLiveUpdate)
        assertEquals(VibrationKind.TRIOLE, ui.vibration)
    }

    // 5. Обрыв связи при активной тренировке
    @Test
    fun `link drop during workout marks no-link`() {
        val ui = WorkoutStatusUi.resolve(event = null, sport = "Ходьба", paused = false,
            connected = false, active = true, enabled = true)
        assertEquals("Ходьба · нет связи", ui.notificationText)
        assertTrue(ui.showLiveUpdate)
        assertNull(ui.vibration)
    }

    // 6. Реконнект при активной тренировке — тихий возврат
    @Test
    fun `reconnect during workout restores text silently`() {
        val ui = WorkoutStatusUi.resolve(event = null, sport = "Ходьба", paused = false,
            connected = true, active = true, enabled = true)
        assertEquals("Ходьба", ui.notificationText)
        assertTrue(ui.showLiveUpdate)
        assertNull(ui.vibration)
    }

    // 7. Фича выключена — старое поведение
    @Test
    fun `disabled feature returns default for every event`() {
        for (event in listOf(0, 1, 2, 3, null)) {
            val ui = WorkoutStatusUi.resolve(event, "Ходьба", paused = false,
                connected = true, active = true, enabled = false)
            assertNull(ui.notificationText)
            assertFalse(ui.showLiveUpdate)
            assertNull(ui.vibration)
        }
    }

    // 8. Другой спорт
    @Test
    fun `sport name is used verbatim`() {
        val ui = WorkoutStatusUi.resolve(event = 0, sport = "Бег", paused = false,
            connected = true, active = true, enabled = true)
        assertEquals("Бег", ui.notificationText)
    }

    // 9. Форма триоли: три равных вибро + равные паузы, уплотнённые (2/3 обычного тика)
    @Test
    fun `triole waveform is three equal pulses in the time of two`() {
        val t = WorkoutStatusUi.TRIOLE_TIMINGS
        // [0, vib, pause, vib, pause, vib]
        assertEquals(6, t.size)
        assertEquals(0L, t[0])
        val vibs = listOf(t[1], t[3], t[5])
        val pauses = listOf(t[2], t[4])
        assertEquals(1, vibs.distinct().size)
        assertEquals(1, pauses.distinct().size)
        // уплотнение: (vib+pause) = 2/3 (TICK_VIB+TICK_PAUSE) обычного тика
        assertEquals(WorkoutStatusUi.TICK_VIB_MS + WorkoutStatusUi.TICK_PAUSE_MS,
            (vibs[0] + pauses[0]) * 3 / 2)
    }

    // 10. Капелька без данных шагов не показывается
    @Test
    fun `capsule hidden without step data`() {
        assertFalse(WorkoutStatusUi.shouldShowCapsule(daySteps = -1, goal = 8000))
        assertTrue(WorkoutStatusUi.shouldShowCapsule(daySteps = 0, goal = 8000))
    }

    // 11. Кривая цель — капельки нет
    @Test
    fun `capsule hidden with nonpositive goal`() {
        assertFalse(WorkoutStatusUi.shouldShowCapsule(daySteps = 5000, goal = 0))
    }

    // 12. Дубликат старта — тот же текст
    @Test
    fun `duplicate start events produce same text`() {
        val a = WorkoutStatusUi.resolve(0, "Ходьба", false, true, true, true)
        val b = WorkoutStatusUi.resolve(0, "Ходьба", false, true, true, true)
        assertEquals(a.notificationText, b.notificationText)
        assertEquals(a.showLiveUpdate, b.showLiveUpdate)
    }
}
