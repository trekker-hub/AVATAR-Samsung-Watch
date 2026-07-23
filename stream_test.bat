@echo off
REM ============================================================
REM  AVATAR watch -> PC streaming: one-click test
REM
REM  Double-click this file (or run it from a terminal). It will:
REM    1. find adb + the watch (offers to connect if none)
REM    2. build & install the updated app on the watch
REM    3. open the localhost:9500 pipe (adb reverse)
REM    4. launch the app on the watch
REM    5. start the PC listener in THIS window
REM
REM  Then on the watch just tap "Monitor All" and data appears here.
REM  Press Ctrl+C to stop the listener.
REM ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PKG=com.example.galaxywatch5"
set "ACT=%PKG%/.presentation.MainActivity"

echo ============================================================
echo   AVATAR watch streaming - one-click test
echo ============================================================
echo.

if not exist "%ADB%" (
  echo [ERROR] adb.exe not found at:
  echo         %ADB%
  echo Install Android Studio / platform-tools, then rerun.
  goto :fail
)

REM ---------- 1. pick a connected watch ----------
:checkdev
set "SERIAL="
"%ADB%" devices > "%TEMP%\avatar_devs.txt" 2>&1
for /f "skip=1 tokens=1,2" %%a in (%TEMP%\avatar_devs.txt) do (
  if "%%b"=="device" if not defined SERIAL set "SERIAL=%%a"
)

if not defined SERIAL (
  echo No watch is connected via adb.
  echo On the watch: Settings ^> Developer options ^> Wireless debugging
  echo shows an IP address and port.
  echo.
  set /p "WATCHIP=Enter watch IP:port (e.g. 192.168.50.30:5555), or press Enter to cancel: "
  if "!WATCHIP!"=="" ( echo Cancelled. & goto :fail )
  echo Connecting to !WATCHIP! ...
  "%ADB%" connect !WATCHIP!
  echo.
  goto :checkdev
)

echo [OK] Using watch: !SERIAL!
REM Pin every adb + Gradle action to THIS watch, so a second device (e.g. the
REM watch showing up twice) can't cause a "more than one device" error.
set "ANDROID_SERIAL=!SERIAL!"
echo.

REM ---------- 2. build + install the updated app ----------
echo [1/4] Building and installing the app on the watch...
echo       (first build can take a minute or two - please wait)
call gradlew.bat :app:installDebug --no-configuration-cache
if errorlevel 1 (
  echo.
  echo [ERROR] Build/install failed - see the messages above.
  goto :fail
)
echo [OK] App installed.
echo.

REM ---------- 3. open the reverse tunnel ----------
echo [2/4] Opening the localhost:9500 pipe...
"%ADB%" -s !SERIAL! reverse tcp:9500 tcp:9500
"%ADB%" -s !SERIAL! reverse --list
echo.

REM ---------- 4. launch the app on the watch ----------
echo [3/4] Launching AVATAR on the watch...
"%ADB%" -s !SERIAL! shell am start -n "%ACT%" >nul 2>&1
echo.

REM ---------- 5. start the listener ----------
echo [4/4] Starting the PC listener on port 9500.
echo.
echo    ============================================================
echo      1. NOW ON THE WATCH: in the AVATAR app, tap "Monitor All"
echo      2. OPEN IN A BROWSER:  http://localhost:9500
echo         (live graphs; data also lands in .\stream_logs\)
echo      Press Ctrl+C to stop.
echo    ============================================================
echo.
start "" http://localhost:9500
python pc_stream_server.py
goto :end

:fail
echo.
pause
exit /b 1

:end
endlocal
