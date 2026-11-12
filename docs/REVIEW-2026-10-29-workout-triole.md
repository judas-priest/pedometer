# Deep Review: ffee0c5..9bf62fb (22 коммита — статус тренировки + капсула + триоль)

Дата: 2026-10-29. Пайплайн: 6 пар ревьюеров (Round 1) → 3 adversarial-верификатора (Round 2) → anchor re-audit.
Требование (из докстринга WorkoutStatusUiTest): «{имя тренировки} обычно; {имя} · Пауза при паузе; {имя} · нет связи при обрыве; на финише — дефолтный статус линка; триоль на старте и финише; капелька = дневные шаги к цели; выключатель возвращает старое поведение».

## Verdict: **DO NOT SHIP**

Один подтверждённый блокер: «Подключить» вручную теперь рвётся через секунды после auth, если ты дома или в тихих часах.

## Blockers (Critical/High)

**B1. Ручной коннект умирает сразу после auth дома/в тихие часы** — `WatchRepository.kt:431` [CONFIRMED]

`onAuthenticated()` вызывает `applyConnectionPolicy("authenticated", link.status.value)`; статус к этому моменту уже `Connected` (WatchLink.kt:147 → invoke :156). Suppress-ветка (`WatchRepository.kt:335`) делает `link.disconnect()` и **не проверяет `manualOverride`** — он читается только в `connect()`:238. Дома (gate default on) или в тихие часы (default 00–07) это детерминированный разрыв: тап «Подключить» → auth ok → disconnect → полный teardown `onLinkDropped`. Противоречит трём докам того же файла (`:108-109`, `:230-231`, `:249`). Введено коммитом eff0c6b (fix orphaned reconnect — сама дыра реальная: `WatchLink.scheduleReconnect` коннектит в обход policy), но collateral не оплачен.

Фикс: в suppress-ветке (или перед вызовом на :431) honoring `manualOverride`.

## Findings (Medium/Low)

1. **`WatchRepository.kt:282`** [CONFIRMED, Medium] — `onHomeWifiChanged(false)` сбрасывает `userDisconnected` на **любом** wifi-off, включая стартовый отчёт из `onCreate` сервиса (:319-323). Ребут роутера / рестарт сервиса с выключенным Wi-Fi стирает явное «Отключить» → автоконнект перезаписывает. Дока `:105-106` («presence monitor must not override the user») теперь врёт. Скоуп перехода шире комментария :280-281 («ушёл из дома»).
2. **`TriolePlayer.kt:30`** [CONFIRMED, Medium] — ветка FAILED и весь диагностический лог на `Log.d` (:21, :28, :30), а proguard-rules.pro:22-27 режет d/i/v в release, которым ставится APK (`installRelease`). Докоммент файла :10-12 обещает отличить «не вызвано» от «съедено» — в release это невозможно. Фикс: `Log.w`/`Log.e` на FAILED (w/e не режутся).
3. **`WatchRepository.kt:791`** [CONFIRMED, Medium/Low] — `else if (watchWorkoutActive)` не рестартует poll после обрыва (`watchWorkoutActive=false`, но `workoutUi != null` держится на :399). Тумблер off→on во время обрыва = капсула замерает. Фикс: `(watchWorkoutActive || workoutUi != null)` — цикл poll'а (:761) по `workoutUi != null` прошёл бы.
4. **`WatchRepository.kt:605`** [PARTIAL, Low] — guard `w.endTime >= liveWorkoutStart` деградирует в «пропустить всё» при `liveWorkoutStart == 0` (виден только pause-статус без 0/1 — зависит от firmware, надо проверять на устройстве). Подпункт «stale start после прошлого финиша» убит: каждый наблюдённый старт (0/1) обновляет :467-469.
5. **Orphaned workoutUi без TTL** [CONFIRMED, Low] — очистка только через живой status-3 или синк summary (:605-610); дома policy режет реконнект → «Ходьба · нет связи» + poll каждые 30 с висят до следующей прогулки (~до 20 ч по режиму). Частично by design (:120), но expiry нет нигде.
6. **`WatchRepository.kt:753`** [CONFIRMED, Low] — `publishWorkoutState` с неизменённым `workout` даёт равный `WatchData` → MutableStateFlow дедупит → комментарий :787 «немедленно» не выполняется. Самолечится следующим unequal emission (HR при живом линке) — материален только в состоянии обрыва.
7. **`WatchConnectionService.kt:212`** [CONFIRMED, Low, intentional] — screen-off гейт давит ВСЕ обновления нотификации, не только workout. Catch-up на SCREEN_ON:207; «missed SCREEN_ON навсегда» убито верификатором (protected broadcasts доходят NOT_EXPORTED-ресиверу живого FGS).
8. **`AndroidManifest.xml:77-81`** [CONFIRMED, Low] — `com.pedometer.VIBRO` нет в intent-filter: implicit `am broadcast -a com.pedometer.VIBRO` молча no-op, работает только `-n com.pedometer/.service.CommandReceiver`. Хедер CommandReceiver тоже не обновлён.
9. **`WatchData.kt:36`** [CONFIRMED, Low] — док «из StepProvider (тот же источник, что кольцо)» врёт: источник — `minute_steps source='phone'` (WatchRepository:763-772). Капсула систематически ≤ кольца (phone дома) — by design, но док вводит в заблуждение.
10. **`WatchRepository.kt:757-759`** [CONFIRMED, Low] — check-then-act race на `stepPollJob` (BLE-поток vs main); утечка ≤30 с дубль-читок, самозакрывается.
11. **`WatchRepository.kt:772`** [CONFIRMED, Low] — non-atomic `.value = .copy` из IO-poll'а может resurrection-ить `workout` после финиша (интерливинг с :477). Окно — длительность DB-запроса раз в 30 с.
12. **`WatchConnectionService.kt:90`** [CONFIRMED, hygiene] — `onWorkoutVibrate` не nullится в onDestroy; stop-path'а у сервиса нет, след ~нулевой.
13. **`CommandReceiver` exported + VIBRO без DEBUG-гейта** [CONFIRMED, Low] — любое приложение может дёргать вибро explicit-intent'ом; для personal-sideload'а приемлемо (NOTIFY — pre-existing, хуже).

## Killed by re-review

- «Капсула замирает при подключённых часах (StepCollector заглушен)» — minute-ряды пишутся безусловно (StepCollector.kt:96-102), гейт `collectEnabled` :104 режет только часовой аккумулятор.
- «R8 может сломать control flow через аргументы логов» — 217 call-site'ов, ноль side-effect-аргументов (единственный «нечистый» — `Calendar.get`, pure).
- «startForeground навсегда сбрасывает workout-текст» — collector StateFlow реплеит текущее состояние в тот же looper-оборот (WatchConnectionService:91-93).
- «daySteps stale утекут в UI» — `withWatchData` не копирует, единственный consumer требует `showLiveUpdate`.
- «AGP 8.9.1 несовместим с compileSdk 36» — wrapper ровно 8.11.1 (минимум AGP 8.9.x), suppress-флаг warning-only, packaging exclude безопасен.
- «paused+обрыв показывает „нет связи" вместо „Пауза"» — требование комбинированный случай не описывает; дизайн-выбор.
- «missed SCREEN_ON = вечный stale» — protected broadcasts доставляются runtime-ресиверу живого FGS; мёртвый сервис = нет нотификации вообще.
- «stale liveWorkoutStart после финиша» — не независимый дефект (старт 0/1 всегда обновляет).

## Acceptance criteria

| Требование | Статус |
|---|---|
| {имя} обычно | MET — WorkoutStatusUi.kt:55-58, тесты 12/12 |
| {имя} · Пауза | MET |
| {имя} · нет связи при обрыве | MET (WatchRepository:394-400 + WorkoutStatusUi:56) |
| финиш → дефолтный линк-статус | MET (:473 → fallback WatchConnectionService:225) |
| триоль старт/финиш | MET (:487 → TriolePlayer; VIBRATE manifest:19) |
| капелька = дневные шаги к цели | MET с оговоркой: phone-only шаги (п.9) |
| выключатель → старое поведение | MET (двойной гейт :478 + WorkoutStatusUi:46-53, тест 7) |

## Backward compatible

**No.** Ручной коннект (B1), контракт manual disconnect (п.1), always-update нотификации при выключенном экране (п.7). Остальные consumer-классы (Room/DAO, UI state, стартовые пути FGS) — совместимы.

## Coverage

| Подсистема | Ревьюеры | Подтверждено / убито | Чисто? |
|---|---|---|---|
| workout-ui (resolver+tests) | 2 | 1 / 2 | нет (п.5,6) |
| service (FGS) | 2 | 4 / 3 | нет (B1, п.1,7,12) |
| repo-data | 2 | 5 / 1 | нет (B1, п.1,3,4,5,10,11) |
| vibro-platform | 2 | 3 / 2 | нет (п.2,8,13) |
| ui-settings | 2 | 2 / 1 | нет (п.3,6) |
| integration | 2 | 5 / 2 | нет (п.9, AGP убито) |

## Out-of-scope additions

Screen-off гейт нотификаций; policy re-assert после auth (B1); сброс `userDisconnected` на wifi-off; GPS-relay re-arm (реальный фикс базового бага — ок); AGP 8.9.1/compileSdk 36/core 1.17; VIBRO test action.

## Residual risks (нужен девайс)

- `canPostPromotedNotifications()` дефолт на ColorOS 16 — если toggle выключен по умолчанию, капсула молча не покажется (деградация до обычной ongoing-нотификации, код grant не запрашивает).
- Шлёт ли часы status 1/0 при реконнекте посреди тренировки — определяет достижимость п.4 и latency очистки п.5.
- Watch-vs-phone clock drift между auth-синками (guard :605).
- Фактическое поведение R8-release и promoted-капсулы на устройстве не прогонялось (собран только debug + unit-тесты; прогон тестов — `tests="12" failures="0"`).
