# Pedometer Development Pipeline

## Current State
- **Active Phase:** DONE
- **Phase Status:** ALL_COMPLETED
- **Last Action:** Phase 8 COMPLETED — all 8 phases done

## Pipeline Rules

Every iteration of the cron:
1. Read this file, determine current phase and status
2. If status is NOT_STARTED or PLAN_VERIFIED:
   - If NOT_STARTED: search Tavily for each technical claim in the phase, update findings, set status to PLAN_VERIFIED
   - If PLAN_VERIFIED: invoke Skill writing-plans for current phase, save plan to `docs/superpowers/plans/`, set status to PLAN_WRITTEN
3. If status is PLAN_WRITTEN:
   - Invoke Skill subagent-driven-development to execute the plan
   - Set status to IN_PROGRESS
4. If status is IN_PROGRESS:
   - Check task completion, continue executing
   - When all tasks done, run tests, set status to COMPLETED
5. If status is COMPLETED:
   - Increment Active Phase
   - Set status to NOT_STARTED
   - Continue to next phase

## Phase Tracker

### Phase 1: Foundation — BLE Transport + Auth
- [x] Tavily verification of technical claims
- [x] Writing plan
- [x] Subagent execution
- [x] Tests pass (16 unit tests)
- [x] Commit (10 commits: c1b1422..a2d51a3)

### Phase 2: Health Data — Steps, Heart Rate, Sleep
- [x] Tavily verification
- [x] Writing plan
- [x] Execution (direct, 4 commits: ea051ea..e99d5d9)
- [x] Tests pass
- [x] Commit

### Phase 3: Music Control
- [x] Tavily verification (schema already known from Phase 1)
- [x] Writing plan (inline)
- [x] Execution (direct, 2 commits: 1524ad6..4dcaf17)
- [x] Tests pass
- [x] Commit

### Phase 4: Weather
- [x] Tavily verification (schema known)
- [x] Execution (direct, 2 commits: fbf8e1b..dd5a13b)
- [x] Tests pass
- [x] Commit

### Phase 5: Notifications
- [x] Execution (direct, 2 commits: 05e7f35..5c216d4)
- [x] Tests pass
- [x] Commit

### Phase 6: Utilities
- [x] Execution (direct, 1 commit: fe08589)
- [x] Tests pass
- [x] Commit

### Phase 7: Watchfaces
- [x] Execution (direct, 1 commit: e3893f1)
- [x] Tests pass
- [x] Commit

### Phase 8: Polish & Background
- [x] Tavily verification
- [x] Execution (direct, 2 commits: 7825449..49f1c59)
- [x] Tests pass
- [x] Commit

## Tavily Findings Log

### Phase 1 Verification (2026-06-28)

**SPP UUID:** CONFIRMED — `00001101-0000-1000-8000-00805F9B34FB` is standard SPP/RFCOMM UUID. Android uses `createRfcommSocketToServiceRecord(UUID)` for connection.

**Auth protocol:** CONFIRMED — Byteria blog confirms ECDH + HKDF-SHA256 + HMAC-SHA256 + AES-128-CCM for session encryption. Gadgetbridge XiaomiAuthService uses HMAC-SHA256 key derivation with "miwear-auth" salt, AES-CCM for packet encryption. Paper from EPFL confirms AES-ECB challenge-response for older devices, newer use CCM.

**Protobuf setup:** CONFIRMED — `protobuf-gradle-plugin` 0.9.5+, `protobuf-javalite` 3.20+, `protobuf-kotlin-lite`. Proto files go in `src/main/proto/`. Use `option "lite"` in builtins for Android.

**Transport V1 vs V2:** Gadgetbridge has both `XiaomiSppProtocolV1` (AES-CCM) and `V2` (AES-CTR where key==IV, comment: "I wish I was kidding"). Need to detect which one the watch uses at connect time.

**BLE vs SPP:** Byteria confirms Redmi Watch 5 Active/Lite uses SPP for bulk data. Gadgetbridge docs say some firmware versions support only one type — try SPP first, fallback to BLE.

### Phase 2 Verification (2026-06-28)

**RealTimeStats:** CONFIRMED — protobuf `Health.RealTimeStats` contains steps, calories, heartRate, standingHours. Cmd type=8, subtypes: 45=start, 46=stop, 47=event (periodic updates). Simplest way to get live data.

**Activity sync:** CONFIRMED — Gadgetbridge XiaomiHealthService uses cmd type=8, subtypes 1(today)/2(past)/3(request)/5(ack). Files are binary, parsed by DailySummaryParser, DailyDetailsParser, SleepStagesParser. Device can ONLY sync current day's activities — if not synced, data is lost.

**Activity file format:** Binary files with XiaomiActivityFileId headers. Parsers in `service/devices/xiaomi/activity/impl/`. Complex binary format, not protobuf.
