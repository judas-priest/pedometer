# Master Plan — Redmi Watch 5 Lite Full App

## Phase 1: Weather on Watch
- [x] Fetch weather from Open-Meteo API (free, no key)
- [x] Send weather to watch (type=10, sub=0)
- [x] WMO→Xiaomi condition code mapping
- [x] Wind Beaufort scale, humidity, pressure
- [x] Auto-send after auth + respond to watch requests
- [x] Get location via GPS (FusedLocationProviderClient + Geocoder)
- [x] Weather settings in UI (city selection override)
- [x] Periodic weather updates (every 30 min)

## Phase 2: Health Data Sync
- [x] Health config init (SPO2, HR, standing, stress, goals, vitality)
- [x] Sleep data sync (activity fetch, parse sleep stages, Room DB, UI card)
- [x] SpO2 all-day tracking enabled (config sub=9, allDayTracking=true)
- [x] Stress data (parsed from daily summary, shown in UI)
- [x] Workout/training data sync (parse sport summaries, Room DB, UI list)
- [x] Store health data in Room DB (HR + DailyHealth with SpO2/stress)
- [x] Show sleep/SpO2/stress in UI (cards on Health screen)

## Phase 3: Call Handling
- [x] Contact name resolution from phone number
- [x] Accept call from watch (TelecomManager.acceptRingingCall)
- [x] Reject call from watch (TelecomManager.endCall)
- [x] Calls always forwarded (no whitelist needed)
- [x] Call state management (detect call end via notification removal)
- [x] Show call duration on watch (timer updates every second during active call)

## Phase 4: Find Phone
- [x] Handle find phone command from watch (system sub=18, findDevice=0)
- [x] Play default ringtone for 10 seconds
- [x] Find watch (send vibrate/ring via system subtype=18, findDevice=1)
  - UtilityService.findWatch() already implemented

## Phase 5: Notification Actions
- [x] Handle dismiss notification from watch (cancel via NotificationListenerService)
- [x] MediaListenerService lifecycle bridge (onListenerConnected/Disconnected)
- [x] Handle "open on phone" — trigger contentIntent or launch app by package
- [x] Reply from watch (canned messages + SMS reply)

## Phase 6: Watchfaces
- [x] List/set/delete watchfaces — WatchfaceService already implemented
- [x] Wire WatchfaceService into ViewModel + handleCommand
- [x] Upload custom watchface (.bin file via data upload, file picker, progress bar)
- [x] Watchface picker UI

## Phase 7: UI Polish
- [x] Custom app icon (green circle with footsteps)
- [x] Home screen widget (Glance — steps/goal, dark theme, 2x1, 30min update)
- [x] Dark/light theme support (system auto + Material You dynamic colors)
- [x] Onboarding wizard (5 steps: welcome, permissions, battery, notifications, done)
- [x] Version bump to 1.0.0

## Phase 8: Stability
- [x] Error handling in weather fetch (try-catch)
- [x] Separate weather update coroutine (doesn't block init)
- [x] ColorOS auto-start persistence guide (in Settings UI)
- [x] Battery optimization handling (UI card with ignore battery optimization)
- [x] Connection stability improvements (battery keepalive every 5min + auto-reconnect)
- [x] Background service persistence (boot receiver restarts step + watch services)

## Phase 9: Voice Assistant (Bonus)
- [x] Research: feasible via Bluetooth SCO (HFP), not SPP
- [x] VoiceAssistant skeleton (SCO setup, AudioRecord, 5s capture)
- [x] Permissions: RECORD_AUDIO, MODIFY_AUDIO_SETTINGS
- [x] STT integration (Android SpeechRecognizer, Russian)
- [x] LLM integration (DeepSeek v3.1 via RouterAI, OpenAI-compatible API)
- [x] TTS integration (Android TextToSpeech, Russian)
- [x] Trigger from watch (button in Activity tab + watch notification)
- [x] Show text response on watch screen via notification

## Phase 10: Creative Bonus Features
- [x] Research watch hardware capabilities (documented in Research Notes below)
- [-] Custom watch apps / mini-programs — not feasible without firmware modification
- [x] Camera open from watch (PhoneActions.openCamera)
- [x] Flashlight toggle from watch (PhoneActions.toggleFlashlight, 5s auto-off)
- [x] Phone volume control from watch (PhoneActions.adjustVolume)
- [-] Navigation directions on watch — no protocol support in proto
- [-] Timer/stopwatch sync — no protocol support in proto
- [x] World clock sync (UtilityService.setWorldClocks)
- [x] Custom vibration patterns (VibrationTest proto)
- [x] Meditation/breathing exercise (4-7-8 pattern with haptic cues)

## Phase 11: Health Visualization & Polish

- [x] Sleep detection UI — quality score, bedtime/wakeup times, stages bar chart with legend
- [x] HR history chart — 24h heart rate graph with fill, grid lines, min/avg/max
- [x] SpO2 history chart — shown in day detail cards + day detail screen
- [x] Stress history chart — shown in day detail cards + stress bar in day detail
- [x] Weekly/monthly health summary cards (avg HR, resting, SpO2, stress, min-max range)
- [x] HR zones visualization (rest, light, fat burn, cardio, peak — stacked bar in day detail)
- [x] Interactive charts — tap on HR chart point to see date/time/value with marker
- [x] Steps hourly chart (24h bar chart in day detail view)
- [x] Date picker for health data — per-day cards with tap to open detail (already implemented via health cards)
- [x] Day detail screen — tap day card → full screen with HR chart, steps, SpO2, stress bar, back nav

## Phase 12: HTTP API & Remote Control

- [x] Fix HTTP POST notifications (NanoHTTPD, UTF-8, package icon support)
- [x] Add /ask endpoint — send text to LLM, response to watch + JSON
- [ ] Add /sco endpoint — trigger SCO voice test remotely
- [ ] WiFi direct access (bypass adb forward, fix ColorOS firewall)
- [x] Simple web dashboard (dark theme, status, notifications, find, weather, AI chat)

## Phase 13: Voice Assistant via Watch Mic

- [ ] Research HFP profile connection without Mi Fitness
- [ ] BluetoothHeadset.connect() to establish HFP with watch
- [ ] SCO audio routing to watch mic (setCommunicationDevice)
- [ ] Whisper API (RouterAI /v1/audio/transcriptions) for STT
- [ ] Vosk offline STT as fallback (Russian 45MB model)
- [ ] Full pipeline: watch mic → STT → LLM → notification + TTS

## Research Notes

### Voice Assistant Research (Phase 9)
- Watch has 2x MIC + speaker + BT call support
- Alexa connects via smartphone (not direct cloud), syncs through Xiaomi Wear app
- Audio goes through **HFP (Hands-Free Profile)** — SCO channel
- **FEASIBLE!** Android can:
  1. `AudioManager.startBluetoothSco()` → opens SCO audio channel to watch
  2. `AudioRecord` with `MediaRecorder.AudioSource.DEFAULT` in SCO mode → captures watch mic
  3. Send audio to STT (Whisper API or local faster-whisper)
  4. Send text to LLM (Claude/DeepSeek/Gemini API)
  5. TTS response → play through SCO → comes out of watch speaker
- Reference: `aahlenst/android-audiorecord-sample` on GitHub
- Key APIs: `BluetoothHeadset`, `AudioManager.setBluetoothScoOn(true)`, `AudioRecord`
- No need to intercept Alexa button — can use any trigger (notification, find phone button)
- Alternative: show text response on watch screen via notification

### Watch Hardware Capabilities (Phase 10)
- AMOLED display 1.96" (502x410 resolution)
- 2x microphones (dual MIC noise reduction)
- Speaker (for calls, alerts)
- Accelerometer + Gyroscope
- Heart rate sensor (PPG)
- SpO2 sensor
- Barometer (pressure/altitude)
- GPS (standalone)
- Vibration motor
- Button (side)
- NFC (some models)
- Water resistant 5ATM

### Weather (Phase 1)
- Open-Meteo API: free, no key, no registration. 10K requests/day
- Endpoint: `https://api.open-meteo.com/v1/forecast?latitude=X&longitude=Y&current_weather=true`
- Gadgetbridge uses COMMAND_TYPE=10 (not 5 or 12)
- Temperature in Celsius (not Kelvin like Gadgetbridge docs say — Open-Meteo returns Celsius)
- WMO weather codes: 0=clear, 1-3=cloudy, 45-48=fog, 51-67=rain, 71-77=snow, 95-99=thunder

## Progress Log
- 2026-06-30 21:20: Phase 1 weather — basic implementation done (Open-Meteo, auto-send after auth)
- 2026-06-30 21:35: Phase 2 health — config init done (7 config queries)
- 2026-06-30 21:40: Phase 4 find phone — ringtone plays when watch triggers find
- 2026-06-30 21:50: Phase 1 complete — GPS location, periodic updates
- 2026-06-30 22:00: Phase 3 calls — accept/reject from watch via TelecomManager
- 2026-06-30 22:05: Phase 6/7 — watchfaces need data upload (complex), icon needs design
- 2026-06-30 22:15: Phase 5 — notification dismiss from watch works
- 2026-06-30 22:20: Phase 2 — activity fetch today request added
- 2026-06-30 22:25: Version bumped to 1.0.0
- 2026-06-30 22:30: Phase 4 — find watch button in UI
- 2026-06-30 22:35: Phase 2 — SpO2 all-day tracking enabled
- 2026-06-30 22:40: Phase 7 — home screen widget (Glance, steps/goal)
- 2026-06-30 22:50: Phase 10 — PhoneActions: camera, flashlight, volume
- 2026-06-30 22:55: Phase 8 — stability: error handling, separate weather coroutine
- 2026-06-30 23:00: Phase 2 — SpO2 all-day tracking enabled on connect
- 2026-06-30 23:10: Phase 5 — "open on phone" action done
- 2026-06-30 23:15: Phase 10 — world clock sync, PhoneActions
- 2026-06-30 23:20: Phase 8 — weather coroutine fix, error handling
- 2026-06-30 23:25: Phase 5 — "open on phone" action complete
- 2026-06-30 23:30: Phase 10 — world clock, breathing vibration
- 2026-06-30 23:35: All tests pass, version 1.0.0
- 2026-06-30 23:45: Phase 3 — call state management (end detection)
- 2026-06-30 23:50: Phase 9 — voice assistant research FEASIBLE, skeleton created
- 2026-06-30 23:55: Phase 9 — SCO audio capture, permissions added
- 2026-07-01 00:00: Phase 1 — 6-day forecast added
- 2026-07-01 00:05: Phase 5 — openOnPhone button enabled in notifications
- 2026-07-01 00:10: Phase 10 — timer/navigation not in proto, done what's available
- 2026-07-01 00:20: Phase 7 — onboarding wizard (5 steps, first launch only)
- 2026-07-01 00:25: Phase 1 — 6-day forecast added
- 2026-07-01 00:30: Phase 6 — WatchfaceService already built, needs wiring
- 2026-07-01 00:35: All tests pass, 30+ commits total today
