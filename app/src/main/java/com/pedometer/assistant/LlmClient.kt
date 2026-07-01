package com.pedometer.assistant

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM client — OpenAI-compatible API (RouterAI, DeepSeek, etc.)
 * Falls back to local rules if no API key.
 */
object LlmClient {
    private const val TAG = "LlmClient"

    var apiUrl: String = "https://routerai.ru/api/v1"
    var apiKey: String = ""
    var model: String = "deepseek/deepseek-chat-v3.1"

    private const val SYSTEM_PROMPT = "Ты — голосовой ассистент на умных часах. Отвечай кратко (1-2 предложения), по-русски. Не используй markdown, эмодзи, форматирование."

    fun ask(query: String): String {
        if (apiKey.isNotBlank()) {
            try {
                val result = askOpenAI(query)
                if (result != null) return result
            } catch (e: Exception) {
                Log.e(TAG, "LLM failed: ${e.message}")
            }
        }
        return localAnswer(query)
    }

    private fun askOpenAI(query: String): String? {
        val url = URL("$apiUrl/chat/completions")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 150)
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", query)
                })
            })
        }

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())

        val code = conn.responseCode
        if (code != 200) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
            Log.e(TAG, "LLM HTTP $code: $error")
            conn.disconnect()
            return null
        }

        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val json = JSONObject(response)
        val text = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()

        Log.i(TAG, "LLM response: $text")
        return text
    }

    private fun localAnswer(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("время") || lower.contains("час") ->
                "Сейчас ${java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date())}"
            lower.contains("дата") || lower.contains("число") || lower.contains("день") ->
                java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale("ru")).format(java.util.Date())
            lower.contains("погода") -> "Посмотрите погоду на часах"
            lower.contains("привет") || lower.contains("здравствуй") -> "Привет! Чем могу помочь?"
            lower.contains("спасибо") -> "Пожалуйста!"
            else -> "Вы сказали: $query"
        }
    }
}
