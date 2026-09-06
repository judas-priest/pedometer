# Battery Audit Log

## Baseline (2026-07-18, before fixes)
- App: Шагомер
- Period: 24 hours
- **Total drain: 9.44%**
- Foreground tasks: 15 mAh
- **Background tasks: 79 mAh**
- Active time: 4 min 30 sec
- Background time: 3h 49min
- Battery mode: Smart (recommended)
- Phone battery: 63%

## Fixes Applied (2026-07-18)
1. PhoneStepCounter sensor stop/start by lifecycle (was always-on 24/7)
2. Debug notification removed from MediaListenerService (fired on every call)
3. AppName cache in MediaListenerService (was PM lookup per notification)
4. Step polling 10s → 30s (3x less ContentResolver IPC)
5. Thread.sleep → delay in POST-AUTH (13 places, was blocking IO pool)
6. DataUploadService Thread → coroutine (cancellable)
7. StepProvider logs reduced (Log.i → Log.d, removed RAW dump)
8. Per-step Log.d removed from PhoneStepCounter
9. weatherJob cancelled in cleanupServices (prevents parallel loops on reconnect)
10. Unused stepDetector sensor removed
11. Double-registration guard on sensor (listening flag)

## Expected Impact
- Main drain source was PhoneStepCounter sensor registered 24/7 in background
- Each step = process wakeup = CPU = battery
- After fix: sensor only active when app is in foreground
- Target: background drain < 20 mAh / 24h

## Next Measurement
- Wait 24h after install, take same screenshot
- Compare background mAh and total %
