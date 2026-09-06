# Refactoring & Code Quality Plan

## Critical — Memory Leaks

- [x] SppConnection: race condition server/client threads, orphan sockets — synchronized connect, tracked server/reconnect threads, interrupt on disconnect
- [x] VoiceAssistant: BluetoothProfile proxy — SCO test deprecated (blocked by ColorOS)
- [x] VoiceAssistant: AudioRecord leak on exception — try/finally around recorder
- [x] MediaListenerService: callTimerRunning cleaned in onDestroy + onListenerDisconnected
- [x] ProtocolHandler: ByteArrayOutputStream capped at 1MB, reset on overflow
- [x] WatchViewModel connect(): cleanupServices() at start to clear stale state

## High — Refactoring

- [x] WatchViewModel — connection, watch services and command handling extracted to `repo/WatchRepository` (branch audit-remediation, 2026-09-06); the ViewModel now holds phone-side state only
- [x] ActivitySync 950 lines — 8 parsers self-contained, no shared state between them
- [x] Duplicate bar charts — HealthBarChart extracted to Charts.kt, WeeklyBarChartFull and HealthMiniChart removed (-18 lines net)
- [x] Duplicate colors — single source in Charts.kt
- [x] StepMetricCards — acceptable for composable

## Medium — Code Quality

- [x] Thread{} usage reviewed — acceptable for audio recording, timer, chunked upload (dedicated threads needed)
- [x] String resources — deferred, app is Russian-only by design, i18n not needed yet
- [x] Room migrations — destructive fallback removed, `MIGRATIONS` list + `missingMigrationSteps` coverage check, schema exported to `app/schemas/` (branch audit-remediation, 2026-09-06)
- [ ] Unit tests — pure logic is covered (auth, packets, reconnect, hour buckets, migration coverage, call routing, state merge); the activity/health parsers in `health/parsers/` are still untested

## Low — Polish

- [x] SCO test deprecated in VoiceAssistant (done above)
- [x] Clean up logging — RT stats, V2 DataPacket, Decrypted → VERBOSE level
- [x] ProGuard rules — reviewed, rules cover protobuf/room/osmdroid/bouncycastle
