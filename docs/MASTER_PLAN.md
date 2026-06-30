# Master Plan — Redmi Watch 5 Lite Full App

## Phase 1: Weather on Watch
- [x] Fetch weather from Open-Meteo API (free, no key)
- [x] Send weather to watch (type=10, sub=0)
- [x] WMO→Xiaomi condition code mapping
- [x] Wind Beaufort scale, humidity, pressure
- [x] Auto-send after auth + respond to watch requests
- [x] Get location via GPS (FusedLocationProviderClient + Geocoder)
- [ ] Weather settings in UI (city selection override)
- [x] Periodic weather updates (every 30 min)

## Phase 2: Health Data Sync
- [x] Health config init (SPO2, HR, standing, stress, goals, vitality)
- [ ] Sleep data sync (activity fetch, parse sleep stages)
- [x] SpO2 all-day tracking enabled (config sub=9, allDayTracking=true)
- [ ] Stress data
- [ ] Workout/training data sync
- [ ] Store health data in Room DB
- [ ] Show sleep/SpO2/stress in UI

## Phase 3: Call Handling
- [x] Contact name resolution from phone number
- [x] Accept call from watch (TelecomManager.acceptRingingCall)
- [x] Reject call from watch (TelecomManager.endCall)
- [x] Calls always forwarded (no whitelist needed)
- [x] Call state management (detect call end via notification removal)
- [ ] Show call duration on watch

## Phase 4: Find Phone
- [x] Handle find phone command from watch (system sub=18, findDevice=0)
- [x] Play default ringtone for 10 seconds
- [x] Find watch (send vibrate/ring via system subtype=18, findDevice=1)
  - UtilityService.findWatch() already implemented

## Phase 5: Notification Actions
- [x] Handle dismiss notification from watch (cancel via NotificationListenerService)
- [x] MediaListenerService lifecycle bridge (onListenerConnected/Disconnected)
- [x] Handle "open on phone" — trigger contentIntent or launch app by package
- [ ] Reply from watch (if supported)

## Phase 6: Watchfaces
- [ ] List installed watchfaces on watch
- [ ] Set active watchface
- [ ] Upload custom watchface (.bin file)
- [ ] Watchface picker UI

## Phase 7: UI Polish
- [ ] Custom app icon (not default Android)
- [x] Home screen widget (Glance — steps/goal, dark theme, 2x1, 30min update)
- [ ] Dark/light theme support
- [x] Onboarding wizard (5 steps: welcome, permissions, battery, notifications, done)
- [x] Version bump to 1.0.0

## Phase 8: Stability
- [x] Error handling in weather fetch (try-catch)
- [x] Separate weather update coroutine (doesn't block init)
- [ ] ColorOS auto-start persistence guide
- [ ] Battery optimization handling (already have UI card)
- [ ] Connection stability improvements
- [ ] Background service persistence tests

## Phase 9: Voice Assistant (Bonus)
- [x] Research: feasible via Bluetooth SCO (HFP), not SPP
- [x] VoiceAssistant skeleton (SCO setup, AudioRecord, 5s capture)
- [x] Permissions: RECORD_AUDIO, MODIFY_AUDIO_SETTINGS
- [ ] STT integration (Whisper API or local)
- [ ] LLM integration (Claude/DeepSeek API)
- [ ] TTS integration (Android TTS or cloud)
- [ ] Trigger from watch (button press or notification)
- [ ] Show text response on watch screen via notification

## Phase 10: Creative Bonus Features
- [ ] Research watch hardware capabilities (sensors, speaker, mic, screen, button)
- [ ] Custom watch apps / mini-programs
- [x] Camera open from watch (PhoneActions.openCamera)
- [x] Flashlight toggle from watch (PhoneActions.toggleFlashlight, 5s auto-off)
- [x] Phone volume control from watch (PhoneActions.adjustVolume)
- [ ] Navigation directions on watch
- [ ] Timer/stopwatch sync
- [x] World clock sync (UtilityService.setWorldClocks)
- [x] Custom vibration patterns (VibrationTest proto)
- [x] Meditation/breathing exercise (4-7-8 pattern with haptic cues)

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
