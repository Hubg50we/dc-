#!/bin/bash
# ========================================================
# Auto Installer for Termux & Linux
# ========================================================

set -e

echo "[+] Starting setup for dc-..."

# Check if running in Termux
if [ -d "/data/data/com.termux" ]; then
    echo "[+] Termux environment detected."
    echo "[+] Updating packages..."
    pkg update -y
    echo "[+] Installing Python and Git..."
    pkg install -y python git
else
    echo "[+] Standard Linux environment detected."
    if command -v apt-get &> /dev/null; then
        sudo apt-get update -y
        sudo apt-get install -y python3 python3-pip git
    fi
fi

# Upgrade pip
echo "[+] Upgrading pip..."
python3 -m pip install --upgrade pip 2>/dev/null || pip install --upgrade pip

# Install dependencies
echo "[+] Installing Python dependencies..."
pip install -r requirements.txt

# Create .env if not present
if [ ! -f ".env" ]; then
    echo "[+] Creating .env from .env.example..."
    cp .env.example .env
fi

# Create tokens.txt if not present
if [ ! -f "tokens.txt" ]; then
    echo "[+] Creating tokens.txt..."
    cp tokens.txt.example tokens.txt
fi

echo ""
echo "========================================================"
echo " Setup complete! "
echo " Next steps:"
echo " 1. Edit .env or tokens.txt with your server ID and tokens:"
echo "    nano .env"
echo "    nano tokens.txt"
echo " 2. Run the bot:"
echo "    bash start.sh"
echo "========================================================"
