package com.pedometer.service

import android.os.Build

/**
 * Результат разрешения статуса тренировки в UI-эффекты.
 *
 * Содержит companion-объект с чистой логикой (текст, капелька, вибро).
 * NOTE: Kotlin запрещает top-level `data class` и `object` с одним именем в одном пакете
 * (JVM name clash), поэтому логика живёт в companion.
 */
data class WorkoutStatusUi(
    /** Текст FGS-нотификации; null = вернуть дефолтный текст статуса линка. */
    val notificationText: String?,
    /** Показывать ли promoted-капсулу (при живой тренировке и валидных данных). */
    val showLiveUpdate: Boolean,
    /** Вибро-паттерн для этого события; null = тишина. */
    val vibration: VibrationKind?,
) {
    companion object {

        /** Обычный одиночный тик (референс для уплотнения триоли). */
        const val TICK_VIB_MS = 150L
        const val TICK_PAUSE_MS = 150L

        /**
         * Триоль: три равных импульса в длительности двух обычных тиков —
         * (vib+pause) = 2/3 (TICK_VIB+TICK_PAUSE) = 200 мс.
         * Импульс 120 мс — ощутим в кармане при ходьбе (60 мс не чувствовались).
         */
        val TRIOLE_TIMINGS = longArrayOf(0L, 120L, 80L, 120L, 80L, 120L)

        /**
         * Чистая логика (без Android, кроме констант): текст, капелька, вибро.
         *
         * event: 0 старт, 1 продолжение, 2 пауза, 3 финиш; null = живого события нет
         * (рендер на обрыве/реконнекте/обновлении шагов).
         */
        fun resolve(
            event: Int?,
            sport: String,
            paused: Boolean,
            connected: Boolean,
            active: Boolean,
            enabled: Boolean,
        ): WorkoutStatusUi {
            // Триоль на старте/финише; при выключенной фиче — тишина даже на границах.
            val vibration = when {
                !enabled -> null
                event == 0 || event == 3 -> VibrationKind.TRIOLE
                else -> null
            }
            // Финиш — терминальное событие: дефолтный UI даже если active ещё не сбросился.
            if (!enabled || !active || event == 3) {
                return WorkoutStatusUi(notificationText = null, showLiveUpdate = false, vibration = vibration)
            }
            val text = when {
                !connected -> "$sport · нет связи"
                paused -> "$sport · Пауза"
                else -> sport
            }
            return WorkoutStatusUi(notificationText = text, showLiveUpdate = true, vibration = vibration)
        }

        /** Капсула показывается только с валидными данными — никаких пустых состояний. */
        fun shouldShowCapsule(daySteps: Int, goal: Int): Boolean = daySteps >= 0 && goal > 0

        /** Требование платформы: promoted возможен только на API 36+. */
        fun promotionSupported(): Boolean = Build.VERSION.SDK_INT >= 36
    }
}

enum class VibrationKind { TRIOLE }
