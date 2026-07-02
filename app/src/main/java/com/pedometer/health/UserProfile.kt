package com.pedometer.health

import android.content.Context
import android.content.SharedPreferences

data class UserProfile(
    val heightCm: Int = 175,
    val weightKg: Int = 70,
    val isMale: Boolean = true,
    val stepGoal: Int = 8000,
    val weatherCity: String = "",  // empty = auto GPS
) {
    val stepLengthM: Double get() = heightCm * (if (isMale) 0.415 else 0.413) / 100.0

    fun calcDistance(steps: Int): Double = steps * stepLengthM / 1000.0 // km

    fun calcCalories(steps: Int): Double = steps * 0.04 * weightKg / 1000.0 // kcal

    companion object {
        private const val PREFS = "user_profile"

        fun load(context: Context): UserProfile {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return UserProfile(
                heightCm = p.getInt("height", 175),
                weightKg = p.getInt("weight", 70),
                isMale = p.getBoolean("male", true),
                stepGoal = p.getInt("goal", 8000),
                weatherCity = p.getString("weather_city", "") ?: "",
            )
        }

        fun save(context: Context, profile: UserProfile) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("height", profile.heightCm)
                .putInt("weight", profile.weightKg)
                .putBoolean("male", profile.isMale)
                .putInt("goal", profile.stepGoal)
                .putString("weather_city", profile.weatherCity)
                .apply()
        }
    }
}
