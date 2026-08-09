# DYLANDOS IPTV ULTIMATE — Release Playbook
### Build the signed APK yourself, or let GitHub Actions build it for you

---

## Part 1 — Build the signed release APK on your own computer

### Prerequisites (one-time)
1. **JDK 17** — https://adoptium.net (Temurin 17, Windows x64 installer).
2. **Android SDK** — install Android Studio, then *SDK Manager* → install
   **Android SDK Platform 36** and **Android SDK Build-Tools** (36.x).
3. **The release keystore** — `android/app/dylandos-release.jks` (2.7 KB).
   ⚠️ It is NOT inside the source zip on purpose. If you didn't download it
   yet, ask me to restart the file server and grab it — losing it means you
   can never update the app on users' devices.
4. **The two keystore passwords** (store password + key password).

### One-time setup
Create `android/local.properties` (this file is gitignored — never committed):

```
sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
DYLANDOS_RELEASE_STORE_PASSWORD=your_store_password_here
DYLANDOS_RELEASE_KEY_PASSWORD=your_key_password_here
```

(Or set them as Windows environment variables instead — the build reads
either. `setx DYLANDOS_RELEASE_STORE_PASSWORD xxxx` then restart the terminal.)

### Build
```
cd android
gradlew.bat assembleFirestickRelease
gradlew.bat assemblePremiumRelease        (optional — same app, arm64)
```

### Outputs
```
android/app/build/outputs/apk/firestick/release/app-firestick-release.apk
android/app/build/outputs/apk/premium/release/app-premium-release.apk
```

### Verify the APK is signed
```
"%ANDROID_HOME%\build-tools\36.0.0\apksigner.bat" verify --print-certs ^
  android\app\build\outputs\apk\firestick\release\app-firestick-release.apk
```

### SHA-256 for OTA (PowerShell)
```
Get-FileHash android\app\build\outputs\apk\firestick\release\app-firestick-release.apk -Algorithm SHA256
```

### Ship it manually (only until CI is set up)
1. Bump `versionCode` (86, 87, …) and `versionName` in `android/app/build.gradle.kts`.
2. Upload the APK somewhere with a direct link (GitHub Release or Dropbox).
3. Edit the OTA gist `6056e65b41641393abf585cecbd92d07` → `update.json`:
   `versionCode`, `version`, `flavors.firestick.apkUrl`, `apkSize`, `sha256`
   (the app refuses updates without a SHA-256 — that's intentional and safe).

---

## Part 2 — GitHub setup so CI builds the APKs for you

The pipeline already exists in the repo (`.github/workflows/android-release.yml`):
tag push → runs tests → builds + signs both flavors → creates a Release with
the APKs → computes SHA-256 → auto-updates your OTA gist. Nothing to write.

### Step 1 — Push the repo
- **Easiest:** GitHub Desktop → *File → Add local repository…* → this folder →
  sign in → **Publish repository**.
- **Or command line:** `gh auth login` then
  `gh repo create iptv-ultimate --private --source . --push`.

### Step 2 — Add the 4 secrets (one-time, ~5 minutes)
Repo on GitHub → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret name | Value |
|---|---|
| `DYLANDOS_KEYSTORE_BASE64` | Base64 of the .jks. Generate with:<br>**PowerShell:** `[Convert]::ToBase64String([IO.File]::ReadAllBytes("android\app\dylandos-release.jks"))`<br>**macOS/Linux:** `base64 -i android/app/dylandos-release.jks \| tr -d '\n'` |
| `DYLANDOS_RELEASE_STORE_PASSWORD` | keystore store password |
| `DYLANDOS_RELEASE_KEY_PASSWORD` | keystore key password |
| `GIST_TOKEN` | GitHub token with **gist** write scope — https://github.com/settings/personal-access-tokens/new (fine-grained, only "Gists" access) |

### Step 3 — Ship a version
1. In `android/app/build.gradle.kts`, bump `versionCode` **and** `versionName`.
   Commit + push.
2. Trigger the pipeline — either:
   - `git tag v4.11.0` → `git push origin v4.11.0`, **or**
   - **No git needed:** repo web page → **Releases → Create a new release** →
     type `v4.11.0` as the tag → **Publish release**. Creating the tag via the
     web page triggers the workflow too.

### Step 4 — Watch it
Repo → **Actions** tab → "Android Build & OTA Release". The first run downloads
Gradle + dependencies (~5–10 min). When green: APKs are on the Release page,
and the OTA gist is already updated — your installed users get the update
prompt on next launch.

---

## Rules that will save you
- **Bump `versionCode` on EVERY release** — the app compares version codes,
  not names. If it doesn't go up, nobody sees the update.
- **Never lose the keystore + passwords.** No keystore = no future updates.
  Keep the .jks and the base64 string in at least two places.
- **CI needs all 4 secrets before the first tag**, or the signing step stops
  the build with a clear error.
- **Keystore is now gitignored** — a fresh clone won't have it. That's normal;
  local builds read `android/local.properties`, CI reads the secret.
