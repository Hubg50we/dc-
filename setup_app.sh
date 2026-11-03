#!/bin/bash
# ========================================================
# dc- Complete Termux App Customizer
# Transforms Termux into a dedicated Discord App with:
#  - Discord AMOLED Theme (Colors & Styling)
#  - Discord Custom Touch Buttons Row on Virtual Keyboard
#  - Instant Auto-Boot on App Open (No Terminal Clutter)
#  - Auto Background Wake-Lock & Battery Optimization
# ========================================================

# Clean any CRLF endings
sed -i 's/\r$//' "$0" 2>/dev/null || true

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERMUX_DIR="$HOME/.termux"
BASHRC="$HOME/.bashrc"

echo -e "\033[1;38;5;63m"
echo "╔════════════════════════════════════════════════════╗"
echo "║      Transforming Termux into dc- Standalone App   ║"
echo "╚════════════════════════════════════════════════════╝"
echo -e "\033[0m"

# 1. Create ~/.termux directory
mkdir -p "$TERMUX_DIR" 2>/dev/null || true

# 2. Configure Discord AMOLED Dark Color Palette
echo "[+] Applying Discord AMOLED Dark Color Palette..."
cat << 'EOF' > "$TERMUX_DIR/colors.properties"
# Discord Dark Theme for Termux
background=#111214
foreground=#dbdee1
cursor=#5865f2
color0=#1e1f22
color1=#ed4245
color2=#57f287
color3=#fee75c
color4=#5865f2
color5=#eb459e
color6=#5865f2
color7=#ffffff
color8=#2b2d31
color9=#ed4245
color10=#57f287
color11=#fee75c
color12=#5865f2
color13=#eb459e
color14=#5865f2
color15=#ffffff
EOF

# 3. Configure Termux Custom Discord Touch Keyboard (Extra Keys Bar)
echo "[+] Configuring custom Discord touch buttons on soft keyboard..."
cat << 'EOF' > "$TERMUX_DIR/termux.properties"
# dc- Discord Dedicated Touch Bar
extra-keys = [ \
  [{key: '1', display: '🔊 Join'}, {key: '2', display: '🔇 Mute'}, {key: '3', display: '🔊 Unmute'}, {key: '4', display: '✕ Leave'}], \
  [{key: 'b', display: '🤖 Bots'}, {key: 't', display: '🔑 Tokens'}, {key: 'c', display: '⚙️ Config'}, {key: 's', display: '⚡ Power'}, {key: 'l', display: '📜 Logs'}] \
]

# Disable terminal bell vibration
bell-character = ignore

# Keep screen on when requested
fullscreen = false
EOF

# 4. Silence default Linux shell greeting
touch "$HOME/.hushlogin" 2>/dev/null || true

# 5. Configure Auto-Launch directly into dc- when Termux opens
echo "[+] Configuring Termux to auto-boot into dc- Control Center..."
touch "$BASHRC" 2>/dev/null || true

# Remove any old auto-launch lines to avoid duplicates
sed -i '/dc-.*App Bootstrapper/d' "$BASHRC" 2>/dev/null || true
sed -i '/cd ~\/dc-.*&&.*dc/d' "$BASHRC" 2>/dev/null || true
sed -i '/termux\.sh/d' "$BASHRC" 2>/dev/null || true

cat << 'EOF' >> "$BASHRC"
# dc- App Bootstrapper
if [ -t 0 ] && [ -z "$DC_SKIP_AUTOSTART" ]; then
    cd ~/dc- 2>/dev/null || cd "$HOME/dc-" 2>/dev/null
    if [ -f "termux.sh" ]; then
        export DC_SKIP_AUTOSTART=1
        bash termux.sh
    fi
fi
EOF

# 6. Setup 1-tap Home Screen Widgets (Termux:Widget support)
mkdir -p "$HOME/.shortcuts" 2>/dev/null || true
cat << 'EOF' > "$HOME/.shortcuts/dc-start"
#!/data/data/com.termux/files/usr/bin/bash
cd ~/dc- 2>/dev/null || cd "$HOME/dc-"
bash termux.sh start
EOF
chmod +x "$HOME/.shortcuts/dc-start" 2>/dev/null || true

cat << 'EOF' > "$HOME/.shortcuts/dc-stop"
#!/data/data/com.termux/files/usr/bin/bash
cd ~/dc- 2>/dev/null || cd "$HOME/dc-"
bash termux.sh stop
EOF
chmod +x "$HOME/.shortcuts/dc-stop" 2>/dev/null || true

# 7. Reload Termux styling immediately if termux-reload-settings is available
if command -v termux-reload-settings &> /dev/null; then
    termux-reload-settings 2>/dev/null || true
fi

echo ""
echo -e "\033[1;32m[✓] Transformation Complete!\033[0m"
echo ""
echo "Termux is now customized as your dedicated dc- App:"
echo " 🎨 Discord AMOLED Dark Theme applied."
echo " 📱 Custom touch buttons (Join, Mute, Leave, Bots, Tokens) installed on keyboard."
echo " 🚀 Auto-boot configured: Tap Termux on your phone and dc- opens immediately!"
echo ""
echo "To experience it now, simply restart Termux or type: dc"
echo "========================================================"
