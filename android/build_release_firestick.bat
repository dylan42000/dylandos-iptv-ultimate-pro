@echo off
REM ═══════════════════════════════════════════════════════════════════
REM DYLANDOS IPTV ULTIMATE v5.0.0
REM BUILD: FIRESTICK SIGNED RELEASE APK
REM ═══════════════════════════════════════════════════════════════════
REM
REM JDK:     C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
REM OS:      Windows 11 Pro
REM OUTPUT:  android\app\build\outputs\apk\firestick\release\
REM KEYSTORE: android\app\dylandos-release.jks
REM PASSWORD: dylandos123 (store + key)
REM ALIAS:    dylandos
REM
REM ═══════════════════════════════════════════════════════════════════

echo.
echo ╔══════════════════════════════════════════════════════════════╗
echo ║       DYLANDOS IPTV ULTIMATE v5.0.0                        ║
echo ║       FIRESTICK SIGNED RELEASE BUILD                       ║
echo ╚══════════════════════════════════════════════════════════════╝
echo.

REM ── Set JDK 21 ────────────────────────────────────────────────
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [INFO] JAVA_HOME: %JAVA_HOME%
echo.
"%JAVA_HOME%\bin\java" -version 2>&1
echo.

REM ── Set signing credentials (env vars override local.properties) ───
set "DYLANDOS_RELEASE_STORE_PASSWORD=dylandos123"
set "DYLANDOS_RELEASE_KEY_PASSWORD=dylandos123"

REM ── Verify keystore exists ─────────────────────────────────────
if not exist "app\dylandos-release.jks" (
    echo [ERROR] Keystore not found at app\dylandos-release.jks
    echo.        Make sure dylandos-release.jks is in the android\app\ folder.
    pause
    exit /b 1
)
echo [OK] Keystore found: app\dylandos-release.jks

REM ── Verify gradle wrapper ──────────────────────────────────────
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [ERROR] Gradle wrapper jar missing at gradle\wrapper\gradle-wrapper.jar
    echo.        Run: gradle wrapper (requires Gradle installed)
    pause
    exit /b 1
)
echo [OK] Gradle wrapper found

cd /d "%~dp0"

echo.
echo ================================================
echo Step 1: Clean old build artifacts
echo ================================================
call gradlew.bat clean
if %ERRORLEVEL% NEQ 0 (
    echo [WARN] Clean had issues, continuing anyway...
)

echo.
echo ================================================
echo Step 2: Building assembleFirestickRelease
echo ================================================
echo.
echo NOTE: Keystore passwords are set via environment variables.
echo       local.properties also contains them as fallback.
echo.

call gradlew.bat assembleFirestickRelease

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ╔══════════════════════════════════════════════════════════╗
    echo ║   ✓ BUILD SUCCESSFUL!                                  ║
    echo ╚══════════════════════════════════════════════════════════╝
    echo.
    echo APK Location:
    echo   %~dp0app\build\outputs\apk\firestick\release\
    echo.
    echo Files:
    dir /b "%~dp0app\build\outputs\apk\firestick\release\*.apk" 2>nul
    echo.
    echo Install on Firestick:
    echo   adb connect FIRESTICK_IP:5555
    echo   adb install "%~dp0app\build\outputs\apk\firestick\release\app-firestick-release.apk"
    echo.
    start "" "%~dp0app\build\outputs\apk\firestick\release"
) else (
    echo.
    echo ╔══════════════════════════════════════════════════════════╗
    echo ║   ✗ BUILD FAILED!                                      ║
    echo ╚══════════════════════════════════════════════════════════╝
    echo.
    echo TROUBLESHOOTING:
    echo   1. Verify JAVA_HOME points to JDK 21:
    echo        C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
    echo.
    echo   2. Verify ANDROID_HOME is set:
    echo        set ANDROID_HOME=C:\Users\YOUR_USER\AppData\Local\Android\Sdk
    echo.
    echo   3. Verify keystore exists:
    echo        android\app\dylandos-release.jks
    echo.
    echo   4. Run clean and retry:
    echo        gradlew.bat clean
    echo        gradlew.bat assembleFirestickRelease --info
    echo.
    echo   5. Check proxy/firewall if Gradle can't download deps
    pause
    exit /b 1
)

pause
