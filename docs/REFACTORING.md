# Refactoring & Code Quality Plan

## Critical — Memory Leaks

- [x] SppConnection: race condition server/client threads, orphan sockets — synchronized connect, tracked server/reconnect threads, interrupt on disconnect
- [x] VoiceAssistant: BluetoothProfile proxy — SCO test deprecated (blocked by ColorOS)
- [x] VoiceAssistant: AudioRecord leak on exception — try/finally around recorder
- [x] MediaListenerService: callTimerRunning cleaned in onDestroy + onListenerDisconnected
- [x] ProtocolHandler: ByteArrayOutputStream capped at 1MB, reset on overflow
- [x] WatchViewModel connect(): cleanupServices() at start to clear stale state

## High — Refactoring

- [ ] WatchViewModel 1050 lines — extract ConnectionManager (connect/disconnect/reconnect)
- [ ] WatchViewModel — extract HealthRepository (activity sync callbacks, Room DB saves)
- [ ] WatchViewModel — extract WeatherManager (fetch/send/forecast)
- [ ] ActivitySync 800+ lines — split parsers into separate files (DailyDetailsParser, SleepParser, WorkoutParser, etc.)
- [ ] Duplicate colors (HeartRed, StepGreen, etc.) — defined in Charts.kt, TodayScreen.kt, DayDetailScreen.kt, HealthDayDetail.kt — single source in Charts.kt, import everywhere
- [ ] StepMetricCards 7 params — create StepMetrics data class

## Medium — Code Quality

- [ ] Replace Thread{}.start() with coroutines: VoiceAssistant, MediaListenerService call timer, DataUploadService
- [ ] String resources — extract Russian hardcoded strings to strings.xml (at least UI-facing ones)
- [ ] Room migrations — replace fallbackToDestructiveMigration with proper Migration objects to preserve data
- [ ] Add unit tests for: ActivitySync parsers (steps, HR, sleep, SpO2), WeatherProvider, UserProfile calculations

## Low — Polish

- [ ] Remove unused imports across all files
- [ ] Remove SCO test code from VoiceAssistant (dead code, blocked by ColorOS)
- [ ] Clean up logging — reduce verbose RT logs to VERBOSE level
- [ ] ProGuard rules review — ensure no over-keeping
