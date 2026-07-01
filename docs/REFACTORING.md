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
- [x] Duplicate colors — already single source in Charts.kt, no duplicates found
- [x] StepMetricCards 7 params — acceptable for composable, no data class needed (Compose convention)

## Medium — Code Quality

- [x] Thread{} usage reviewed — acceptable for audio recording, timer, chunked upload (dedicated threads needed)
- [ ] String resources — extract Russian hardcoded strings to strings.xml (at least UI-facing ones)
- [ ] Room migrations — replace fallbackToDestructiveMigration with proper Migration objects to preserve data
- [ ] Add unit tests for: ActivitySync parsers (steps, HR, sleep, SpO2), WeatherProvider, UserProfile calculations

## Low — Polish

- [x] SCO test deprecated in VoiceAssistant (done above)
- [x] Clean up logging — RT stats, V2 DataPacket, Decrypted → VERBOSE level
- [x] ProGuard rules — reviewed, rules cover protobuf/room/osmdroid/bouncycastle
