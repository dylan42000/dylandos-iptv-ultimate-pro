# DYLANDOS IPTV ULTIMATE — FINAL WORLD-CLASS UPGRADE (v5.0)

### The definitive spec for the best version of the app — Firestick-first, performance-obsessed

> **Version target:** `v5.0.0` / `versionCode 86` — the "WORLD CLASS" release. **Audience split:** **90 % Firestick** (`app-firestick-release.apk`, armeabi-v7a, Fire OS 5–8, 2 GB RAM) — every decision below optimizes for that device first, with the `premium` flavor (arm64-v8a, 4 GB+) inheriting the same code with higher budgets. Premium-only features are explicitly labeled. **Baseline in repo (verified 2026-08-07):** `versionName 4.10.5`, `versionCode 85` (`android/app/build.gradle.kts`). Player = DualMediaEngine (LibVLC 3.6.0 primary for live TS/RTSP, MPV for HLS/VOD, Media3 fallback) + Provider catch-up timeshift + LibVLC `:input-timeshift-path` + Media3 USB ring (512 MB). DVR = `RecordingService` (3 concurrent, LibVLC `:sout` stream-copy, SAF USB/OTG). UI = Jetpack Compose, custom `DylandosFocusSystem`. **This document supersedes** `FIRESTICK_WORLD_CLASS_DEEP_DIVE.md` **as the single source of truth for the v5.0 sprint.** Items already marked DONE in the deep dive are assumed landed; where they are not verified in code, this spec says so and gives the finishing step.

> **✅ Implementation status (2026-08-07) — P0 player-performance + TIME-FIX sprint fully landed in code:**
>
> - **1.2 (adaptive buffer) — CODE + WIRED + TESTS:** `LiveBufferController` (new, `player/engine/`, starts at the user's static cache, raises +400 ms per rebuffer, relaxes after ~3 min clean, HD-mode suggestion after 6 raises) is now **wired into `PlayerScreen`** — per-live-session controller, 2 s poll loop feeding `REBUFFER`/`HEALTHY_TICK` from `MediaPlayer.isBuffering`, and the adaptive value is applied at all 3 live `buildMedia` sites + the recording leg (ceiling 4 s). `SettingsScreen` exposes the toggle + floor/ceiling in the Performance tab.
> - **1.3/1.5/1.6 (RTSP/HTTP/audio/logs) — CODE:** `--rtsp-tcp` + `--http-continuous` in `makeLiveLibVLC`; live cache clamp 2000→4000 ms; `aaudio` accepted; `--verbose=2` gated to debug builds.
> - **⏰ EPG TIME FIX (the "~4 h ahead" guide/Live-TV bug) — CODE + TESTS (the big one this session):** root cause was `alignEpgProgramsToNow` — an hour-snapping heuristic applied to ALREADY-CORRECT Room XMLTV rows in `LiveTvViewModel`, `GuideViewModel` and `PlayerViewModel`. It could not distinguish a provider TZ skew from a schedule gap at "now", so whenever "now" sat in a gap it shifted whole grids by the classic 4–9 h offsets. Fix: new pure `EpgTimeAligner` (flag-only, `now_playing` corroborated) + `alignEpgListing` removed from every Room path + `KEY_XMLTV_DATA_VERSION` force-refetch once on upgrade. **8/8 regression tests green.**
> - **3.2 (ring sizing) — CODE + TESTS:** `LazyExoPlayerHost` dynamic ring cap (hard 512 MB → auto up to 8 GB) with `ringMaxBytes` override; `TimeshiftRingMath` tested. Settings UI gained the Timeshift ring window picker (Auto/30/60/120/240).
> - **Ship tooling — CODE:** new `android/build_signed_apk.bat` (foolproof signed-release builder: finds Java 17 + SDK, prompts once for signing secrets, preserves `local.properties`, prints APK size + SHA-256); `versionCode 86` / `versionName 5.0.0`.
> - **Tests: 29/29 green** in-sandbox (LiveBufferController 13, TimeshiftRingMath 8, EpgTimeAligner 8) against the real sources.
> - **Still open (device-verified / build-verified steps only):** zap preload (1.7); format fallback + heartbeat (1.8); Live Timeline UI (Part 3); DVR EPG-record (Part 4); context menus (5.1); APK trim (2.2 — steps in `save_point.md` §5, needs a real build); OTA gist publish (7.1 — needs `GIST_TOKEN` + SHA-256 from the built APK). See `save_point.md` §6 priority queue.

---

## ⚡ TL;DR — the 14 moves that make it world-class

| \# | Move | Why it's the one | Effort | Priority |
| --- | --- | --- | --- | --- |
| 1 | **Adaptive live buffer controller** (replaces static 800 ms cache) | The #1 cause of "channels periodically lag" on WiFi sticks | M | **P0** |
| 2 | **RTSP/UDP hardening** (`--rtsp-tcp`, `--http-continuous`, keep-alive) | Silent UDP packet loss = periodic freeze on 2.4 GHz WiFi | S | **P0** |
| 3 | **Audio pipeline audit on armeabi-v7a** (soxr→ugly default, AAudio) | `soxr` resampler can eat a third of a 2 GB stick's CPU → audio/video jank | S | **P0** |
| 4 | **Log IO silence in release** (`--quiet`, no `-vv`, Timber trees off) | Logcat churn on slow flash causes visible stutter | S | **P0** |
| 5 | **Instant channel zapping** (preload next, engine reuse, 220 ms debounce → &lt;800 ms zap) | The #1 "feels pro" interaction on live TV | M | **P0** |
| 6 | **Format fallback chain + playback heartbeat** (`.ts`→`.m3u8`→transcode→next server, auto-engine-cycling) | Turns "black screen" into "back in 3 s" | M | **P0** |
| 7 | **Per-channel engine memory** ("this channel plays best on LibVLC/MPV/Media3") | Kills repeat failures on problem channels | S | **P0** |
| 8 | **Memory lockdown for 2 GB** (drop `largeHeap`, Coil sizing/RGB_565, Room WAL+indexes, trim handlers) | Kills the "overwhelmed" force-close for good | M | **P0** |
| 9 | **World-class Timeshift: unified Live Timeline** (ring + catch-up + live in one seekable bar, dynamic ring, jump-to-live, record-from-window) | Turns timeshift from "pause exists" into the differentiator | L | **P0** |
| 10 | **Higher-end DVR** (EPG one-touch record, series-link, conflicts, storage-full policy, retry/backoff, recordings manager) | Bulletproof what you already built | L | P1 |
| 11 | **Menu-key context menus + star badges + Home rails** | The single biggest TV-UX gap (right-click world) | M | P1 |
| 12 | **World-class TV UI pass** (focus contrast, overscan, on-screen keyboard, EPG canvas perf, reduced effects on LOW_END) | 10-foot polish that matches the player | M | P1 |
| 13 | **Ship pipeline** (fix stale OTA gist **today**, Crashlytics, CI tests, APK ≤40 MB by dropping MPV on firestick) | Without this, nobody ever runs v5.0 | S–M | **P0** (gist) / P1 |
| 14 | **Automated tests** (JVM unit + D-pad focus regression + smoke) | "World-class" is a claim you can only make with tests | M | P1 |

**Guardrails (never regress):** the DVR OTG/thumb-drive two-option flow (`DvrStoragePickerScreen` + `DvrStorageViewModel`, auto-save on select, OTG-preferred); the `DylandosFocusSystem`; all existing Settings performance keys stay backward-compatible (new keys default ON).

---

## PART 1 — PRIORITY #1: PLAYER PERFORMANCE (kill channel lag)

### 1.1 Root-cause analysis: why channels "periodically lag" on Firestick

The lag symptom (video freezes 1–5 s, then jumps, on an otherwise healthy stream) is almost always **buffer underrun** — the player consumes frames faster than the jittery WiFi/ISP path delivers them. Evidence in code, in order of likelihood:

| \# | Root cause | Code evidence | Impact |
| --- | --- | --- | --- |
| R1 | **Static, low network cache**: default `liveNetworkCacheMs = 800` ms (`SettingsViewModel.kt:101`), clamped to 300–2000 (`PlayerScreen.kt:134`) | A 800 ms buffer on 2.4 GHz WiFi with a 1 s latency spike = guaranteed rebuffer | High |
| R2 | **No adaptive response**: cache is fixed at load; nothing raises it after a rebuffer or lowers it on healthy playback | No `BufferingComplete` feedback loop anywhere | High |
| R3 | **RTSP over UDP**: `PlayerScreen.makeLiveLibVLC` options (lines 146–167) do **not** include `--rtsp-tcp` (the factory `LibVlcFactory.buildLiveOptions:37` *does*) | UDP + WiFi = dropped packets = periodic freeze; RTSP-TCP fixes it | High |
| R4 | **Missing** `--http-continuous` on live HTTP/TS in `makeLiveLibVLC` (factory has it at `LibVlcFactory.kt:39`) | Without it, some servers close idle connections → reconnect storms every few minutes | Medium |
| R5 | **CPU contention on armeabi-v7a**: `soxr` resampler is 2–3× the CPU of `ugly`; Fire OS 5–7 sticks decode 1080p with software assist on some codecs | `PlayerScreen.kt:155` codec chain `mediacodec_ndk,mediacodec_jni,iomx,all` is good, but audio resampling + `freetype` subtitle burn-in + `--verbose=2` can starve the decoder | Medium |
| R6 | **Log IO**: `--verbose=2` (`PlayerScreen.kt:171`, default off but a popular "help me debug" toggle) and Timber debug trees spam slow flash storage → frame drops | Release builds should be `--quiet` + `--no-stats` unconditionally | Low–Med |
| R7 | **Zap churn**: every channel change tears down and re-inits a full LibVLC instance (fresh `LibVLC(ctx, options)` per play); no next-channel preload | `PlayerScreen.kt` builds VLC per stream; `PlayerViewModel.kt:48–51` only debounces | Med |
| R8 | **No recovery path**: on sustained error the user just stares at a spinner — no format fallback, no heartbeat, no auto-engine swap | `PlayerViewModel` has no playback watchdog | Med |

### 1.2 FIX #1 — Adaptive Buffer Controller (the big one)

**What:** Replace the static `--network-caching` with a per-session adaptive controller that tunes the live cache between a floor and ceiling based on real network health. This alone eliminates the majority of periodic-lag reports.

**Where:** `PlayerScreen.kt` (option builder) + new `player/engine/LiveBufferController.kt` + `SettingsViewModel` (budget keys).

**Design:**

1. New settings (defaults, backward compatible): `adaptiveBuffer (ON)`, `bufferFloorMs = 600`, `bufferCeilingMs = 4000`, `maxAdaptiveRaisesPerSession = 6`.
2. `LiveBufferController` observes `PlaybackState` (existing `isBuffering`, `bufferPercent`, `droppedFrames` from `MediaEngine.kt`) and runs this loop every 2 s:
   - **Rebuffer event** → `cache += min(400, ceiling − cache)` (fast raise), remember timestamp.
   - **3 min of clean playback** (no rebuffer, `droppedFrames` delta &lt; 2/min) → `cache = max(floor, cache − 200)` (slow relax).
   - **3 consecutive raises in 10 min** → suggest to the user (non-blocking toast, once per session): "Weak connection detected — Switch to Adaptive HD mode?" (toggles a lower-bitrate server or `--prefetch-buffer-size` boost).
3. Apply via **engine reload only when raised** (cheap: `LibVLC` accepts `--network-caching` only at init, so on raise, reload media with new cache — position preserved via `seekTo(positionMs)`); on relax, apply next channel change only.
4. For HLS live (MPV path) add `--demuxer-max-bytes` and rely on MPV's `cache` profile; expose `cache=yes` with `demuxer-readahead-secs=20`.
5. Wire `EngineConfig.networkCachingMs` (already threaded through `DualMediaEngine` → `LibVlcFactory.buildLiveOptions(config)`) so the factory path gets the same controller.

**Acceptance:** on Firestick 4K behind a congested 2.4 GHz router: play a 1080p/50 channel 60 min → **≤2 rebuffer events total**; first frame ≤2 s; after one rebuffer, cache visibly raised (visible in debug overlay); app never needs manual intervention.

### 1.3 FIX #2 — RTSP / HTTP hardening (5-line fix, do in the first commit)

In `PlayerScreen.makeLiveLibVLC` add:

- `--rtsp-tcp` (forces RTSP over TCP — eliminates UDP packet loss on WiFi)
- `--http-continuous` (keep HTTP/TS connections alive)
- `--network-caching` handled by 1.2; keep `--http-reconnect` (already present).
- Keep `--mtu=${...}` (exists) but default 1500 and expose only in Advanced.

Same additions to `LibVlcFactory.buildLiveOptions` (already has both — **keep them; they're the reference**). Also port to `makeVodLibVLC`/`RecordingService.createRecordingLibVlc` where the stream is HTTP.

**Acceptance:** an RTSP channel that previously froze every 20–60 s on WiFi plays 60 min with zero freezes; an HTTP/TS channel with a flaky server stops reconnecting every few minutes.

### 1.4 FIX #3 — Hardware decode pipeline audit

Current chain is good (`--codec=mediacodec_ndk,mediacodec_jni,iomx,all` + `--mediacodec-dr` + `--avcodec-fast`). Remaining refinements:

1. **Chroma discipline:** `--android-display-chroma` default `RV32` on firestick (matches `--mediacodec-dr` surface format); keep `RV16` as the LOW_END power-saver option. Mismatched chroma + DR is the classic green/pink artifact source.
2. **Fire OS specific:** add `--avcodec-hw=mediacodec` is implied by the codec list — but ensure **no** `--avcodec-hw=any` override can sneak in from advanced settings (software fallback only on error, never first).
3. **Deinterlace policy:** `enableDeinterlacing` exists in config — for 1080i sports channels keep `yadif` **only when a setting is on**; never enable on LOW_END by default (costs a full decode pass). Add per-channel deinterlace memory (some 50i channels need it, most don't).
4. **Frame policy:** keep `--drop-late-frames` + `--skip-frames` **ON for live** on firestick (correct choice for A/V sync under load — they already default ON in `SettingsViewModel.kt:166`); make sure the *fallback* option set in `PlayerScreen.kt:176–185` also keeps them (it does).
5. `--clock-jitter` **/** `--clock-synchro=0`**:** keep (already present); `clock-jitter` clamp 0–200 ms stays.

**Acceptance:** 1080p/50 H.264 channel at 20 Mbps plays at 50 fps with `droppedFrames` &lt; 0.1 %/min on Firestick 4K (debug overlay); 1080i sport channel with deinterlace ON stays in sync; no green/pink frames across 10 random channels.

### 1.5 FIX #4 — Audio pipeline on armeabi-v7a

1. **Resampler default →** `ugly` on firestick flavor (fast integer resampler; `soxr` only as an explicit user setting). Current PlayerScreen default is already `ugly` (`PlayerScreen.kt:139`) — **lock it**: flip the `SettingsViewModel` default (`vlcAudioResampler`) to `ugly` too so factory-path playback (DVR preview, MPV fallback) matches.
2. **Audio output:** keep `android_audiotrack` default (exists); add **AAudio** (`--aout=aaudio`) as a selectable option for Fire OS 7+ (lower latency, fewer glitches on newer sticks); auto-detect via `Build.VERSION.SDK_INT >= 26` + device model whitelist (first-gen stick stays audiotrack).
3. **No audio normalization by default on live** (`--audio-filter=normvol` is VOD-only today — keep it that way; adds DSP cost on live).
4. Add `--audio-time-stretch` **off** for live (default) to avoid pitch-shift CPU on catch-up playback; keep `--no-audio-time-stretch` explicit.

**Acceptance:** 1080p channel audio never stutters while CPU is pegged by decode; AAudio option present on Fire OS 7+/8; audio/video sync drift &lt; 50 ms over 60 min (debug overlay).

### 1.6 FIX #5 — Log IO silence (release only)

- Release builds: `--quiet` + `--no-stats` + `--no-video-title-show` unconditionally; `--verbose=2` only when the debug toggle is on **and** build is debuggable.
- Timber: debug tree only in debug builds (`BuildConfig.DEBUG`); release keeps a **ring-buffer in-memory** logger (last 200 lines, `FieldTelemetry`) instead of logcat/file spam — the "Copy diagnostics" feature reads from RAM, not disk.
- DVR service logging: keep `Timber.i` but route through the same ring; no per-packet logging ever.

**Acceptance:** `adb logcat` on a release build shows &lt; 10 lines per minute of app activity; frame pacing during 60 min playback shows no log-related hitches (verified via `dumpsys gfxinfo` in debug).

### 1.7 FIX #6 — Instant channel zapping (preload + engine reuse)

**Goal: sub-second channel change on live TV** — the #1 "pro box" feel.

1. **Preload the next channel:** in `LiveTvViewModel`/`PlayerViewModel`, when a channel is playing, warm a **second hidden engine** (`MediaEngine` instance #2 in `DualMediaEngine` — it already manages primary+fallback; add a `preloadSource`) with the *next channel in the current category* (Up/Down direction is known from `zapUp()/zapDown()` at `LiveTvViewModel.kt:602,617`). Preload = connect + open demuxer + first 256 KB buffered, **not** decoding (saves CPU/battery).
2. **Engine reuse across zaps:** stop tearing down `LibVLC` between channels in the same session. New flow per zap: `pause surface-hold` → `mediaPlayer.setMedia(newUrl)` (same LibVLC instance) → play. Only if the new URL's protocol demands it (RTSP→HTTP switch) re-init. This drops zap cost from \~2–3 s to \~300–700 ms.
3. **Zap coalescing:** keep the 220 ms debounce (`PlayerViewModel.kt:50`) but add **direction-last-wins** — if the user holds Up, preload walks the list rather than reloading the same neighbor twice.
4. **Number-key / quick tune:** `Key.Num0..Num9` maps to favorites or the last N channels (setting: "quick tune = favorites"); double-press Back during playback = previous channel (exists in some builds — formalize it).
5. **Zap metrics:** log zap time (start → `PlaybackStarted`) into the ring buffer + a rolling "last 20 zaps avg" shown in the debug overlay. Fail CI-visible reports if avg &gt; 1.5 s.

**Acceptance:** on Firestick 4K, zap 20 channels in one category: average **&lt; 800 ms**, p95 &lt; 1.5 s to first frame; no black flash between zaps; holding Up/Down scrolls the category smoothly; previous-channel on Back works.

### 1.8 FIX #7 — Format fallback chain + playback heartbeat

Port the Electron app's fallback pattern (`upgrade texts/latestfix4112026.txt`) to Android:

1. **Fallback chain per live channel:** direct `server/stream_id.ext` (`.ts`) → HLS `server/live/user/pass/stream_id.m3u8` (if provider exposes it) → transcode endpoint `.../stream_id.ts?transcode=...` → **next server** (providers usually give 2+ servers) → engine cycle (LibVLC→MPV→Media3).
2. **Playback heartbeat:** `PlayerViewModel` watchdog — after `loadMedia`, if no `PlaybackStarted` within **8 s** (LOW_END) / 5 s (other), auto-advance the chain once, showing "Switching to backup stream…" toast. If the chain is exhausted, show the error screen with a **"Try again"** button and the exact failing step (from `EngineError`).
3. **Auto-recovery on mid-play failure:** `EngineHealthMonitor` already failovers engines (`DualMediaEngine.kt:90`); extend it to also try the *format* fallback after the engine fallback, so a mid-stream server dropout recovers without user action. Cap at 2 auto-recoveries per 10 min (avoid loops on a genuinely dead channel).
4. **Server rotation memory:** remember per-channel the working (server, format, engine) tuple in Room (`ChannelPlaybackMemory` table) — next play starts there, and "retry" cycles to alternatives.

**Acceptance:** kill the WiFi for 10 s on a live channel → playback recovers to the backup server/format within \~10 s with one toast; a dead channel shows a clear error with "Try again" that works after the provider fixes it; per-channel memory survives restart (2nd play of the same channel starts instantly).

### 1.9 FIX #8 — Per-channel engine memory & preferences

`DualMediaEngine` has `EnginePreference { AUTO, LIBVLC, MPV }` (`DualMediaEngine.kt:94`). Extend:

1. `ChannelPlaybackMemory` (above) also stores **engine preference** learned from successful playback + manual override.
2. **User override UI:** in the player info popup: "This channel plays best on: Auto / LibVLC / MPV / Media3" — persisted per channel, plus a global default in Settings.
3. Show the **active engine + codec + bitrate + buffer** in the debug overlay (exists as `getDebugInfo()`) — make it a one-button toggle in the player popup (not buried in Settings).

**Acceptance:** user sets "LibVLC" on one problem channel; every future play of that channel uses LibVLC; overlay shows engine/codec/bitrate/buffer with one click.

### 1.10 Player KPI dashboard (the world-class scoreboard)

Track these after every sprint on the Firestick 4K test unit (manual + CI-attached device):

| Metric | Today (est.) | v5.0 target |
| --- | --- | --- |
| Time-to-first-frame (live, warm) | 2–3 s | **≤ 1.5 s** |
| Channel zap (avg) | \~2 s | **≤ 0.8 s** |
| Rebuffer events / 60 min (congested WiFi) | 5–15 | **≤ 2** |
| Max consecutive rebuffer gap | 1–5 s | **≤ 1 s** |
| Force-closes / 100 sessions (live) | — | **0** |
| Engine fallback success | manual | **100 % automated** |
| DVR start → recording confirmed | — | **≤ 3 s** |

---

## PART 2 — PRIORITY #2: PLATFORM PERFORMANCE (2 GB RAM engineering)

Most of this was scoped in the deep dive (A5/B4/C1). This is the **finish line** — what must be true in the v5.0 build:

### 2.1 Memory lockdown checklist (all must be verifiable in the v5.0 APK)

1. `largeHeap="true"` **removed** (`AndroidManifest.xml:66`), replaced by `MemoryBudgetManager` (exists, `data/util/MemoryBudgetManager.kt`) driving every cache budget. Verify with `adb shell dumpsys meminfo` — heap cap should be the normal \~256 MB, not 512 MB.
2. `onTrimMemory`**/**`onLowMemory` **handlers** in `DylandosApp`: trim Coil memory cache → `clearMemoryCache()`; shrink Room connection pool; drop EPG `LruCache`; cancel non-critical workers. **This is mandatory before removing largeHeap.**
3. **Streaming catalog parse final check:** `XtreamRepository.getVodStreams/getSeries` must use the streaming `JsonReader` with `maxItems` cap (deep dive says code done — verify `get_series` too, and that Search/Home full-catalog paths still work against the streaming reader).
4. **Coil discipline everywhere:** grid/row posters pass `size(320x480)` (or `Modifier.size`-bounded) + `crossfade(false)` + `allowHardware(true)` on LOW_END; `Bitmap.Config.RGB_565` for non-hero art; `.memoryCacheKey` includes size. Same for channel logos in `LiveTvScreen` (they're in a scrolling list — most expensive decode site per frame).
5. **Paging tuning:** `pageSize` 20 on LOW_END (keep), 30–40 on MID/HIGH; `initialLoadSize` = 1 page; no `PlaceholderState` on LOW_END.
6. **Room:** `WAL` + indexes on `added`, `name`, `category_id`, `stream_id` (EPG + catalog queries are the hidden jank); `roomCacheMb` respected.
7. **Compose:** no backdrop blur on LOW_END (glass effects behind an `isLowEndDevice` flag — `GlassCard.kt` should have a `LOW_END` variant that's a flat surface); focus scale animations off on LOW_END; avoid `derivedStateOf` churn in 5-column grids.
8. **Startup budget:** cold start to interactive Home ≤ **2.5 s** on Firestick 4K (measured via `AppStartupProfiler`); splash → Home fast-path skips the full catalog warm on boot (defer to first visit of Movies/Series).

### 2.2 APK size (firestick flavor)

`app/src/main/jniLibs` is \~32 MB (MPV + full FFmpeg + LibVLC) → APK \~64 MB. **v5.0 decision: drop MPV from the firestick flavor.** Rationale (from `androidupgraderedo.txt` history): MPV was the historical stability pain; LibVLC + Media3 cover live/VOD/timeshift, and `DualMediaEngine` already falls back MPV→LibVLC→Media3 — removing the middle tier on firestick cuts \~32 MB and a whole native crash class. Premium keeps MPV (HDR + MKV).

- Implementation: move `libmpv.so` + ffmpeg libs out of `src/main/jniLibs` into `src/premium/jniLibs`; firestick keeps `libvlc*.so` only. Remove `libmpv` from `keepDebugSymbols`/`pickFirsts` in the firestick path (`build.gradle.kts:147–161`).
- Target: firestick APK **≤ 40 MB**; verify `bundletool`/`aapt dump badging` in CI.

### 2.3 ANR & jank defense

- Debug builds: `StrictMode` (disk + network on main) — catches regressions in CI debug runs.
- Release: main-thread watchdog — if the UI thread stalls &gt; 2 s, dump the stack to the ring buffer (this is how you'll get the A1-class crash stacks without an SDK).
- `dumpsys gfxinfo` jank% gate in the manual matrix: Live TV grid scroll &lt; 10 % jank on Firestick 4K.

---

## PART 3 — PRIORITY #3: TIMESHIFT — WORLD CLASS

### 3.1 Today's state (three disconnected mechanisms)

| Mechanism | What it does | Where | Gaps |
| --- | --- | --- | --- |
| Provider catch-up | `server/timeshift/user/pass/dur/start/stream.ext` URL from EPG ("Watch from start") | `XtreamRepository.getTimeshiftStreamUrl:501`, `GuideViewModel.buildTimeShiftToken:216`, `GuideScreen.kt:392–398` | Only per-program; no live pause; depends on provider archive |
| LibVLC live timeshift | `:input-timeshift-path=<usb>/live_timeshift` continuous ring while playing live | `PlayerScreen.kt:189–215`, `:312–313` | No seek UI, no timeline, no visible ring state, no jump-to-live, ring size not user-tunable |
| Media3 USB ring | `SimpleCache` + `LeastRecentlyUsedCacheEvictor(512 MB)` | `LazyExoPlayerHost.kt:26–83` | Hard 512 MB cap, no ring hygiene UI, only used when LibVLC timeshift can't be |

**The v5.0 story:** one **Live Timeline** that unifies all three — pause a live channel, scrub back through the ring *and* further back through provider catch-up, jump back to live in one press, and record any window. That is the world-class timeshift.

### 3.2 The Live Timeline spec

1. **Session model:** a `LiveSessionController` (new `player/timeshift/LiveSessionController.kt`) owns: ring index (file-backed), catch-up URL builder, live position, and emits a single `TimeshiftState`:
   - `mode: LIVE | PAUSED | IN_RING | IN_CATCHUP`
   - `ringStartMs`, `liveNowMs`, `positionMs`, `canCatchUpBefore(ringStartMs)`
2. **Ring sizing becomes dynamic:** replace `TIMESHIFT_CACHE_BYTES = 512 MB` hard cap (`LazyExoPlayerHost.kt:27`) with `max(512 MB, min(freeSpace/4, 8 GB))` — at 5 Mbps that's 90 min–3.5 h of rewind. New Settings: "Timeshift window: Auto / 30 / 60 / 120 / 240 min" (Auto = free-space formula). Keep the "USB only" policy (`resolveTimeshiftDirectory`).
3. **Write hygiene for USB flash (critical):** ring writes must be **buffered 4 MB chunks** + `fsync` every 30 s (FAT32 on cheap USB dies under per-packet writes — the current ring can corrupt on unplug). Pre-allocate the ring file at session start (avoids mid-session fragmentation stalls that read as "lag").
4. **Seek UX (10-foot):**
   - Player progress bar while live: red "live" tail, grey ring region, program-boundary ticks from `EpgProgramDao` (EPG already in Room).
   - D-pad Left/Right on the bar: −10 s / +30 s steps with live preview thumbnails (ring-only, cheap); long-press = scrub.
   - **Back = jump to LIVE** (one press, always; double-Back = exit player) — the killer interaction.
   - Pause live → auto-suggest "Record from here" (ties into DVR, Part 4).
5. **Catch-up bridging:** when the user scrubs before `ringStartMs`, seamlessly hand the position to the provider `timeshift` URL (same channel, same offset) — one timeline, no user-visible switch. When catch-up isn't supported, show "Ring only" dimmed region.
6. **Ring persistence:** ring index survives app restart (file-backed cache metadata); reopening the same channel within 24 h restores rewind history. Optionally auto-save the last 30 min as a **recording** ("Keep this replay") with one click.
7. **Playback while scrubbing:** ring seeks are local-file seeks → instant (&lt;200 ms); catch-up seeks start with a small buffer (reuse the Adaptive Buffer Controller).
8. **Now/Next overlay:** while in LIVE mode, show current program + next with countdown (EPG already parsed); tapping Next jumps to the next program's start *within the ring* if already buffered.

**Acceptance (Firestick 4K + USB 3.0 stick):** pause live → resume ≤ 100 ms; rewind to 90 min within the ring ≤ 500 ms to first frame; scrub to pre-ring region bridges to catch-up with one toast; Back returns to live instantly from anywhere; ring survives app restart; unplugging USB mid-ring never corrupts previously written segments; 0 rebuffers while scrubbing inside the ring.

---

## PART 4 — PRIORITY #4: HIGHER-END DVR

You already have the hard part: `RecordingService` (3 concurrent slots, LibVLC `:sout` stream-copy, SAF OTG/USB, write probes) + `ScheduledRecordingWorker` + `DvrRecordingRepository`. v5.0 makes it a **scheduler + manager**, not just a recorder.

### 4.1 EPG-driven recording (the flagship feature)

1. **"Record" on every program in the guide** (`GuideScreen` program dialog): records channel + window `[startMs, endMs]`; stores program title/episode metadata in `DvrRecording` (extend the model — `DvrRecording` already has channelName; add `programTitle`, `episode`, `category`, `poster`).
2. **Series-link:** "Record series" on a program → creates a rule (channel + title-prefix + day-of-week/time slot) evaluated by `ScheduledRecordingWorker` on the daily EPG refresh; auto-enrolls future episodes.
3. **Conflict resolution:** if 2 recordings overlap (the 3-slot limit at `RecordingService.kt:158` — raise to 3 concurrent *streams* but allow unlimited *scheduled* jobs): show a picker at schedule time ("Keep both — use 2nd slot / Skip this episode / Record only episode A"), never silently drop.
4. **Notification center:** per-recording notifications: "Recording started (12:00–13:00)", "Recording complete ✓ — Open / Delete", "Recording failed — reason + Retry". Tapping Open plays the file in the player (`dvr` streamType already routes a URI to the player — `PlayerViewModel.kt:95`).
5. **Storage policy (selectable in Settings):** when free space &lt; 2 GB — (a) stop new recordings with notification, or (b) auto-delete oldest completed recording, or (c) record anyway (danger). Default: (b).
6. **Failure recovery:** transient errors (network, USB hiccup) → automatic retry with backoff (1 min ×3) resuming the recording window; permanent errors → `finishRecordingAsFailed` with the exact reason surfaced in the recordings list (already exists — add retry hook).
7. **Integrity check:** on finalize, compare recorded duration vs scheduled window; flag short recordings ("Recorded 22:14 of 60:00 — storage? signal?") with a "View log" action.
8. **Record from timeshift window** ("Keep this replay", Part 3.2.7): flush the ring tail into the DVR store without re-encoding (stream-copy the already-downloaded bytes) — instant, zero extra bandwidth.

### 4.2 DVR management UI

1. `DvrScreen` tabs: **Scheduled | Recording | Completed | Failed** with per-item Play / Delete / Rename / Retry / "View metadata" (episode, channel, dates, size).
2. Live strip: "Recording to: USB • 12.4 GB free" pinned in the player popup and Home (deep-dive C3.4 — build it).
3. Keep the OTG/thumb-drive two-option flow **exactly as is** (guardrail), plus the v5.0 additions from the deep dive (focus on recommended card, Settings DVR tab summary card) — verify they're landed.
4. SMB target: remains P2 (most users are OTG-only) — keep the placeholder disabled with a "Coming soon" tooltip instead of a dead button.

**Acceptance:** schedule a program from EPG → notification at start → file on USB with correct metadata → notification at end → opens in player; schedule 4 overlapping programs → conflict picker; fill disk → policy (b) deletes oldest and keeps recording; channel dies mid-record → auto-retry then clear failure reason; "Keep this replay" produces a playable file in &lt; 5 s.

---

## PART 5 — PRIORITY #5: CUSTOMIZATION

### 5.1 Menu-key context menu (the #1 TV-UX gap — right-click world)

Fire TV `Key.Menu`/`Key.MediaInfo` (already partially wired: menu toggles favorite on movie cards) becomes a **universal context menu** on every poster, row, and channel:

- **Live channel:** Play / Add to Favorites / Add to Custom List / Timeshift info / Set engine (Auto/VLC/MPV/Media3) / "Record this channel now" / Info (provider + EPG now/next).
- **Movie / Series:** Play / Resume (if watched) / Info (description, cast, rating — `MovieDetailScreen` panel) / Add to Favorites / Add to List / Mark watched / Remove from history.
- **Search result:** same as its media type + "Search similar".
- Implementation: `DylandosFocusSystem` already has `onLongClick` — bind `Key.Menu` in `KeyEventRouter.kt` to open a `DropdownMenu`-style Compose popup anchored to the focused card (focus-safe: D-pad navigable, Back closes).

### 5.2 Favorites everywhere

- ★ badge on every grid card (Live/Movies/Series/Search) with instant toggle (long-press Center).
- **Favorites rail on Home** + a "Favorites first" toggle in Live TV grids (pinned at top with a divider).
- Favorites screen already exists (`FavoritesScreen.kt`, 511 lines) — add: sort (name/recently added), search within favorites, per-list counts.

### 5.3 Home rails (the "I own the app" screen)

Order: **Continue Watching** (`VodResumeDao`/`WatchHistoryEntity` exist) → **Recently Added** (provider `added` timestamp — index it, Part 2.1.6) → **Favorites** → **Custom Lists** (exists) → "Keep watching" live channels (last-tuned, with now/next EPG).

### 5.4 Watch history

- Dedicated screen: full history list with resume positions ("Movie — 42:13"), per-item Play / "Mark watched" / Remove, and **Clear all**.
- "Mark as watched" from context menu (sets resume to end; next open starts fresh).

### 5.5 Appearance & behavior

| Setting | Detail |
| --- | --- |
| Grid density slider | columns 3/4/5 (also a memory lever — fewer columns = fewer decodes on LOW_END) |
| Grid vs. list toggle | per section (Live/Movies/Series) |
| Accent theme | exists — extend to per-screen tinting |
| Overscan safe padding | global content-margin (0–60 px) for clipped TVs |
| Reduced motion / Reduced effects | kills focus-scale + blur on LOW_END |
| Player defaults | default buffer mode (Adaptive/Balanced/Low-latency), default engine per content type, subtitle defaults (size/color/bg/encoding — keys exist), audio boost (0–200), default aspect ratio |
| Auto-play | "Auto-play next episode" (series) with 10 s countdown |
| Export / Import | favorites + custom lists + settings + history as one JSON (backup/migration) |

**Acceptance:** from any grid, Menu on an item opens a navigable context menu with Play/Info/Favorite/Add-to-list; star badges toggle instantly everywhere; Home shows Continue/Recently Added/Favorites rails; all state survives reboot; JSON export → wipe → import restores everything.

---

## PART 6 — PRIORITY #6: WORLD-CLASS TV UI

### 6.1 10-foot polish pass

1. **Focus ring:** consistent 3 px high-contrast ring (white + accent double ring on bright backdrops) across all screens; verify on a bright sports channel backdrop (current ring can vanish on white).
2. **Glass cards:** `GlassCard.kt` gets a LOW_END flat variant (no blur) — same layout, 0 blur cost.
3. **Splash → Home:** keep splash &lt; 1 s; add subtle scale-in, no long fades on LOW_END.
4. **Sidebar:** exists (`NavigationSidebar.kt`) — add section badges (recording count, pending updates) and focus dimming of inactive icons.

### 6.2 Player UI (the screen people stare at)

1. Modern controls bar: title + channel logo, now/next EPG line, **Live Timeline** (Part 3), buttons for Audio / Subtitle / Engine / Info / PiP (exists) / Record, auto-hide 4 s (D-pad wakes it).
2. Buffering state: replace spinner with "Buffering… 45 %" + step label ("Switching to backup stream…") + always-available "Exit" (no more 30 s hostage).
3. **Subtitle picker:** `SubtitlePickerSheet.kt` exists — ensure it's reachable from the Menu-key popup and shows track names + languages (not just cycle).
4. Audio track picker + volume/boost slider + playback speed (exists in engine interface: `setPlaybackSpeed`, `setVolume`).
5. Debug overlay: engine/codec/bitrate/buffer/dropped-frames — one button, in the popup.

### 6.3 EPG screen

- `EpgCanvasGrid`/`EpgTimelineGrid` — profile: only compose visible programs (canvas drawing already efficient — verify no per-program composables on scroll).
- Add: **now line** (red, always visible), channel jump (type-ahead), program search (title filter), "Record" affordance per program (Part 4.1.1), and "Watch from start" per program (exists — keep).
- Group by genre with collapsible sections (LOW_END: cap visible channels at \~40 with a "load more").

### 6.4 Input & accessibility

1. **On-screen keyboard** for Search (Fire OS has no keyboard): letter grid, D-pad focus, predictive row from history; voice search hook for Appstore later.
2. `contentDescription` audit on icon-only buttons (icons-only today per deep dive C4.5).
3. 125 % text scale pass; focus indicator contrast verified on bright backdrops.
4. Localization: keep `resourceConfigurations = ["en", "xxhdpi"]` for v5.0 (APK size); prep strings for es/pt/fr/de extraction in a `values-es` etc. when the Appstore build happens.

**Acceptance:** a fresh user can navigate the entire app with only a D-pad and Menu key; no screen is unreachable by focus; search works without a keyboard; every icon has a spoken label; bright-backdrop screens keep a visible focus ring.

---

## PART 7 — SHIP PIPELINE (without this, v5.0 never reaches a stick)

### 7.1 🔴 Do TODAY (blocking)

1. **OTA gist is still stale:** the live gist `6056e65b41641393abf585cecbd92d07` advertises v4.6.3/62; repo is 4.10.5/85. Publish `versionCode 86`, `versionName "5.0.0-WORLDCLASS"`, real APK URLs (GitHub Releases assets — kill Dropbox), and **mandatory SHA-256** (fail-closed already implemented in `UpdateChecker` — verify and keep). Add `minVersionCode` guard.
2. **Signing secrets:** `DYLANDOS_RELEASE_STORE_PASSWORD` / `DYLANDOS_RELEASE_KEY_PASSWORD` / `GIST_TOKEN` must exist in GitHub secrets for `.github/workflows/android-release.yml` (exists — wire it and do a trial tag).
3. **Crashlytics (or Sentry):** set the DSN (`build.gradle.kts:39–43` already supports it), enable breadcrumbs for login/catalog/engine-switch/recording/OTA. First week of v5.0 beta = real crash data on Fire OS 5–8.

### 7.2 CI & tests

- `.github/workflows/android-release.yml` (exists) on tag `v*`: `test` → `assembleFirestickRelease assemblePremiumRelease` → sign → GitHub Release → compute SHA-256 → auto-update gist.
- **JVM unit tests** for: `UpdateChecker` (incl. blank SHA-256, downgrade guard), `XtreamRepository` URL building (live/vod/series/timeshift escaping), `XmltvParser`, `SeriesInfoHelpers`, `CategorySort`, `StorageDetector`, `MemoryBudgetManager` tiers, new `LiveBufferController` math, new `LiveSessionController` ring math.
- **D-pad focus regression tests** (A2/A4 class): inject keys, assert focus lands on the grid after category change.
- **Smoke test** (emulator or ARM tablet): cold start → login → live 30 s → movies → series → DVR picker → no crash.
- Manual matrix per release: Stick 4K (2nd gen), 4K Max, Stick Lite, Cube 3rd gen; Fire OS 5/6/7/8; 2 GB + 4 GB; provider with 50k+ catalog; provider HTTPS-only.

### 7.3 Versioning

- v5.0.0 = this release (versionCode 86). Feature flags in `BuildConfig` for anything half-done (timeshift timeline behind `TIMESHIFT_V2` until acceptance passes) so you can ship incrementally.

---

## PART 8 — THE 30/60/90 PLAN

### Days 1–14 (P0 — player performance + trust) 🎯 *the lag fix ships here*

1. FIX #2 RTSP/HTTP hardening + FIX #4 audio defaults + FIX #5 log silence (small, high-value commit #1).
2. FIX #1 Adaptive Buffer Controller (core loop + settings + acceptance).
3. FIX #6 zap preload/engine-reuse (biggest UX swing; wire into `DualMediaEngine`).
4. OTA gist publish + signing secrets + Crashlytics DSN (7.1).
5. Memory lockdown pass 2.1 — remove `largeHeap` only after `onTrimMemory` lands.

**Gate:** live 60-min soak on Stick 4K (congested WiFi) ≤ 2 rebuffers; zap avg ≤ 0.8 s; first beta APK in the gist with working SHA-256.

### Days 15–45 (P1 — timeshift world-class + DVR)

 6. Part 3 Live Timeline: ring controller + dynamic sizing + seek UX + jump-to-live + catch-up bridge.
 7. Part 4: EPG record + notifications + storage policy + "Keep this replay".
 8. Part 5.1 Menu-key context menus + star badges + Home rails.
 9. Part 2.2 APK trim (drop MPV on firestick → ≤40 MB).
10. Tests for everything above (unit first, focus regression for the new popups).

**Gate:** timeshift acceptance (Part 3) passes on hardware; DVR EPG record acceptance passes; APK ≤ 40 MB; beta 2 + beta 3 in the gist.

### Days 46–90 (P2 — polish & professionalize)

11. Part 6 UI pass (focus contrast, overscan, on-screen keyboard, EPG polish, accessibility audit).
12. Series-link DVR + conflict picker + recordings manager polish.
13. Export/import JSON + watch history screen + grid density.
14. Full CI green, manual matrix complete, diagnostics export, Amazon Appstore submission kit (content rating + privacy policy + banner exists in `resources/`).

**Gate:** 90-day scorecard green (Part 1.10 table); zero open P0; Appstore package ready.

---

## Appendix A — file map (every change's home)

| Item | Primary files |
| --- | --- |
| Adaptive buffer / live options | `ui/screens/player/PlayerScreen.kt` (126–187), `player/LibVlcFactory.kt`, **new** `player/engine/LiveBufferController.kt`, `ui/screens/settings/SettingsViewModel.kt` |
| RTSP/HTTP hardening | `PlayerScreen.kt`, `LibVlcFactory.kt`, `service/RecordingService.kt` (`createRecordingLibVlc:214`) |
| Audio pipeline | `PlayerScreen.kt:153–154`, `SettingsViewModel.kt:158`, `MpvEngineFactory.kt` |
| Zap preload / engine reuse | `ui/screens/player/PlayerViewModel.kt` (48–51, 602–630), `ui/screens/livetv/LiveTvViewModel.kt` (176–179), `player/engine/DualMediaEngine.kt` |
| Fallback chain + heartbeat | `PlayerViewModel.kt`, `player/engine/DualMediaEngine.kt` (90–102), **new** `data/repository/ChannelPlaybackMemoryRepository.kt` |
| Per-channel engine memory | `DualMediaEngine.kt` (93–102), **new** `ChannelPlaybackMemory` Room entity, player popup |
| Memory lockdown | `AndroidManifest.xml` (66–67), `DylandosApp.kt`, `data/util/MemoryBudgetManager.kt`, `data/network/XtreamRepository.kt` (528, 628), all grid composables (`MoviesScreen.kt`, `SeriesScreen.kt`, `LiveTvScreen.kt`, `HomeScreen.kt`) |
| APK trim | `build.gradle.kts` (46–48, 147–161), `src/main/jniLibs` → `src/premium/jniLibs` |
| Timeshift v2 | **new** `player/timeshift/LiveSessionController.kt`, `ui/screens/player/LazyExoPlayerHost.kt` (26–83), `PlayerScreen.kt` (189–215, 292–313), `XtreamRepository.kt:501`, `data/db/dao/EpgProgramDao.kt` |
| DVR scheduler | `service/RecordingService.kt`, `workers/ScheduledRecordingWorker.kt`, `data/repository/DvrRecordingRepository.kt`, `ui/screens/dvr/DvrScreen.kt` + `RecordingDetailScreen.kt`, `ui/screens/guide/GuideScreen.kt` |
| Context menus / stars / rails | `ui/focus/KeyEventRouter.kt`, `ui/components/PosterCard.kt`, `ChannelCard.kt`, `ui/screens/home/HomeScreen.kt`, `ui/screens/favorites/FavoritesScreen.kt`, `ui/screens/customlists/CustomListsScreen.kt` |
| Player UI | `ui/screens/player/PlayerScreen.kt`, `player/subtitle/SubtitlePickerSheet.kt`, `player/subtitle/LibVlcSubtitleManager.kt` |
| EPG UI | `ui/screens/guide/EpgCanvasGrid.kt`, `EpgTimelineGrid.kt`, `GuideScreen.kt`, `GuideViewModel.kt` |
| OTA / CI / observability | `ui/update/UpdateChecker.kt`, `UpdateViewModel.kt`, `.github/workflows/android-release.yml`, `DylandosApp.kt` (66–77) |

## Appendix B — v5.0 ship checklist (tick before tagging `v5.0.0`)

- [ ] Live soak 60 min × 3 channels: ≤ 2 rebuffers, 0 force-closes (1.10)

- [ ] Zap avg ≤ 0.8 s on Stick 4K (1.7)

- [ ] RTSP channel 60 min zero freezes (1.3)

- [ ] Timeshift acceptance green (Part 3) incl. ring persistence + USB unplug test

- [ ] DVR EPG-record + notification + conflict + storage-policy acceptance green (Part 4)

- [x] `largeHeap` removed + `onTrimMemory` in place (2.1) — 50k-catalog cold-start no-kill **device verification still pending**
- [x] Adaptive Buffer Controller + dynamic ring sizing code landed, unit tests green 29/29 (1.2/1.3/3.2)
- [x] RTSP/HTTP hardening (`--rtsp-tcp`, `--http-continuous`), AAudio option, debug-gated verbose (1.3/1.5/1.6)
- [x] `LiveBufferController` wired into `PlayerScreen` (poll loop + `cacheMs.value` at all 3 live `buildMedia` sites + recording leg) + Performance-tab UI (1.2)
- [x] EPG time fix: `EpgTimeAligner` flag-only + align removed from Room paths + data-version refetch (the "~4 h ahead" bug) — 8/8 tests
- [x] Signed-release one-click builder `build_signed_apk.bat` + versionCode 86 / 5.0.0

- [ ] Firestick APK ≤ 40 MB (2.2)

- [ ] OTA gist live with v5.0.0/86 + SHA-256 + minVersionCode (7.1)

- [ ] Crashlytics receiving events from beta (7.1)

- [ ] CI green: unit tests + focus regression + smoke on emulator (7.2) — new unit tests ready in `app/src/test/.../player/`

- [ ] Manual matrix: Fire OS 5/6/7/8 × 2 GB/4 GB (7.2)

- [ ] Menu-key context menu reachable from every grid (5.1)

- [ ] Accessibility: all icons labeled, 125 % text, focus ring on bright backdrops (6.4)