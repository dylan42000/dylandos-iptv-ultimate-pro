# DYLANDOS IPTV ULTIMATE — Push & Build Guide (Windows 11)

## Step 1: Push to GitHub

Open **Git Bash** or **Command Prompt** in your project root:

```bash
git push origin v5.0.0-world-class
```

Then open the PR on GitHub:
```
https://github.com/dylandos/iptv-ultimate/pull/new/v5.0.0-world-class
```

## Step 2: Build the Signed Firestick APK

### Prerequisites
- ✅ JDK 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`
- ✅ Android SDK installed
- ✅ `ANDROID_HOME` set (e.g. `C:\Users\YOUR_USER\AppData\Local\Android\Sdk`)

### Method A: One-Click Build Script (Recommended)
```batch
cd android
build_release_firestick.bat
```

This script will:
1. Set `JAVA_HOME` to your JDK 21 path
2. Set signing passwords as env vars
3. Clean old build artifacts
4. Build `assembleFirestickRelease`
5. Open the output folder when done

### Method B: Manual Build
```batch
cd android
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set DYLANDOS_RELEASE_STORE_PASSWORD=dylandos123
set DYLANDOS_RELEASE_KEY_PASSWORD=dylandos123
gradlew.bat clean
gradlew.bat assembleFirestickRelease
```

### Output APK
```
android/app/build/outputs/apk/firestick/release/app-firestick-release.apk
```

## Step 3: Install on Firestick
```bash
adb connect FIRESTICK_IP:5555
adb install android/app/build/outputs/apk/firestick/release/app-firestick-release.apk
```

## Troubleshooting

### Build fails with "Missing DYLANDOS_RELEASE_STORE_PASSWORD"
The signing config reads from env vars first, then `local.properties`.
Make sure you either:
- Run `build_release_firestick.bat` (sets env vars automatically), OR
- Check `android/local.properties` has the passwords (it's already set up)

### App still crashes on startup
First, get the crash log:
```bash
adb logcat -s AndroidRuntime:D *:S
```
Or to see all Dylandos logs:
```bash
adb logcat -s DYLANDOS:D *:S
```

### Common ADB issues
```bash
adb kill-server
adb start-server
adb connect FIRESTICK_IP:5555
```
