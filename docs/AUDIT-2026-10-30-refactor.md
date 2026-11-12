# Refactor Audit: pedometer (master 9bf62fb, ~14.6k LOC)

Дата: 2026-10-30. Пайплайн: orient → 7 module-аудиторов + архитектор + tooling-runner (9 параллельно) → самостоятельная anchor-верификация top-находок → приоритизация.
Статус: отчёт по master; капсульная ветка `worktree-remove-workout-capsule` (7 коммитов) уже убила часть находок — помечено.

## Архитектурная модель (5 строк)

1. Entry: MainActivity (Compose, ручная boolean-навигация) + WatchConnectionService (FGS) + ресиверы (CommandReceiver, PhoneCallReceiver, StepCollector, MediaListenerService).
2. Транспорт: `bt/` SPP+WatchLink (epoch/watchdog/reconnect) → `ProtocolHandler` (V1/V2, auth) → `WatchRepository` — синглтон-хаб 1170 LOC: policy (wifi-gate/quiet/presence/manual), workout, 11 hand-built сервисов, персистентность в Room.
3. Состояние: `WatchData` StateFlow → `WatchViewModel` (847 LOC, 10+ ответственностей) → `WatchState` (56 полей) → 4 таба.
4. Здоровье: `health/ActivitySync` (697 LOC — транспорт+парсеры+DTO) → Room; `ElevationAnalytics` (GAP-конвейер) — ядро чистое и протестировано (17 тестов), но сам файл тянет `android.util.Log`, `org.json`, `HttpURLConnection` — «чистая» верно только для math-ядра.
5. Циклы: repo↔vm (`WatchData.kt:6` импортирует `vm.WatchState` ↔ VM:31 `import repo.withWatchData`), repo↔service (`WatchRepository.kt:28` импортирует `service.WorkoutStatusUi` ↔ `BootReceiver.kt:7` + FQ-`repo.WatchData` в Service:211) — «repo — нижний слой» неверно. Замечание v2: сам WatchRepository из vm НИЧЕГО не импортирует — цикл идёт через файл WatchData.kt, не через repo.

## Stacks

Kotlin 2.0.21 / AGP 8.9.1 / Gradle 8.11.1 / compileSdk 36, target 35, min 26 / Compose BOM **2024.11.00 (~2 года позади)** / Room 2.6.1 / health-connect **1.1.0-alpha10** / protobuf 4.28.3 (ok) / coroutines unpinned. Tooling: **detekt/ktlint/spotless отсутствуют**; lint = единственный гейт (12 errors, 63 warnings); 19 тест-файлов — вся чистая логика, **0 тестов** на repo/vm/Service (три churn-топа).

Churn-топ (6 мес): WatchViewModel (86), WatchRepository (37), MainActivity (36), SettingsTab (33; ConnectScreen.kt не существует — врос в SettingsTab), ElevationAnalytics/ActivitySync (по 22).

## Верификация (самостоятельная, file:line)

| Находка | Вердикт |
|---|---|
| `telecom.endCall()` без API-гварда (WatchRepository.kt:741), `catch (Exception)` не ловит NoSuchMethodError → краш на API 26/27 | **CONFIRMED** |
| Auth step3 FAILED → всё равно `onAuthenticated()` (ProtocolHandler.kt:210-219) | **CONFIRMED** |
| Write-failure → тихая смерть: `disconnect()` ставит `running=false` до закрытия сокета → readLoop finally видит `wasRunning=false` → ни `onDisconnected`, ни reconnect; статус «Connected» навсегда (SppConnection.kt:167-188, 203-213) | **CONFIRMED** |
| `SppConnection.write` без синхронизации — 4+ потоков-писателей | **CONFIRMED** |
| HMAC-mismatch → `return` → висим до 30s watchdog (ProtocolHandler.kt:196-199) | **CONFIRMED** |
| Циклы repo↔vm, repo↔service | **CONFIRMED** (:6 WatchData.kt; :28 WatchRepository.kt) |
| 14/15 мутаций StateFlow неатомарны, `bumpRoom` теряет инкременты (:390-392) | **CONFIRMED** |
| O(n²) fold сборки чанков (ActivitySync.kt:219) | **PARTIAL** — fold в `:219` выполняется ОДИН раз на файл (внутри `chunkNum == totalChunks`), не на каждый чанк. Реально O(n·k) цепочка копий `acc + chunk` — неэффективно, но не квадратично; накопление `:208-215` — обычный список |
| HrCodec: `readInt` catch-break = ложный EOF; `inflate` бросает незахваченный DataFormatException | **PARTIAL** — `readInt` НЕ даёт ложный EOF: `readInt` бросает только на истинном конце потока, catch-break = штатный терминатор. Реальный баг рядом: `HrCodec.kt:45` `readByte()` бросает незахваченный EOF при длине ≡ 4 (mod 5); `inflate` (`:37`) — CONFIRMED, плюс усечённый deflate → `finished()` никогда true → бесконечный цикл |
| `release.keystore` в git | **CONFIRMED** (git ls-files) |
| FINE без COARSE (manifest:10) | **CONFIRMED** |
| DataUploadService: unchecked chunkSize/resume от watch → ArithmeticException/IndexOutOfBounds | PARTIAL (краш-класс реален, рантайм не воспроизводился) |

REFUTED/уточнено:
- «2500 LOC мёртвых parked-модулей» — weather/music/watchface/widget/debug **живые** (reachability прослежен); мёртв только точечный код (см. QW#8).
- «ConnectScreen.kt 343 LOC» — файл не существует.
- Находки про `repo.startStepPoll`/`daySteps`/capsule — **уже выпилены на ветке remove-workout-capsule**.

## Backlog

### Quick wins (S, делать сразу)

| # | Что | Где |
|---|---|---|
| 1 | Все `_data.value = _data.value.copy(...)` → `_data.update { }` (14 сайтов) — закрывает класс гонок | WatchRepository.kt (390, 541, 549, 717, 720, 727, 1129, 1140…) |
| 2 | `onWorkoutVibrate = null` в service.onDestroy + `@Volatile` на `WatchNotificationBridge.protocolHandler/notificationListener` | WatchConnectionService.kt:329; WatchNotificationBridge.kt:15-16 |
| 3 | API-гвард `endCall()` (`if (SDK_INT >= 28)`) — единственный lint-Critical краш; там же `acceptRingingCall()` (`:737`) — тот же класс бага, гвардить оба | WatchRepository.kt:737,741 |
| 4 | Auth step3: статус != 1 → fail-fast, не `onAuthenticated()` (проверить на устройстве — коммент про «другие firmware» не подтверждён) | ProtocolHandler.kt:219-221 |
| 5 | `synchronized` на `SppConnection.write` | SppConnection.kt:167 |
| 6 | `ACCESS_COARSE_LOCATION` в манифест (сейчас только FINE). Доп. из v2: `BLUETOOTH_SCAN` (`:8`) объявлен БЕЗ `neverForLocation` → скан-результаты фильтруются по локации и FINE несёт двойную нагрузку | AndroidManifest.xml:8,10 |
| 7 | Перенос `withWatchData` в vm/ и `WorkoutStatusUi` в util/ — убивает **только** цикл repo↔service (repo:28 → WorkoutStatusUi — единственное ребро repo→service). Цикл repo↔vm это НЕ убивает: `WatchData.kt:6` импортирует `vm.WatchState`, и vm→repo остаётся в любом случае — нужно переносить сам `WatchState`/`WatchData`, а не только extension | WatchData.kt:6,48; WatchRepository.kt:28 |
| 8 | Смертный код одним коммитом: `SppConnection.autoReconnect`-блок (~35 строк; флаг нигде не ставится true — `attemptReconnect` недостижим), `PacketV2.opCodeForChannelPlaintext`, `PacketV1.dataTypeForChannel`, `ChannelHandler`, `UtilityService` 4 метода (sendCalendarEvent/sendContacts/setWorldClocks/sendBreathingVibration; сам класс живой), `PhoneActions` целиком, dead DAO (`getDaily`, `getSleepForDate`, `getLastHeartRate`, `cleanOldHeartRate`, все 3 метода `step_snapshots`; сама таблица требует миграции), `CommandReceiver` FIND/REFRESH (+ манифест intent-filter; ветки — no-op с логом, но приёмник exported и жив), `NotificationService.sendNotification`, `repo.getReminders/isAtHome`, `HealthConnectReader.writeSteps/readWeekSteps`, `StepProviderReader.probeAllPaths`, DEBUG-дамп ActivitySync:396-426 (уточнение v2: гейт `BuildConfig.DEBUG` — жив в debug-сборках намеренно, до верификации HR на реальной тренировке; резать только из release-осознанно) | по файлам |
| 9 | finally-disconnect в обоих fetch (сейчас `conn.disconnect()` только на success-пути, без `use{}`); readTimeout добавить только в `fetchForecast` — в `fetch()` readTimeout=10000 уже есть (уточнение v2) | WeatherProvider.kt:126-128,148-155 |
| 10 | Clamp `chunkSize∈[256,64512]`, `resume∈0..size`, cancel перед новым upload | watchface/DataUploadService.kt:84-113 |
| 11 | HrCodec: типизированная ошибка + log+skip вместо молчащего truncate/crash | HrCodec.kt:37,44 |
| 12 | `git rm --cached release.keystore` + правило в .gitignore (сейчас `*.jks` есть, `*.keystore` — нет; пароли в keystore.properties игнорируются, сам файл закоммичен) | repo root |
| 13 | `PrefsKeys` object + консолидация prefs (уточнено v2: **4 файла**, не 3 — `pedometer_prefs`, `user_profile`, `notification_whitelist`, `app_prefs`; 7 файлов трогают prefs). Риски дублирования подтверждены: KEY_AUTH/KEY_MAC — приватные копии констант в repo:63-64 и vm:139-140; SettingsTab:293-297 дублирует литералами quiet/wifi/workout-ключи из repo:65-69. Бонус-хазард не из аудита: `SupplementEditorScreen.kt:36-38` читает `prefs.all.filterValues { it is Int }` — любое Int в файле считается окном добавки | repo:62-69, vm:138-140, SettingsTab:292-297, MainActivity:62, SupplementEditorScreen:36 |
| 14 | ktlint/detekt гейт — сейчас единственный барьер это lint | build.gradle.kts |

### Strategic (M/L, порядок зависимостей)

1. **Characterization-тесты на `applyConnectionPolicy` + workout-машину** (repo:315-347, 465-513, 600-610) — prerequisite всего. Уточнение v2: в текущем виде НЕ юнит-тестированы напрямую — `applyConnectionPolicy` тянет `context.getSharedPreferences` (:317), `link.disconnect()/connect()` (:335-339), `healthService`-сайд-эффекты (:332-334), а `LocalTime.now()` захардкожен в 3 местах (:188, :235, :316; Clock в файле отсутствует). workout-lambda (`:465-513`) смешивает `WorkoutStatusUi.resolve` с `WatchNotificationBridge.sendToWatch`/`scope.launch`. Значит: сначала минимальная экстракция (чистый policy + injected clock, send-коллбеки в параметры), потом characterization. Уже чистое и нетестированное рядом: `WorkoutStatusUi.resolve` (service/, 0 Android-зависимостей, теста нет) — с него можно начать. История: 5+ регрессий-фиксов в этих строках (2d6551a, eff0c6b, 4546a3c…).
2. **Split WatchRepository (L)** — после (1): `ConnectionPolicy` (чистая, injected clock) → `WorkoutTracker` → `HealthPersister` (7 доменных persist-блоков из buildServices:434-749) → wiring-классы. repo остаётся композишн-рут + StateFlow.
3. **LinkSession facade (M)** — после фикса write-детекта: 12 сервисов получают guarded send вместо прямых `protocolHandler` + глобального bridge (сейчас silent no-op при write-failure).
4. **ActivitySync split (L)** — parsers → `parsers/`, DTO → model, `FileAssembler` с gap-детектом (сейчас: dropped BLE chunk = тихо битый файл). Characterization: `DailyDetailsParser`, `SleepParser`, `handleRawData` — ноль тестов.
5. **WatchViewModel split (L)** — 10 ответственностей → use-cases (`HealthAnalytics`, `Supplements`, `CalendarRepository`, GPS-relay → в Service). Внутри: `loadDayInsights` O(walks×minutes×HR-window) → сортировка+бинпоиск (S-M, отдельно); alcohol-heuristic 60-дней rescan каждый refresh → кэш по дню (M).
6. **WatchState 56 полей → пер-экранные StateFlows (L)** — вместе с (5); сейчас каждый copy инвалидирует все 4 таба.
7. **StepsAggregator (M/L)** — 4 источника «шагов дня» в 3 модулях (StepProviderReader, minute_steps, PhoneStepCounter, watch hourly); капсульная ветка уже убила один дубликат-poll.
8. **Координированный bump: Kotlin+KSP+AGP+Gradle+Room+Compose BOM (L)** — Compose на 2024.11, health-connect на alpha.
9. **Charts split (M)** — 9 чартов+dialog в одном файле; `TimeSeriesChart` поглощает 3 копии line-chart; `pointerInput(data.size)` stale-closure риск (Charts.kt:90); MapView без onDetach (WorkoutMap.kt:108-189).
10. **Навигация: `sealed Screen` вместо 8-9 boolean-флагов (M)** — MainActivity:53-60; DayDetail рендерится внутри pager вопреки выработанному паттерну (MainActivity:181-189 — конфигурация того самого stale-lambda бага).

### Decline (осознанно отклонено)

- AES-CTR key-as-IV — унаследовано от протокола Mi Fitness, «фикс» разорвёт хендшейк. Только коммент-предупреждение (AuthService.kt:58).
- StaticFieldLeak lint ×4 — process-lifetime singleton, benign.
- QUERY_ALL_PACKAGES, battery-optimization, MAC в сорсах — personal sideload, не Play.
- V1 encrypted-RX path — уточнение v2: это НЕ мёртвый код, а живая fallback-ветка negotiation (`ProtocolHandler.kt:159-168` — при ответе `payload[0] < 2` идёт `startAuth()` через V1-путь). На этом устройстве часы всегда отвечают ≥ 2, но удаление ветки = падение на старой прошивке. Оставить как fallback или удалить — needs device-verify.
- `LocalTime.now()` в policy — уйдёт при injected clock в Strategic-1.
- Цветовые литералы, `Unit?` ParseResult, Dp-параметры StepRing, версия из BuildConfig — косметика, поймает ktlint-сweep.

## Верификация v2 (5 субагентов по коду, 2026-10-30)

Прогнали все находки второй волной субагентов. Итог по группам:

**Подтверждено полностью:** endCall-краш, 14/15 неатомарных StateFlow (ровно 14 copy + 1 `update{` на `:436`), auth step3 (единственный гвард — `WatchLink.kt:145` по state, статус не проверяется), HMAC-mismatch → 30s watchdog → `abortAttempt` → реконнект, write без синхронизации (субагент насчитал **6** писательских потоков: spp-read ACK, connectJob IO, main/receiver, NotificationListener, DataUploadService-coroutine, сервисы), zombie-link, DataUploadService, manifest, release.keystore (`.gitignore` покрывает `*.jks`, но не `*.keystore`), VM 847 LOC + alcohol rescan без кэша, 7-8 boolean-флагов навигации + DayDetail в pager, Charts-монолит (3 копии: HrChart:135, WeightChart:193, ElevationChart:261; stale `pointerInput(data.size)`:90), WorkoutMap без onDetach/onRelease, tooling/версии (version catalog отсутствует вовсе), 19 тест-файлов / 0 на repo+vm+Service, R8 strip (Log.w/e сохраняются), QW#2 (leak `onWorkoutVibrate` + non-volatile bridge).

**Скорректировано (в текст выше внесены правки):**
1. HrCodec `readInt` — не баг; реальный баг — `readByte` при len≡4 (mod 5) + бесконечный цикл на усечённом deflate.
2. O(n²) fold — на деле O(n·k), fold один раз на файл.
3. Циклы: оба ребра repo↔vm/repo↔service существуют, но cited import-строки аудита неверны; QW#7 убивает только repo↔service.
4. WeatherProvider: `fetch()` уже имеет readTimeout; реальная проблема — `finally`-disconnect в обоих.
5. Prefs: 4 файла, не 3; + хазард `SupplementEditorScreen` (все Int в файле = окна добавок).
6. Strategic-1: `applyConnectionPolicy` сейчас не characterization-руем напрямую — сначала минимальная экстракция (чистый policy + injected clock), потом тесты.
7. V1 encrypted-RX — живой negotiation-fallback, не мёртвый код.
8. ElevationAnalytics — «чистая» только math-ядро; файл тянет Log/org.json/HttpURLConnection.

**Уточнение по zombie-link (важно для фикса):** порядок `running=false` → `socket.close()` в `disconnect()` — осознанный (коммент `WatchLink.kt:244-251`: user-initiated disconnect не должен стрелять callback). Фикс QW#5/транспорт должен различать user-disconnect и I/O-failure (например, флаг `closingByUser`), а не просто двигать строки.

**Уточнение по bumpRoom:** окно гонки узкое, но реальное — 5 вызовов в `scope.launch(IO)` (repo:561,577,627,668,705) + `onWorkoutPersist` (:599) на BT-потоке; StateFlow конфлейт равных значений = потерянная нотификация Room-ревизии, UI может пропустить перечитывание.

**Уточнение по DataUploadService (хуже, чем в аудите):** не только краш. `chunkSize < 4` → отрицательный `partSize` → `totalParts ≤ 0` → цикл (`:118`) делает 0 итераций → `:138` репортит `finish(true)` — **ложный успех** аплоада без единого байта.

## Per-module health

| Модуль | LOC | Churn | Findings (C/H/M/L) | Debt theme |
|---|---|---|---|---|
| repo/+service/+notification | 2182 | #2,#10 | 0/5/7/4 | god-hub: policy+workout+wiring+persist в одном |
| vm/+entry | ~1100 | #1 | 0/2/6/5 | god-VM, reverse-зависимость repo→vm |
| bt/+auth/+proto | 1666 | — | 0/3/5/6 | тихие отказы транспорта; WatchLink на пределе сложности |
| health | 2841 | #7,#8 | 0/2/8/5 | god-ActivitySync, парсеры без тестов |
| ui (screens) | ~3400 | #4,#5 | 0/3/7/6 | god-компосаблы, UI → prefs/DAO напрямую |
| ui components | ~750 | — | 0/1/5/5 | Charts-монолит, 3 копии line-chart |
| data+long-tail | ~2600 | #9 | 1/3/8/4 | dead DAO/таблица, retention нет, corrupt speed в экспорте |
| Архитектура | — | — | 2C/5H/4M/1L | циклы repo↔vm↔service, 6 прямых touch-ей хаба |
| Tooling | — | — | 1C/3H/2M/5L | нет статанализа; Compose BOM −2 года |

## Residual / UNVERIFIABLE

- Тред-аффинити BLE-колбэков (`ActivitySync.pendingChunks` синхронизация) — выведено из runBlocking-коммента, не проверено рантаймом.
- Auth step3 «firmware возвращает не-1» — только коммент в коде; решается одним наблюдением на устройстве (QW#4).
- Phonebook subtype 0 vs 7 — нужен Gadgetbridge-сорс для вердикта.
- Frame-interleaving от параллельных write — улик в логах не найдено; фикс синхронизацией стоит 1 строку.
- R8-вырезание debug/ в release — выведено из BuildConfig-гейта, не проверено по mapping.
- Minute_steps/gps_points row counts на устройстве — structural finding, не измерено.

**Итог:** 2 чётких crash-класса (endCall/acceptRingingCall API-гвард, DataUploadService clamp), 1 класс «ложный успех» (DataUploadService chunkSize<4 → finish(true); auth step3 FAILED → onAuthenticated) — всё Quick wins на S-усилие. Главная стратегическая развилка — characterization-тесты на policy/workout (сначала минимальная экстракция чистого policy + injected clock — см. правку Strategic-1), без которых WatchRepository не распилен безопасно.
