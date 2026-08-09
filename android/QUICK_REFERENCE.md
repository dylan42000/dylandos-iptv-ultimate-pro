# DYLANDOS IPTV ULTIMATE - Android Quick Reference

## Version: 4.9.0 | LibVLC + Media3 | Firestick 4K Optimized

---

## BUILD COMMANDS

```bash
cd android

# Firestick Release (Fire TV Stick 4K)
./gradlew assembleFirestickRelease

# Premium Release (High-end devices)
./gradlew assemblePremiumRelease
```

Set `JAVA_HOME` to Adoptium JDK 21 if needed.

Signing: `DYLANDOS_RELEASE_STORE_PASSWORD` + `DYLANDOS_RELEASE_KEY_PASSWORD` in env or `local.properties`.

---

## APK OUTPUT LOCATIONS

```
app/build/outputs/apk/firestick/release/app-firestick-release.apk
app/build/outputs/apk/premium/release/app-premium-release.apk
```

Also copied to `android/release/` or repo `release/` after ship builds.

---

## PLAYBACK ENGINES

| Content | Engine |
|---------|--------|
| Live TV / DVR | LibVLC |
| USB timeshift (pause-live) | Media3 only |
| Movies / Series VOD | Media3 (`LazyExoPlayerHost`) |

MPV is **not** used on Android. Do not add `libmpv` AARs.

---

## KEY PATHS

- `player/LibVlcFactory.kt` — LibVLC options
- `ui/screens/player/LazyExoPlayerHost.kt` — deferred Media3 + USB cache
- `ui/screens/player/PlayerScreen.kt` / `PlayerViewModel.kt` — zap, last-channel, timeshift UX
- `data/util/FieldTelemetry.kt` — anonymized crash / playback / decode codes
- `ui/screens/customlists/CustomListsScreen.kt` — folders + reorder

---

## FIRESTICK REMOTE CHEAT SHEET

- Number pad → channel zap (Live TV + player)
- Back / last-channel recall in player when available
- Menu or long-press channel → add to custom list / favorite
- Parental PIN when opening locked categories
