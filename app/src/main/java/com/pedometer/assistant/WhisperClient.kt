package com.pedometer.assistant

import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whisper-compatible STT via OpenAI-compatible API (RouterAI).
 * POST /v1/audio/transcriptions with multipart form data.
 */
object WhisperClient {
    private const val TAG = "WhisperClient"

    fun transcribe(wavData: ByteArray): String? {
        val apiUrl = LlmClient.apiUrl
        val apiKey = LlmClient.apiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key, skipping Whisper")
            return null
        }

        return try {
            val boundary = "----Boundary${System.currentTimeMillis()}"
            val url = URL("$apiUrl/audio/transcriptions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.doOutput = true

            val out = DataOutputStream(conn.outputStream)

            // model field
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            out.writeBytes("whisper-1\r\n")

            // language field
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
            out.writeBytes("ru\r\n")

            // file field
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wavData)
            out.writeBytes("\r\n")

            out.writeBytes("--$boundary--\r\n")
            out.flush()

            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "Whisper HTTP $code: $error")
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            val text = json.optString("text", "").trim()
            Log.i(TAG, "Whisper result: $text")
            text.ifBlank { null }

        } catch (e: Exception) {
            Log.e(TAG, "Whisper failed: ${e.message}")
            null
        }
    }
}
