@echo off
setlocal enabledelayedexpansion
title dc- Discord Controller - Windows PC Setup & Installer

echo ========================================================
echo        dc- DISCORD VOICE CONTROLLER (WINDOWS PC)
echo ========================================================
echo.

:: 1. Check Python installation
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Python is not detected in your PATH.
    echo [*] Checking for winget...
    winget --version >nul 2>&1
    if %errorlevel% equ 0 (
        echo [*] Installing Python 3.12 via winget...
        winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
        echo [!] Please restart this script after Python completes installing.
        pause
        exit /b 1
    ) else (
        echo [!] Please download and install Python 3.10+ from https://www.python.org/downloads/
        echo [!] Make sure to check "Add Python to PATH" during installation.
        start https://www.python.org/downloads/
        pause
        exit /b 1
    )
)

echo [+] Python is installed:
python --version
echo.

:: 2. Create virtual environment or install dependencies
if exist "requirements.txt" (
    echo [*] Installing required dependencies...
    python -m pip install --upgrade pip
    python -m pip install -r requirements.txt
    if %errorlevel% neq 0 (
        echo [!] Error installing dependencies.
        pause
        exit /b %errorlevel%
    )
    echo [+] Dependencies installed successfully.
)

:: 2.5 Setup StreamBot voice & video streaming engine
if exist "streambot\package.json" (
    where node >nul 2>&1
    if %errorlevel% equ 0 (
        if not exist "streambot\node_modules" (
            echo [*] Installing StreamBot engine dependencies...
            pushd "%~dp0streambot"
            call npm install
            call npm run build
            popd
            echo [+] StreamBot engine prepared.
        )
    )
)

:: 3. Setup .env file
if not exist ".env" (
    if exist ".env.example" (
        echo [*] Initializing .env configuration from template...
        copy ".env.example" ".env" >nul
        echo [+] Created .env
    )
)

:: 4. Setup tokens.txt
if not exist "tokens.txt" (
    if exist "tokens.txt.example" (
        copy "tokens.txt.example" "tokens.txt" >nul
    ) else (
        type nul > "tokens.txt"
    )
    echo [+] Created tokens.txt (Paste your Discord tokens here)
)

:: 5. Create Desktop Shortcut via PowerShell
echo [*] Creating Desktop Shortcut...
powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut([System.IO.Path]::Combine([Environment]::GetFolderPath('Desktop'), 'dc- Discord Controller.lnk')); $s.TargetPath = [System.IO.Path]::Combine('%~dp0', 'start.bat'); $s.WorkingDirectory = '%~dp0'; $s.Description = 'dc- Discord 24/7 Voice Controller & Streaming Studio'; $s.Save()" >nul 2>&1

echo.
echo ========================================================
echo [+] SETUP COMPLETE! 
echo [+] Desktop shortcut created: 'dc- Discord Controller'
echo ========================================================
echo.
echo Launching dc- Controller now...
timeout /t 2 >nul
start "" "%~dp0start.bat"
exit /b 0
