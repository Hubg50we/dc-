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
python3 -m pip install -r requirements.txt --break-system-packages 2>/dev/null || \
pip install -r requirements.txt --break-system-packages 2>/dev/null || \
pip install -r requirements.txt

# Ensure start.sh has execute permissions
chmod +x start.sh 2>/dev/null || true

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

# Create global shortcut 'dc' in Termux
if [ -n "$PREFIX" ] && [ -d "$PREFIX/bin" ]; then
    cat << 'EOF' > "$PREFIX/bin/dc"
#!/data/data/com.termux/files/usr/bin/bash
DIR="$HOME/dc-"
if [ ! -d "$DIR" ]; then
    DIR="$(pwd)"
fi
cd "$DIR" && bash start.sh "$@"
EOF
    chmod +x "$PREFIX/bin/dc" 2>/dev/null || true
    echo "[+] Created instant shortcut command: 'dc'"
fi

echo ""
echo "========================================================"
echo " Fully Mobile Setup Complete! "
echo ""
echo " Just type:  dc"
echo " (This automatically starts the bot and opens the UI in your phone's browser!)"
echo "========================================================"
