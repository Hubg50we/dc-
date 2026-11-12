@echo off
setlocal
title dc- 24/7 Server Runner (Windows)

cd /d "%~dp0"

echo ========================================================
echo          dc- 24/7 Dedicated Server Runner
echo ========================================================

:: Check Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Python is not installed or not in PATH!
    echo [*] Running install.bat...
    call "%~dp0install.bat"
    exit /b %errorlevel%
)

:: Launch StreamBot engine if present
if exist "streambot\dist\index.js" (
    where node >nul 2>&1
    if %errorlevel% equ 0 (
        echo [*] Launching StreamBot Media Relay on port 8080...
        start /b "" cmd /c "cd /d ""%~dp0streambot"" && node dist/index.js"
    )
)

echo [*] Starting dc- Web Server on http://localhost:5000 ...
python main.py

if %errorlevel% neq 0 (
    echo [!] Server exited with error code %errorlevel%
    pause
)
