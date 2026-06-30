# Master Plan — Redmi Watch 5 Lite Full App

## Phase 1: Weather on Watch
- [ ] Get location (GPS or saved city)
- [ ] Fetch weather from free API (OpenWeatherMap or wttr.in)
- [ ] Send weather to watch (type=5, sub=10)
- [ ] Periodic weather updates (every 30 min)
- [ ] Weather settings in UI (city selection)

## Phase 2: Health Data Sync
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
- [ ] Handle find phone command from watch
- [ ] Play loud sound on phone when triggered
- [ ] Find watch (send vibrate command to watch)

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
(Updated by cron — findings, best practices, API docs)

## Progress Log
(Updated by cron — what was done each iteration)
