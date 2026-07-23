@echo off
REM ============================================================
REM  AVATAR streaming - FAST run (no rebuild)
REM
REM  Use this every day. It does NOT rebuild/reinstall the app.
REM  It just: picks the watch, opens the pipe, launches the app,
REM  and starts the PC listener in this window.
REM
REM  Only use stream_test.bat (the full one) after you CHANGE the
REM  app's code - that's the only time a reinstall is needed.
REM
REM  Then on the watch tap "Monitor All". Ctrl+C stops the listener.
REM ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "PKG=com.example.galaxywatch5"
set "ACT=%PKG%/.presentation.MainActivity"

echo ============================================================
echo   AVATAR streaming - fast run (no rebuild)
echo ============================================================
echo.

if not exist "%ADB%" (
  echo [ERROR] adb.exe not found at:
  echo         %ADB%
  goto :fail
)

REM ---------- pick a connected watch ----------
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
  set /p "WATCHIP=Enter watch IP:port (e.g. 192.168.50.30:5555), or Enter to cancel: "
  if "!WATCHIP!"=="" ( echo Cancelled. & goto :fail )
  "%ADB%" connect !WATCHIP!
  echo.
  goto :checkdev
)

echo [OK] Using watch: !SERIAL!
echo.

REM ---------- open the pipe (targeted at this watch) ----------
echo Opening the localhost:9500 pipe...
"%ADB%" -s !SERIAL! reverse tcp:9500 tcp:9500
"%ADB%" -s !SERIAL! reverse --list
echo.

REM ---------- launch the app on the watch ----------
echo Launching AVATAR on the watch...
"%ADB%" -s !SERIAL! shell am start -n "%ACT%" >nul 2>&1
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
