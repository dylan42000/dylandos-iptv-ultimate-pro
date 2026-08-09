@echo off
REM DYLANDOS IPTV ULTIMATE - Android Build Script
REM Version: 1.5.0-ULTRA

echo ================================================
echo DYLANDOS IPTV ULTIMATE - Android Build
echo Version: 1.5.0-ULTRA
echo ================================================
echo.

REM ── Set JDK 21 ────────────────────────────────────────────────
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Check if Gradle wrapper exists
if not exist ".\gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: Gradle wrapper jar is missing!
    echo.
    echo Please download gradle-wrapper.jar:
    echo 1. Go to: https://services.gradle.org/distributions/gradle-8.2-bin.zip
    echo 2. Extract gradle-8.2-bin.zip
    echo 3. Copy gradle-wrapper.jar from gradle-8.2\lib\plugins\ to .\gradle\wrapper\
    echo.
    echo Or run: choco install gradle (if you have Chocolatey)
    echo Then run: gradle wrapper
    pause
    exit /b 1
)

:menu
echo.
echo Select build variant:
echo 1. Firestick Debug (ARMv7 - for testing)
echo 2. Firestick Release (ARMv7 - for Fire TV Stick 4K)
echo 3. Premium Debug (ARM64 - for testing)
echo 4. Premium Release (ARM64 - for high-end devices)
echo 5. Build ALL variants
echo 6. Clean build
echo 7. Exit
echo.
set /p choice="Enter choice (1-7): "

if "%choice%"=="1" goto firestick_debug
if "%choice%"=="2" goto firestick_release
if "%choice%"=="3" goto premium_debug
if "%choice%"=="4" goto premium_release
if "%choice%"=="5" goto build_all
if "%choice%"=="6" goto clean
if "%choice%"=="7" goto end
goto menu

:firestick_debug
echo.
echo Building Firestick DEBUG (ARMv7)...
echo.
call gradlew.bat assembleFirestickDebug
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Build successful!
    echo APK location: app\build\outputs\apk\firestick\debug\
    start "" "app\build\outputs\apk\firestick\debug"
) else (
    echo ✗ Build failed! Check error messages above.
)
pause
goto menu

:firestick_release
echo.
echo Building Firestick RELEASE (ARMv7)...
echo.
call gradlew.bat assembleFirestickRelease
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Build successful!
    echo APK location: app\build\outputs\apk\firestick\release\
    start "" "app\build\outputs\apk\firestick\release"
) else (
    echo ✗ Build failed! Check error messages above.
)
pause
goto menu

:premium_debug
echo.
echo Building Premium DEBUG (ARM64)...
echo.
call gradlew.bat assemblePremiumDebug
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Build successful!
    echo APK location: app\build\outputs\apk\premium\debug\
    start "" "app\build\outputs\apk\premium\debug"
) else (
    echo ✗ Build failed! Check error messages above.
)
pause
goto menu

:premium_release
echo.
echo Building Premium RELEASE (ARM64)...
echo.
call gradlew.bat assemblePremiumRelease
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Build successful!
    echo APK location: app\build\outputs\apk\premium\release\
    start "" "app\build\outputs\apk\premium\release"
) else (
    echo ✗ Build failed! Check error messages above.
)
pause
goto menu

:build_all
echo.
echo Building ALL variants...
echo.
call gradlew.bat assembleFirestickDebug assembleFirestickRelease assemblePremiumDebug assemblePremiumRelease
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ All builds successful!
    echo.
    echo APK locations:
    echo - Firestick Debug:   app\build\outputs\apk\firestick\debug\
    echo - Firestick Release: app\build\outputs\apk\firestick\release\
    echo - Premium Debug:     app\build\outputs\apk\premium\debug\
    echo - Premium Release:   app\build\outputs\apk\premium\release\
    echo.
    start "" "app\build\outputs\apk"
) else (
    echo ✗ Build failed! Check error messages above.
)
pause
goto menu

:clean
echo.
echo Cleaning build artifacts...
echo.
call gradlew.bat clean
if %ERRORLEVEL% EQU 0 (
    echo ✓ Clean successful!
) else (
    echo ✗ Clean failed!
)
pause
goto menu

:end
echo.
echo Thank you for using DYLANDOS IPTV ULTIMATE Build Tool!
echo.
pause
exit /b 0
