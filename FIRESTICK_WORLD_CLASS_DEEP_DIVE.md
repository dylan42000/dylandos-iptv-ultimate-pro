# DYLANDOS IPTV ULTIMATE — Firestick (Android) Deep Dive

### Bug Fixes & Upgrades Roadmap to World-Class

> **Scope:** The **firestick** product flavor only (`app-firestick-release.apk`, armeabi-v7a, Fire TV Stick 4K-class hardware: 2 GB RAM, 32-bit ARM, Fire OS 5–8). **Baseline verified in repo:** `versionName 4.10.5`, `versionCode 85` (`android/app/build.gradle.kts`). **Baseline verified live (fetched 2026-08-06):** the OTA gist `6056e65b41641393abf585cecbd92d07` still advertises **v4.6.3 / versionCode 62** — more on that below, it's the #1 operational bug. **Reading guide:** Part A = the 7 issues you filed, Part B = bugs found in the audit, Part C = world-class upgrades, Part D = prioritized 90-day plan.
> ****✅ Implementation status (2026-08-06):**
>
> - **B1/B2 (OTA integrity) - CODE DONE, LIVE GIST STILL STALE:** `UpdateChecker.parseUpdateJson` now fail-closes on blank SHA-256 (refuses unverified payloads); `UpdateViewModel.verifySha256` is mandatory (never skips a missing hash). The **live gist still advertises v4.6.3/62** - paste real values from `GIST INFO.txt` (now a publish template, v4.10.5/85) into gist `6056e65b41641393abf585cecbd92d07` before shipping.
> - **A1 (Settings crash) - DONE:** composition crash boundary around all 13 tabs + `SettingsTabErrorCard` fallback (a tab can no longer kill the app to the Firestick home screen). Also fixed stale About strings (Hilt 2.57.2, target SDK 35).
> - **A5 (OOM / force-close) - DONE:** `XtreamRepository.getVodStreams/getSeries` take a `maxItems` cap; the streaming `JsonReader` stops parsing early, and capped browse fetches bypass the shared cache (Home stats / Search still see the full catalog). Movies/Series ViewModels pass 2x display-cap headroom before their existing filter+take.
> - **A2/A4 (focus stuck) - DONE:** the category→grid handoff flag is no longer consumed when `itemCount==0` mid-load (the root cause of "works once, then stuck"), plus a 400 ms last-chance retry after the 3 s window. Series: episode focus uses a 2.5 s retry loop instead of a 32 ms one-shot, and pressing OK on a season now hands focus to its episodes.
> - **A6 (DVR) - DONE:** auto-prefer USB/OTG over a persisted internal path (SAF stays user-locked), picker parks D-pad focus on the recommended card after scan.
> - **B3/B4/B5 - DONE:** guarded Sentry crash reporting (enabled only when `SENTRY_DSN` is set), `onTrimMemory` clears Coil caches, `allowBackup=false`.
> - **B6 - DONE (needs a build):** AGP 8.7.3→8.9.1, Gradle 8.9→8.11.1, removed `suppressUnsupportedCompileSdk`.
> - **C6 - DONE:** `UpdateChecker.parseUpdateJson` is `internal` + 3 JVM test files (OTA parsing incl. fail-closed SHA-256, series season derivation, category sort).
> - **C7 - DONE:** `.github/workflows/android-release.yml` (tests on PR; tag pushes build+sign both flavors, create GitHub Release, compute SHA-256, auto-update the OTA gist via `.github/scripts/publish_update_json.py`). Needs `DYLANDOS_RELEASE_STORE_PASSWORD`, `DYLANDOS_RELEASE_KEY_PASSWORD`, `GIST_TOKEN` secrets.
> - **A7 - PARTIAL:** Menu-key already toggles favorite on movie cards; a full Play/Info/Add-to-list context dialog remains a P2 enhancement.
> - **Not done (documented only):** C2 player pass, C3 EPG-record DVR, C4 overscan/keyboard, B10 APK-size trim, live gist still needs real hashes + URLs.

---

## TL;DR — the 10 moves that matter most

| \# | Move | Why | Effort |
| --- | --- | --- | --- |
| 1 | **Fix the OTA channel** (stale gist + blank SHA-256 + Dropbox link rot) | Users are stuck on old builds; Firestick updates install with **no integrity check** today | S |
| 2 | **Add cloud crash reporting** (Firebase Crashlytics or Sentry) | Issues #1 and #5 are crashes we cannot diagnose without telemetry; today crashes go to a local `crash_log.txt` you never see | S |
| 3 | **Kill the "overwhelmed" force-close** (catalog load OOM) with streaming JSON + load throttling | The #1 user-facing complaint, Firestick-specific | M |
| 4 | **Add automated tests** (JVM unit + Compose UI) | Zero tests today (`app/src/test` and `app/src/androidTest` don't exist) — every change risks regressions | M |
| 5 | **GitHub Actions CI** that builds both flavors, signs, and publishes OTA metadata automatically | Replaces manual `build_android.bat` + hand-edited gist | M |
| 6 | Fix remaining D-pad focus escapes (Settings right-edge, Series seasons, Movies category rail) | Your top-3 filed complaints; partially mitigated, not closed | M |
| 7 | Remove `android:largeHeap="true"` + memory leak hardening | On 2 GB devices largeHeap masks leaks and raises LMK pressure | S–M |
| 8 | Harden update install: **mandatory SHA-256**, HTTPS hosting on GitHub Releases | Replaces Dropbox link rot + unverified installs | S |
| 9 | Player upgrade pass: audio/subtitle track UX, channel zapping, timeshift pause, resume everywhere | The differentiator for "world-class IPTV player" | L |
| 10 | Amazon Appstore submission checklist (banner, content rating, privacy policy) | Distribution channel Fire TV users actually trust | S |

**Guardrail (from your notes, don't regress):** the DVR "OTG or thumb drive" two-option flow in Settings — `DvrStoragePickerScreen` + `DvrStorageViewModel` — works; all changes below preserve it.

---

## PART A — The 7 issues you filed, with code-grounded root causes

### A1. Settings: scrolling right from Categories to the end closes the app

**Status:** partially mitigated, not closed. The tab row already has edge-guards (`SettingsScreen.kt:151–203`): the Back button is non-focusable, the last tab's Right/Down hand focus to content, and an `onPreviewKeyEvent` (lines 103–126) intercepts L/R near the end. There's also a documented prior crash here — `SettingsScreen.kt:3115` comment: *"UnsatisfiedLinkError … was killing the process when scrolling to the About tab on Firestick"* (native LibVLC probe in About).

**Root causes still possible:**

- A native `UnsatisfiedLinkError`/`NoClassDefFoundError` class of crash when tabs that touch LibVLC/native stacks recompose (About previously; check Performance/DVR tabs too). These are `Error`s, not `Exception`s — many `runCatching` blocks don't catch them.
- Focus escaping the content group at the right edge of a tab's `LazyColumn`/`LazyRow` and landing on a non-visible element, triggering a back/`finish` path.

**Fixes (P0):**

1. **Add crash reporting first** (Part C.8) so the next occurrence gives us the exact stack instead of a guess. In parallel, pull `adb logcat -b crash` / the app's `crash_log.txt` (written by `FieldTelemetry.recordCrash`, `DylandosApp.kt:70`) from a user's device.
2. Wrap the whole Settings `when(selectedTab)` content in a `runCatching`-style boundary that logs and shows a "Settings error" card instead of dying (defensive, not a replacement for fixing the root cause).
3. Ensure every tab content root is wrapped in `dylandosFocusGroup(trapExit = true)` with `left = FocusRequester.Cancel` on the first focusable and `right = Cancel` on the last, so a tab can never hand focus past its own content box.
4. Never call native `LibVLC(ctx, …)` during composition or on the main thread in any tab — move to `remember { }` + try/catch(Throwable) and `Dispatchers.IO` (About already hardcodes the version string; do the same anywhere else that probes the library).

**Acceptance:** on a Firestick, cycle every tab with Right from Categories → About repeatedly; app never exits, focus wraps or parks on the last item.

---

### A2. Movies & Series: focus gets stuck in the category rail after the first category change

**Status:** heavily worked already. `MoviesScreen.kt:115–217` and `SeriesScreen.kt:118–251` implement a full focus state machine: `categoryFR`/`gridEntryFR`, retry-until-success loops (2.5–3 s deadlines), and left-edge cards that hand focus back to the rail. The reported symptom ("works the first time, then you get stuck") smells like one of:

- **Stale** `FocusRequester` — requesters are `remember { }` at screen scope, but when the active category changes the grid items recompose/re-key; a requester attached to a removed node silently fails, and the retry loop only covers the *first* entry.
- `initialFocusDone` **latch** (`SeriesScreen.kt:134`) — once true it never re-arms on category change; if the retry loop misfires once, focus is stranded in the rail with no re-trigger.
- **The 25 s catalog load** (`LOAD_TIMEOUT_MS` in both ViewModels) — the grid is empty while loading, so `gridEntryFR.requestFocus()` targets a not-yet-composed node; when data lands, nobody re-requests.

**Fixes (P0):**

1. Re-arm focus on **data change**, not just first composition: `LaunchedEffect(uiState.itemsGeneration, gridItemCount)` → if focus is still in the rail after N ms and the grid now has items, `gridEntryFR.requestFocus()` again.
2. Replace boolean latches with counter-based triggers (SeriesScreen already uses a tick counter for episodes — apply the same pattern to the rail/grid hand-off).
3. Bind each grid item's `FocusRequester` to its item key (not `index`), and null it out when the item leaves composition (`Modifier.focusRequester` with `remember(itemId)`).
4. Add a "press Down from rail always moves to grid" `onKeyEvent` on the rail container itself as a hard fallback, so the user can never be truly stuck (currently `Down` is only handled inside focused cards).

**Acceptance:** change category 10× in a row on a Firestick; every time the grid takes focus after ≤1 press; Left on grid returns to the rail; Down on rail always enters the grid.

---

### A3. Search: non-live results should open media info (description, actors, rating)

**Status:** mostly implemented already. `SearchScreen.kt:240–270` navigates Movies → `MovieDetail` and Series → `SeriesDetail`; `MovieDetailScreen.kt:231–303` renders rating, year, genre, duration, MPAA, plot, director, and cast. So the wiring exists — the gaps are:

- **SeriesDetail** (`SeriesDetailScreen.kt`, 87 lines) relies on Xtream's own `get_series_info` (`plot`, `cast`, `genre`, `director`, `rating` come from the provider and are often empty). Movies get **TMDB enrichment** (`TmdbApiService`), Series do not.
- If a user's provider has no info, MovieDetail falls back to whatever the list item carried — which can be blank.
- From search, tapping a **series** goes to SeriesDetail, but if the series was opened before, it shares the parent `SeriesViewModel` and can show the popup-in-grid state instead of a clean detail page.

**Fixes (P1):**

1. Add TMDB enrichment for series: `searchTv` + `tv/{id}` in `TmdbApiService`, merge `overview`, `vote_average`, `first_air_date`, `credits` into the series detail when provider fields are blank (cache in Room so it's one-time).
2. Always show a metadata panel in `SeriesDetailPopup` (the full-screen variant) mirroring MovieDetail's layout — plot/cast/director/rating badges.
3. Ensure `viewModel.setAutoOpenSeries(s)` + `prepareMovieDetail(movie)` stash the full hit in the ViewModel so detail never depends on the catalog cache being warm (already done for movies; verify series path).

**Acceptance:** searching any movie/series shows a full info panel with description, cast, director, rating within \~2 s; works offline-of-provider once cached.

---

### A4. Series: can't reach episodes after choosing a season; focus stuck on seasons

**Status:** heavily worked (`SeriesScreen.kt:1331–1400`): per-season `FocusRequester`s, a tick-based `firstEpisodeFr` re-launch, and a 2.5 s retry loop. The residual symptom ("first item is seasons and I can't get focus unless I press Center to arrow to episodes") matches a **focus-timing race**: the episode `LazyColumn` for the newly selected season composes *after* `firstEpisodeFr.requestFocus()` fires, and the `wantEpisodeFocusTick` effect only delays 32 ms.

**Fixes (P0):**

1. Replace the fixed 32 ms delay with a **composition-aware retry** like the seasons loop: poll `firstEpisodeFr.requestFocus()` every 50 ms until it returns true or the column reports `itemCount > 0`, with a 3 s deadline.
2. On season change, first park focus on the season chip (which is already composed), *then* move to the first episode — never request the episode requester directly from a season-change click.
3. Give every episode row a stable `key = "ep_${id}"` and attach its `FocusRequester` to that key so refocus after scroll works.

**Acceptance:** open a 5-season series, switch seasons via D-pad 10×, land on episode 1 every time without a Center press; resume card also reachable.

---

### A5. Movies/Series: "loads for 30 seconds then gets overwhelmed and force-closes"

**Status:** mitigated, not solved. There's a real memory subsystem already: `MemoryBudgetManager` (8% heap → 16–32 MB Coil cache on low-end), Paging 3 (page size 20), caps `MAX_ALL_ITEMS = 200` / `MAX_CATEGORY_ITEMS = 300`, 25 s `LOAD_TIMEOUT_MS`, and `largeHeap="true"` in the manifest. Yet the user still sees 30 s + kill on Firestick-class devices. Likely remaining causes, in order:

1. **Full-list materialization**: `XtreamRepository.getVodStreams()`/`getSeries()` (`XtreamRepository.kt:528, 628`) parse the *entire* provider JSON (often 10k–100k movies) into a `List` with Gson **before** the `.take(200/300)` cap is applied. On a 2 GB stick that's a multi-hundred-MB transient spike → LMK kill (the "overwhelmed" symptom). The 25 s timeout then can't even fire because Gson + allocation churn stalls the main thread → ANR.
2. **First-load burst**: entering Movies fires a full catalog sync; Paging `initialLoadSize = 20` helps the grid, but the category-population pass (`persistCategory`, `SeriesViewModel.kt:339`) still walks everything.
3. **Coil decode spikes**: a 5-column grid of full-resolution `stream_icon` posters (often 1080p originals) decoded to `Bitmap.Config.ARGB_8888` during fast scroll → jank + OOM. Caches are sized, but per-image decode size isn't capped at the call sites.

**Fixes (P0 — this is the big one):**

1. **Stream the JSON, don't materialize it.** Replace Gson full-object parse for `get_vod_streams`/`get_series` with a streaming parser (kotlinx-serialization `Json.decodeToSequence` / `JsonReader`) that yields items one at a time; stop after the cap (e.g. 250) is reached. This alone eliminates the transient spike. Keep Gson for small payloads (EPG, auth).
2. **Cap image decode size at the source**: pass `Modifier.size()`-bounded requests or Coil `size(320x480)` in the grid/row item composables (`MoviesScreen` poster cards, `LiveTvScreen` channel rows, `SeriesScreen` cards) — thumbnails never need full-res bitmaps. Add `.memoryCacheKey` including the size.
3. **Stagger the initial load**: load categories first, then page the first category; don't sync all categories at once. Add a "syncing…" progress with a **cancel** action (currently a 30 s uncancellable wait).
4. **Remove** `largeHeap` after 1–3 (see B4) — a bounded heap surfaces leaks instead of letting them silently grow to the LMK threshold.
5. Keep `LOAD_TIMEOUT_MS` but apply it *inside* the streaming parse (per-chunk), so a stalled provider cancels in seconds, not 30.
6. Add `onLowMemory()`/`onTrimMemory()` in `DylandosApp` to trim Coil's memory cache + LruCaches (Room, EPG) when the OS asks.

**Acceptance:** cold-launch into a 50k-movie provider on Firestick 4K: first grid frame &lt; 3 s, no kill, no ANR, memory delta under budget; scroll a 1k-item category without stutter.

---

### A6. DVR: selecting OTG auto-focuses internal drive; want auto-save + storage info on selection

**Status:** mostly implemented. `DvrStorageViewModel.selectOption()` persists to DataStore immediately (`DvrStorageViewModel.kt:115–118`), `autoSelectBestStorage` prefers the persisted choice or OTG (lines 120–133), and each card already shows free space + ACTIVE/RECOMMENDED badges (`DvrStoragePickerScreen.kt:155–198`).

**Residual gaps:**

- **No explicit initial focus** on the recommended/OTG card — Compose falls back to whatever is focusable first; on some FireOS versions that lands on Internal, which reads as "auto-focused on internal".
- The picker is a *screen*; from Settings the DVR tab shows the **stored** value, but the user wants the choice + space summary visible right in the Settings DVR tab, not only in the picker.
- `autoSelectBestStorage` will happily keep an internal path if it was persisted — but the user wants USB to win when present. Current code deliberately doesn't override (comment at :124). For this user, **auto-switch to OTG when detected**, with a toast.

**Fixes (P1):**

1. After scan completes, `LaunchedEffect(options)` → `requestFocus()` on the card matching `selectedOption` (or the first OTG card).
2. In the Settings **DVR tab**, render the active recording target as a card: name, badge (USB/Internal/SAF), free/total GB, "Change" button that opens the picker — so selection + space is visible without leaving Settings.
3. Change `autoSelectBestStorage` priority: persisted → **OTG/external (always preferred when writable and free &gt; 500 MB)** → internal → SAF. Keep the persisted path only when USB is absent. Show a "Recording to internal (USB not detected)" notice when falling back.
4. Write a write-probe (create/write/fsync/delete) before committing a target and surface failures (`STORAGE_NOT_WRITABLE` style), per the v4.7.0 spec already in `androidupgraderedo.txt`.

**Acceptance:** plug OTG → open Settings → DVR tab shows USB drive, free space, ACTIVE badge, and focus is on it; selecting any target persists instantly; unplugging USB falls back visibly to internal with a warning.

---

### A7. More customization: favorites per channel/movie/series, richer options

**Status:** favorites exist (`FavoritesScreen.kt` 511 lines; toggles in Search; `FavoriteDao`), plus Custom Lists (`CustomListsScreen.kt` 627 lines) and theme/accent customization. What's missing for "world-class" is **per-surface affordances**:

**Fixes (P1/P2):**

1. **Menu-key (Fire TV** `Key.Menu`**/**`Key.MediaInfo`**) context menu on every poster/row**: Add to Favorites, Add to List, Play, Info, Resume (if watched). This is the single highest-value TV-UX addition — right-click equivalent.
2. Favorites: a **★ badge on every grid card** (Movies/Series/Live/Search) with instant toggle (long-press on remote Center = favorite, matching `dylandosFocusable`'s `onLongClick`).
3. **Home screen rails**: Continue Watching (already have `VodResumeDao`/`WatchHistoryEntity`), Recently Added (provider `added` timestamp), Favorites, per-custom-list rails.
4. Watch history screen (view/clear), resume-per-episode for Series (exists — surface it), "Mark as watched".
5. Appearance: poster size/density slider for the grid (fewer columns = fewer decodes = also a memory lever), grid vs. list toggle, per-screen accent tinting.
6. Custom Lists: rename/delete/duplicate, reorder items, "Add all in category to list", export/import (JSON) for backup.

**Acceptance:** from any grid, Menu key on an item offers Play/Info/Favorite/Add-to-list; Home has Continue/Recently Added/Favorites rails; all state survives reboot.

---

## PART B — Bugs found in the audit (not on your list)

### B1. 🔴 OTA channel is stale — users can't get updates (verified live)

I fetched the live gist `6056e65b41641393abf585cecbd92d07` on 2026-08-06: it advertises **versionCode 62 / v4.6.3**, while the app in this repo is **versionCode 85 / v4.10.5**. The repo's `GIST INFO.txt` is even older (v2.4.1 / code 26).

**Consequences:**

- Users on 62–84 are told "no update" forever — the last \~6 releases never reached them.
- Users below 62 would be **offered a downgrade** to 4.6.3 (checker only compares `remote > local`, `UpdateChecker.kt:92`).
- The 4.6.3 Dropbox links may already be dead (link rot).

**Fix (P0, do today):** bump the gist to `versionCode: 85`+ with the real APK URLs and changelog, or better — move to the automated pipeline in C.7 (GitHub Releases + computed SHA-256 + gist auto-update). Add a `minVersionCode` guard that refuses to offer any update below the current minimum.

### B2. 🔴 Firestick OTA installs are unverified (SHA-256 skipped)

`UpdateViewModel.verifySha256IfProvided()` (`UpdateViewModel.kt:275`) **returns true when the hash is blank** — and the live gist's `flavors.firestick.sha256` is `""`. So every Firestick OTA install today happens with zero integrity check (the premium flavor at least has a hash). For a sideloaded app that already holds provider credentials and `REQUEST_INSTALL_PACKAGES`, a tampered update is a code-execution vector.

**Fix (P0):** make SHA-256 **mandatory** (refuse the update when missing), verify before `installApk`, and only then offer install. With the CI pipeline, hashes are computed automatically so there's no burden.

### B3. 🟠 No crash reporting — you're flying blind on A1/A5

There is a good *local* handler (`DylandosApp.kt:66–77`: logs + `FieldTelemetry.recordCrash` to `crash_log.txt`, keeps background threads alive). But nothing is sent to you. Every "closes to the Firestick home screen" is a guessing game.

**Fix (P1):** Firebase Crashlytics (free, tiny, Play/Appstore-compatible) or Sentry; keep the local handler for background-thread policy. Crashlytics gives you the exact stack for A1/A5 within days of shipping.

### B4. 🟠 `android:largeHeap="true"` + `android:hardwareAccelerated` + no `onTrimMemory`

`AndroidManifest.xml:66–67`. `largeHeap` lets the process grow beyond the normal heap cap (Fire TV sticks already have a low LMK threshold); combined with no `onTrimMemory` handler, the app can grow until the kernel kills it — literally the "overwhelmed" symptom. Remove `largeHeap` once A5 streaming lands; add `onTrimMemory`/`onLowMemory` to shrink Coil cache + DAO caches first.

### B5. 🟠 `android:allowBackup="true"` with plaintext Xtream credentials

`AndroidManifest.xml:59`. DataStore holds server URL + username/password; backups (Google/ADB) would copy them. For a sideloaded Fire TV app it's low-risk, but if you ever ship on Amazon Appstore with cloud backup, it's a leak. Set `allowBackup="false"` or add `android:fullBackupContent` / `dataExtractionRules` excluding the DataStore file.

### B6. 🟠 compileSdk 36 on AGP 8.7.3 is out-of-band

`build.gradle.kts` uses compileSdk 36 + `android.suppressUnsupportedCompileSdk=36` (`gradle.properties`) + targetSdk 35. AGP 8.7.3 formally supports up to compileSdk 35; you're suppressing a warning, not getting official API-36 support. Upgrade AGP → 8.9.x (or 8.10) + matching Gradle → 8.11.x, keep targetSdk 35 (Android 15 rules: edge-to-edge, FGS). Also Kotlin 2.2.10 + KSP 2.2.10-2.0.2 is fine, but bump Compose BOM (2024.12.01) → 2025.x for focus/perf fixes — **carefully**, with A2/A4 as regression tests.

### B7. 🟡 Stale/misleading strings

- `SettingsScreen.kt:3154` AboutTab hardcodes "Hilt 2.51.1" but the project uses 2.57.2; `:3152` says "Android 14 (API 34)" but targetSdk is 35. Feed these from `BuildConfig`/`Build.VERSION`.
- Repo root `GIST INFO.txt` and `android/ota-update-*.json` (20+ files) are stale snapshots of the OTA payload — rename to `docs/` with a "historical" note so nobody edits them by mistake (the live gist is the source of truth).

### B8. 🟡 `network_security_config` trusts **user-installed CAs**

`res/xml/network_security_config.xml:22` — needed for self-signed provider certs, but it also defeats certificate pinning on any domain and makes MITM trivial on rooted devices. Acceptable for IPTV; document it, and consider scoping cleartext per-provider-host (can't do statically since hosts are user-entered — but at least keep `usesCleartextTraffic` + NSC consistent: currently both say "allow all", fine, just know it).

### B9. 🟡 No tests exist

`app/src/test` and `app/src/androidTest` are absent (only junit/espresso deps in `build.gradle.kts:254–260`). The code is full of pure logic that is *cheap to test*: `UpdateChecker` JSON parsing, `XtreamRepository` URL building, `XmltvParser`, `SeriesInfoHelpers`, `CategorySort`, `StorageDetector`, `ContentFilterRepository`. See C.6.

### B10. 🟡 32 MB of `jniLibs` (mpv + full ffmpeg stack) in the Firestick APK

`app/src/main/jniLibs` is \~32 MB (libmpv + libavcodec/format/filter/device + swscale/swresample + libvlc). The APK is \~64 MB. If the runtime playback chain is MPV → LibVLC → Media3 (`build.gradle.kts:244–247`), the mpv+ffmpeg bundle is the download-size driver. Consider: (a) drop mpv entirely and rely on LibVLC+Media3 (fewer native deps = fewer crash classes, `androidupgraderedo.txt` history suggests mpv was a stability pain), or (b) split the firestick flavor to exclude ffmpeg extras. Measure first — A1's native crash history says fewer decoders is safer on Fire OS.

---

## PART C — World-class upgrades (Firestick-specific)

### C1. Performance & memory (the platform differentiator)

1. **Streaming JSON catalog parse** (A5.1) — biggest single win.
2. **Coil request sizing + no crossfade on LOW_END** — set `crossfade(false)` and `allowHardware(true)` when `deviceTier == LOW_END`; add `size(320x480)` to grid items; prefer `Bitmap.Config.RGB_565` for non-hero posters (accept quality hit on 1080p TV for speed).
3. **Paging tuning for TV**: increase `pageSize` to 30–40 on MID/HIGH tiers, keep 20 on LOW; use `PlaceholderState`-free simple loading to avoid double-composition cost on 2 GB.
4. **ANR defense**: move any DB/IO off main (audit DAO calls in ViewModels), add a `MainThreadChecker` (StrictMode in debug builds only), and a watchdog that logs the main-thread stack when it stalls &gt; 2 s.
5. **Startup budget**: `AppStartupProfiler` already exists — define a budget (e.g. cold start &lt; 2.5 s on Firestick 4K) and fail CI on regression once you have the timing pipeline.
6. **Room**: enable `WAL` + `setJournalMode`, keep `roomCacheMb` budget, add indexes on the columns you filter/sort by (`added`, `name`, `category_id`) — sorting 50k rows unindexed is a hidden jank source.

### C2. Playback (make it feel like a pro IPTV box)

1. **Player engine selection UI**: expose "MPV / LibVLC / ExoPlayer" per-content-type with auto, and show the active engine + codec + bitrate overlay in the info panel (debug flag). Store per-channel remember ("this channel plays best on X").
2. **Audio & subtitle track pickers in the player UI** (PlayerScreen has `spuTrack`/`audioTrack` plumbing — surface it as a Menu-key popup with track names, not just cycle).
3. **Live TV: channel zapping** (Up/Down = prev/next channel in category with instant preload of next), **timeshift/pause live** (LibVLC timeshift exists for DVR — expose pause+resume on live), now/next EPG overlay in the player.
4. **Resume everywhere**: VOD resume (exists) + series episode resume (exists) + **live channel position where supported**; "Start over" and "Resume from 12:34" in the player popup.
5. **Buffering UX**: instead of a spinner, show "Buffering… 45%" with a retry-after-5s + "try next server/format" action; auto-fallback `.ts` → `.m3u8` → transcode format on failure (the Electron app has this pattern in `upgrade texts/latestfix4112026.txt`; port it).
6. **Player reliability**: heartbeat/timeout on `PlayerViewModel` — if no `playing` event within N s, auto cycle engines once before erroring; keep PiP (exists) but add "PiP disabled on LOW_END" setting.

### C3. DVR (already your pride — make it bulletproof)

1. EPG-triggered **"Record this program"** button in the guide; series-link recordings (record all episodes).
2. **Conflict detection** (two recordings same time) with a picker; **storage-full policy** (auto-delete oldest or stop with notification — selectable).
3. Recording **notifications** with open-file action; a recordings "Completed/Failed" tab in DvrScreen with per-file play/delete/rename.
4. Keep the **auto-save on select** behavior (A6) and surface a live "Recording to: USB • 12.4 GB free" strip in the player and Home.
5. SMB target (the placeholder in the picker) — schedule as P2; most users are OTG-only.

### C4. TV-grade UX polish

1. **Fire TV Menu-key context menus everywhere** (A7.1) — biggest UX gap.
2. **Overscan safety**: add a global content-safe padding setting (Fire OS overscans); many sticks clip edges.
3. **Reduced motion + reduced effects on LOW_END** (disable focus scale, backdrop blur) — also a perf win.
4. **On-screen keyboard for Search** (Fire OS has no keyboard) with letter-grid focus; voice search via Amazon voice if you ever target Appstore.
5. **Accessibility**: `contentDescription` audit on icons (icons-only buttons today), focus indicator contrast on bright backdrops, 125% text scale pass.
6. **Localization**: `resourceConfigurations = ["en", "xxhdpi"]` strips everything — adding es/pt/fr/de is a big IPTV-market win, but weigh against APK size (use App Bundles or per-locale APKs if you go Appstore).

### C5. Content & data

1. **TMDB enrichment for Series** (A3.1) + backdrop fallbacks for the player background.
2. **EPG**: parse + render already exist (`XmltvParser`, `EpgCanvasGrid`, `EpgTimelineGrid`) — add program search, channel grouping, "now playing" jump in the player.
3. **Watch history screen** + "clear all"; export favorites/lists/history as JSON (backup + migration).

### C6. Testing (foundation for "world-class" claims)

1. **JVM unit tests** (fast, no device) for: `UpdateChecker` (parse gist payloads incl. blank sha256, legacy schemas, downgrade guard), `XtreamRepository` URL construction (auth, escaping, `.ts` default), `XmltvParser`, `SeriesInfoHelpers`/`displaySeasons`, `CategorySort`, `StorageDetector` (mock), `ContentFilterRepository` presets, `MemoryBudgetManager` tiers.
2. **Instrumented smoke test** on a Fire TV emulator (or ARM tablet): cold start → login → live TV plays 30 s → movies grid → series → DVR picker; assert no crash, using `adb shell am start` + Compose `createAndroidComposeRule`.
3. **Focus traversal UI tests** for A2/A4: inject D-pad keys, assert focus lands on the grid after category change (this turns your filed bugs into regression tests).
4. **CI gate**: `gradlew test` + `assembleFirestickRelease` on every PR; lint on main.
5. **Manual matrix** (ship checklist): Fire TV Stick 4K (2nd gen), 4K Max, Stick Lite, Cube 3rd gen; Fire OS 5/6/7/8; 2 GB + 4 GB devices; provider with 50k+ catalog; provider with HTTPS-only.

### C7. Release engineering (the thing that makes you "professional")

1. **GitHub Actions workflow** (`.github/workflows/android.yml`):
   - on tag `v*`: `assembleFirestickRelease assemblePremiumRelease` → unit tests → sign with secrets (`DYLANDOS_RELEASE_*` already supported by `build.gradle.kts:72–84`) → compute SHA-256 (script exists: `scripts/compute-sha256.ps1`, add a bash twin) → **create GitHub Release** with both APKs → regenerate `update.json` → push to the gist via API token → (optional) auto-open an Amazon Appstore submission.
   - APK size + version bump checks in the same job.
2. **Replace Dropbox as the CDN** (link rot + no checksums): GitHub Releases asset URLs are stable, HTTPS, and the API gives you size + date for free. The app already knows how to parse any JSON; point the gist at release assets.
3. **OTA hardening**: mandatory sha256 (B2), downgrade guard, `minRequiredVersionCode`, install-succeeded/failed callbacks surfaced to the user, and a "check for updates" button in About (manual trigger).
4. **Version discipline**: single source of truth via `versionCode`/`versionName` in Gradle; gist never hand-edited again.

### C8. Observability & security

1. **Crashlytics/Sentry** (B3) + breadcrumbs for the big flows (login, catalog load, engine switch, recording start/stop, OTA).
2. Optional **opt-in usage analytics** (screen views, load times) — gives you real Firestick performance data.
3. Keep local `crash_log.txt` export in About ("Copy diagnostics") so remote users can paste logs — cheap and effective for a sideloaded app.
4. **Secrets hygiene**: TMDB key currently in code (per `NetworkModule.kt:99` comment) — move to `BuildConfig` from `local.properties`/CI secrets like the signing config already does. Consider a per-device provider-credential obfuscation layer (at-rest encryption via Android Keystore) for the DataStore password.

---

## PART D — 90-day prioritized plan

### Weeks 1–2 (P0 — stability & trust)

1. Fix OTA gist now (B1) + make SHA-256 mandatory (B2).
2. Ship Crashlytics/Sentry (B3) — you need data for the next two items.
3. A1 Settings crash: crash boundary + focus edge hardening + logcat capture.
4. A5 memory: streaming JSON parse + Coil sizing + `onTrimMemory`; remove `largeHeap` behind a flag first, then for real.
5. A2/A4 focus: re-arm on data change, key-bound requesters, tick counters.

### Weeks 3–6 (P1 — product quality)

 6. A6 DVR: initial focus + auto-prefer-USB + Settings DVR target card.
 7. A3 series TMDB enrichment + full detail panel parity.
 8. A7 favorites: Menu-key context menus + star badges + Home rails (Continue Watching/Recently Added/Favorites).
 9. B6 toolchain: AGP/Gradle/Compose BOM refresh with regression tests on focus.
10. Player: track pickers, zapping, timeshift pause, engine fallback UX.

### Weeks 7–12 (P2 — professionalize)

11. C6 test suite + CI gates; C7 GitHub Actions release pipeline (kill Dropbox).
12. C3 DVR: EPG record, conflict handling, notifications, storage-full policy.
13. C4 UX pass: overscan, reduced motion, on-screen keyboard, accessibility audit.
14. C8 security: Keystore-encrypted credentials, diagnostics export, TMDB key hygiene.
15. Evaluate Amazon Appstore submission (banner exists; needs content rating + privacy policy + Fire TV device testing).

---

## Appendix — files you'll touch in for each item

| Item | Primary files |
| --- | --- |
| A1 Settings crash | `ui/screens/settings/SettingsScreen.kt` (tab row 103–221, AboutTab 3114) |
| A2 Movies focus | `ui/screens/movies/MoviesScreen.kt` (115–217) |
| A2 Series focus | `ui/screens/series/SeriesScreen.kt` (118–251) |
| A3 Search info | `ui/screens/search/SearchScreen.kt` (240–270), `data/network/TmdbApiService.kt`, `ui/screens/series/SeriesDetailScreen.kt` |
| A4 Seasons focus | `ui/screens/series/SeriesScreen.kt` (1331–1400) |
| A5 OOM | `data/network/XtreamRepository.kt` (528, 628), `data/network/XtreamApiService.kt`, `DylandosApp.kt`, `ui/screens/movies/MoviesViewModel.kt`, `ui/screens/series/SeriesViewModel.kt` |
| A6 DVR storage | `ui/screens/dvr/DvrStorageViewModel.kt`, `ui/screens/dvr/DvrStoragePickerScreen.kt`, `ui/screens/settings/SettingsScreen.kt` (DVR tab) |
| A7 Favorites/customization | `ui/screens/favorites/FavoritesScreen.kt`, `ui/screens/customlists/CustomListsScreen.kt`, `ui/components/`, `ui/screens/home/HomeScreen.kt` |
| B1/B2 OTA | `data/network/UpdateChecker.kt`, `ui/update/UpdateViewModel.kt`, live gist `6056e65b41641393abf585cecbd92d07` |
| B4 largeHeap / B5 backup | `AndroidManifest.xml` |
| B6 toolchain | `android/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.properties` |
| B10 APK size | `app/build.gradle.kts` (packaging/jniLibs), `app/src/main/jniLibs` |

---

*Compiled against the repo as of 2026-08-06. "Verified" = read the code or fetched the live endpoint; "Likely/inferred" = needs a logcat/Crashlytics capture to confirm — which is exactly why B3 is item #2.*