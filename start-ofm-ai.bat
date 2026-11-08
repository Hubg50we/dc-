@echo off
title Our Free Model (OFM) AI Engine - Ebony-Vinyl/dsh-our-free-model
echo ======================================================
echo   Launching Our Free Model (OFM) Proxy on port 18900
echo   Powered by: https://github.com/Ebony-Vinyl/dsh-our-free-model
echo ======================================================
if exist "..\dsh-our-free-model\server.mjs" (
    cd /d "..\dsh-our-free-model"
    node server.mjs
) else if exist "dsh-our-free-model\server.mjs" (
    cd /d "dsh-our-free-model"
    node server.mjs
) else (
    echo [!] Could not locate dsh-our-free-model folder.
    echo Please clone https://github.com/Ebony-Vinyl/dsh-our-free-model next to dc-
    pause
)
