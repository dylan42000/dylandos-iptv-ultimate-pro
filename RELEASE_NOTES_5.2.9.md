# 5.2.9 beta — Firestick, Android Premium, and Windows

Android versionCode: **100**. Windows version: **5.2.9**.

Each release APK packages its intended architecture: ARMv7 for Firestick and ARM64 for Premium. Removed inherited filters that previously bundled both architectures in each flavor.

Movies and Series now start on categories. The browser no longer cancels upward movement across the entire grid, cards have one focus target, returning from details restores the saved poster, and moving right from categories preserves the grid selection. Live TV scrolls to the saved channel before requesting focus. Search preserves the selected section and result position and avoids the lazy focus-restorer path when changing result sections. Search results are deduplicated and matching runs off the UI thread.

The stacked command and featured-item headers were removed from Live TV, Movies, and Series so the content area receives the available screen height. The global sidebar remains available, shows compact labels, expands on focus, and scrolls on short displays.

Android DVR now releases native resources and file handles on failure, uses concurrent maps for recording jobs, and refuses known critically low storage. Media3 buffering has an explicit 32 MiB target. Timeshift sizing leaves half of known remaining storage available when space is low, clamps seeks to a known duration, consistently tracks pause state, and prefers available local rewind before provider restart.

Windows DVR waits for bytes before reporting successful startup. Missing FFmpeg and failed streams leave visible library records. Atomic, serialized library writes prevent concurrent completions from losing entries. Disk-discovered recordings use unique path hashes; output filenames are collision resistant. The bundled FFmpeg handles normalized stream URLs, TS/HLS input, and TS/MP4/MKV output. Quick-record controls distinguish already completed recordings and surface failures. Scheduled recordings resume when the app opens during their recording window, and long timers no longer overflow. The app must remain running for scheduled recordings.

Windows poster grids no longer add spacing twice, use a stable cell component, and remember scroll/focus across remounts for up to 40 catalogue views.

Validation commands:

- `npm run build`
- `node scripts/test-dvr.cjs` — generates media, records HTTP TS and HLS, decodes TS/MP4/MKV output, checks failed input and missing FFmpeg, concurrent library persistence, unique IDs, and slot cleanup.
- Android: `gradlew.bat :app:testFirestickDebugUnitTest :app:assembleFirestickRelease :app:assemblePremiumRelease --no-parallel --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"`
- `apksigner verify --verbose --print-certs` and `zipalign -c -P 16 -v 4` for each signed APK.

No Firestick or Android device was connected. Remote navigation, the reported search crash, provider-specific recording, long playback sessions, and physical USB removal need device testing. A Media3 cache does not turn an unseekable live TS stream into a DVR timeline; rewind still depends on a seekable media window or provider catch-up. Zero crashes and every provider stream working cannot be established from local builds and synthetic media tests.

For beta testing, check Movies/Series category entry; scrolling up and down beyond the viewport; left/right between sidebar, categories, and posters; Back from an item deep in each list; Search section changes followed by playback and Back; ten-minute DVR playback after recording; and pause/rewind while DVR is recording to the chosen USB device.

The Gist payload is `gists/ota-update-5.2.9.json` (also copied to `android/OTA_GIST_TEMPLATE_5.2.9.json`). It contains actual APK sizes and SHA-256 hashes after release preparation. Replace both `apkUrl` placeholders with the corresponding uploaded 5.2.9 APK URLs, then replace `update.json` in Gist `c3eda014bd60ef939da246f1896066e4`. The live Gist was not modified: authenticated publishing access and the new download URLs were unavailable. Existing 5.2.8 download links must not be paired with 5.2.9 hashes.

Android build tooling was updated to AGP 8.10.1 for Kotlin 2.2 compatibility, following [Google's compatibility table](https://developer.android.com/build/kotlin-support). Windows installer and portable builds are unsigned because no Windows signing certificate is configured; Android uses the existing release keystore.
