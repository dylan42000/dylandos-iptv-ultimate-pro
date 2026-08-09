# GRADLE WRAPPER FIX GUIDE

## Problem
The Gradle wrapper jar (`gradle-wrapper.jar`) is missing from `android/gradle/wrapper/`.

## Solution (Choose ONE method)

### Method 1: Download Gradle Wrapper Jar Directly
1. Download: https://services.gradle.org/distributions/gradle-8.2-bin.zip
2. Extract the ZIP file
3. Copy `gradle-8.2/lib/plugins/gradle-wrapper.jar` 
4. Paste into `android/gradle/wrapper/gradle-wrapper.jar`
5. Run `build_android.bat` again

### Method 2: Install Gradle and Regenerate Wrapper (Windows)
```powershell
# Install Gradle via Chocolatey
choco install gradle

# Navigate to android folder
cd "d:\downloads\ULTIMATE IPTV NEW FUTURE\Ultimate iptv Pro\android"

# Regenerate wrapper
gradle wrapper --gradle-version 8.2

# Build
.\build_android.bat
```

### Method 3: Install Gradle and Regenerate Wrapper (Linux/macOS)
```bash
# Install Gradle (Ubuntu/Debian)
sudo apt install gradle

# Or macOS with Homebrew
brew install gradle

# Navigate to android folder
cd android/

# Regenerate wrapper
gradle wrapper --gradle-version 8.2

# Make executable
chmod +x build_android.sh

# Build
./build_android.sh
```

### Method 4: Use Android Studio
1. Open `android/` folder in Android Studio
2. Android Studio will automatically download Gradle wrapper
3. Use Build menu → "Build Bundle(s) / APK(s)" → "Build APK(s)"
4. Or run from terminal in Android Studio: 
   - `./gradlew assembleFirestickRelease`
   - `./gradlew assemblePremiumRelease`

## Verification
After fixing, you should see:
```
android/
  gradle/
    wrapper/
      gradle-wrapper.jar  ← THIS FILE MUST EXIST
      gradle-wrapper.properties
```

## Quick Download Link
Direct download (8.2): 
https://services.gradle.org/distributions/gradle-8.2-bin.zip

## Once Fixed
Run the build script:
- **Windows**: Double-click `build_android.bat`
- **Linux/macOS**: `./build_android.sh`

Or build manually:
```bash
# Firestick (ARMv7 - Fire TV Stick 4K)
./gradlew assembleFirestickRelease

# Premium (ARM64 - High-end devices)
./gradlew assemblePremiumRelease

# Both
./gradlew assembleFirestickRelease assemblePremiumRelease
```

## Expected Output
```
app/build/outputs/apk/firestick/release/
  app-firestick-release.apk  ← Install on Fire TV Stick 4K

app/build/outputs/apk/premium/release/
  app-premium-release.apk    ← Install on Shield, Chromecast, etc.
```
