# Android 5.3.0 / build 101

This update adds a local MPEG-TS timeshift timeline, fixes the startup watchdog's false failure during pause, and makes local rewind take precedence over provider replay. Menu opens buffered restart and return-to-live options. The disk buffer is capped at 2 GiB and cannot rewind before tuning began. Provider HLS playback depends on the seekable window supplied by that provider.

Home uses compact shortcuts and recent additions. Configure **Settings → Ratings & language** for optional TMDB discovery, movie/series ratings and trailers, and optional IMDb/Rotten Tomatoes scores through OMDb. Scores require a confident title match and configured API keys. Home discovery matches highly rated titles released in the last three years, with at least 100 TMDB votes, against the current subscription's filtered catalog. Language defaults to English. Home section switches and My Lists provide customization without extra playback work.

Movie details now render before recommendation ranking completes. Movies and Series can fall back to a populated category when their initial All view is empty. Live TV remembers selections within categories. Search hands keyboard focus to the results tabs. The guide selects programs with left/right, offers selected-program options, and reloads listings when its time window changes. Seven-day navigation is limited by provider EPG coverage. Reminders open the guide; Android sleep and notification settings can affect delivery.

## Build signed APKs

From the project folder in PowerShell:

```powershell
cd android
.\gradlew.bat :app:assembleFirestickRelease :app:assemblePremiumRelease --no-daemon --no-parallel --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
cd ..
node scripts/prepare-beta-release.cjs
```

The existing release signing configuration is used. The final command copies the APKs to `release/` and generates `gists/ota-update-5.3.0.json` plus `android/OTA_GIST_TEMPLATE_5.3.0.json`, with hashes and sizes calculated from those exact APKs. It refuses older builds.

Replace the two download URL placeholders with the corresponding hosted APK URLs before publishing the Gist. Do not publish a new version with older APK URLs or hashes. The preparation script does not publish or alter the live Gist.

Use the generated JSON as `update.json` in Gist `c3eda014bd60ef939da246f1896066e4`. Version name is `5.3.0`, version code is `101`, and `mandatory` remains `false`. The live Gist has not been updated automatically because publishing access and the new hosted download URLs are unavailable.

## Device validation

On the authorized Firestick, local timeshift paused while the buffer continued growing, and rewind moved from 13 seconds to 3 seconds. A subsequent pause exceeded the old startup-watchdog deadline without its false playback error. Search section changes and return to results were exercised. Two real recordings appeared in the DVR library; the larger recording was 50,398,308 bytes (48.1 MiB), and FFmpeg decoded its first five seconds without errors. These checks used an interim build of the changes; final-package checks are recorded below.

Recording account selection now restricts alternate accounts to the current provider, preventing a channel ID from being sent to a different provider's server. The guide now requests categories and channels concurrently, prevents overlapping initial loads, surfaces network failures, and allows 60 seconds for large catalogs. Its compact day picker replaces the oversized summary panel.

Final release verification: both signed variants built successfully, 55 Android unit tests passed with zero failures/errors, and both APKs passed certificate and alignment checks. Firestick contains ARMv7 native libraries; Premium contains ARM64. The final Firestick APK installed successfully as `5.3.0-FIRESTICK`, version code 101. Magnum's Movies screen loaded actual Latest Movies titles after the fallback correction. Guide loading, its compact layout, and the program-options menu were exercised; its day picker now uses the configured display time zone. Full seven-day provider data and long recording/playback soak tests remain beta coverage.

## Remaining beta coverage

The final-package checks also confirmed Magnum's Series titles load and the day picker lists Today followed by the correct next six local dates. Dino 4k sub1 was restored as the active account after testing.

- The existing Windows 5.2.9 DVR fixes and artifacts remain available; this 5.3.0 release is Android.
- LAN sharing of recordings is not implemented in this release.
- TMDB/OMDb live-service validation requires API keys; no shared keys are embedded.
- Firestick testing uses the authorized device at `192.168.1.205:5555`. Premium shares these Android changes but requires separate hardware beta testing.
