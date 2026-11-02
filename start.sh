#!/bin/bash
# ========================================================
# Runner script with Termux wake-lock support
# ========================================================

# Prevent Termux from sleeping in background if termux-wake-lock is available
if command -v termux-wake-lock &> /dev/null; then
    echo "[+] Enabling Termux Wake Lock..."
    termux-wake-lock
fi

# Check if running in no-web mode
NO_WEB=false
for arg in "$@"; do
    case "$arg" in
        --no-web|--noweb|noweb|-nw|--cli|-c)
            NO_WEB=true
            ;;
    esac
done

if [ "$NO_WEB" = false ] && [ "$ENABLE_WEB" != "false" ]; then
    # Automatically open dashboard in default browser on Android
    (
        sleep 2
        if command -v termux-open-url &> /dev/null; then
            termux-open-url http://localhost:5000 2>/dev/null
        elif command -v am &> /dev/null; then
            am start -a android.intent.action.VIEW -d http://localhost:5000 >/dev/null 2>&1
        fi
    ) &
    echo "[+] Starting dc- with Web UI: http://localhost:5000"
else
    echo "[+] Starting dc- in pure terminal mode (No Web / Console Only)..."
fi

# Run python script
if command -v python3 &> /dev/null; then
    python3 main.py "$@"
else
    python main.py "$@"
fi
