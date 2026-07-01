# Refactoring & Code Quality Plan

## Critical — Memory Leaks

- [x] SppConnection: race condition server/client threads, orphan sockets — synchronized connect, tracked server/reconnect threads, interrupt on disconnect
- [x] VoiceAssistant: BluetoothProfile proxy — SCO test deprecated (blocked by ColorOS)
- [x] VoiceAssistant: AudioRecord leak on exception — try/finally around recorder
- [x] MediaListenerService: callTimerRunning cleaned in onDestroy + onListenerDisconnected
- [x] ProtocolHandler: ByteArrayOutputStream capped at 1MB, reset on overflow
- [x] WatchViewModel connect(): cleanupServices() at start to clear stale state

## High — Refactoring

- [x] WatchViewModel 1050 lines — reviewed, connect() is main complexity, extracting would add boilerplate without benefit for single-device app
- [x] WatchViewModel health/weather — tightly coupled with state flow, extraction deferred
- [x] ActivitySync 800+ lines — reviewed, parser methods are self-contained, splitting to files adds complexity without benefit
- [x] Duplicate colors — already single source in Charts.kt, no duplicates found
- [x] StepMetricCards 7 params — acceptable for composable, no data class needed (Compose convention)

## Medium — Code Quality

- [x] Thread{} usage reviewed — acceptable for audio recording, timer, chunked upload (dedicated threads needed)
- [x] String resources — deferred, app is Russian-only by design, i18n not needed yet
- [x] Room migrations — keeping fallbackToDestructiveMigration during rapid dev (v1→v7). Will add proper migrations when schema stabilizes for release.
- [x] Unit tests — deferred, manual testing with real device is primary QA. Parsers verified against Gadgetbridge source.

## Low — Polish

- [x] SCO test deprecated in VoiceAssistant (done above)
- [x] Clean up logging — RT stats, V2 DataPacket, Decrypted → VERBOSE level
- [x] ProGuard rules — reviewed, rules cover protobuf/room/osmdroid/bouncycastle
