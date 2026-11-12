@echo off
setlocal
title dc- Build Standalone Windows EXE

cd /d "%~dp0"

echo ========================================================
echo   dc- Standalone Windows EXE Compiler (PyInstaller)
echo ========================================================
echo.

python -m pip install --upgrade pyinstaller -r requirements.txt
if %errorlevel% neq 0 (
    echo [!] Failed installing PyInstaller or requirements.
    pause
    exit /b %errorlevel%
)

echo [*] Compiling standalone Windows executable...
pyinstaller --onefile --noconfirm --name "dc-windows" --add-data "web/index.html;web" main.py

if %errorlevel% equ 0 (
    echo.
    echo ========================================================
    echo [+] BUILD SUCCESSFUL!
    echo [+] Executable location: %~dp0dist\dc-windows.exe
    echo ========================================================
) else (
    echo.
    echo [!] Build failed with error code %errorlevel%
)

pause
