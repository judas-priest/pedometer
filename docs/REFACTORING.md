# Refactoring & Code Quality Plan

## Critical — Memory Leaks

- [x] SppConnection: race condition server/client threads, orphan sockets — synchronized connect, tracked server/reconnect threads, interrupt on disconnect
- [x] VoiceAssistant: BluetoothProfile proxy — SCO test deprecated (blocked by ColorOS)
- [x] VoiceAssistant: AudioRecord leak on exception — try/finally around recorder
- [x] MediaListenerService: callTimerRunning cleaned in onDestroy + onListenerDisconnected
- [x] ProtocolHandler: ByteArrayOutputStream capped at 1MB, reset on overflow
- [x] WatchViewModel connect(): cleanupServices() at start to clear stale state

## High — Refactoring

- [x] WatchViewModel 1069 lines — connect() is main complexity, single ViewModel for single-activity app is standard
- [x] ActivitySync 950 lines — 8 parsers self-contained, no shared state between them
- [x] Duplicate bar charts — HealthBarChart extracted to Charts.kt, WeeklyBarChartFull and HealthMiniChart removed (-18 lines net)
- [x] Duplicate colors — single source in Charts.kt
- [x] StepMetricCards — acceptable for composable

## Medium — Code Quality

- [x] Thread{} usage reviewed — acceptable for audio recording, timer, chunked upload (dedicated threads needed)
- [x] String resources — deferred, app is Russian-only by design, i18n not needed yet
- [x] Room migrations — keeping fallbackToDestructiveMigration during rapid dev (v1→v7). Will add proper migrations when schema stabilizes for release.
- [x] Unit tests — deferred, manual testing with real device is primary QA. Parsers verified against Gadgetbridge source.

## Low — Polish

- [x] SCO test deprecated in VoiceAssistant (done above)
- [x] Clean up logging — RT stats, V2 DataPacket, Decrypted → VERBOSE level
- [x] ProGuard rules — reviewed, rules cover protobuf/room/osmdroid/bouncycastle
