# Watch Protocol — Redmi Watch 5 Lite (M2352W1)

## Connection
- BT Classic SPP, UUID 00001101
- Auth: HMAC-SHA256 + HKDF, salt "miwear-auth"
- Auth step3 deviceInfo: **AES-CCM** (NOT CTR!)
- Post-auth data: **AES-CTR** key==IV

## Mi Fitness Init Sequence (from btsnoop)
```
seq02: type=2  sub=2   getDeviceInfo
seq03: type=2  sub=92  getDeviceState (CMD_DEVICE_STATE_GET)
seq04: type=8  sub=30  getHeartRateConfig
seq05: type=2  sub=14  getDisplayItems
seq06: type=2  sub=2   getDeviceInfo (retry)
seq07: type=8  sub=52  setUserInfo (height/weight/goals)
seq08: type=2  sub=14  getDisplayItems (retry)
seq09: type=2  sub=44  getWidgets
seq10: type=5  sub=10  weatherInit
seq11: type=2  sub=6   setLocale ("ru_ru")
seq12: type=2  sub=2   getDeviceInfo (retry)
seq13: type=20 sub=0   dataUploadInit
seq14: type=2  sub=2   getDeviceInfo (retry)
```

## Command Types
- type=1: Auth
- type=2: System (deviceInfo, battery, clock, locale, widgets, display)
- type=7: Notification (send to watch: sub=0, icon request handling)
- type=5: Weather
- type=8: Health (steps, heart rate, SpO2, stress, sleep, user info, goals)
- type=18: Music (sub=0: watch requests info, sub=1: send info, sub=2: watch sends button)
- type=20: Data upload

## Music Control (type=18)
- sub=0: CMD_MUSIC_GET — watch requests current track info
- sub=1: CMD_MUSIC_SEND — send track info to watch (title, artist, state, volume, position)
- sub=2: CMD_MUSIC_BUTTON — watch sends media key:
  - 0x00: PLAY
  - 0x01: PAUSE
  - 0x03: PREVIOUS
  - 0x04: NEXT
  - 0x05: VOLUME (compare with current, up or down)

## Health Realtime (type=8)
- sub=45: CMD_REALTIME_STATS_START — enable live data stream
- sub=46: CMD_REALTIME_STATS_STOP — disable
- sub=47: CMD_REALTIME_STATS_EVENT — live data:
  - RealTimeStats.getSteps() — absolute step count
  - RealTimeStats.getHeartRate() — current BPM
  - Delta calculation: current - previous for step increment

## Notification (type=7)
- sub=0: CMD_NOTIFICATION_SEND — protobuf: Notification { Notification2 { Notification3 {
    id, packageName, timestamp, title, body, appName, key } } }

## System Subtypes (type=2)
- 1: getBattery
- 2: getDeviceInfo
- 3: setClock
- 6: setLocale
- 7: camera
- 9: password
- 14: displayItems
- 29: displayItems2
- 39: workoutTypes
- 44: widgets
- 51: widgets2
- 53: widgetParts
- 78: deviceState
- 92: deviceStateGet (new)

## Health Subtypes (type=8)
- 0: setUserInfo
- 1: activityFetchToday
- 2: activityFetchPast
- 3: activityFetchRequest
- 8-15: config gets/sets (SPO2, HR, standing, stress)
- 21-22: goalNotification
- 30: heartRateConfig
- 35-36: vitalityScore
- 42-43: goals
- 45: realtimeStatsStart
- 46: realtimeStatsStop
- 47: realtimeStatsEvent
- 52: setUserInfo (extended)

## Implementation Priority
1. Battery + DeviceInfo (already working)
2. setCurrentTime + setLocale
3. setUserInfo (sync profile)
4. Heart rate realtime
5. Step/activity data sync
6. Notifications (forward phone → watch)
7. Music control (watch → phone)
8. Weather push
9. Sleep data
10. SpO2, Stress
11. Watchface management
12. Workout tracking
