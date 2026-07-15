@echo off
rem Double-click to open the AVATAR data puller window. No terminal stays open.
start "" powershell -NoProfile -ExecutionPolicy Bypass -Sta -WindowStyle Hidden -File "%~dp0AvatarPuller.ps1"
