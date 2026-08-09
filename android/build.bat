@echo off
REM ═══════════════════════════════════════════════════════════════════
REM DYLANDOS IPTV ULTIMATE - Build Script (JDK 21)
REM ═══════════════════════════════════════════════════════════════════
REM JDK 21 at: C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
REM ═══════════════════════════════════════════════════════════════════
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call gradlew.bat assembleDebug
pause
