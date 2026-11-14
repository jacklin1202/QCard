@echo off
rem QCard one-click reconnect: restarts the QCard phone helper from the PC.
rem Works for LDPlayer (adb bundled) or any adb on PATH. ASCII only.
setlocal
title QCard Reconnect

set "ADB="
if exist "C:\LDPlayer\LDPlayer14\adb.exe" set "ADB=C:\LDPlayer\LDPlayer14\adb.exe"
if not defined ADB if exist "C:\LDPlayer\LDPlayer9\adb.exe" set "ADB=C:\LDPlayer\LDPlayer9\adb.exe"
if not defined ADB (
  where adb >nul 2>nul
  if not errorlevel 1 set "ADB=adb"
)
if not defined ADB (
  echo [QCard] adb not found. Install LDPlayer, or put Android platform-tools on PATH.
  pause
  exit /b 1
)
echo [QCard] Using adb: %ADB%

"%ADB%" start-server >nul 2>nul

set "SERIAL="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
  if "%%B"=="device" if not defined SERIAL set "SERIAL=%%A"
)
if not defined SERIAL (
  echo [QCard] No device found. Start your emulator ^(or plug in your phone^) first, then run again.
  pause
  exit /b 1
)
echo [QCard] Target device: %SERIAL%

echo [QCard] Opening QCard once so it can prepare the start script...
"%ADB%" -s %SERIAL% shell monkey -p io.github.jacklin1202.qcard -c android.intent.category.LAUNCHER 1 >nul 2>nul
ping -n 4 127.0.0.1 >nul

echo [QCard] Starting the phone helper...
"%ADB%" -s %SERIAL% shell sh /sdcard/Android/data/io.github.jacklin1202.qcard/files/usb-start.sh
ping -n 3 127.0.0.1 >nul

"%ADB%" -s %SERIAL% shell pidof io.github.jacklin1202.qcard:helper >nul 2>nul
if errorlevel 1 (
  echo [QCard] Helper is still not running.
  echo [QCard] Open QCard, read the banner message, then run this file again.
) else (
  echo [QCard] Helper is running. You are good to go.
)
pause
endlocal
