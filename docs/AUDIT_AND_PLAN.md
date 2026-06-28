# Pedometer App — Audit & Development Plan

## Current State (2026-06-28)
- APK builds, runs on OnePlus Ace 5 Ultra (ColorOS 16)
- Step counting works via OPLUS StepProvider (day_offset) + TYPE_STEP_COUNTER
- UI: step ring, 7-day chart, day detail, user profile, settings
- Watch connection: parked (Mi Fitness conflict, no SDK)
- Mi Fitness deleted from phone — needs reinstall from Play Store before watch work

## Priority Tasks

### P0 — Fix Known Bugs
- [x] Background service: change foregroundServiceType to "health" (was "connectedDevice")
- [x] Add FOREGROUND_SERVICE_HEALTH + REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to manifest
- [x] Request ACTIVITY_RECOGNITION runtime permission before starting service (SettingsTab switch)
- [x] Fix BootReceiver: check backgroundServiceEnabled before starting, handle MY_PACKAGE_REPLACED
- [x] Russian notification text ("Шагомер" / "Подсчёт шагов...")
- [x] Duplicate calories/distance formulas — verified: defined once in UserProfile, no code duplication

### P1 — Reliable Background Service
- [x] Foreground service with TYPE_STEP_DETECTOR for real-time step events
- [x] TYPE_STEP_COUNTER for daily totals (backup/cross-check + reboot snapshots)
- [x] Boot receiver (BOOT_COMPLETED + MY_PACKAGE_REPLACED) to restart service
- [x] Call startForeground() within 5 seconds of service creation (HEALTH type)
- [x] Battery optimization whitelisting — card в SettingsTab с кнопкой "Отключить оптимизацию"
- [x] ColorOS setup wizard — инфо-карточка с инструкцией + кнопка "Настройки приложения"

### P2 — Room DB & Hourly Data
- [x] Room DB schema: 3 tables (DailySteps, HourlySteps, StepSnapshot) — `data/` package
- [x] Room dependencies + KSP plugin added to build.gradle
- [x] Bucket TYPE_STEP_DETECTOR events by hour into hourly_steps (in StepCounterService)
- [x] Save TYPE_STEP_COUNTER snapshots for reboot delta computation
- [x] Migrate StepProvider daily data to Room — upsertDaily() в periodic refresh loop
- [x] Read hourly data in UI — HourlyStepChart в DayDetailScreen, данные из Room каждые 10с
- [x] Handle TYPE_STEP_COUNTER reset on reboot — detect via snapshot comparison, clean old snapshots

### P3 — UI Improvements
- [x] Hourly chart in day detail (0-6-12-18-24, like OHealth) — HourlyStepChart
- [x] 3-ring design: steps (green), calories (orange), distance (blue) — ActivityRings composable
- [x] Animate ring progress with animateFloatAsState (1s tween, FastOutSlowInEasing)
- [x] Week/Month tab views — FilterChip переключатель, history расширена до 30 дней
- [ ] Consider Vico library for bar charts (compose-m3) — deferred, current Canvas chart works fine
- [x] ActivityTab: weekly/monthly summaries, streaks, goal tracking (was stub)
- [x] ConnectScreen cleanup: removed unused watch-connection params (dead code)
- [x] Fix divide-by-zero guards: stepGoal=0, maxSteps=0, distanceGoal=0 edge cases
- [x] Notification icon: ic_media_play → ic_menu_directions (more fitting for pedometer)
- [x] Service notification: shows step detector count instead of session-only counter, throttled to every 50 steps
- [x] All unit tests pass (14 tests: Auth, PacketV1, PacketV2)

### P4 — Health Connect
- [x] Health connect client dependency already present (connect-client:1.1.0-alpha10)
- [x] Declare READ_STEPS / WRITE_STEPS / READ_HEART_RATE permissions
- [x] PermissionsRationaleActivity + ViewPermissionUsageActivity alias in manifest
- [x] Write collected steps to Health Connect — writeSteps() method added
- [x] Read steps via aggregate() — already working (readTodaySteps, readWeekSteps)
- [ ] Background read with WorkManager (deferred — not critical, current periodic refresh sufficient)

### P5 — Watch (Blocked)
- [ ] Reinstall Mi Fitness from Play Store
- [ ] Re-pair watch
- [ ] Try Gadgetbridge: Debug → Add test device manually → force BT Classic
- [ ] Or: capture btsnoop from Mi Fitness
- [ ] Or: study Gadgetbridge XiaomiProtobufSupport source

## Technical Reference (from research)

### Manifest permissions needed:
```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.WRITE_STEPS" />
```

### Service declaration:
```xml
<service
    android:name=".StepCounterService"
    android:foregroundServiceType="health"
    android:exported="false" />
```

### Boot receiver:
```xml
<receiver android:name=".BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

### Battery whitelist check:
```kotlin
val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
val whitelisted = pm.isIgnoringBatteryOptimizations(packageName)
```

### Key accuracy notes:
- TYPE_STEP_COUNTER: cumulative since reboot, high accuracy, low power (~2% battery/day)
- TYPE_STEP_DETECTOR: per-step events, real-time, higher power
- Phone in pocket: ~95% accuracy. Loose bag: ~88%.
- Use hardware sensor (not raw accelerometer)
- pedo_minute sensor (0x4f) on OnePlus may give per-minute data

## Cron Audit Checklist
1. Are all permissions declared in AndroidManifest.xml?
2. Does foreground service start and show notification?
3. Is step data persisting across app restarts?
4. Are there any crashes in logcat?
5. Is Room DB schema up to date?
6. Are there unused imports/dead code?
7. Is the UI responsive and matching design goals?
8. Are there any new best practices to adopt?
