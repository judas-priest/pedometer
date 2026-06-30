package com.pedometer.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Voice Assistant via watch microphone/speaker.
 * Uses Bluetooth SCO (HFP) to capture audio from watch mic
 * and play responses through watch speaker.
 *
 * Flow:
 * 1. Start SCO → opens audio channel to watch
 * 2. Record from watch mic (AudioRecord + SCO)
 * 3. Send to STT (speech-to-text)
 * 4. Send text to LLM (Claude/DeepSeek/Gemini)
 * 5. TTS response → play through SCO → watch speaker
 *
 * TODO: Implement STT/LLM/TTS integration
 */
class VoiceAssistant(private val context: Context) {
    companion object {
        private const val TAG = "VoiceAssistant"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false

    fun startListening() {
        if (isListening) return
        isListening = true

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Enable Bluetooth SCO — routes audio to/from watch
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.startBluetoothSco()
        audioManager?.isBluetoothScoOn = true

        Log.i(TAG, "SCO started, waiting for connection...")

        // Give SCO time to connect
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startRecording()
        }, 2000)
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
            audioRecord?.startRecording()
            Log.i(TAG, "Recording from watch mic...")

            // Record for 5 seconds
            Thread {
                val buffer = ByteArray(bufferSize)
                val allData = mutableListOf<Byte>()
                val startTime = System.currentTimeMillis()

                while (isListening && System.currentTimeMillis() - startTime < 5000) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        allData.addAll(buffer.take(read))
                    }
                }

                stopListening()
                Log.i(TAG, "Recorded ${allData.size} bytes from watch mic")

                // TODO: Send allData to STT → LLM → TTS → play back
                // For now just log
                processAudio(allData.toByteArray())
            }.start()
        } catch (e: SecurityException) {
            Log.e(TAG, "No RECORD_AUDIO permission", e)
            stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            stopListening()
        }
    }

    private fun processAudio(data: ByteArray) {
        Log.i(TAG, "Processing ${data.size} bytes of audio...")
        // TODO: STT → LLM → TTS pipeline
        // 1. Send data to Whisper API for speech-to-text
        // 2. Send text to Claude/DeepSeek API
        // 3. Convert response to speech (TTS)
        // 4. Play through SCO (watch speaker)
    }

    fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
        audioManager?.mode = AudioManager.MODE_NORMAL

        Log.i(TAG, "Stopped listening")
    }
}
