package com.pedometer.health

import android.content.Context
import android.util.Log
import com.pedometer.data.StepDatabase
import com.pedometer.data.Supplement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SupplementSeed {
    private const val TAG = "SupplementSeed"
    private const val KEY_SEEDED = "supplements_seeded"

    /** One-time seed of the user's stack. Fasting stays empty until ALA arrives. */
    suspend fun seedIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("pedometer_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return@withContext
        val dao = StepDatabase.get(context).stepDao()
        val items = listOf(
            Supplement(name = "B1 100 мг", slot = "breakfast", sort = 0),
            Supplement(name = "B12 10 мкг", slot = "breakfast", sort = 1),
            Supplement(name = "D3 250 МЕ", slot = "breakfast", sort = 2),
            Supplement(name = "Астаксантин 4.8 мг", slot = "breakfast", sort = 3),
            Supplement(name = "Хонда №1", slot = "breakfast", sort = 4),
            Supplement(name = "Уридин", slot = "breakfast", sort = 5),
            Supplement(name = "Бенфотиамин №1", slot = "breakfast", sort = 6),
            Supplement(name = "Хонда №2", slot = "flex", sort = 7),
            Supplement(name = "Бенфотиамин №2", slot = "flex", sort = 8),
        )
        items.forEach { dao.insertSupplement(it) }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "Seeded ${items.size} supplements")
    }
}
