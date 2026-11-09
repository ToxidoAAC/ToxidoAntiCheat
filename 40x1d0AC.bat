@echo off
chcp 65001 >nul
title Toxido Anti Cheat
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-app.ps1"

if errorlevel 1 (
    echo.
    echo Baslatma basarisiz oldu.
    echo Ayrintilar: %APPDATA%\40x1d0AC\launcher.log
    pause
)

endlocal
