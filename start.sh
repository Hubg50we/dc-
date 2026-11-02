#!/bin/bash
# ========================================================
# dc- Mobile Termux Runner
# 100% Terminal Controlled - Zero Web Browser Popups
# ========================================================

# Clean any Windows CRLF endings if present
sed -i 's/\r$//' "$0" 2>/dev/null || true

# Prevent Termux from sleeping in background if termux-wake-lock is available
if command -v termux-wake-lock &> /dev/null; then
    termux-wake-lock 2>/dev/null || true
fi

# Determine python command
PY_CMD="python3"
if ! command -v python3 &> /dev/null; then
    PY_CMD="python"
fi

# If background flag passed: start daemon
if [ "$1" == "--bg" ] || [ "$1" == "daemon" ]; then
    pkill -f "python.*main.py" 2>/dev/null || true
    echo "[+] Starting dc- background engine..."
    nohup "$PY_CMD" main.py > bot.log 2>&1 &
    sleep 1
    echo "[✓] Background engine started."
    exit 0
fi

# Default: Launch interactive Termux TUI directly
exec "$PY_CMD" tui.py "$@"
