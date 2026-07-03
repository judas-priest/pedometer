# Pedometer

Android companion app for **Redmi Watch 5 Lite** (M2352W1) — a complete replacement for Mi Fitness.

Direct SPP (Bluetooth Classic) connection to the watch using reverse-engineered Xiaomi protobuf protocol. No cloud, no Mi Fitness, no data collection.

## Features

- **Steps** — real-time step count from phone sensors + watch, walk/run breakdown, daily goal ring
- **Heart Rate** — live HR from watch, today's chart, weekly trend
- **SpO2 & Stress** — blood oxygen and stress levels from watch
- **Sleep** — sleep stages (deep/light/REM/awake), duration, quality score
- **Weather** — current + 6-day forecast sent to watch (Open-Meteo API)
- **Notifications** — forward phone notifications to watch, per-app whitelist
- **Music Control** — control phone music player from watch
- **Phone Calls** — incoming call with contact name on watch, accept/reject from wrist
- **Find Phone / Find Watch** — ring either device
- **Alarms** — create, edit, delete watch alarms
- **Watchfaces** — list, switch, delete, upload custom watchfaces
- **Workouts** — GPS relay for watch workouts, route on map (OSMDroid)
- **Calendar & Contacts** — sync to watch
- **Activity History** — 7/30 day charts, daily detail with hourly breakdown

## Screenshots

*Coming soon*

## Tech Stack

- Kotlin, Jetpack Compose, Material 3
- Protobuf lite (Xiaomi protocol)
- Room DB for local storage
- SPP (RFCOMM) Bluetooth Classic connection
- AES-CCM auth + AES-CTR post-auth encryption
- OSMDroid for workout maps
- FusedLocationProvider for GPS relay

## Device Support

- **Watch:** Redmi Watch 5 Lite (M2352W1, SiFli SF32LB551)
- **Phone:** Any Android 8.0+ (tested on OnePlus Ace 5 Ultra, ColorOS 16)

## Setup

1. Pair watch with Mi Fitness first (needed to extract auth key)
2. Extract auth key: Mi Fitness > Profile > About > tap logo 10x > logs
3. Search for `encryptKey` in `XiaomiFit.main.log`
4. Enter MAC address and auth key in app settings
5. Force stop Mi Fitness before connecting

## Build

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Requires: JDK 17+, Android SDK

## Battery

- No background service without watch connection
- GPS only during active workout
- Weather updates every 2 hours (with 30-min cache)
- Step polling every 10s only when app is visible
- SPP idle uses Bluetooth sniff mode automatically

## Credits

Protocol reverse-engineered with help from [Gadgetbridge](https://gadgetbridge.org/) source code.

## License

MIT
