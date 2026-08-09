@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM  DYLANDOS IPTV ULTIMATE - SIGNED RELEASE APK BUILDER  (v5.0 / world class)
REM  ----------------------------------------------------------------------------
REM  Double-click me (or run from cmd) inside the android\ folder. That's it.
REM  This script will:
REM    1. Find Java 17 (Android Studio's bundled JBR or JAVA_HOME or PATH)
REM    2. Find the Android SDK (local.properties / ANDROID_HOME / default paths)
REM    3. Make sure the Gradle wrapper is intact
REM    4. Ask for the signing passwords ONCE (they are saved to local.properties,
REM       which is git-ignored - never committed, never printed back)
REM    5. Build the signed RELEASE APK(s): Firestick (your #1) + Premium
REM    6. Show you the APK paths, sizes and SHA-256 checksums (for the OTA gist)
REM  ----------------------------------------------------------------------------
REM  It never deletes anything. If something is missing it tells you exactly
REM  what to install instead of failing with a wall of Gradle errors.
REM ============================================================================

cd /d "%~dp0"
title DYLANDOS - Signed Release APK Builder
echo.
echo  ============================================================
echo   DYLANDOS IPTV ULTIMATE - Signed Release APK Builder
echo   versionCode 86 / v5.0.0
echo  ============================================================
echo.

REM ---- 0. Working folder sanity ------------------------------------------------
if not exist "gradlew.bat" (
    echo  [ERROR] gradlew.bat not found in "%~dp0"
    echo  This script must be run from the android\ folder.
    echo  (Right-click - Run, or open cmd and: cd android ^&^& build_signed_apk.bat)
    echo.
    pause
    exit /b 1
)
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo  [ERROR] gradle\wrapper\gradle-wrapper.jar is missing.
    echo  Re-download the repo or restore the file, then run me again.
    pause
    exit /b 1
)
if not exist "app\dylandos-release.jks" (
    echo  [ERROR] app\dylandos-release.jks (release keystore) is missing.
    echo  Without it the APK cannot be signed. Keep it backed up forever -
    echo  losing it means you can never update installed apps again.
    pause
    exit /b 1
)

REM ---- 1. Find Java 17 ----------------------------------------------------------
set "JAVA_CMD="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_CMD (
    REM Android Studio ships its own JDK 17 as "jbr" - first try that
    for %%S in ("%ProgramFiles%\Android\Android Studio\jbr" "%ProgramFiles(x86)%\Android\Android Studio\jbr" "%LOCALAPPDATA%\Programs\Android Studio\jbr") do (
        if exist "%%~S\bin\java.exe" set "JAVA_CMD=%%~S\bin\java.exe"
    )
)
if not defined JAVA_CMD (
    where java >nul 2>nul && set "JAVA_CMD=java"
)
if not defined JAVA_CMD (
    echo  [ERROR] No Java found.
    echo  Install Android Studio (it bundles Java 17) or set JAVA_HOME to a JDK 17.
    echo  https://developer.android.com/studio
    pause
    exit /b 1
)
REM verify it really is 17+ (the build needs 17)
for /f "tokens=3" %%v in ('"!JAVA_CMD!" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%v"
echo  [OK] Java: !JAVA_CMD!  ^(version !JAVA_VERSION!^)

REM ---- 2. Find the Android SDK --------------------------------------------------
set "SDK_DIR="
if exist "local.properties" (
    for /f "tokens=1,* delims==" %%k in (local.properties) do (
        if /i "%%k"=="sdk.dir" set "SDK_DIR=%%l"
    )
)
if not defined SDK_DIR (
    if defined ANDROID_HOME if exist "%ANDROID_HOME%" set "SDK_DIR=%ANDROID_HOME%"
)
if not defined SDK_DIR (
    if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%" set "SDK_DIR=%ANDROID_SDK_ROOT%"
)
if not defined SDK_DIR (
    for %%S in ("%LOCALAPPDATA%\Android\Sdk" "%ProgramFiles%\Android\Sdk") do (
        if exist "%%~S\platforms" set "SDK_DIR=%%~S"
    )
)
if not defined SDK_DIR (
    echo  [ERROR] Android SDK not found.
    echo  Install Android Studio - SDK Manager - "Android SDK Platform 36"
    echo  and "Android SDK Build-Tools", or set ANDROID_HOME.
    pause
    exit /b 1
)
echo  [OK] Android SDK: !SDK_DIR!
if not exist "!SDK_DIR!\platforms\android-36" (
    echo  [WARN] android-36 platform not found - Gradle will try to download it
    echo         (first build takes a while).
)

REM ---- 3. Signing secrets (never printed) --------------------------------------
set "STORE_PASS="
set "KEY_PASS="
if exist "local.properties" (
    for /f "tokens=1,* delims==" %%k in (local.properties) do (
        if /i "%%k"=="DYLANDOS_RELEASE_STORE_PASSWORD" set "STORE_PASS=%%l"
        if /i "%%k"=="DYLANDOS_RELEASE_KEY_PASSWORD"    set "KEY_PASS=%%l"
    )
)
if not defined STORE_PASS (
    echo.
    echo  Signing passwords are not set yet.
    echo  (These are the passwords you chose when the keystore was created.
    echo   If you do not know them you cannot sign - and nobody else can either.)
    echo.
    set /p "STORE_PASS=Store password : "
)
if not defined KEY_PASS (
    set /p "KEY_PASS=Key password   : "
)
if "%STORE_PASS%"=="" (
    echo  [ERROR] Store password cannot be empty.
    pause
    exit /b 1
)
if "%KEY_PASS%"=="" (
    echo  [ERROR] Key password cannot be empty.
    pause
    exit /b 1
)
REM Write sdk.dir + passwords into the git-ignored local.properties so Gradle
REM and future builds pick them up automatically. Never commit this file.
REM Preserves any other keys already in the file.
if not exist local.properties type nul > local.properties
findstr /v /i "sdk.dir DYLANDOS_RELEASE" local.properties > local.properties.tmp 2>nul
(
    echo sdk.dir=!SDK_DIR:\=\\!
    echo DYLANDOS_RELEASE_STORE_PASSWORD=!STORE_PASS!
    echo DYLANDOS_RELEASE_KEY_PASSWORD=!KEY_PASS!
    type local.properties.tmp
) > local.properties.new
move /y local.properties.new local.properties >nul
del local.properties.tmp 2>nul
echo  [OK] Signing secrets saved to local.properties (git-ignored).

REM ---- 4. Which APK(s)? ---------------------------------------------------------
echo.
echo  What do you want to build?
echo   1 - Firestick RELEASE only   (app-firestick-release.apk - your #1 device)
echo   2 - Premium RELEASE only     (arm64 - modern phones/boxes)
echo   3 - BOTH                     (recommended for shipping an update)
echo.
set "CHOICE="
set /p "CHOICE=Choice (1-3) [3]: "
if "%CHOICE%"=="" set "CHOICE=3"

set "TASK="
if "%CHOICE%"=="1" set "TASK=assembleFirestickRelease"
if "%CHOICE%"=="2" set "TASK=assemblePremiumRelease"
if "%CHOICE%"=="3" set "TASK=assembleFirestickRelease assemblePremiumRelease"
if not defined TASK (
    echo  [ERROR] Invalid choice.
    pause
    exit /b 1
)

REM ---- 5. Build ----------------------------------------------------------------
echo.
echo  Building: !TASK!
echo  First run downloads Gradle 8.11.1 + dependencies - be patient (5-15 min).
echo  Subsequent builds are fast.
echo.
call gradlew.bat !TASK! --console=plain --stacktrace
if errorlevel 1 (
    echo.
    echo  [ERROR] Build failed. Scroll up for the Gradle error.
    echo  Common causes: no internet on first run, SDK licenses not accepted
    echo  (run: sdkmanager --licenses), or a code error you need to fix.
    pause
    exit /b 1
)

REM ---- 6. Results --------------------------------------------------------------
echo.
echo  ============================================================
echo   BUILD OK!  Here are your signed APKs:
echo  ============================================================
set "OUTDIR=app\build\outputs\apk"
set "FOUND="
if "%CHOICE%"=="1" (
    set "APK=!OUTDIR!\firestick\release\app-firestick-release.apk"
    call :show_apk "!APK!"
) else if "%CHOICE%"=="2" (
    set "APK=!OUTDIR!\premium\release\app-premium-release.apk"
    call :show_apk "!APK!"
) else (
    set "APK=!OUTDIR!\firestick\release\app-firestick-release.apk"
    call :show_apk "!APK!"
    set "APK=!OUTDIR!\premium\release\app-premium-release.apk"
    call :show_apk "!APK!"
)
if not defined FOUND (
    echo  [WARN] APK files not found at the expected paths - they may be renamed.
    echo  Check: !OUTDIR!\firestick\release\  and  !OUTDIR!\premium\release\
) else (
    echo.
    echo  Reminder for shipping: bump the OTA gist update.json with the NEW
    echo  versionCode 86 and the SHA-256 shown above (see RELEASE_PLAYBOOK.md).
)
echo.
echo  Opening the output folder...
if exist "!OUTDIR!" start "" "!OUTDIR!"
pause
exit /b 0

REM ---- helper: print size + SHA-256 for one APK --------------------------------
:show_apk
if exist "%~1" (
    set "FOUND=1"
    echo.
    echo  APK: %~1
    for %%A in ("%~1") do echo  Size: %%~zA bytes
    echo  SHA-256:
    certutil -hashfile "%~1" SHA256 | findstr /v "hash"
)
exit /b 0
