#!/bin/bash
# ========================================================
# dc- Advanced Termux Control Center
# ========================================================

# Clean potential CRLF
sed -i 's/\r$//' "$0" 2>/dev/null || true

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# Colors
C_RESET="\033[0m"
C_BOLD="\033[1m"
C_BLURPLE="\033[1;38;5;63m"
C_GREEN="\033[1;32m"
C_RED="\033[1;31m"
C_YELLOW="\033[1;33m"
C_CYAN="\033[1;36m"
C_GRAY="\033[0;90m"

is_running() {
    pgrep -f "python.*main.py" >/dev/null 2>&1
}

start_bot() {
    if is_running; then
        echo -e "${C_YELLOW}[!] Bot is already running in background.${C_RESET}"
        return 0
    fi

    echo -e "${C_CYAN}[+] Acquiring Termux Wake Lock...${C_RESET}"
    termux-wake-lock 2>/dev/null || true

    echo -e "${C_CYAN}[+] Starting dc- engine in background...${C_RESET}"
    if command -v python3 &> /dev/null; then
        nohup python3 main.py > bot.log 2>&1 &
    else
        nohup python main.py > bot.log 2>&1 &
    fi

    echo -e "${C_CYAN}[+] Waiting for server to initialize...${C_RESET}"
    sleep 2

    if is_running; then
        echo -e "${C_GREEN}[✓] Bot successfully started! (PID: $(pgrep -f 'python.*main.py' | head -n1))${C_RESET}"
        echo -e "${C_BLURPLE}[+] Launching Mobile Web UI...${C_RESET}"
        open_browser
    else
        echo -e "${C_RED}[✕] Failed to start bot. Recent error log:${C_RESET}"
        tail -n 10 bot.log 2>/dev/null || true
    fi
}

stop_bot() {
    if ! is_running; then
        echo -e "${C_YELLOW}[!] Bot is not currently running.${C_RESET}"
        return 0
    fi

    echo -e "${C_CYAN}[+] Sending graceful shutdown to accounts...${C_RESET}"
    pkill -f "python.*main.py" 2>/dev/null || true
    sleep 1

    if ! is_running; then
        echo -e "${C_GREEN}[✓] Bot stopped safely.${C_RESET}"
        termux-wake-unlock 2>/dev/null || true
    else
        pkill -9 -f "python.*main.py" 2>/dev/null || true
        echo -e "${C_YELLOW}[✓] Bot process terminated.${C_RESET}"
    fi
}

restart_bot() {
    echo -e "${C_CYAN}[+] Restarting dc-...${C_RESET}"
    stop_bot
    sleep 1
    start_bot
}

open_browser() {
    URL="http://localhost:5000"
    if command -v termux-open-url &> /dev/null; then
        termux-open-url "$URL" 2>/dev/null || true
    elif command -v am &> /dev/null; then
        am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1 || true
    else
        echo -e "${C_CYAN}[*] Open your browser and visit: ${C_BOLD}$URL${C_RESET}"
    fi
}

show_status() {
    if ! is_running; then
        echo -e "${C_RED}[✕] Bot is currently offline.${C_RESET}"
        echo -e "    Start it with: ${C_BOLD}dc start${C_RESET}"
        return 1
    fi

    python3 -c "
import urllib.request, json, sys

try:
    req = urllib.request.urlopen('http://127.0.0.1:5000/api/status', timeout=3)
    data = json.loads(req.read().decode())
    
    print('\033[1;38;5;63m' + '='*52 + '\033[0m')
    print('\033[1m              dc- Termux Live Status\033[0m')
    print('\033[1;38;5;63m' + '='*52 + '\033[0m')
    
    tot = data.get('total_accounts', 0)
    on = data.get('online_accounts', 0)
    vc = data.get('voice_accounts', 0)
    up = data.get('uptime', 0)
    
    print(f' \033[1;32m●\033[0m Accounts: \033[1m{tot}\033[0m  |  Online: \033[1;32m{on}\033[0m  |  Voice: \033[1;38;5;63m{vc}\033[0m  |  Up: \033[1m{up}s\033[0m')
    print(f' \033[1;36m●\033[0m Server: \033[0;90m{data.get(\"default_guild\")}\033[0m  Channel: \033[0;90m{data.get(\"default_channel\")}\033[0m')
    print('\033[0;90m' + '-'*52 + '\033[0m')
    
    accounts = data.get('accounts', [])
    if not accounts:
        print('  No accounts currently loaded.')
    for a in accounts:
        name = a.get('name') or 'Unknown'
        aid = a.get('id')
        v_ch = a.get('voice_channel')
        state = '\033[1;32mON 📱\033[0m' if a.get('online') else '\033[1;31mOFF\033[0m'
        
        flags = []
        if a.get('mute'): flags.append('Muted')
        if a.get('deaf'): flags.append('Deaf')
        f_str = f' ({', '.join(flags)})' if flags else ''
        
        if v_ch:
            v_str = f'\033[1;32m🔊 Voice: {v_ch}\033[0m'
        else:
            v_str = '\033[0;90mIdle\033[0m'
            
        print(f'  [{aid}] \033[1m{name:<14}\033[0m [{state}] {v_str}{f_str}')
    print('\033[1;38;5;63m' + '='*52 + '\033[0m')
except Exception as e:
    print(f'\033[1;31m[!] Error querying status: {e}\033[0m')
" 2>/dev/null || curl -s http://127.0.0.1:5000/api/status
}

show_logs() {
    if [ ! -f "bot.log" ]; then
        echo -e "${C_YELLOW}[!] bot.log does not exist yet.${C_RESET}"
        return 1
    fi
    echo -e "${C_CYAN}[+] Streaming live logs (Press Ctrl+C to exit)...${C_RESET}\n"
    tail -n 25 -f bot.log
}

send_quick_action() {
    ACTION="$1"
    CH="$2"
    if ! is_running; then
        echo -e "${C_RED}[!] Bot is not running. Start with 'dc start' first.${C_RESET}"
        return 1
    fi

    python3 -c "
import urllib.request, json, sys
data = json.dumps({'action': '$ACTION', 'channel_id': '$CH', 'percent': 100}).encode()
req = urllib.request.Request('http://127.0.0.1:5000/api/action', data=data, headers={'Content-Type': 'application/json'})
try:
    res = urllib.request.urlopen(req, timeout=8)
    out = json.loads(res.read().decode())
    print('\033[1;32m[✓]\033[0m ' + out.get('message', 'Action executed'))
except Exception as e:
    print('\033[1;31m[✕] Error:\033[0m', e)
"
}

setup_widgets() {
    echo -e "${C_CYAN}[+] Setting up 1-Tap Home-Screen Widgets for Termux...${C_RESET}"
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

    echo -e "${C_GREEN}[✓] Shortcuts created in ~/.shortcuts!${C_RESET}"
    echo -e "    You can add a widget on your phone screen using ${C_BOLD}Termux:Widget${C_RESET}."
}

setup_boot() {
    echo -e "${C_CYAN}[+] Setting up Termux Auto-Boot on phone restart...${C_RESET}"
    mkdir -p "$HOME/.termux/boot" 2>/dev/null || true

    cat << 'EOF' > "$HOME/.termux/boot/start-dc"
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
sleep 5
cd ~/dc- 2>/dev/null || cd "$HOME/dc-"
bash termux.sh start
EOF
    chmod +x "$HOME/.termux/boot/start-dc" 2>/dev/null || true
    echo -e "${C_GREEN}[✓] Auto-boot script installed in ~/.termux/boot/start-dc${C_RESET}"
    echo -e "    Requires ${C_BOLD}Termux:Boot${C_RESET} app to trigger on device restart."
}

edit_config() {
    echo -e "${C_BLURPLE}=== Server & Channel Configuration ===${C_RESET}"
    CURR_G=""
    CURR_C=""
    if [ -f ".env" ]; then
        CURR_G=$(grep "^GUILD_ID=" .env 2>/dev/null | cut -d'=' -f2)
        CURR_C=$(grep "^CHANNEL_ID=" .env 2>/dev/null | cut -d'=' -f2)
    fi
    echo -e "Current Server (Guild) ID: ${C_GREEN}${CURR_G:-Not set}${C_RESET}"
    echo -e "Current Channel ID:        ${C_GREEN}${CURR_C:-Not set}${C_RESET}\n"

    echo -ne "Enter New Voice Channel ID (or press Enter to keep): "
    read -r new_c
    echo -ne "Enter New Server (Guild) ID (or press Enter to keep): "
    read -r new_g

    FINAL_C="${new_c:-$CURR_C}"
    FINAL_G="${new_g:-$CURR_G}"

    if [ -n "$FINAL_C" ] || [ -n "$FINAL_G" ]; then
        if is_running; then
            python3 -c "
import urllib.request, json
payload = {'action': 'set_config', 'channel_id': '$FINAL_C', 'guild_id': '$FINAL_G'}
req = urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
try:
    res = urllib.request.urlopen(req, timeout=5)
    print('\033[1;32m[✓] Saved to .env and applied to live bot!\033[0m')
except Exception as e:
    print('\033[1;31m[!] Error:\033[0m', e)
"
        else
            if [ -n "$FINAL_G" ]; then
                grep -q "^GUILD_ID=" .env && sed -i "s/^GUILD_ID=.*/GUILD_ID=$FINAL_G/" .env || echo "GUILD_ID=$FINAL_G" >> .env
            fi
            if [ -n "$FINAL_C" ]; then
                grep -q "^CHANNEL_ID=" .env && sed -i "s/^CHANNEL_ID=.*/CHANNEL_ID=$FINAL_C/" .env || echo "CHANNEL_ID=$FINAL_C" >> .env
            fi
            echo -e "${C_GREEN}[✓] Configuration saved to .env!${C_RESET}"
        fi
    fi
}

interactive_menu() {
    while true; do
        clear
        RUNNING_LABEL="${C_RED}OFFLINE${C_RESET}"
        if is_running; then
            RUNNING_LABEL="${C_GREEN}ONLINE (PID: $(pgrep -f 'python.*main.py' | head -n1))${C_RESET}"
        fi

        echo -e "${C_BLURPLE}╔══════════════════════════════════════════════════╗${C_RESET}"
        echo -e "${C_BLURPLE}║${C_RESET}       ${C_BOLD}dc- Advanced Termux Control Center${C_RESET}         ${C_BLURPLE}║${C_RESET}"
        echo -e "${C_BLURPLE}║${C_RESET}       Status: ${RUNNING_LABEL}                       ${C_BLURPLE}║${C_RESET}"
        echo -e "${C_BLURPLE}╚══════════════════════════════════════════════════╝${C_RESET}"
        echo -e ""
        echo -e " ${C_BOLD}[Engine Controls]${C_RESET}"
        echo -e "   ${C_CYAN}1)${C_RESET} 🚀 Start Bot (Background + Wake Lock)"
        echo -e "   ${C_CYAN}2)${C_RESET} 🛑 Stop Bot"
        echo -e "   ${C_CYAN}3)${C_RESET} 🔄 Restart Bot"
        echo -e "   ${C_CYAN}4)${C_RESET} 🌐 Open Mobile Web UI (Dashboard)"
        echo -e ""
        echo -e " ${C_BOLD}[Voice & Accounts]${C_RESET}"
        echo -e "   ${C_CYAN}5)${C_RESET} 📊 Show Live Status & Accounts Table"
        echo -e "   ${C_CYAN}6)${C_RESET} 🔊 Join / Move Voice Channel"
        echo -e "   ${C_CYAN}7)${C_RESET} 🔇 Mute All"
        echo -e "   ${C_CYAN}8)${C_RESET} 🔊 Unmute All"
        echo -e "   ${C_CYAN}9)${C_RESET} ✕  Disconnect All from Voice"
        echo -e "   ${C_CYAN}c)${C_RESET} ⚙️  Edit Server & Channel IDs (.env)"
        echo -e ""
        echo -e " ${C_BOLD}[Termux Utilities]${C_RESET}"
        echo -e "   ${C_CYAN}l)${C_RESET} 📜 View Live Logs (tail -f)"
        echo -e "   ${C_CYAN}t)${C_RESET} 🔑 Edit tokens.txt"
        echo -e "   ${C_CYAN}w)${C_RESET} 📱 Install 1-Tap Home Screen Widget"
        echo -e "   ${C_CYAN}b)${C_RESET} ⚡ Setup Auto-Start on Phone Boot"
        echo -e "   ${C_CYAN}0)${C_RESET} 🚪 Exit to Shell"
        echo -e ""
        echo -ne "${C_BOLD}Select an option [0-9, c, l, t, w, b]: ${C_RESET}"
        read -r choice

        case "$choice" in
            1) start_bot; echo -e "\nPress Enter to continue..."; read -r ;;
            2) stop_bot; echo -e "\nPress Enter to continue..."; read -r ;;
            3) restart_bot; echo -e "\nPress Enter to continue..."; read -r ;;
            4) open_browser ;;
            5) clear; show_status; echo -e "\nPress Enter to continue..."; read -r ;;
            6)
                echo -ne "Enter Voice Channel ID: "
                read -r ch_id
                if [ -n "$ch_id" ]; then
                    send_quick_action "move" "$ch_id"
                fi
                echo -e "\nPress Enter to continue..."; read -r
                ;;
            7) send_quick_action "mute" ""; echo -e "\nPress Enter to continue..."; read -r ;;
            8) send_quick_action "unmute" ""; echo -e "\nPress Enter to continue..."; read -r ;;
            9) send_quick_action "disconnect" ""; echo -e "\nPress Enter to continue..."; read -r ;;
            c|C) edit_config; echo -e "\nPress Enter to continue..."; read -r ;;
            l|L) show_logs ;;
            t|T)
                if command -v nano &> /dev/null; then
                    nano tokens.txt
                else
                    vi tokens.txt
                fi
                ;;
            w|W) setup_widgets; echo -e "\nPress Enter to continue..."; read -r ;;
            b|B) setup_boot; echo -e "\nPress Enter to continue..."; read -r ;;
            0|q|Q) echo -e "${C_CYAN}Bye!${C_RESET}"; break ;;
            *) echo -e "${C_RED}Invalid option.${C_RESET}"; sleep 1 ;;
        esac
    done
}

# CLI dispatcher
case "$1" in
    start)
        start_bot
        ;;
    stop)
        stop_bot
        ;;
    restart)
        restart_bot
        ;;
    status)
        show_status
        ;;
    logs|log)
        show_logs
        ;;
    ui|web|open)
        open_browser
        ;;
    join|move)
        send_quick_action "move" "$2"
        ;;
    mute)
        send_quick_action "mute" ""
        ;;
    unmute)
        send_quick_action "unmute" ""
        ;;
    leave|disconnect)
        send_quick_action "disconnect" ""
        ;;
    config|settings)
        edit_config
        ;;
    channel|set-channel)
        if [ -n "$2" ]; then
            send_quick_action "set_default_channel" "$2"
        else
            echo "Usage: dc channel <channel_id>"
        fi
        ;;
    guild|server|set-guild)
        if [ -n "$2" ]; then
            python3 -c "
import urllib.request, json
req = urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps({'action': 'set_guild_id', 'guild_id': '$2'}).encode(), headers={'Content-Type': 'application/json'})
try:
    urllib.request.urlopen(req, timeout=5)
    print('\033[1;32m[✓] Server ID updated to $2 (saved to .env)\033[0m')
except Exception:
    import os
    print('Bot offline. Updating .env directly...')
" 2>/dev/null || true
            sed -i "s/^GUILD_ID=.*/GUILD_ID=$2/" .env 2>/dev/null || echo "GUILD_ID=$2" >> .env
            echo -e "${C_GREEN}[✓] Server ID set to $2${C_RESET}"
        else
            echo "Usage: dc guild <guild_id>"
        fi
        ;;
    widget)
        setup_widgets
        ;;
    boot)
        setup_boot
        ;;
    menu)
        interactive_menu
        ;;
    *)
        # Default behavior with no arguments:
        # If running, show status or menu; if not running, start bot & open browser!
        if is_running; then
            interactive_menu
        else
            start_bot
        fi
        ;;
esac
