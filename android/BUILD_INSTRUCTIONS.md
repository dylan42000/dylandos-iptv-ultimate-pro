# DYLANDOS IPTV ULTIMATE - Android Build Instructions

## Quick Start

### 1. Build Debug APK

```bash
cd android
./gradlew assembleDebug
```

The APK will be located at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### 2. Install on Device

#### Android Phone/Tablet (USB)
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Firestick (WiFi)
```bash
# Enable ADB debugging on Firestick:
# Settings > My Fire TV > Developer Options > ADB Debugging ON

# Connect to Firestick
adb connect <firestick-ip-address>:5555

# Install APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. First Run Setup

1. Launch "DYLANDOS IPTV ULTIMATE" from app drawer
2. Tap on Settings (gear icon)
3. Enter your Xtream Codes credentials:
   - Server URL (e.g., `http://example.com:8080`)
   - Username
   - Password
4. Tap "Save Configuration"
5. Go back to Home
6. Tap "Live TV" to start watching

## Build Variants

### Debug Build (Development)
- Includes logging
- Larger APK size
- Not minified
- Debugging enabled

```bash
./gradlew assembleDebug
```

### Release Build (Production)
- ProGuard enabled
- Minified and optimized
- Smaller APK size
- Requires signing key

```bash
./gradlew assembleRelease
```

## File Sizes

- **Debug APK**: ~35-45 MB
- **Release APK**: ~20-25 MB (after ProGuard)

## Testing Commands

### List all tasks
```bash
./gradlew tasks
```

### Run unit tests
```bash
./gradlew test
```

### Clean build
```bash
./gradlew clean
```

### Check dependencies
```bash
./gradlew dependencies
```

## Common Issues

### Gradle wrapper not executable
```bash
chmod +x gradlew
```

### Java version mismatch
Ensure JDK 17 is installed:
```bash
java -version
# Should show: openjdk version "17.x.x"
```

### Android SDK not found
Set ANDROID_HOME environment variable:
```bash
export ANDROID_HOME=/path/to/Android/Sdk
```

---

**Need help?** Check the main [README.md](README.md) for full documentation.
