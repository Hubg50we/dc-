#!/bin/bash
# ========================================================
# Auto Installer for Termux & Linux
# ========================================================

export DEBIAN_FRONTEND=noninteractive

echo "[+] Starting setup for dc-..."

# Clean potential CRLF line endings
sed -i 's/\r$//' *.sh 2>/dev/null || true

# Check if running in Termux
if [ -d "/data/data/com.termux" ] || [ -n "$TERMUX_VERSION" ]; then
    echo "[+] Termux environment detected."
    echo "[+] Updating package lists (non-interactive)..."
    pkg update -y -o Dpkg::Options::="--force-confold" 2>/dev/null || apt-get update -y 2>/dev/null || true
    echo "[+] Installing Python, Git, and dependencies..."
    pkg install -y python python-pip git 2>/dev/null || pkg install -y python git 2>/dev/null || true
else
    echo "[+] Linux / VPS environment detected."
    if command -v apt-get &> /dev/null; then
        sudo apt-get update -y 2>/dev/null || true
        sudo apt-get install -y python3 python3-pip git 2>/dev/null || true
    fi
fi

# Ensure pip is available
python3 -m ensurepip --upgrade 2>/dev/null || true

# Upgrade pip safely
echo "[+] Upgrading pip..."
python3 -m pip install --upgrade pip --break-system-packages 2>/dev/null || \
pip install --upgrade pip --break-system-packages 2>/dev/null || \
pip install --upgrade pip 2>/dev/null || true

# Install dependencies with PEP 668 compatibility
echo "[+] Installing Python dependencies..."
python3 -m pip install -r requirements.txt --break-system-packages 2>/dev/null || \
pip install -r requirements.txt --break-system-packages 2>/dev/null || \
pip install -r requirements.txt

# Ensure executable permissions on shell scripts
chmod +x start.sh install.sh termux.sh setup_app.sh 2>/dev/null || true

# Run setup_app.sh to customize Termux styling & touch bar
if [ -f "setup_app.sh" ]; then
    bash setup_app.sh
fi

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

# Create global shortcut 'dc' in Termux ($PREFIX/bin/dc)
if [ -n "$PREFIX" ] && [ -d "$PREFIX/bin" ]; then
    cat << 'EOF' > "$PREFIX/bin/dc"
#!/data/data/com.termux/files/usr/bin/bash
DIR="$HOME/dc-"
if [ ! -d "$DIR" ]; then
    DIR="$(pwd)"
fi
cd "$DIR" && bash termux.sh "$@"
EOF
    chmod +x "$PREFIX/bin/dc" 2>/dev/null || true
    echo "[+] Created instant shortcut command: 'dc'"
fi

# Also add alias to bashrc / zshrc as fallback
if [ -f "$HOME/.bashrc" ]; then
    grep -q "alias dc=" "$HOME/.bashrc" || echo 'alias dc="cd ~/dc- && bash termux.sh"' >> "$HOME/.bashrc"
fi

echo ""
echo "========================================================"
echo " 📱 Fully Mobile Termux Setup Complete! "
echo ""
echo " Run dc- anytime in Termux by simply typing:  dc"
echo " (Opens the full-screen interactive TUI menu directly in Termux!)"
echo " Zero browser hassle - 100% controlled in your terminal."
echo "========================================================"
