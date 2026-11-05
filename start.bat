@echo off
setlocal
title dc- Discord Voice Controller (Windows PC)

cd /d "%~dp0"

:: 1. If precompiled standalone executable exists, launch it directly!
if exist "dist\dc-windows.exe" (
    echo [*] Starting dc- Standalone Windows Executable...
    "dist\dc-windows.exe"
    if %errorlevel% neq 0 (
        echo [!] Exited with code %errorlevel%
        pause
    )
    exit /b %errorlevel%
)

if exist "dc-windows.exe" (
    echo [*] Starting dc- Standalone Windows Executable...
    "dc-windows.exe"
    if %errorlevel% neq 0 (
        echo [!] Exited with code %errorlevel%
        pause
    )
    exit /b %errorlevel%
)

:: 2. Otherwise run with Python runtime
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Python was not found!
    echo [*] Running install.bat to set up Python...
    call "%~dp0install.bat"
    exit /b %errorlevel%
)

echo [*] Starting dc- Discord Voice Controller on Windows PC...
python main.py

if %errorlevel% neq 0 (
    echo.
    echo [!] dc- exited with error code %errorlevel%
    pause
)
