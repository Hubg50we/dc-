#!/bin/bash
# ========================================================
# Runner script with Termux wake-lock support
# ========================================================

# Prevent Termux from sleeping in background if termux-wake-lock is available
if command -v termux-wake-lock &> /dev/null; then
    echo "[+] Enabling Termux Wake Lock..."
    termux-wake-lock
fi

# Run python script
if command -v python3 &> /dev/null; then
    python3 main.py "$@"
else
    python main.py "$@"
fi
