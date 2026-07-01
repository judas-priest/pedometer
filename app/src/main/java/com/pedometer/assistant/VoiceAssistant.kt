package com.pedometer.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.pedometer.notification.WatchNotificationBridge
import java.util.Locale

class VoiceAssistant(private val context: Context) {
    companion object {
        private const val TAG = "VoiceAssistant"
    }

    private var audioManager: AudioManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var onResult: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("ru")
                Log.i(TAG, "TTS ready")
            }
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            sendToWatch("Распознавание речи недоступно")
            return
        }

        Log.i(TAG, "Starting speech recognition (phone mic)...")
        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        // SpeechRecognizer MUST run on main thread
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post { doStartRecognition() }
    }

    private fun doStartRecognition() {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i(TAG, "Recognized: $text")
                recognizer.destroy()

                if (text.isNullOrBlank()) {
                    sendToWatch("Не удалось распознать речь")
                } else {
                    onResult?.invoke(text)
                    sendToWatch("Думаю...")
                    // Ask LLM in background
                    Thread {
                        val response = LlmClient.ask(text)
                        sendToWatch(response)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            speak(response)
                        }
                    }.start()
                }
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Recognition error: $error")
                recognizer.destroy()
                sendToWatch("Ошибка распознавания ($error)")
            }

            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "Ready for speech")
                sendToWatch("Слушаю...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.i(TAG, "End of speech") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    private fun sendToWatch(text: String) {
        WatchNotificationBridge.sendToWatch(
            id = 99999,
            packageName = "com.pedometer.assistant",
            appName = "Ассистент",
            title = "Ответ",
            body = text,
        )
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_response")
        }
    }

    fun testSco() {
        sendToWatch("Открываю SCO...")
        Log.i(TAG, "=== SCO TEST START ===")

        Thread {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                // Step 0: Connect HFP profile to watch
                val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                val watchMac = context.getSharedPreferences("pedometer_prefs", Context.MODE_PRIVATE)
                    .getString("mac_address", "") ?: ""

                if (watchMac.isNotBlank() && btAdapter != null) {
                    try {
                        val watchDevice = btAdapter.getRemoteDevice(watchMac)
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var headsetProxy: android.bluetooth.BluetoothHeadset? = null

                        btAdapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                            @Suppress("MissingPermission")
                            override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                                headsetProxy = proxy as android.bluetooth.BluetoothHeadset
                                val connected = headsetProxy?.connectedDevices ?: emptyList()
                                Log.i(TAG, "HFP proxy connected, devices: ${connected.map { it.address }}")

                                if (watchDevice !in connected) {
                                    try {
                                        // Use hidden connect method via reflection
                                        val connectMethod = proxy.javaClass.getMethod("connect", android.bluetooth.BluetoothDevice::class.java)
                                        connectMethod.invoke(proxy, watchDevice)
                                        Log.i(TAG, "HFP connect requested for $watchMac")
                                        sendToWatch("HFP подключение...")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "HFP connect failed: ${e.message}")
                                    }
                                } else {
                                    Log.i(TAG, "Watch already connected via HFP")
                                    sendToWatch("HFP уже подключен")
                                }
                                latch.countDown()
                            }
                            override fun onServiceDisconnected(profile: Int) {}
                        }, android.bluetooth.BluetoothProfile.HEADSET)

                        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        Thread.sleep(2000) // wait for HFP to establish
                    } catch (e: Exception) {
                        Log.e(TAG, "HFP setup failed: ${e.message}")
                    }
                }

                // Step 1: Start SCO and route audio to BT device
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true

                // Force audio routing to BT SCO device
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val btDevices = am.availableCommunicationDevices.filter {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                    if (btDevices.isNotEmpty()) {
                        am.setCommunicationDevice(btDevices.first())
                        Log.i(TAG, "Set communication device to BT SCO: ${btDevices.first().productName}")
                        sendToWatch("BT SCO: ${btDevices.first().productName}")
                    } else {
                        Log.w(TAG, "No BT SCO device found")
                        sendToWatch("BT SCO не найден")
                    }
                }

                Log.i(TAG, "SCO requested, waiting 3s for connection...")
                sendToWatch("Жду SCO 3с...")
                Thread.sleep(3000)

                val scoOn = am.isBluetoothScoOn
                Log.i(TAG, "SCO connected: $scoOn")

                // Step 2: Try to record
                val sampleRate = 8000
                val bufSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                Log.i(TAG, "AudioRecord bufSize=$bufSize")

                if (bufSize <= 0) {
                    sendToWatch("AudioRecord: буфер невалидный ($bufSize)")
                    stopSco(am)
                    return@Thread
                }

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    sendToWatch("AudioRecord не инициализирован (state=${recorder.state})")
                    stopSco(am)
                    return@Thread
                }

                recorder.startRecording()
                sendToWatch("Запись 3сек... говори в часы!")
                Log.i(TAG, "Recording started, 3 seconds...")

                val allData = mutableListOf<Byte>()
                val buffer = ByteArray(bufSize)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 3000) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        for (i in 0 until read) allData.add(buffer[i])
                    }
                }

                recorder.stop()
                recorder.release()

                // Step 3: Analyze
                val totalBytes = allData.size
                val totalSamples = totalBytes / 2
                val durationMs = totalSamples * 1000 / sampleRate

                // Check if audio has actual signal (not silence)
                var maxAmplitude = 0
                var sumAmplitude = 0L
                for (i in 0 until totalBytes - 1 step 2) {
                    val sample = (allData[i].toInt() and 0xFF) or (allData[i + 1].toInt() shl 8)
                    val amplitude = kotlin.math.abs(sample.toShort().toInt())
                    if (amplitude > maxAmplitude) maxAmplitude = amplitude
                    sumAmplitude += amplitude
                }
                val avgAmplitude = if (totalSamples > 0) sumAmplitude / totalSamples else 0

                val result = "SCO=$scoOn\n${totalBytes} байт (${durationMs}мс)\nmax=$maxAmplitude avg=$avgAmplitude\n" +
                    if (maxAmplitude > 100) "ЕСТЬ СИГНАЛ! (${if (maxAmplitude > 2000) "громко" else "тихо"})" else "тишина"

                Log.i(TAG, "=== SCO TEST RESULT: $result ===")
                sendToWatch(result)

                stopSco(am)

                // Play back recorded audio through phone speaker
                if (maxAmplitude > 100) {
                    sendToWatch("Воспроизвожу через 2сек...")
                    Thread.sleep(2000)
                    Log.i(TAG, "Playing back recorded audio...")
                    try {
                        val audioData = allData.toByteArray()
                        val track = android.media.AudioTrack(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                            android.media.AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build(),
                            audioData.size,
                            android.media.AudioTrack.MODE_STATIC,
                            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                        )
                        track.write(audioData, 0, audioData.size)
                        track.play()
                        Thread.sleep(durationMs.toLong() + 500)
                        track.stop()
                        track.release()
                        Log.i(TAG, "Playback done")
                    } catch (e: Exception) {
                        Log.e(TAG, "Playback failed", e)
                    }
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "SCO test: no permission", e)
                sendToWatch("Нет разрешения на микрофон")
            } catch (e: Exception) {
                Log.e(TAG, "SCO test failed", e)
                sendToWatch("SCO ошибка: ${e.message}")
            }
        }.start()
    }

    private fun stopSco(am: AudioManager) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        }
        am.stopBluetoothSco()
        am.isBluetoothScoOn = false
        am.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "SCO stopped")
    }

    fun destroy() {
        tts?.shutdown()
    }
}
