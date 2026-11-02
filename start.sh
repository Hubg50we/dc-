#!/bin/bash
# ========================================================
# Runner script with Termux wake-lock support
# ========================================================

# Clean any Windows CRLF endings if present
sed -i 's/\r$//' "$0" 2>/dev/null || true

# Kill any existing stale instances to prevent duplicate token conflicts
pkill -f "python.*main.py" 2>/dev/null || true

# Prevent Termux from sleeping in background if termux-wake-lock is available
if command -v termux-wake-lock &> /dev/null; then
    echo "[+] Enabling Termux Wake Lock..."
    termux-wake-lock 2>/dev/null || true
fi

# Automatically open dashboard in default browser on Android in background
(
    sleep 2
    if command -v termux-open-url &> /dev/null; then
        termux-open-url http://localhost:5000 2>/dev/null
    elif command -v am &> /dev/null; then
        am start -a android.intent.action.VIEW -d http://localhost:5000 >/dev/null 2>&1
    fi
) &

echo "[+] Starting dc-..."
echo "[+] Web UI Dashboard: http://localhost:5000"

# Run python script
if command -v python3 &> /dev/null; then
    exec python3 main.py "$@"
else
    exec python main.py "$@"
fi
