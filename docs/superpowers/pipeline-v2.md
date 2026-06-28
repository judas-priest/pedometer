# Pedometer V2 — Development Pipeline

## Goal
Своё приложение "Здоровье" для OnePlus Ace 5 Ultra + Redmi Watch 5 Lite.
Получает данные из ВСЕХ доступных источников: датчики телефона, системные сервисы ColorOS, часы по BLE/SPP.

## Current State
- **Active Task:** AUDIT_COMPLETE
- **Next:** IMPLEMENT_PHONE_SENSORS
- **Last Action:** Аудит источников данных завершён

## Аудит OnePlus Ace 5 Ultra (PLC110, Dimensity 9400+, ColorOS 16)

### Все датчики телефона
| Handle | Название | Производитель | Тип | Permission |
|--------|----------|---------------|-----|------------|
| 0x01 | icm456xy acc | iven_sense | accelerometer(1) | — |
| 0x02 | mmc5603 mag | memsic | magnetic_field(2) | — |
| 0x03 | orientation | mtk | orientation(3) | — |
| 0x04 | icm456xy gyro | iven_sense | gyroscope(4) | — |
| 0x08 | tcs3720 ps | ams | proximity(8) | — |
| 0x09 | gravity | mtk | gravity(9) | — |
| 0x0a | linear_acc | mtk | linear_acceleration(10) | — |
| 0x0b | rot_vec | mtk | rotation_vector(11) | — |
| 0x0e | uncali_mag | mtk | magnetic_field_uncalibrated(14) | — |
| 0x0f | game_rotvec | mtk | game_rotation_vector(15) | — |
| 0x10 | uncali_gyro | mtk | gyroscope_uncalibrated(16) | — |
| **0x11** | **significant** | **mtk** | **significant_motion(17)** | — |
| **0x12** | **step_detect** | **mtk** | **step_detector(18)** | **ACTIVITY_RECOGNITION** |
| **0x13** | **step_count** | **mtk** | **step_counter(19)** | **ACTIVITY_RECOGNITION** |
| 0x14 | geo_rotvec | mtk | geomagnetic_rotation_vector(20) | — |
| 0x1b | dev_orient | mtk | device_orientation(27) | — |
| 0x23 | uncali_acc | mtk | accelerometer_uncalibrated(35) | — |
| 0x4b | pickup | oplus | pickup_detect(65611) | — |
| 0x4e | lux_aod | oplus | lux_aod(65614) | — |
| **0x4f** | **pedo_minute** | **oplus** | **pedometer_minute(33171034)** | **ACTIVITY_RECOGNITION** |
| 0x51 | elevator_detect | oplus | elevator_detect(65617) | — |
| **0x52** | **oplus_act_recog** | **oplus** | **activity_recognition(65618)** | — |
| 0x53 | stationary | oplus | station_detect(65619) | — |
| 0x54 | rotation_detect | oplus | rotation_detect(33171078) | — |
| 0x5f | tcs3720 cct | ams | rgb(33171074) | — |
| 0x66 | ai_shutter | oplus | ai_shutter(33171102) | — |
| 0xda | step_detect_wakeup | mtk | step_detector(18) wakeup | ACTIVITY_RECOGNITION |

### Здоровье-релевантные датчики
1. **step_count (0x13)** — MediaTek, TYPE_STEP_COUNTER. Total steps since reboot. 3 активных подключения от `com.oplus.aiunit.vision.x7i`
2. **step_detect (0x12)** — MediaTek, TYPE_STEP_DETECTOR. Event на каждый шаг
3. **pedo_minute (0x4f)** — OPLUS кастомный, шагомер поминутный. 2 активных подключения
4. **oplus_act_recog (0x52)** — OPLUS, распознавание активности (ходьба/бег/в машине). 4-8 подключений
5. **significant (0x11)** — Значительное движение. 3 подключения
6. **elevator_detect (0x51)** — Детектор лифта/лестницы. 4 подключения

### Источник 1: Аппаратный сенсор TYPE_STEP_COUNTER
- Handle: 0x13, MediaTek
- Permission: `ACTIVITY_RECOGNITION` (dangerous, runtime request)
- API: `SensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)`
- Возвращает: total steps since last reboot (Long)
- Foreground Service нужен для постоянного отслеживания
- **Статус: ГОТОВ К ИСПОЛЬЗОВАНИЮ**

### Источник 2: OPLUS pedo_minute (кастомный поминутный шагомер)
- Handle: 0x4f, type 33171034
- Permission: `ACTIVITY_RECOGNITION`
- Кастомный OPLUS сенсор — поминутная гранулярность шагов
- Нужно исследовать формат данных (SensorEvent.values[])
- **Статус: ДОСТУПЕН, нужно исследовать**

### Источник 3: OPLUS StepProvider (ContentProvider)
- Package: `com.oplus.healthservice`
- Authority: `com.oplus.healthservice.stepprovider`
- Provider: `StepProvider`
- Permission: `com.oplus.healthservice.permission.STEP_PROVIDER` (**prot=normal, auto-grant!**)
- AI сервис `com.oplus.aiunit.vision.x7i` читает сенсор → пишет в StepProvider
- Данные: подсчитанные шаги (история, дневные итоги — схему нужно исследовать из кода)
- **Статус: ДОСТУПЕН, permission normal = бесплатно**

### Источник 4: OPLUS Activity Recognition (кастомный)
- Handle: 0x52, type 65618
- Без permission — свободный доступ!
- Распознаёт: ходьба, бег, велосипед, машина, статика
- **Статус: ДОСТУПЕН**

### Источник 5: Health Connect API
- Package: `com.android.healthconnect.controller` (v16, SDK 36)
- API: `HealthConnectClient`, `StepsRecord`, `HeartRateRecord`
- Permissions: `READ_STEPS`, `READ_HEART_RATE` (runtime)
- Агрегирует данные из ВСЕХ приложений (Mi Fitness, OHealth)
- **Статус: ДОСТУПЕН, стандартный Android API**

### Источник 6: Часы Redmi Watch 5 Lite (BLE/SPP)
- MAC: E8:E6:09:31:23:D8, Auth key: ba2ae13b1dba45e4f53e28e98b6b5846
- Протокол: Xiaomi protobuf, SPP V2 (magic A5A5)
- Auth: РАБОТАЕТ
- Post-auth data: НЕ РАБОТАЕТ (V2 AES-CTR — часы ACK но не отвечают)
- Блокер: Mi Fitness держит exclusive BT, автозапуск
- **Статус: AUTH OK, DATA BLOCKED — нужен btsnoop/фикс шифрования**

### Источник 7: HeyTap Health (OHealth)
- Package: `com.heytap.health`
- ContentProviders: SportHealthProvider, MainSmProvider, TpSmProvider
- Закрытые API, signature permissions
- **Статус: НЕДОСТУПЕН напрямую, но пишет в Health Connect**

### Приложения на телефоне
- `com.heytap.health` — OHealth (здоровье ColorOS)
- `com.oplus.healthservice` — системный сервис шагов (StepProvider)
- `com.xiaomi.wearable` — Mi Fitness (часы)
- `com.android.healthconnect.controller` — Health Connect v16
- `nodomain.freeyourgadget.gadgetbridge.nightly` — Gadgetbridge 0.92.0 (установлен для тестов)

## Pipeline Rules

1. Читай этот файл, определяй текущую задачу
2. Выполняй по порядку: research → implement → test → commit → next
3. Гугли через Tavily при любых сомнениях
4. Обновляй этот файл после каждого действия
5. Gadgetbridge source: `/tmp/gadgetbridge/`
6. Project dir: `/home/dima/Projects/pedometer`
7. Phone: OnePlus Ace 5 Ultra, 192.168.1.213 (порт меняется)
8. unset LD_PRELOAD перед adb командами

## Task Tracker

### Task 1: Phone Step Counter (TYPE_STEP_COUNTER)
- [ ] Создать `PhoneStepCounter.kt` — SensorManager + foreground service
- [ ] Интегрировать в WatchViewModel/UI
- [ ] Показывать шаги с телефона в HealthDashboard
- [ ] Тест на устройстве
- [ ] Commit

### Task 2: OPLUS StepProvider
- [ ] Исследовать схему данных (columns, URIs, paths)
- [ ] Создать `OplusStepReader.kt` — ContentResolver query
- [ ] Получить историю шагов (дневные итоги)
- [ ] Интегрировать в UI
- [ ] Commit

### Task 3: Health Connect Integration
- [ ] Добавить Health Connect SDK в dependencies
- [ ] Создать `HealthConnectReader.kt` — read StepsRecord, HeartRateRecord
- [ ] Агрегировать данные из всех источников
- [ ] Показать объединённые данные в UI
- [ ] Commit

### Task 4: Fix Watch V2 Encryption
- [ ] Захватить btsnoop от Mi Fitness (HCI log)
- [ ] Сравнить encrypted пакеты с нашими
- [ ] Найти разницу в формате/шифровании
- [ ] Исправить ProtocolHandler
- [ ] Тест подключения с получением данных
- [ ] Commit

### Task 5: Unified Health Dashboard
- [ ] Объединить все источники в один UI
- [ ] Приоритет: часы > Health Connect > StepProvider > сенсор
- [ ] График шагов за день/неделю
- [ ] Пульс (с часов или Health Connect)
- [ ] Commit

### Task 6: Background Service
- [ ] Foreground service для постоянного подсчёта
- [ ] WorkManager для периодической синхронизации
- [ ] Notification с текущими шагами
- [ ] Auto-start при загрузке
- [ ] Commit
