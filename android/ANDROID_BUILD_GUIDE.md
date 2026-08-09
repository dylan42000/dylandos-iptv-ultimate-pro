# DYLANDOS IPTV ULTIMATE - Android Build Guide

## Version: 4.10.0

LibVLC + MPV + Media3 Android implementation of DYLANDOS IPTV ULTIMATE, optimized for Amazon Fire TV Stick 4K and high-end Android devices.

---

## Build Variants

### 1. Firestick (ARMv7 - Fire TV Stick 4K)
- **ABI**: `armeabi-v7a` (32-bit ARM)
- **Target**: Amazon Fire TV Stick 4K, Fire TV Stick Lite
- **RAM**: Optimized for 2GB RAM
- **Build Command**:
  ```bash
  ./gradlew assembleFirestickRelease
  ```

### 2. Premium (ARM64 - High-End Devices)
- **ABI**: `arm64-v8a` (64-bit ARM)
- **Target**: NVIDIA Shield, Chromecast with Google TV, modern Android phones/tablets
- **Build Command**:
  ```bash
  ./gradlew assemblePremiumRelease
  ```

---

## Playback Configuration

### Primary engines
| Content | Engine cascade |
|---------|----------------|
| Movies / Series (VOD) | **MPV** → LibVLC → Media3 |
| Live TV / DVR | **LibVLC** → Media3 |
| USB pause-live timeshift | **Media3** only (512 MB USB ring) |

MPV natives (`libmpv.so`, `libplayer.so`, FFmpeg) live under `app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}/` (extracted from mpv-android release APKs).

### Live LibVLC profile (low latency)
Configured in `LibVlcFactory.kt` / Settings → LibVLC (network caching, drop late frames, chroma).

### VOD MPV profile
`MpvEngineFactory.PlaybackProfile.VOD_LOW_END` on Firestick (bilinear scale, modest demuxer cache). Failover is automatic on init/load/timeout errors.

---

## Signing

Release builds require:
- `android/app/dylandos-release.jks`
- `DYLANDOS_RELEASE_STORE_PASSWORD`
- `DYLANDOS_RELEASE_KEY_PASSWORD`

Provide secrets via environment variables or untracked `android/local.properties`. Never commit passwords.

```bash
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
cd android
.\gradlew.bat assembleFirestickRelease
```

---

## Feature Matrix (Firestick)

| Feature | Status |
|---------|--------|
| Live TV + number zap + last channel | ✅ |
| EPG guide grid (device-local display, provider-zone parse) | ✅ |
| Movies / Series + MPV primary | ✅ |
| DVR + series/keyword rule conflicts | ✅ |
| Custom lists (folders, reorder, Menu add) | ✅ |
| Parental PIN + locked categories | ✅ |
| USB timeshift (Media3) | ✅ |
| Field telemetry (anon crash/playback codes) | ✅ |

---

## Notes

1. USB timeshift owns the live pipeline exclusively while enabled — disable Timeshift to fall back to LibVLC live.
2. OTA: bump `versionCode` / Gist JSON after shipping APKs.
3. EPG: offset-less XMLTV uses provider/feed timezone (never device TZ). Short-EPG trusts UTC epochs from the panel.
