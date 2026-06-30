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
- [ ] SpO2 data (config sub=8, allDayTracking=2)
- [ ] Stress data
- [ ] Workout/training data sync
- [ ] Store health data in Room DB
- [ ] Show sleep/SpO2/stress in UI

## Phase 3: Call Handling
- [ ] Fix contact name resolution for incoming calls
- [ ] Accept/reject call from watch
- [ ] Call state management (ringing → connected → ended)
- [ ] Show call duration on watch

## Phase 4: Find Phone
- [x] Handle find phone command from watch (system sub=18, findDevice=0)
- [x] Play default ringtone for 10 seconds
- [ ] Find watch (send vibrate command from phone)

## Phase 5: Notification Actions
- [ ] Handle dismiss notification from watch
- [ ] Handle "open on phone" action from watch
- [ ] Reply from watch (if supported)

## Phase 6: Watchfaces
- [ ] List installed watchfaces on watch
- [ ] Set active watchface
- [ ] Upload custom watchface (.bin file)
- [ ] Watchface picker UI

## Phase 7: UI Polish
- [ ] Custom app icon (not default Android)
- [ ] Home screen widget (steps, HR, battery)
- [ ] Dark/light theme support
- [ ] Onboarding wizard (first launch)
- [ ] Version bump to 1.0

## Phase 8: Stability
- [ ] ColorOS auto-start persistence
- [ ] Battery optimization handling
- [ ] Crash reporting / error handling
- [ ] Connection stability improvements
- [ ] Background service persistence tests

## Phase 9: Voice Assistant (Bonus)
- [ ] Intercept Alexa button press on watch
- [ ] Capture audio stream via SPP
- [ ] STT (speech-to-text) — Whisper or cloud API
- [ ] Send to LLM (Claude/DeepSeek/Gemini)
- [ ] TTS (text-to-speech) response
- [ ] Send audio back to watch speaker
- [ ] Or show text response on watch screen

## Phase 10: Creative Bonus Features
- [ ] Research watch hardware capabilities (sensors, speaker, mic, screen, button)
- [ ] Custom watch apps / mini-programs
- [ ] Camera remote control
- [ ] Flashlight toggle from watch
- [ ] Phone volume control from watch
- [ ] Navigation directions on watch
- [ ] Timer/stopwatch sync
- [ ] World clock
- [ ] Custom vibration patterns
- [ ] Meditation/breathing exercises with haptic feedback

## Research Notes

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
