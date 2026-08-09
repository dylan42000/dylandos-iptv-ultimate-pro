# DYLANDOS IPTV ULTIMATE - Android Edition

Complete Android/TV IPTV player with Xtream Codes API support, matching the Windows version's functionality and design.

## Features

- ✅ **Xtream Codes Integration** - Full API support for Live TV, VOD, and Series
- ✅ **LibVLC + Media3** - Live/DVR on LibVLC; VOD + USB timeshift on ExoPlayer
- ✅ **Neon Cyan Theme** - Beautiful dark theme matching Windows version
- ✅ **Android TV Support** - Optimized for TV/Firestick with D-pad navigation
- ✅ **Room Database** - Local caching for offline access
- ✅ **Hilt DI** - Clean architecture with dependency injection
- ✅ **DVR Recording** - Scheduled + keyword/series rules with conflict checks
- ✅ **EPG Grid** - Cable-style electronic program guide
- ✅ **Custom Lists** - Folders, reorder, Menu/long-press add from Live TV
- ✅ **Parental PIN** - Locked categories on Live / Movies / Series
- ✅ **Field Telemetry** - Anonymized crash + playback failure codes (no credentials)

## Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin | 2.2.10 |
| UI | Jetpack Compose | 1.7+ |
| Architecture | MVVM + Clean | - |
| DI | Hilt | 2.51+ |
| Player | LibVLC 3.6.0 + Media3/ExoPlayer 1.5.0 | - |
| Networking | Retrofit + OkHttp | 2.11 / 5.0 |
| Database | Room | 2.6+ |
| Images | Coil | 2.7+ |
| Min SDK | 21 (Android 5.0) | - |
| Target SDK | 35 (Android 15) | - |

## Project Structure

```
android/
├── app/
│   ├── build.gradle.kts          # App-level Gradle configuration
│   ├── proguard-rules.pro        # ProGuard rules for release
│   └── src/main/
│       ├── AndroidManifest.xml   # App manifest
│       ├── java/.../ultimate/    # Kotlin sources
│       └── res/                  # Resources
├── build.gradle.kts              # Root Gradle
└── gradle.properties
```

## Build Variants

| Flavor | ABI | Target |
|--------|-----|--------|
| `firestick` | armeabi-v7a | Fire TV Stick 4K |
| `premium` | arm64-v8a | Shield / modern TV |

```bash
cd android
./gradlew assembleFirestickRelease
# APK: app/build/outputs/apk/firestick/release/
```

Signing uses `DYLANDOS_RELEASE_STORE_PASSWORD` / `DYLANDOS_RELEASE_KEY_PASSWORD` from env or untracked `local.properties` plus `app/dylandos-release.jks`.

## Playback Notes

- **Live TV / DVR:** LibVLC (low-latency TS)
- **USB pause-live timeshift:** Media3 only (single-engine; disable Timeshift to use LibVLC live)
- **VOD / Series:** Media3 via `LazyExoPlayerHost`, LibVLC fallback on hard failures

Do not reintroduce MPV / DualMediaEngine on Android.

## Troubleshooting

**Issue**: Playback fails with "Playback error occurred"  
→ Check stream URL, network, and Settings → LibVLC / Timeshift. Failures are logged anonymously to `files/field_telemetry.jsonl`.
