# Pedometer - Roadmap

> Android-приложение для прямого подключения к Redmi Watch 5 Lite (M2352W1) по BLE без Mi Fitness.

## Device Info

- **Model:** Redmi Watch 5 Lite (lchz.watch.n65bgl, M2352W1)
- **Chip:** SiFli SF32LB551, RT-Thread RTOS
- **Bluetooth:** 5.3 (BLE + Classic SPP)
- **Protocol:** Xiaomi protobuf over SPP/BLE, encrypted (AES-CCM/AES-CTR)
- **Auth key:** извлекается из логов Mi Fitness (encryptKey)
- **Reference:** Gadgetbridge (codeberg.org/Freeyourgadget/Gadgetbridge), Byteria blog

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Bluetooth:** Android Bluetooth API (SPP RFCOMM + BLE GATT)
- **Serialization:** Protobuf (google protobuf lite)
- **Crypto:** javax.crypto (AES-CCM, AES-CTR, HMAC-SHA256)
- **Storage:** Room DB
- **Min SDK:** 26 (Android 8.0)

## Protocol Reference

- **Protobuf schema:** `/tmp/gadgetbridge/app/src/main/proto/xiaomi.proto` (сохранить в проект)
- **Auth flow:** `XiaomiAuthService.java` — HMAC-SHA256 key derivation с солью "miwear-auth", AES-CCM шифрование
- **Transport V1:** `XiaomiSppProtocolV1.java` — L1 пакеты (magic 0xA5A5, type, seq, len, CRC16-CCITT, payload)
- **Transport V2:** `XiaomiSppProtocolV2.java` — AES-CTR шифрование
- **SPP UUID:** `00001101-0000-1000-8000-00805F9B34FB`

---

## Phase 1: Foundation — BLE Transport + Auth

**Goal:** Подключиться к часам по Bluetooth, пройти аутентификацию, получить device info и батарею.

**Deliverable:** Приложение с экраном "Connect" — показывает статус подключения, серийник, прошивку, заряд батареи.

**Key files to study:**
- `XiaomiSppSupport.java` — SPP connection management
- `XiaomiSppProtocolV1.java` / `V2.java` — packet framing
- `XiaomiAuthService.java` — auth handshake
- `XiaomiSystemService.java` — device info, battery

**Tasks:**
1. Scaffold Android проект (Kotlin, Compose, protobuf plugin)
2. Скопировать и адаптировать xiaomi.proto
3. Реализовать L1 packet framing (encode/decode)
4. Реализовать SPP Bluetooth connection (RFCOMM)
5. Реализовать auth handshake (nonce exchange, HMAC, AES-CCM)
6. Запросить device info + battery
7. UI: экран подключения с отображением статуса
8. Хранение auth key в SharedPreferences

---

## Phase 2: Health Data — Steps, Heart Rate, Sleep

**Goal:** Получать данные о шагах (сегодня + история), пульсе, сне, SpO2, стрессе, калориях.

**Deliverable:** Экран "Health" — текущие шаги, график пульса, история сна.

**Key files to study:**
- `XiaomiHealthService.java` — health commands
- `XiaomiActivityFileFetcher.java` — activity data sync
- `XiaomiActivityParser.java` — parsing activity files
- `DailySummaryParser.java`, `DailyDetailsParser.java`, `SleepStagesParser.java`

**Tasks:**
1. Реализовать запрос RealTimeStats (шаги, калории, пульс в реальном времени)
2. Реализовать sync activity files (история шагов, пульса)
3. Парсинг DailySummary, DailyDetails
4. Парсинг SleepStages, SleepDetails
5. Room DB для хранения истории
6. UI: dashboard с текущими шагами, графики пульса и сна

---

## Phase 3: Music Control

**Goal:** Управлять воспроизведением музыки с часов.

**Deliverable:** Часы показывают текущий трек и могут play/pause/next/prev/volume.

**Key files to study:**
- `XiaomiMusicService.java` — music info & media key handling

**Protobuf:**
```
Music.MusicInfo — state, volume, track, artist, position, duration
Music.MediaKey — key (0=play, 1=pause, 3=prev, 4=next, 5=vol), volume
```

**Tasks:**
1. Реализовать получение MediaKey команд с часов
2. Интеграция с Android MediaSession (перехват текущего трека)
3. Отправка MusicInfo на часы (трек, артист, длительность, статус)
4. NotificationListenerService для отслеживания текущего плеера

---

## Phase 4: Weather

**Goal:** Отправлять погоду на часы.

**Deliverable:** Часы показывают текущую погоду и прогноз.

**Key files to study:**
- `XiaomiWeatherService.java`

**Protobuf:**
```
Weather.WeatherCurrent — condition, temperature, humidity, wind, uv, aqi
Weather.WeatherForecast — daily entries с температурой, условиями, sunrise/sunset
```

**Tasks:**
1. Интеграция с weather API (OpenWeatherMap или аналог)
2. Конвертация данных в protobuf формат часов
3. Отправка текущей погоды + прогноза
4. Периодическое обновление (WorkManager)
5. UI: настройка локации

---

## Phase 5: Notifications

**Goal:** Пересылать уведомления с телефона на часы, обработка звонков.

**Deliverable:** Пуши с иконками приложений на часах, входящие звонки с accept/reject.

**Key files to study:**
- `XiaomiNotificationService.java`

**Protobuf:**
```
Notification.Notification3 — package, appName, title, body, isCall, repliesAllowed
Notification.CannedMessages — готовые ответы
Notification.NotificationReply — ответ с часов
```

**Tasks:**
1. NotificationListenerService для перехвата пушей
2. Конвертация уведомлений в protobuf
3. Отправка иконок приложений на часы
4. Обработка входящих звонков (CallScreeningService)
5. Обработка dismiss/reply с часов
6. UI: настройка фильтра приложений

---

## Phase 6: Utilities — Calendar, Contacts, Alarms, Find

**Goal:** Синхронизация календаря, контактов, будильников. Найти телефон/часы.

**Deliverable:** Все утилитарные функции работают.

**Key files to study:**
- `XiaomiCalendarService.java`
- `XiaomiPhonebookService.java`
- `XiaomiScheduleService.java`
- `XiaomiSystemService.java` (find device, camera)

**Tasks:**
1. Синхронизация событий календаря (ContentResolver)
2. Загрузка контактов на часы
3. Управление будильниками и напоминаниями
4. Find phone / Find watch
5. Remote camera shutter
6. World clocks

---

## Phase 7: Watchfaces

**Goal:** Установка кастомных циферблатов.

**Deliverable:** Выбор и установка watchface из файла.

**Key files to study:**
- `XiaomiWatchfaceService.java`
- `XiaomiDataUploadService.java` — chunked data upload protocol

**Protobuf:**
```
Watchface — list, install, delete, set active
DataUpload — chunked transfer with MD5
```

**Tasks:**
1. Реализовать chunked data upload protocol
2. Парсинг watchface файла (magic 5AA53412, metadata)
3. UI: выбор файла, превью, установка
4. Управление установленными циферблатами (list, delete, set active)

---

## Phase 8: Polish & Background

**Goal:** Фоновая работа, автопереподключение, стабильность.

**Tasks:**
1. Foreground Service для постоянного подключения
2. Автопереподключение при разрыве связи
3. Battery optimization (Doze, App Standby)
4. UI polish, настройки, onboarding
5. Экспорт данных (CSV, JSON)

---

## Execution Order

```
Phase 1 ──> Phase 2 ──> Phase 3 ──┐
                                    ├──> Phase 8
Phase 1 ──> Phase 4 ──> Phase 5 ──┘
                   │
                   └──> Phase 6 ──> Phase 7
```

Phase 1 обязательна первой. Phase 2-7 можно параллелить после Phase 1.
Phase 8 — после того как основные фичи работают.

---

## Key Risks

1. **Transport V1 vs V2** — Redmi Watch 5 Lite может использовать V2 (AES-CTR). Нужно определить при первом подключении.
2. **BLE vs SPP** — некоторые прошивки поддерживают только один тип. Gadgetbridge рекомендует пробовать оба.
3. **Auth key invalidation** — hard reset часов меняет MAC и ключ. Нужен re-pairing flow.
4. **Activity file format** — бинарный формат, парсинг требует точного соответствия версии прошивки.
