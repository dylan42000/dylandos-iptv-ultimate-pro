# SAVE POINT — DYLANDOS IPTV ULTIMATE (v5.0 WORLD CLASS upgrade)

> **Created:** 2026-08-07 (UTC). **Purpose:** if we disconnect/restart, this file tells the next session exactly where we are, what the app is, how to build/test it, what has been done, and what is next. Read this FIRST, then `FINAL_WORLD_CLASS_UPGRADE_v5.md` (the full v5.0 spec), then continue.

---

## 1. What this app is

- **DYLANDOS IPTV ULTIMATE** — IPTV player by "Dylandos".
- **Two codebases in one repo:**
  - **Electron/Windows app** (`electron/`, `src/` — React+Vite, hls.js, node-mpv). Windows-only desktop player.
  - **Android app** (`android/` — Kotlin, Jetpack Compose, Hilt, Room, Paging 3, Coil, Media3/ExoPlayer, LibVLC 3.6.0, MPV, WorkManager). **← THE PRIORITY.** 90 % of users are on **Fire TV Stick** (Fire OS 5–8, armeabi-v7a, 2 GB RAM).
- **Android flavors:** `firestick` (armeabi-v7a only, `-FIRESTICK`) and `premium` (arm64-v8a, `-PREMIUM`).
- **Current version:** `versionName 4.10.5`, `versionCode 85`. Target for this work: **v5.0.0 / versionCode 86**.
- **App id:** `com.dylandos.iptv.ultimate` (+ `.firestick` / `.premium` / `.debug` suffixes).

## 2. Key architecture (Android)

| Area | Files |
| --- | --- |
| Player orchestrator | `android/app/src/main/java/com/dylandos/iptv/ultimate/player/engine/DualMediaEngine.kt` (LibVLC primary for live TS/RTSP, MPV for HLS/VOD, Media3 last resort, engine failover + `EnginePreference`) |
| Live player screen | `ui/screens/player/PlayerScreen.kt` (3571 lines; builds its own LibVLC via `makeLiveLibVLC`/`makeVodLibVLC`, MPV via `MpvPlayerWrapper`, Media3 via `LazyExoPlayerHost`) |
| Player VM | `ui/screens/player/PlayerViewModel.kt` (zap debounce 220 ms, timeshift token parsing, stream URL resolution) |
| Media3 ring | `ui/screens/player/LazyExoPlayerHost.kt` (SimpleCache + LRU evictor, hard 512 MB cap) |
| Live TV list | `ui/screens/livetv/LiveTvViewModel.kt` (zap up/down, 220 ms debounce) |
| Settings state | `ui/screens/settings/SettingsViewModel.kt` (1031 lines; DataStore `settings`; perf keys: `liveNetworkCacheMs=800`, `vlcAudioResampler=ugly`, `vlcAudioOutput=android_audiotrack`, `timeshiftEnabled`, etc.) |
| DVR | `service/RecordingService.kt` (3 concurrent, LibVLC `:sout` stream-copy, SAF OTG/USB), `workers/ScheduledRecordingWorker.kt`, `data/repository/DvrRecordingRepository.kt`, `ui/screens/dvr/*` |
| Timeshift | provider catch-up URL (`data/network/XtreamRepository.kt:501 getTimeshiftStreamUrl`), LibVLC `:input-timeshift-path` (PlayerScreen `resolveTimeshiftDirectory`), Media3 USB ring |
| Memory | `data/util/MemoryBudgetManager.kt` (DeviceTier LOW_END etc.), `DylandosApp.kt` (`onTrimMemory` B4 already implemented, Coil budget loader, Sentry guarded by `SENTRY_DSN`) |
| Focus system | `ui/focus/DylandosFocusSystem.kt`, `FocusStateRepository.kt`, `KeyEventRouter.kt` |
| OTA | `ui/update/UpdateChecker.kt`, `UpdateViewModel.kt` (fail-closed SHA-256 done); **live gist `6056e65b41641393abf585cecbd92d07` is STALE (advertises v4.6.3/62)** |

## 3. Workspace docs (read before coding)

- **`FINAL_WORLD_CLASS_UPGRADE_v5.md`** — the v5.0 spec: 14 moves, Parts 1–8, file map, ship checklist. **This is the working plan.**
- `FIRESTICK_WORLD_CLASS_DEEP_DIVE.md` — prior audit (issues A1–A7, bugs B1–B10, C1–C8). Items marked DONE there are assumed landed; verify when touching them.
- `android/ANDROID_BUILD_GUIDE.md`, `ANDROID_QUICKSTART.md`, `FINAL_ANDROID_SUMMARY.md` — build docs.
- `PUSH_GUIDE.md`, `RELEASE_PLAYBOOK.md` — release flow.
- `upgrade texts/latestfix4112026.txt` — Electron changelog; contains the format-fallback pattern to port.

## 4. Build & test setup (the user's machine — sandbox has NO Java/Gradle/Android SDK)

### Build (Windows)
```
cd android
build_android.bat          # or: build_android.sh (Linux/mac)
```
- Requires: JDK 17, Android SDK (compileSdk 36), Gradle 8.11.1 wrapper (bundled: `android/gradlew` + `gradle-wrapper-8.2.jar`).
- **Signing:** release needs `android/local.properties` (untracked) or env vars:
  - `DYLANDOS_RELEASE_STORE_PASSWORD`, `DYLANDOS_RELEASE_KEY_PASSWORD`; keystore `android/app/dylandos-release.jks` (alias `dylandos`).
- Debug builds include all ABIs (x86/x86_64 for LDPlayer emulator compat) — see `android/app/build.gradle.kts:100–107`.

### Tests (JVM, no device)
```
cd android && ./gradlew test            # runs app/src/test/** (JUnit4)
./gradlew assembleFirestickDebug       # compile check for the firestick flavor
./gradlew lint                         # optional
```

### Manual testing (Fire TV)
1. Sideload: `adb connect <stick-ip>:5555` (enable ADB debugging on the stick) → `adb install -r app-firestick-debug.apk` (or `release`).
2. Logcat: `adb logcat -s Timber:* Dyl*:* | grep -i -E "engine|buffer|vlc|mpv|exo|dvr"`.
3. Memory: `adb shell dumpsys meminfo com.dylandos.iptv.ultimate.firestick` — verify heap cap ~256 MB (NOT 512 MB) after largeHeap removal.
4. Frame jank: `adb shell dumpsys gfxinfo com.dylandos.iptv.ultimate.firestick framestats` (debug builds).
5. Crashes: `adb logcat -b crash`; app also writes `crash_log.txt` (FieldTelemetry) + "Copy diagnostics" in About.
6. Emulator: LDPlayer 9 (x86_64, debug build only) or `sdkmanager` AVD with an ARM image (slow).

### CI (exists, needs secrets)
- `.github/workflows/android-release.yml`: tests on PR; on tag `v*` builds+signs both flavors, creates GitHub Release, computes SHA-256, auto-updates the OTA gist via `.github/scripts/publish_update_json.py`.
- Needs GitHub secrets: `DYLANDOS_RELEASE_STORE_PASSWORD`, `DYLANDOS_RELEASE_KEY_PASSWORD`, `GIST_TOKEN`.

## 5. ✅ DONE in this session (2026-08-07) — code implemented, needs a build

| # | Change | Files |
| --- | --- | --- |
| 1 | **RTSP/HTTP hardening** for live: added `--rtsp-tcp` + `--http-continuous` to `makeLiveLibVLC` | `PlayerScreen.kt` |
| 2 | **Live cache ceiling raised** 2000→4000 ms so the adaptive controller's values aren't clamped away | `PlayerScreen.kt` (`makeLiveLibVLC` + `buildMedia`) |
| 3 | **AAudio audio output** option accepted (Fire OS 7+) | `PlayerScreen.kt` |
| 4 | **Verbose logging gated to debug builds only** (release always `--no-stats`) | `PlayerScreen.kt` |
| 5 | **NEW `TimeshiftRingMath`** — pure, tested ring-sizing math (dynamic cap: max(512 MB, min(free/4, 8 GB)); optional target-window minutes) | `player/timeshift/TimeshiftRingMath.kt` |
| 6 | **NEW `LiveBufferController`** — pure, tested adaptive buffer controller (raise +400 ms on rebuffer, relax after ~3 min clean, HD-mode suggestion after 6 raises) | `player/engine/LiveBufferController.kt` |
| 7 | **Dynamic timeshift ring sizing** in Media3 host (replaces hard 512 MB cap; honors `ringMaxBytes`) | `LazyExoPlayerHost.kt` |
| 8 | **New settings:** `adaptiveBuffer` (on), `bufferFloorMs` (600), `bufferCeilingMs` (4000), `timeshiftWindowMinutes` (0=auto) — state defaults, DataStore keys, load, setters | `SettingsViewModel.kt` |
| 9 | **Removed `android:largeHeap="true"`** (onTrimMemory already implemented in `DylandosApp` — watch LMK on 2 GB sticks during soak test; revert this one line if force-closes appear) | `AndroidManifest.xml` |
| 10 | **JVM unit tests** for #5 and #6 | `app/src/test/java/.../player/engine/LiveBufferControllerTest.kt`, `.../player/timeshift/TimeshiftRingMathTest.kt` |
| 11 | **⏰ EPG TIME FIX ("~4 h ahead")** — root cause: `alignEpgProgramsToNow` hour-snapping heuristic ran over ALREADY-CORRECT Room XMLTV rows in LiveTv/Guide/Player VMs; a schedule gap at "now" was indistinguishable from provider TZ skew, so grids got shifted by the classic 4–9 h offsets. Fix: new pure `EpgTimeAligner` (flag-only, `now_playing`-corroborated) + `alignEpgListing` removed from all Room paths + `KEY_XMLTV_DATA_VERSION` force-refetch once on upgrade. 8/8 regression tests | `data/network/EpgTimeAligner.kt` (new), `XtreamRepository.kt`, `LiveTvViewModel.kt`, `GuideViewModel.kt`, `PlayerViewModel.kt`, `app/src/test/.../data/network/EpgTimeAlignerTest.kt` (new) |
| 12 | **LiveBufferController WIRED into PlayerScreen** — per-live-session controller (starts at user's static cache via new `initialCacheMs`), 2 s poll loop from `MediaPlayer.isBuffering`, adaptive value applied at all 3 live `buildMedia` sites + recording leg (ceiling 4 s) | `PlayerScreen.kt`, `LiveBufferController.kt` (initialCacheMs) |
| 13 | **Settings UI** — Adaptive Buffer toggle + floor/ceiling sliders (Performance tab), Timeshift ring window picker Auto/30/60/120/240 (Playback tab) | `SettingsScreen.kt` |
| 14 | **Version bump to 5.0.0 / versionCode 86** | `app/build.gradle.kts` |
| 15 | **NEW `build_signed_apk.bat`** — one-click signed release builder (finds Java 17 + Android SDK, prompts once for keystore passwords → git-ignored `local.properties`, preserves existing keys, prints APK size + SHA-256 via certutil) | `android/build_signed_apk.bat` (new) |

### Not wired yet (needs a real build/device — see priority queue)
- **LiveBufferController mid-session reload** — currently the adaptive cache applies at the NEXT media build (channel change / session start). A mid-stream "reload media with raised cache + seekTo(position)" is deliberately NOT auto-wired (risk of black-flash on a struggling network); test on device first, then add if soak shows it helps.
- **Timeshift UI (Part 3 of spec)** — Live Timeline, seek bar, jump-to-live: design in spec; NOT implemented (large UI work).
- **Zap preload/engine reuse (Part 1.7)** — NOT implemented (needs DualMediaEngine changes + device testing).
- **Format fallback chain + heartbeat (Part 1.8)** — NOT implemented (needs PlayerViewModel watchdog).
- **DVR EPG-record/series-link (Part 4)** — NOT implemented (needs guide + scheduler work).
- **Menu-key context menus / Home rails (Part 5)** — NOT implemented.
- **APK trim (drop MPV on firestick, ≤40 MB)** — NOT done. Exact steps: move `app/src/main/jniLibs/armeabi-v7a/{libmpv.so, libplayer.so, libavcodec.so, libavdevice.so, libavfilter.so, libavformat.so, libavutil.so, libswresample.so, libswscale.so}` into `app/src/premium/jniLibs/arm64-v8a/` (32 MB). Keep `libc++_shared.so` in main (libvlc-all AAR may need it; packagingOptions lines 148–155 already handle dedupe). `MpvPlayerWrapper` degrades gracefully when natives are missing (engine failover), so firestick just loses MPV-for-HLS. **Requires a real build to verify** — do it in the same session as the soak test.
- **OTA gist publish** — still stale; must publish v5.0.0/86 + real SHA-256 + GitHub Release URLs before shipping.
- **Room indexes / streaming-JSON final verification / Coil sizing pass** — verify per spec Part 2.1 (some marked done in deep dive; confirm in code).

## 6. Priority queue (what to do next, in order)

1. **Build the signed release APK with `build_signed_apk.bat`** (needs Java 17 + Android SDK + the two keystore passwords on the user's machine). Then `adb install` on a Stick 4K and run the 60-min soak (congested WiFi): gate ≤2 rebuffers, zap ≤0.8 s, 0 force-closes (largeHeap removal is the one thing to watch).
2. **Verify the EPG time fix on device**: open Guide + Live TV after first launch — the XMLTV data-version bump forces one refetch, so times should line up with wall clock within minutes. If any channel still looks off, check `logcat -s Timber:*` for "EPG:" lines.
3. **Publish the OTA gist** (v5.0.0/86, real SHA-256 from the built APK, GitHub Releases URLs) + set CI secrets (PUSH_GUIDE.md table).
4. **Timeshift v2** (Part 3) — Live Timeline controller + seek UI.
5. **Zap preload** (Part 1.7) — DualMediaEngine preload channel + engine reuse.
6. **DVR EPG-record** (Part 4) — guide record button + notifications + storage policy.
7. **Context menus** (Part 5.1) + Home rails (Part 5.3).
8. **APK trim** (firestick ≤40 MB) — exact steps in §5; do it in the same session as the soak so it gets a build check.
9. Tests for everything as it lands; keep `gradlew test` green (new suites: LiveBufferController, TimeshiftRingMath, EpgTimeAligner — 29 tests).

**Session note:** sandbox has no Android SDK — the full app build must run on the user's machine via `build_signed_apk.bat`. JDK 17 + kotlinc live in `/tmp/dk` (ephemeral) and were used to compile & run the pure-Kotlin unit suites against the REAL sources (LiveBufferController 13, TimeshiftRingMath 8, EpgTimeAligner 8 = **29/29 green**).

## 7. Guardrails (never regress)

- **DVR OTG/thumb-drive two-option flow** (`DvrStoragePickerScreen` + `DvrStorageViewModel`): auto-save on select, OTG preferred, SAF stays user-locked. Don't touch.
- `DylandosFocusSystem` / focus edge-guards (A2/A4 fixes). Don't break focus retry loops.
- OkHttp pinned at **4.12.0** — never bump to 5.x (breaks Coil 2.7.0 at runtime). Comment in `build.gradle.kts:222–227`.
- `--rtsp-tcp`/`--http-continuous` must stay in live options (that was the point of this session).
- Media3 timeshift ring stays USB-only (`resolveTimeshiftDirectory` policy) — internal flash is precious on sticks.
- All new settings default to existing behavior (backward compatible).

## 8. Quick file index (most-edited this session)

- `android/app/src/main/java/com/dylandos/iptv/ultimate/ui/screens/player/PlayerScreen.kt`
- `android/app/src/main/java/com/dylandos/iptv/ultimate/player/timeshift/TimeshiftRingMath.kt` (new)
- `android/app/src/main/java/com/dylandos/iptv/ultimate/player/engine/LiveBufferController.kt` (new)
- `android/app/src/main/java/com/dylandos/iptv/ultimate/ui/screens/player/LazyExoPlayerHost.kt`
- `android/app/src/main/java/com/dylandos/iptv/ultimate/ui/screens/settings/SettingsViewModel.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/test/java/com/dylandos/iptv/ultimate/player/engine/LiveBufferControllerTest.kt` (new)
- `android/app/src/test/java/com/dylandos/iptv/ultimate/player/timeshift/TimeshiftRingMathTest.kt` (new)
