# TECHNICAL MANIFEST — DYLANDOS IPTV ULTIMATE (ANDROID FIRESTICK EDITION)

> **Document Class:** Comprehensive Deep-Dive Architecture & Integration Guide  
> **Target Version:** 4.9.0 (versionCode 79) · **Release Date:** July 16, 2026  
> **Namespace:** `com.dylandos.iptv.ultimate`  
> **Min SDK:** 21 (Android 5.0 Lollipop) · **Target SDK:** 35 (Android 15) · **Compile SDK:** 36 (Android 16)  
> **JVM Target:** Java 17 · **Kotlin Version:** 2.2.10  
> **Annotation Processing:** Google DevTools KSP (Kotlin Symbol Processing)
> **Playback:** LibVLC 3.6.0 (live / DVR / timeshift) + AndroidX Media3 / ExoPlayer 1.5.0 (VOD host) — **MPV removed**
> **Room schema:** v13 (`epg_channel_aliases`, `cached_movies`, `cached_series`)

---

## 1. Executive Summary & App Identity

The Android port of **DYLANDOS IPTV ULTIMATE** is a high-performance, landscape-locked IPTV, VOD, and Series streaming application built on modern Android technologies. Initially designed as a companion to the Windows application, this edition contains unique performance, caching, and playback modifications that enable it to run flawlessly on resource-constrained devices, with a specific focus on the **Amazon Fire TV Stick 4K** platform (which features limited 32-bit ARMv7 hardware, a 2.0 GB RAM ceiling, and restrictive file storage access).

This document serves as an exhaustive technical guide for developers, AI vibe-coding agents, and deep-dive maintenance utilities to modify, repair, or upgrade the application while maintaining high memory efficiency, robust playback, and flawless TV focus flow.

### Core Key Metrics

| Build Metric | Description | Value |
|---|---|---|
| **Package Name** | Main Application ID | `com.dylandos.iptv.ultimate` |
| **Firestick Variant Suffix** | Application ID Suffix | `com.dylandos.iptv.ultimate.firestick` |
| **Premium Variant Suffix** | Application ID Suffix | `com.dylandos.iptv.ultimate.premium` |
| **Debug Suffix** | Debug Application ID Suffix | `.debug` |
| **Version Name** | Current Release Version | `4.9.0-FIRESTICK` / `4.9.0-PREMIUM` |
| **Version Code** | Database / Play Store Tracker | `79` |
| **Signing Profile** | Keystore Alias / Security Certificate | `dylandos` / `dylandos-release.jks` |
| **Cleartext Support** | Allow cleartext HTTP streams | `true` (Enabled in XML Network Security) |
| **OTA Gist Endpoint** | Update payload URL | `https://api.github.com/gists/6056e65b41641393abf585cecbd92d07` |

---

## 2. Product Flavor & Build Matrix

The Gradle configuration (`android/app/build.gradle.kts`) implements a dual-variant product flavor matrix categorized by the `deviceType` dimension. This optimizes the packaging, binary sizes, and native libraries for low-end and high-end hardware.

```
                  ┌──────────────────────┐
                  │   deviceType Flavor  │
                  └──────────┬───────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
      ┌───────────────┐             ┌───────────────┐
      │  firestick    │             │   premium     │
      │ (armeabi-v7a) │             │  (arm64-v8a)  │
      └───────────────┘             └───────────────┘
```

### The Variants

#### `firestick` Variant
* **Application ID Suffix:** `.firestick`
* **Version Name Suffix:** `-FIRESTICK`
* **Target Hardware:** Amazon Fire TV Stick 4K, Fire TV Stick Lite, low-cost Android TV boxes.
* **ABI Optimization:** Stripped to `armeabi-v7a` (32-bit ARM). All 64-bit `.so` files are excluded.
* **Resource Configuration:** Stripped to English language (`en`) and `xxhdpi` pixel densities to minimize APK footprints (aims for ~25-30 MB).
* **RAM Allocation:** Programmed to operate under strict low-memory budgets (optimized for 1.5 GB - 2 GB devices).

#### `premium` Variant
* **Application ID Suffix:** `.premium`
* **Version Name Suffix:** `-PREMIUM`
* **Target Hardware:** Nvidia Shield TV, Chromecast with Google TV, high-end tablets, and mobile devices.
* **ABI Optimization:** Compiled for `arm64-v8a` (64-bit ARM) only.
* **Resource Configuration:** Includes all supported international languages and asset densities.
* **RAM Allocation:** Tailored for 3 GB - 8 GB RAM capacities.

#### `debug` Variant (Build Type Override)
* **Application ID Suffix:** `.debug`
* **Version Name Suffix:** `-DEBUG`
* **Emulator/Simulator Support:** Automatically overrides ABI filters to package `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` files. This ensures full runtime compatibility with Android emulators such as LDPlayer 9, BlueStacks, or official Android Studio AVDs on x86_64 CPU architectures, preventing JNI load crashes (`UnsatisfiedLinkError`).

---

## 3. High-Level System Architecture

The application is structured around a strict **MVVM (Model-View-ViewModel)** clean architectural pattern utilizing **Dagger Hilt** for dependency injection, **Retrofit** for network communications, **Room** for local cache storage, and **Jetpack Compose** for building the entire TV-centric UI.

```
┌────────────────────────────────────────────────────────────────────────┐
│                              PRESENTATION                              │
│  [Jetpack Compose UI Screens] ◄──(Observe State)── [ViewModels]        │
│                                                       │                │
│                                                 (Trigger Actions)      │
│                                                       ▼                │
└───────────────────────────────────────────────────────┬────────────────┘
                                                        │
┌───────────────────────────────────────────────────────▼────────────────┐
│                              DOMAIN LAYER                              │
│  [Repositories]                                                        │
│  - XtreamRepository            - DvrRecordingRepository                │
│  - ContentFilterRepository     - SafeRepositoryCall                    │
└───────────────────────────────────────────────────────┬────────────────┘
                                                        │
┌───────────────────────────────────────────────────────▼────────────────┐
│                               DATA LAYER                               │
│  ┌──────────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │   Room DB (v13)      │  │  Retrofit API    │  │  DataStore       │  │
│  │  (SQLite Cache)      │  │  (Xtream / TMDB) │  │  (User Prefs)    │  │
│  └──────────────────────┘  └──────────────────┘  └──────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     Playback / Service Engines                   │  │
│  │   - LibVlcFactory (libvlc.so)       - LazyExoPlayerHost (Media3) │  │
│  │   - RecordingService / USB timeshift - FieldTelemetry (anon)     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### Unidirectional Data Flow (UDF)
1. The **UI Screens** observe immutable state objects emitted via `StateFlow` structures from their respective **ViewModels**.
2. User D-pad events (Clicks, long-presses, text entries) are dispatched directly as events back to the **ViewModels**.
3. **ViewModels** coordinate business logic asynchronously within a `viewModelScope` by calling the thread-safe APIs exposed by the **Repositories**.
4. **Repositories** act as the single source of truth, balancing network queries (using **Retrofit**) and locally cached tables (using the **Room Database**).

---

## 4. Package and File Structure Manifest

Below is the directory roadmap mapping the functional subsystems in `android/app/src/main/java/com/dylandos/iptv/ultimate/`:

```
com.dylandos.iptv.ultimate
│
├── DylandosApp.kt                      # Main Application bootstrapping class
│
├── data/                               # Data Subsystem
│   ├── db/
│   │   ├── AppDatabase.kt              # Room Database configuration (Schema v13)
│   │   ├── dao/
│   │   │   └── Daos.kt                 # Channel, Favorite, WatchHistory, DVR, EPG, VodResume DAOs
│   │   └── entity/
│   │       └── Entities.kt             # Room mapping schemas (ChannelEntity, EpgProgramEntity, etc.)
│   ├── filter/
│   │   └── ContentFilterRepository.kt  # Content filters (e.g. Parental Lock, hidden categories)
│   ├── model/
│   │   ├── XtreamModels.kt             # Serialized mappings for Xtream Codes queries
│   │   ├── UpdateInfo.kt               # OTA Update schemas
│   │   └── SavedAccount.kt             # DataStore credentials mappings
│   ├── network/
│   │   ├── XtreamApiService.kt         # Retrofit endpoints for Xtream API
│   │   ├── XtreamRepository.kt         # Data caching coordinator
│   │   ├── XmltvParser.kt              # SAX XMLTV EPG data feed parser
│   │   ├── UpdateChecker.kt            # OTA manager utilizing Android DownloadManager
│   │   └── TmdbApiService.kt           # TMDB media metadata lookup API
│   └── util/
│       ├── AppStartupProfiler.kt       # Logs milliseconds taken during launch phases
│       ├── MemoryBudgetManager.kt      # Dynamic RAM allocation analyzer
│       ├── StorageDetector.kt          # Maps physical paths for OTG/USB DVR storage
│       ├── SeriesInfoHelpers.kt        # Regex tools for Season/Episode parsing
│       └── CategorySort.kt             # Custom list sort rules
│
├── di/                                 # Dependency Injection Modules
│   ├── NetworkModule.kt                # okhttp3, retrofit2, and API builders
│   └── DatabaseModule.kt               # Room instances, DAO injections, DataStore
│
├── player/                             # Playback Engine Subsystem (LibVLC + Media3 only)
│   ├── LibVlcFactory.kt                # Builds custom LibVLC instance option strings
│   └── subtitle/
│       ├── LibVlcSubtitleManager.kt    # Tracks and sets internal subtitles
│       └── SubtitlePickerSheet.kt      # Compose bottom sheet for subtitles selection
│
├── receivers/
│   └── BootReceiver.kt                 # Reschedules DVR recording actions post reboot
│
├── service/
│   ├── RecordingService.kt             # Foreground DVR processor (dataSync/mediaPlayback types)
│   └── PlayerRecordingBridge.kt        # TEE recorder (shares live player stream)
│
├── ui/                                 # Presentation / Compose Interface Subsystem
│   ├── MainActivity.kt                 # Single Activity entry point
│   ├── common/
│   │   └── ComposableUtils.kt          # Reusable UI component extensions
│   ├── components/
│   │   └── TvFocusable.kt              # Generic TV focus container
│   ├── focus/
│   │   ├── DylandosFocusSystem.kt      # Scale scaling, color borders, spring-damped mod
│   │   ├── FocusStateRepository.kt     # Focus location tracking
│   │   └── KeyEventRouter.kt           # Custom key router for canvas elements
│   ├── navigation/
│   │   └── Navigation.kt               # Routing engine for the screens
│   ├── player/
│   │   └── PipController.kt            # Enters Android PiP mode on supported APIs
│   ├── screens/
│   │   ├── splash/                     # SplashScreen & SplashViewModel
│   │   ├── login/                      # LoginScreen & LoginViewModel
│   │   ├── home/                       # HomeScreen & HomeViewModel
│   │   ├── livetv/                     # LiveTvScreen & LiveTvViewModel
│   │   ├── guide/                      # GuideScreen, GuideViewModel, & EpgCanvasGrid
│   │   ├── movies/                     # MoviesScreen & MoviesViewModel
│   │   ├── series/                     # SeriesScreen & SeriesViewModel
│   │   ├── player/                     # Fullscreen player UI overlay
│   │   ├── dvr/                        # DvrScreen, DvrViewModel, Storage selectors
│   │   ├── search/                     # Real-time search UI
│   │   └── settings/                   # Custom system settings
│   ├── theme/
│   │   ├── AppTheme.kt                 # MaterialTheme configurations
│   │   ├── Color.kt                    # Brand color guidelines
│   │   ├── Theme.kt                    # Color palette configurations
│   │   └── Type.kt                     # Font parameters
│   └── update/
│       ├── UpdateViewModel.kt          # Manages OTA dialogue transitions
│       ├── UpdateDialog.kt             # Prompt to apply updates
│       └── WhatsNewDialog.kt           # Displays recent changelogs
│
└── workers/                            # WorkManager Asynchronous Tasks
    ├── ScheduledRecordingWorker.kt     # Triggers background DVR actions via WorkManager
    └── WatchHistoryPruneWorker.kt      # Cleans old history logs daily
```

---

## 5. Bootstrapping, Exception Handling & Memory Budgeting

Due to the limited hardware profiles on Fire TV devices, the initialization process in `DylandosApp.kt` implements precise startup tracking and dynamic resource allocation.

### MemoryBudgetManager Implementation
The application contains a `MemoryBudgetManager.kt` class which inspects physical RAM and JVM limits, classifying the host hardware into three execution budgets:
* **LOW_END:** (RAM < 2.5 GB) Optimized for Firesticks.
* **MID_RANGE:** (2.5 GB ≤ RAM < 4.5 GB) Mid-tier Android TV boxes.
* **HIGH_END:** (RAM ≥ 4.5 GB) High-end devices (e.g. Shield TV Pro).

Subsystems subscribe to this manager to customize their caches and video buffers:

```kotlin
// MemoryBudgetManager allocation ratios
LOW_END -> MemoryBudget(
    totalHeapMb     = maxHeapMb,
    totalRamMb      = totalRamMb,
    imageCacheMb    = (maxHeapMb * 0.08).toInt().coerceIn(16, 32), // Coil Memory Cache (16-32 MB)
    imageDiskCacheMb = 50,                                          // Coil Disk Cache (50 MB)
    engineBufferMb  = (maxHeapMb * 0.15).toInt().coerceIn(24, 48), // LibVLC / Media3 buffer budget (24-48 MB)
    roomCacheMb     = 8,                                           // Room Write Cache
    isLowEndDevice  = true,
    deviceTier      = DeviceTier.LOW_END
)
```

By capping Coil's memory cache to **8% of the JVM heap (clamped to 16–32 MB)**, the application prevents Out Of Memory (OOM) situations on Firestick devices when navigating large channel and VOD poster listings.

### Custom Uncaught Exception Handler
The exception handler writes stack traces to `crash_log.txt` inside the app's `filesDir` directory. To prevent the log from growing indefinitely on flash storage, the logger implements a hard limit of **256 KB**. If the log exceeds this threshold, the first half of the log is discarded:

```kotlin
val crashLog = java.io.File(filesDir, "crash_log.txt")
if (crashLog.exists() && crashLog.length() > 256 * 1024) {
    val lines = crashLog.readLines()
    crashLog.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
}
crashLog.appendText("${System.currentTimeMillis()}: ${throwable.javaClass.simpleName}: ${throwable.message}\n")
```

To avoid crashes from interrupting background processes, the handler keeps the application alive if a background thread crashes. Only main-thread exceptions are forwarded to the system crash dialog.

---

## 6. Room Database Cache & Migrations (v13)

The data layer uses Room (`AppDatabase.kt`) to cache content metadata locally. This keeps the user interface responsive and enables offline features like DVR recording lookups without reloading data from the IPTV server on every boot.

**Current schema (v13):**
- Channels, favorites, watch history, DVR recordings, scheduled recordings
- `epg_programs` + `epg_channel_aliases` (XMLTV match map)
- `vod_resume`, custom lists
- `cached_movies` / `cached_series` — Room-paged VOD catalogs (Phase A OOM fix)

```
                   ┌──────────────────────────────────┐
                   │           AppDatabase            │
                   └────────────────┬─────────────────┘
                                    │
    ┌──────────────┬────────────────┼──────────────┬──────────────┐
    ▼              ▼                ▼              ▼              ▼
┌───────┐      ┌───────┐        ┌───────┐      ┌───────┐      ┌───────┐
│channel│      │favori.│        │watch_h│      │dvr_rec│      │epg_pro│
└───────┘      └───────┘        └───────┘      └───────┘      └───────┘
```

### Table Definitions

1. **`channels` (`ChannelEntity`):** Local cache for IPTV live streams. Index is set on `categoryId` to optimize sidebar navigation.
2. **`favorites` (`FavoriteEntity`):** Stores user favorites for Live TV, Movies, and Series. Uses a composite key to prevent duplicates.
3. **`watch_history` (`WatchHistoryEntity`):** Saves progress for Series episodes. If progress exceeds 85%, the episode is marked as completed.
4. **`dvr_recordings` (`DvrRecordingEntity`):** Stores planned, active, completed, and failed DVR actions. Indexed by `(status, startTimeMs)` to speed up list queries.
5. **`epg_programs` (`EpgProgramEntity`):** Stores up to 7 days of TV guide entries parsed from XMLTV feeds.
6. **`vod_resume` (`VodResumeEntity`):** Stores resume points for Movies. Saves progress every 30 seconds and when playback stops. If progress exceeds 90%, the item is marked as watched.

### Migration Roadmap

* **v1 → v2:** Added `WatchHistoryEntity` and `lastFetchedAt` timestamp column to `channels` cache.
* **v2 → v3:** Added SQL indices on `categoryId` in the `channels` table to optimize query speeds.
* **v3 → v4:** Added `dvr_recordings` table to support initial DVR scheduling.
* **v4 → v5:** Added `epg_programs` table with index patterns to accommodate XMLTV EPG data.
* **v5 → v6:** No-op migration to protect DVR lists and prevent database wipes during application updates.
* **v6 → v7:** Added composite index on `(status, startTimeMs)` in `dvr_recordings`.
* **v7 → v8:** Added `vod_resume` table.

---

## 7. Network Layer & Protocol Pinning

Network requests are managed by Retrofit and a custom OkHttpClient configured in `NetworkModule.kt`. 

### OkHttpClient Configuration
* **Keep-Alive:** Configured for 10 concurrent connections with a 5-minute keep-alive timeout.
* **Request Dispatcher:** Limit set to 20 concurrent connections.
* **Disk Cache:** Capped at 4 MB to minimize flash storage usage.
* **Protocol Selection:** Set to HTTP/2 and HTTP/1.1 fallback support.

> [!IMPORTANT]
> **OkHttp Version Pinning:** The OkHttp client library is pinned at version `4.12.0`. Upgrading to version 5.x alpha causes Coil `2.7.0` image loaders to fail with `NoSuchMethodError` crashes, which prevents channel logos and poster art from loading.

### Xtream Codes API Routes
The `XtreamApiService.kt` interface routes queries to the provider's `player_api.php` file using the following queries:

```kotlin
// Interface signature matching the Xtream Codes protocol structure
interface XtreamApiService {
    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") u: String,
        @Query("password") p: String
    ): XtreamAuth

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_live_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_live_streams"
    ): List<XtreamChannel>

    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_vod_streams"
    ): List<XtreamMovie>

    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_series"
    ): List<XtreamSeries>

    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_series_info",
        @Query("series_id") id: String
    ): XtreamSeriesInfoResponse
}
```

---

## 8. Playback Engine Architecture (LibVLC + Media3)

Playback is LibVLC for live/DVR plus Media3 (`LazyExoPlayerHost`) for VOD and USB live timeshift. There is no third native engine layer.

```
                    ┌─────────────────────────┐
                    │      PlayerScreen       │
                    └───────────┬─────────────┘
                                │
          ┌─────────────────────┼─────────────────────┐
          ▼                     ▼                     ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│     LibVLC       │  │ Media3 ExoPlayer │  │  RecordingService│
│  Live TS / DVR   │  │ VOD + USB timeshift│ │  foreground DVR │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

### Engine Selection Rules

| Stream Type | Format | Primary Engine | Fallback Engine | Reason |
|---|---|---|---|---|
| **Live TV** | `.ts`, MPEG-TS, RTSP, HLS | **LibVLC** | Media3 | LibVLC handles raw TS / network drops and DVR `:sout` copy. |
| **Live + USB timeshift** | pause-live buffer on USB | **Media3** | — | Single-engine path; LibVLC not mixed while timeshift owns live. |
| **Movies / VOD** | `.mp4`, `.mkv`, HLS | **Media3 (ExoPlayer)** via `LazyExoPlayerHost` | LibVLC | Media3 for VOD host; LibVLC remains available for problematic streams. |
| **TV Series** | Episodic VOD | **Media3 (ExoPlayer)** | LibVLC | Matches the VOD playback path. |
| **DVR / catch-up** | Scheduled + Xtream timeshift URLs | **LibVLC** / Media3 per path | — | Stream-copy recording stays on LibVLC. |

### LibVLC Engine Config (`LibVlcFactory.kt`)
For live channels, LibVLC prioritizes low-latency stream startup:

```kotlin
val liveOptions = listOf(
    "--network-caching=1500",                  // 1.5 seconds buffer target
    "--live-caching=1500",
    "--clock-jitter=0",                        // Disable jitter compensation for lower delay
    "--no-drop-late-frames",
    "--no-skip-frames",
    "--rtsp-tcp",                              // Use TCP for more stable RTSP playback
    "--http-reconnect",                        // Reconnect automatically if connection drops
    "--aout=opensles",                         // Best compatibility audio output for FireOS
    "--audio-resampler=soxr",
    "--codec=mediacodec_ndk,iomx,all",         // NDK Hardware acceleration fallback sequence
    "--mediacodec-dr",                         // Direct surface rendering
    "--no-mediacodec-adaptive-playback"        // Prevents resolution flickering on Firestick
)
```

---

## 9. DVR Recording and Storage Management

The DVR system runs as a foreground service (`RecordingService.kt`) with two distinct recording methods:

```
                      ┌──────────────────────┐
                      │   RecordingService   │
                      └──────────┬───────────┘
                                 │
                 ┌───────────────┴───────────────┐
                 ▼                               ▼
      ┌──────────────────────┐        ┌──────────────────────┐
      │   Direct Mux Mode    │        │       TEE Mode       │
      │  (Dedicated Stream)  │        │   (Shared Connection)│
      └──────────────────────┘        └──────────────────────┘
```

1. **Direct Mode (Standard):** Launches an independent, headless LibVLC instance (`--vout=dummy`, `--aout=dummy`) and saves the stream to disk using direct copy:
   `:sout=#std{access=file,mux=ts,dst=/path/to/file.ts}`
2. **TEE Mode:** Uses `PlayerRecordingBridge.kt` to share the stream connection of an active live player. This prevents opening a second connection, helping users stay within connection limits set by their IPTV provider.

### OTG USB Storage Integration
Due to the Storage Access Framework (SAF) limitations on Amazon FireOS (where USB drives can lose their mount paths after a system restart), the app uses `StorageDetector.kt` to find direct mount paths:

```kotlin
// Scan paths on FireOS
val mountPoints = listOf("/mnt/media_rw/", "/storage/", "/mnt/usb/")
for (point in mountPoints) {
    val dir = File(point)
    if (dir.exists() && dir.isDirectory) {
        val contents = dir.listFiles()
        // Find writable subdirectories on the drive
    }
}
```

If a direct mount path is not found, the app falls back to SAF-registered directory trees (`DocumentFile`).

### DVR Task Scheduling
WorkManager manages recording tasks via `ScheduledRecordingWorker.kt` (configured as a `@HiltWorker`). The workflow operates as follows:

```
  User schedules record via EPG
             │
             ▼
  WorkManager creates OneTimeWorkRequest with delay
             │
             ▼
  [Device Wakes / Alarm Fires]
             │
             ▼
  Check: activeCount < 3?
        ├── YES ──► Start RecordingService (Foreground Notification)
        └── NO  ──► Display Error Toast / Notification
```

The app schedules a matching stop task using the planned duration of the program.

---

## 10. TV-Centric D-Pad Navigation and Focus System

Standard Jetpack Compose focus layouts do not always handle TV D-pad inputs correctly. To ensure smooth navigation on Firesticks, the app uses a custom focus system.

```
                    ┌─────────────────────────┐
                    │    dylandosFocusable    │
                    └───────────┬─────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│Scale: 1.08x   │       │Cyan border    │       │Intercept Center
│(Spring-damped)│       │(150ms fade)   │       │Enter keys     │
└───────────────┘       └───────────────┘       └───────────────┘
```

### The `dylandosFocusable` Modifier
All focusable items on screens use the `dylandosFocusable` modifier. This modifier applies key TV optimizations:

1. **Focus Glow and Borders:** Fades in a neon-cyan border (`#00D4FF`) over 150 milliseconds.
2. **Spring Scale:** Smoothly scales the focused element size to `1.08f` using a spring animation to indicate focus.
3. **Key Interception:** Intercepts `DirectionCenter`, `Enter`, and `NumPadEnter` key events on physical remotes and routes them to the element's click action:

```kotlin
.onKeyEvent { keyEvent ->
    if (keyEvent.type == KeyEventType.KeyUp &&
        (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter ||
         keyEvent.key == Key.NumPadEnter)) {
        onClick()
        true
    } else false
}
```

### Focus Grouping and State
* **`dylandosFocusGroup()` Modifier:** Prevents the D-pad from leaving specific containers (e.g. sidebars) unexpectedly.
* **`FocusStateRepository`:** A global singleton that tracks active focus groups. On the playback screen, it suppresses default D-pad movements when player overlays are hidden.
* **`KeyEventRouter`:** Manages custom focus moves on complex grids, such as the `EpgCanvasGrid`, where standard Compose item layouts would cause performance delays.

---

## 11. Over-The-Air (OTA) Update System

The application checks for updates using a dedicated OkHttpClient to parse version metadata from a GitHub Gist.

```
 Launch check ──► Query GitHub Gist API URL ──► Parse first JSON file found
                                                        │
                                                        ▼
 Update Available ◄── Check if remote versionCode > current versionCode
         │
         ▼
 User accepts prompt ──► Download APK via DownloadManager ──► Validate SHA-256
                                                                     │
                                                                     ▼
 Trigger System Installer ◄── Register FileProvider URI ◄────────────┘
```

### OTA Payload Format
The update checker supports legacy configurations and version formats:

```json
{
  "versionCode": 67,
  "versionName": "4.6.8",
  "minSdkVersion": 21,
  "releaseNotes": "1. LibVLC + Media3 playback\n2. Optimizes focus\n3. Resolves 4K buffering",
  "flavors": {
    "firestick": {
      "apkUrl": "https://www.dropbox.com/scl/fi/firestick_release.apk?dl=1",
      "sha256": "4b7b25712e5c8e312a02e604f86d8b2d87e076722d561a355152a5c829db212e"
    },
    "premium": {
      "apkUrl": "https://www.dropbox.com/scl/fi/premium_release.apk?dl=1",
      "sha256": "8c7f21226e3c8d352b02e604f86d8b2d87e076722d561a355152a5c829db52ef"
    }
  }
}
```

### Update Flow Tasks
1. **Metadata Query:** Reads the JSON payload from the Gist.
2. **SHA-256 Check:** Computes the hash of the downloaded file and verifies it against the `sha256` value in the JSON payload.
3. **Trigger Installation:** Uses `FileProvider` to register a safe URI (`com.dylandos.iptv.ultimate.fileprovider`) and starts the system package installer:

```kotlin
val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
    data = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
}
context.startActivity(intent)
```

---

## 12. Permissions, Hardware & Android Manifest

The `AndroidManifest.xml` configuration uses specific flags and overrides to maintain TV and FireOS compatibility.

### Manifest Excerpt & Overrides
```xml
<!-- Required Permissions -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<!-- Optional Hardware features for leanback compatibility -->
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.type.television" android:required="false" />
<uses-feature android:name="android.hardware.wifi" android:required="false" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

### Essential Application Flags

* **`hardwareAccelerated="true"`:** Ensures smooth transition animations and border overlays on lower-performance TV processors.
* **`largeHeap="true"`:** Allocates more memory to the app process to prevent OOM errors when decoding large image files or managing network buffers.
* **`usesCleartextTraffic="true"`:** Allows HTTP playback, which is required because many IPTV providers do not use secure HTTPS streams.

---

## 13. AI Vibe-Coding and Upgrade Guidelines

When modifying this repository, AI agents and automated coding tools must follow these rules to maintain stability and prevent build or runtime errors.

### Dependency Safety Rule
Do not change OkHttp or Coil versions unless they are updated together. Coil 2.7.0 is compiled for OkHttp 4.x. Upgrading the OkHttp library to 5.x will break image loading, causing network requests to fail silently.

### Gradle Cache Resolution
If you see unexpected build errors after changing configuration scripts, clear the dependency cache:

```powershell
# Run Gradle sync with cache bypass flags
./gradlew clean assembleDebug --no-build-cache --no-configuration-cache --refresh-dependencies
```

### Proguard Rules
Native LibVLC JNI hooks must not be renamed or optimized. Add the following rules in `proguard-rules.pro`:

```proguard
# Keep VLC classes and native methods
-keep class org.videolan.libvlc.** { *; }
-keepclassmembers class org.videolan.libvlc.** {
    native <methods>;
}

# Keep Room database and DAO code
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
```

### TV Safe Zone Margins
When editing screens or components:
* Keep layouts within the TV "overscan safe zone" (a 5% margin around the screen edges).
* Avoid layouts that require vertical scrolling to show key buttons. Keep elements on screen where possible to optimize navigation.
* Ensure focused elements are scrolled into view when a D-pad navigation event occurs.
* Design all dialog windows to capture focus, preventing the focus from moving to elements behind the dialog.
