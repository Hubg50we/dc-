#!/bin/bash
# ========================================================
# dc- Advanced Termux Control Center
# 100% Terminal-Based Discord Manager (Zero Browser Hassle)
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

PY_CMD="python3"
if ! command -v python3 &> /dev/null; then
    PY_CMD="python"
fi

is_running() {
    pgrep -f "python.*main.py" >/dev/null 2>&1
}

start_bot() {
    if is_running; then
        echo -e "${C_YELLOW}[!] Bot engine is already running.${C_RESET}"
        return 0
    fi

    echo -e "${C_CYAN}[+] Acquiring Termux Wake Lock...${C_RESET}"
    termux-wake-lock 2>/dev/null || true

    echo -e "${C_CYAN}[+] Starting dc- background engine...${C_RESET}"
    nohup "$PY_CMD" main.py > bot.log 2>&1 &
    sleep 2

    if is_running; then
        echo -e "${C_GREEN}[✓] Bot successfully started in background! (PID: $(pgrep -f 'python.*main.py' | head -n1))${C_RESET}"
        echo -e "${C_GRAY}    Type 'dc' anytime to open the full interactive Termux TUI.${C_RESET}"
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

    echo -e "${C_CYAN}[+] Stopping Discord engine cleanly...${C_RESET}"
    "$PY_CMD" -c "
import urllib.request, json
try:
    req = urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps({'action': 'shutdown'}).encode(), headers={'Content-Type': 'application/json'})
    urllib.request.urlopen(req, timeout=3)
except Exception:
    pass
" 2>/dev/null || true

    pkill -f "python.*main.py" 2>/dev/null || true
    sleep 1

    if ! is_running; then
        echo -e "${C_GREEN}[✓] Bot stopped safely. Termux wake-lock released.${C_RESET}"
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

show_status() {
    if ! is_running; then
        echo -e "${C_RED}[✕] Bot is currently offline.${C_RESET}"
        echo -e "    Start it with: ${C_BOLD}dc start${C_RESET} or open TUI with: ${C_BOLD}dc${C_RESET}"
        return 1
    fi

    "$PY_CMD" -c "
import urllib.request, json, sys

try:
    req = urllib.request.urlopen('http://127.0.0.1:5000/api/status', timeout=3)
    data = json.loads(req.read().decode())
    
    print('\033[1;38;5;63m' + '═'*52 + '\033[0m')
    print('\033[1m              dc- Termux Live Status\033[0m')
    print('\033[1;38;5;63m' + '═'*52 + '\033[0m')
    
    tot = data.get('total_accounts', 0)
    on = data.get('online_accounts', 0)
    vc = data.get('voice_accounts', 0)
    up = data.get('uptime', 0)
    m, s = divmod(up, 60)
    h, m = divmod(m, 60)
    up_fmt = f'{h}h {m}m {s}s' if h else f'{m}m {s}s'
    
    print(f' \033[1;32m●\033[0m Accounts: \033[1m{tot}\033[0m  |  Online: \033[1;32m{on}\033[0m  |  Voice: \033[1;38;5;63m{vc}\033[0m  |  Up: \033[1m{up_fmt}\033[0m')
    print(f' \033[1;36m●\033[0m Server: \033[0;90m{data.get(\"default_guild\")}\033[0m  Channel: \033[0;90m{data.get(\"default_channel\")}\033[0m')
    print('\033[0;90m' + '─'*52 + '\033[0m')
    
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
            v_str = f'\033[1;32m🔊 {v_ch}\033[0m'
        else:
            v_str = '\033[0;90mIdle\033[0m'
            
        print(f'  [{aid}] \033[1m{name:<14}\033[0m [{state}] {v_str}{f_str}')
    print('\033[1;38;5;63m' + '═'*52 + '\033[0m')
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
        echo -e "${C_RED}[!] Bot is not running. Starting background engine...${C_RESET}"
        start_bot
        sleep 2
    fi

    "$PY_CMD" -c "
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

open_browser_manual() {
    URL="http://localhost:5000"
    echo -e "${C_CYAN}[+] Opening Web UI in browser: ${C_BOLD}$URL${C_RESET}"
    if command -v termux-open-url &> /dev/null; then
        termux-open-url "$URL" 2>/dev/null || true
    elif command -v am &> /dev/null; then
        am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1 || true
    else
        echo -e "Open browser and navigate to: $URL"
    fi
}

# ========================================================
# CLI Router
# ========================================================
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
    join|move)
        send_quick_action "move" "$2"
        ;;
    mute)
        send_quick_action "mute" ""
        ;;
    unmute)
        send_quick_action "unmute" ""
        ;;
    deafen)
        send_quick_action "deafen" ""
        ;;
    undeafen)
        send_quick_action "undeafen" ""
        ;;
    leave|disconnect)
        send_quick_action "disconnect" ""
        ;;
    tokens|token)
        if [ "$2" == "add" ] && [ -n "$3" ]; then
            "$PY_CMD" -c "
import urllib.request, json
try:
    req = urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps({'action': 'add_token', 'token': '$3'}).encode(), headers={'Content-Type': 'application/json'})
    r = json.loads(urllib.request.urlopen(req, timeout=5).read().decode())
    print('\033[1;32m[✓]\033[0m ' + r.get('message'))
except Exception:
    with open('tokens.txt', 'a') as f: f.write('$3\n')
    print('\033[1;32m[✓] Saved to tokens.txt\033[0m')
"
        elif [ "$2" == "list" ]; then
            "$PY_CMD" -c "
import urllib.request, json
try:
    d = json.loads(urllib.request.urlopen('http://127.0.0.1:5000/api/tokens', timeout=3).read().decode())
    for t in d.get('tokens', []):
        print(f'[{t.get(\"id\")}] {t.get(\"name\") or \"?\"} - {t.get(\"token_masked\")}')
except Exception:
    import os
    if os.path.exists('tokens.txt'):
        with open('tokens.txt') as f:
            for idx, l in enumerate(f, 1):
                if l.strip(): print(f'[{idx}] {l.strip()[:6]}...{l.strip()[-4:]}')
"
        else
            echo "Usage: dc token add <token>  OR  dc token list"
        fi
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
            "$PY_CMD" -c "
import urllib.request, json
try:
    urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps({'action': 'set_guild_id', 'guild_id': '$2'}).encode(), headers={'Content-Type': 'application/json'}), timeout=5)
    print('\033[1;32m[✓] Server ID set to $2\033[0m')
except Exception:
    pass
" 2>/dev/null || true
            sed -i "s/^GUILD_ID=.*/GUILD_ID=$2/" .env 2>/dev/null || echo "GUILD_ID=$2" >> .env
            echo -e "${C_GREEN}[✓] Server ID set to $2 (saved to .env)${C_RESET}"
        else
            echo "Usage: dc guild <guild_id>"
        fi
        ;;
    mobile)
        "$PY_CMD" -c "
import urllib.request, json
try:
    urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps({'action': 'set_device_mode', 'device_mode': 'mobile'}).encode(), headers={'Content-Type': 'application/json'}), timeout=5)
except Exception: pass
" 2>/dev/null || true
        sed -i "s/^DISCORD_DEVICE=.*/DISCORD_DEVICE=mobile/" .env 2>/dev/null || echo "DISCORD_DEVICE=mobile" >> .env
        echo -e "${C_GREEN}[✓] Discord identity set to 📱 Android Mobile (Green Phone Icon)${C_RESET}"
        ;;
    desktop)
        "$PY_CMD" -c "
import urllib.request, json
try:
    urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:5000/api/action', data=json.dumps({'action': 'set_device_mode', 'device_mode': 'desktop'}).encode(), headers={'Content-Type': 'application/json'}), timeout=5)
except Exception: pass
" 2>/dev/null || true
        sed -i "s/^DISCORD_DEVICE=.*/DISCORD_DEVICE=desktop/" .env 2>/dev/null || echo "DISCORD_DEVICE=desktop" >> .env
        echo -e "${C_GREEN}[✓] Discord identity set to 💻 Desktop Browser${C_RESET}"
        ;;
    web|browser)
        # ONLY opens browser when user explicitly requests it
        open_browser_manual
        ;;
    menu|tui|"")
        # DEFAULT BEHAVIOR:
        # Launch full interactive Termux TUI directly! No browser popup!
        exec "$PY_CMD" tui.py "$@"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Type 'dc' to open the interactive Termux TUI"
        ;;
esac
