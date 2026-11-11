#!/usr/bin/env bash
# ========================================================
# dc- 24/7 Dedicated Server Runner (Linux / VPS / Cloud)
# ========================================================
set -e

# Detect architecture & directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================================"
echo "          dc- 24/7 Dedicated Server Runner"
echo "========================================================"

# Check Python 3
if command -v python3 &>/dev/null; then
    PY_BIN="python3"
elif command -v python &>/dev/null; then
    PY_BIN="python"
else
    echo "[!] Error: Python 3 is not installed."
    exit 1
fi

# Check FFmpeg
if ! command -v ffmpeg &>/dev/null; then
    echo "[!] Warning: ffmpeg is not installed. Audio and video streaming may be limited."
    echo "    Install with: sudo apt-get update && sudo apt-get install -y ffmpeg"
fi

# Ensure virtual environment exists
if [ ! -d "venv" ]; then
    echo "[+] Creating Python virtual environment..."
    "$PY_BIN" -m venv venv
fi

# Activate venv
source venv/bin/activate

# Install / update requirements
echo "[+] Checking dependencies..."
pip install --quiet --upgrade pip
pip install --quiet -r requirements.txt yt-dlp || pip install -r requirements.txt

# Create required directories
mkdir -p uploads recordings

# If --daemon flag passed, run with nohup in background
if [ "$1" == "--daemon" ] || [ "$1" == "-d" ]; then
    echo "[+] Launching dc- server in background (24/7 daemon mode)..."
    pkill -f "python.*main.py" 2>/dev/null || true
    nohup python main.py > server.log 2>&1 &
    sleep 2
    if pgrep -f "python.*main.py" >/dev/null; then
        echo "[✓] dc- server is running in background!"
        echo "    Web UI: http://localhost:5000"
        echo "    Logs: tail -f server.log"
    else
        echo "[!] Failed to start daemon. Check server.log for errors."
    fi
    exit 0
fi

# Default: Run foreground server
echo "[+] Starting dc- server on port 5000..."
exec python main.py "$@"
