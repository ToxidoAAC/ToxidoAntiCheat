@echo off
start "" powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0scripts\run-app.ps1"
exit /b 0
