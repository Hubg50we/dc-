#!/usr/bin/env python3
"""
dc- Interactive Termux Control Center (TUI)
Full Terminal User Interface for Discord Multi-Account Manager.
Zero dependencies: runs natively with standard Python 3.
"""

import os
import sys
import json
import time
import urllib.request
import urllib.error
import subprocess
import re

# Ensure UTF-8 output
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ANSI Color Codes
RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
ITALIC = "\033[3m"
UNDERLINE = "\033[4m"

# Colors
RED = "\033[38;5;196m"
GREEN = "\033[38;5;46m"
YELLOW = "\033[38;5;226m"
BLUE = "\033[38;5;39m"
PURPLE = "\033[38;5;129m"
CYAN = "\033[38;5;51m"
WHITE = "\033[38;5;255m"
GRAY = "\033[38;5;244m"
DARK_GRAY = "\033[38;5;238m"
BLURPLE = "\033[38;5;63m"
ACCENT = "\033[38;5;75m"

BASE_URL = "http://127.0.0.1:5000"
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.join(PROJECT_DIR, ".env")
TOKENS_FILE = os.path.join(PROJECT_DIR, "tokens.txt")
LOG_FILE = os.path.join(PROJECT_DIR, "bot.log")


def clear():
    os.system("cls" if os.name == "nt" else "clear")


def api_get(endpoint, timeout=3):
    try:
        url = f"{BASE_URL}{endpoint}"
        req = urllib.request.Request(url, headers={"User-Agent": "dc-Termux-TUI/2.0"})
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return json.loads(res.read().decode("utf-8"))
    except Exception:
        return None


def api_post(endpoint, data=None, timeout=6):
    try:
        url = f"{BASE_URL}{endpoint}"
        payload = json.dumps(data or {}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "User-Agent": "dc-Termux-TUI/2.0"
            }
        )
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"status": "error", "message": f"HTTP {e.code}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def is_running():
    status = api_get("/api/status", timeout=1.5)
    return status is not None


def get_pid():
    if os.name == "nt":
        return None
    try:
        out = subprocess.check_output(["pgrep", "-f", "python.*main.py"], stderr=subprocess.DEVNULL)
        pids = out.decode().strip().split("\n")
        return pids[0] if pids and pids[0] else None
    except Exception:
        return None


def read_env():
    data = {}
    if os.path.exists(ENV_FILE):
        with open(ENV_FILE, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    data[k.strip()] = v.strip().strip("'\"")
    return data


def update_env(updates):
    data = read_env()
    data.update(updates)
    lines = []
    keys_written = set()
    if os.path.exists(ENV_FILE):
        with open(ENV_FILE, "r", encoding="utf-8") as f:
            for line in f:
                clean = line.strip()
                if clean and not clean.startswith("#") and "=" in clean:
                    k = clean.split("=", 1)[0].strip()
                    if k in updates:
                        lines.append(f"{k}={updates[k]}\n")
                        keys_written.add(k)
                    else:
                        lines.append(line)
                else:
                    lines.append(line)
    for k, v in updates.items():
        if k not in keys_written:
            lines.append(f"{k}={v}\n")
    with open(ENV_FILE, "w", encoding="utf-8") as f:
        f.writelines(lines)


def print_header(title=""):
    clear()
    status = api_get("/api/status", timeout=1.5)
    env = read_env()
    guild_id = (status.get("default_guild") if status else env.get("GUILD_ID")) or "Not set"
    channel_id = (status.get("default_channel") if status else env.get("CHANNEL_ID")) or "Not set"
    device_mode = (status.get("device_mode") if status else env.get("DISCORD_DEVICE", "mobile")).lower()
    dev_str = "📱 Android Mobile" if device_mode == "mobile" else "💻 Desktop"

    pid = get_pid()
    if status:
        tot = status.get("total_accounts", 0)
        on = status.get("online_accounts", 0)
        vc = status.get("voice_accounts", 0)
        up = status.get("uptime", 0)
        mins, secs = divmod(up, 60)
        hrs, mins = divmod(mins, 60)
        up_str = f"{hrs}h {mins}m {secs}s" if hrs else f"{mins}m {secs}s"
        badge = f"{GREEN}{BOLD}● ONLINE{RESET}"
        if pid:
            badge += f" {GRAY}(PID: {pid}){RESET}"
        stats_line = (
            f" {BOLD}Accounts:{RESET} {WHITE}{tot}{RESET} │ "
            f"{BOLD}Online:{RESET} {GREEN}{on}{RESET} │ "
            f"{BOLD}In Voice:{RESET} {CYAN}{vc}{RESET} │ "
            f"{BOLD}Uptime:{RESET} {WHITE}{up_str}{RESET}"
        )
    else:
        badge = f"{RED}{BOLD}○ OFFLINE{RESET}"
        stats_line = f" {YELLOW}Engine stopped. Start engine to connect Discord accounts.{RESET}"

    width = 54
    print(f"{BLURPLE}╔{'═' * width}╗{RESET}")
    print(f"{BLURPLE}║{RESET}  {BOLD}{WHITE}dc- Mobile Termux Command Center{RESET}{' ' * (width - 34)}{BLURPLE}║{RESET}")
    print(f"{BLURPLE}║{RESET}  Engine: {badge}{' ' * (width - 12 - (len('ONLINE (PID: xxxxx)') if status else len('OFFLINE')))}{BLURPLE}║{RESET}")
    print(f"{BLURPLE}╠{'═' * width}╣{RESET}")
    print(f"{BLURPLE}║{RESET} {stats_line}")
    print(f"{BLURPLE}║{RESET}  {BOLD}Server:{RESET}  {WHITE}{guild_id:<18}{RESET} {BOLD}Voice Ch:{RESET} {WHITE}{channel_id:<16}{RESET}")
    print(f"{BLURPLE}║{RESET}  {BOLD}Device:{RESET}  {GREEN}{dev_str:<18}{RESET} {BOLD}Port:{RESET}     {WHITE}5000{' ' * 14}{BLURPLE}║{RESET}")
    print(f"{BLURPLE}╚{'═' * width}╝{RESET}")

    if title:
        print(f"\n{ACCENT}{BOLD}─── {title.upper()} ───{RESET}")


def pause():
    input(f"\n{GRAY}Press [Enter] to return...{RESET}")


# ---------------------------------------------------------------------------
# Section 1: Engine Power Controls
# ---------------------------------------------------------------------------
def start_engine_background():
    if is_running():
        print(f"{YELLOW}[!] Engine is already running.{RESET}")
        return
    print(f"{CYAN}[+] Requesting Termux Wake Lock...{RESET}")
    try:
        subprocess.run(["termux-wake-lock"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

    print(f"{CYAN}[+] Launching dc- background engine (NO browser popup)...{RESET}")
    py_cmd = "python3" if os.system("command -v python3 >/dev/null 2>&1") == 0 else "python"
    
    if os.name == "nt":
        subprocess.Popen([sys.executable, "main.py"], creationflags=subprocess.CREATE_NEW_CONSOLE)
    else:
        with open("bot.log", "a") as log:
            subprocess.Popen([py_cmd, "main.py"], stdout=log, stderr=log, start_new_session=True)

    print(f"{CYAN}[+] Initializing connections...{RESET}")
    for _ in range(8):
        time.sleep(0.5)
        if is_running():
            print(f"{GREEN}[✓] Engine started successfully in background!{RESET}")
            return
    print(f"{YELLOW}[?] Engine started. Checking status...{RESET}")


def stop_engine():
    if not is_running():
        print(f"{YELLOW}[!] Engine is not running.{RESET}")
        return
    print(f"{CYAN}[+] Stopping Discord accounts cleanly...{RESET}")
    api_post("/api/action", {"action": "shutdown"})
    time.sleep(1)

    if os.name != "nt":
        subprocess.run(["pkill", "-f", "python.*main.py"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            subprocess.run(["termux-wake-unlock"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

    print(f"{GREEN}[✓] Bot stopped safely. Wake-lock released.{RESET}")


def restart_engine():
    print(f"{CYAN}[+] Restarting dc- engine...{RESET}")
    stop_engine()
    time.sleep(1.5)
    start_engine_background()


# ---------------------------------------------------------------------------
# Section 2: Voice Actions
# ---------------------------------------------------------------------------
def menu_voice_actions():
    while True:
        print_header("Voice Controls")
        if not is_running():
            print(f"{RED}[✕] Bot is offline. Please start the engine first.{RESET}")
            pause()
            return

        print(f"  {CYAN}[1]{RESET} 🔊 Join / Move to Default Channel")
        print(f"  {CYAN}[2]{RESET} 🎯 Move to Custom Channel ID")
        print(f"  {CYAN}[3]{RESET} 📑 Move via Saved Room Preset")
        print(f"  {CYAN}[4]{RESET} 🔇 Mute All Accounts")
        print(f"  {CYAN}[5]{RESET} 🔊 Unmute All Accounts")
        print(f"  {CYAN}[6]{RESET} 🎧 Deafen All Accounts")
        print(f"  {CYAN}[7]{RESET} 👂 Undeafen All Accounts")
        print(f"  {CYAN}[8]{RESET} ✕  Disconnect All from Voice")
        print(f"  {CYAN}[9]{RESET} 🎚️ Percentage Slice Move/Mute (10%-100%)")
        print(f"  {CYAN}[0]{RESET} ⬅️ Back to Main Menu")

        choice = input(f"\n{BOLD}Choice [0-9]: {RESET}").strip()
        if choice == "0":
            break
        elif choice == "1":
            res = api_post("/api/action", {"action": "move_default", "percent": 100})
            print(f"{GREEN}[✓]{RESET} {res.get('message', 'Moved all')}")
            pause()
        elif choice == "2":
            ch = input(f"{BOLD}Enter Target Voice Channel ID: {RESET}").strip()
            if ch:
                res = api_post("/api/action", {"action": "move", "channel_id": ch, "percent": 100})
                print(f"{GREEN}[✓]{RESET} {res.get('message', 'Moved')}")
            pause()
        elif choice == "3":
            menu_apply_preset()
        elif choice == "4":
            res = api_post("/api/action", {"action": "mute", "percent": 100})
            print(f"{GREEN}[✓]{RESET} {res.get('message', 'Muted')}")
            pause()
        elif choice == "5":
            res = api_post("/api/action", {"action": "unmute", "percent": 100})
            print(f"{GREEN}[✓]{RESET} {res.get('message', 'Unmuted')}")
            pause()
        elif choice == "6":
            res = api_post("/api/action", {"action": "deafen", "percent": 100})
            print(f"{GREEN}[✓]{RESET} {res.get('message', 'Deafened')}")
            pause()
        elif choice == "7":
            res = api_post("/api/action", {"action": "undeafen", "percent": 100})
            print(f"{GREEN}[✓]{RESET} {res.get('message', 'Undeafened')}")
            pause()
        elif choice == "8":
            res = api_post("/api/action", {"action": "disconnect", "percent": 100})
            print(f"{GREEN}[✓]{RESET} {res.get('message', 'Disconnected')}")
            pause()
        elif choice == "9":
            menu_percent_slice()


def menu_apply_preset():
    presets_data = api_get("/api/presets") or {}
    presets = presets_data.get("presets", [])
    if not presets:
        print(f"{YELLOW}[!] No saved presets found. Add presets in Preset Manager.{RESET}")
        pause()
        return

    print(f"\n{BOLD}Saved Channel Presets:{RESET}")
    for idx, p in enumerate(presets, 1):
        print(f"  {CYAN}[{idx}]{RESET} {p.get('name')} {GRAY}({p.get('channel_id')}){RESET}")

    c = input(f"\nSelect preset [1-{len(presets)}] or [Enter] cancel: ").strip()
    if c.isdigit() and 1 <= int(c) <= len(presets):
        sel = presets[int(c) - 1]
        ch = sel.get("channel_id")
        res = api_post("/api/action", {"action": "move", "channel_id": ch, "percent": 100})
        print(f"{GREEN}[✓] Moved to {sel.get('name')}: {res.get('message')}{RESET}")
        pause()


def menu_percent_slice():
    print(f"\n{BOLD}Select Percentage of Accounts:{RESET}")
    print(f"  [1] 10%   [2] 25%   [3] 50%   [4] 75%   [5] Custom %")
    c = input("Choice: ").strip()
    pct = 100
    if c == "1": pct = 10
    elif c == "2": pct = 25
    elif c == "3": pct = 50
    elif c == "4": pct = 75
    elif c == "5":
        custom = input("Enter percent (1-100): ").strip()
        if custom.isdigit(): pct = max(1, min(100, int(custom)))
    
    print(f"\nAction for {pct}% of accounts:")
    print("  [1] Move to Channel  [2] Mute  [3] Unmute  [4] Disconnect")
    act_c = input("Choice: ").strip()
    if act_c == "1":
        ch = input("Enter Voice Channel ID: ").strip()
        if ch:
            res = api_post("/api/action", {"action": "move", "channel_id": ch, "percent": pct})
            print(f"{GREEN}[✓] {res.get('message')}{RESET}")
    elif act_c == "2":
        res = api_post("/api/action", {"action": "mute", "percent": pct})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
    elif act_c == "3":
        res = api_post("/api/action", {"action": "unmute", "percent": pct})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
    elif act_c == "4":
        res = api_post("/api/action", {"action": "disconnect", "percent": pct})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
    pause()


# ---------------------------------------------------------------------------
# Section 3: Live Bot Accounts & Single Bot Control
# ---------------------------------------------------------------------------
def menu_bot_accounts():
    while True:
        print_header("Discord Bot Accounts")
        status = api_get("/api/status")
        if not status:
            print(f"{RED}[✕] Bot is offline. Start engine to view live accounts.{RESET}")
            pause()
            return

        accounts = status.get("accounts", [])
        if not accounts:
            print(f"{YELLOW}No Discord accounts loaded. Go to Token Manager to add tokens.{RESET}")
            pause()
            return

        print(f"{'#':<3} {'Name':<15} {'Status':<10} {'Voice Room':<18} {'Flags':<10}")
        print(f"{'─'*3} {'─'*15} {'─'*10} {'─'*18} {'─'*10}")

        for a in accounts:
            aid = str(a.get("id"))
            name = (a.get("name") or "Unknown")[:14]
            state = f"{GREEN}ONLINE 📱{RESET}" if a.get("online") else f"{RED}OFFLINE{RESET}"
            v_ch = a.get("voice_channel")
            v_str = f"{CYAN}🔊 {v_ch[:15]}{RESET}" if v_ch else f"{GRAY}Idle{RESET}"
            flags = []
            if a.get("mute"): flags.append("Muted")
            if a.get("deaf"): flags.append("Deaf")
            flg_str = f"{YELLOW}{','.join(flags)}{RESET}" if flags else f"{GRAY}-{RESET}"

            print(f"{aid:<3} {BOLD}{name:<15}{RESET} {state:<18} {v_str:<26} {flg_str}")

        print(f"\n{BOLD}Options:{RESET}")
        print(f"  {CYAN}[#]{RESET} Enter Account # (e.g. 1) to control that specific bot")
        print(f"  {CYAN}[r]{RESET} 🔄 Refresh Accounts Table")
        print(f"  {CYAN}[0]{RESET} ⬅️ Return to Main Menu")

        choice = input(f"\nSelect option: ").strip()
        if choice == "0":
            break
        elif choice.lower() == "r":
            continue
        elif choice.isdigit():
            aid = int(choice)
            target = next((a for a in accounts if a.get("id") == aid), None)
            if target:
                control_single_bot(target)
            else:
                print(f"{RED}[!] Account #{aid} not found.{RESET}")
                pause()


def control_single_bot(bot):
    name = bot.get("name") or f"Account #{bot.get('id')}"
    aid = bot.get("id")
    print(f"\n{BOLD}Controlling [{aid}] {name}:{RESET}")
    print(f"  {CYAN}[1]{RESET} 🔊 Move this bot to Default Channel")
    print(f"  {CYAN}[2]{RESET} 🎯 Move this bot to Custom Channel ID")
    print(f"  {CYAN}[3]{RESET} 🔇 Toggle Mute")
    print(f"  {CYAN}[4]{RESET} 🎧 Toggle Deafen")
    print(f"  {CYAN}[5]{RESET} ✕  Disconnect this bot from Voice")
    print(f"  {CYAN}[0]{RESET} Cancel")

    c = input(f"Choice: ").strip()
    if c == "1":
        res = api_post("/api/action", {"action": "account_action", "account_id": aid, "subaction": "move_default"})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
        pause()
    elif c == "2":
        ch = input("Enter Voice Channel ID: ").strip()
        if ch:
            res = api_post("/api/action", {"action": "account_action", "account_id": aid, "subaction": "move", "channel_id": ch})
            print(f"{GREEN}[✓] {res.get('message')}{RESET}")
            pause()
    elif c == "3":
        new_m = not bot.get("mute", False)
        res = api_post("/api/action", {"action": "account_action", "account_id": aid, "subaction": "mute" if new_m else "unmute"})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
        pause()
    elif c == "4":
        new_d = not bot.get("deaf", False)
        res = api_post("/api/action", {"action": "account_action", "account_id": aid, "subaction": "deafen" if new_d else "undeafen"})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
        pause()
    elif c == "5":
        res = api_post("/api/action", {"action": "account_action", "account_id": aid, "subaction": "disconnect"})
        print(f"{GREEN}[✓] {res.get('message')}{RESET}")
        pause()


# ---------------------------------------------------------------------------
# Section 4: Token Manager (100% In-Termux, No nano/vi Needed!)
# ---------------------------------------------------------------------------
def menu_token_manager():
    while True:
        print_header("Token Manager")
        tokens_info = api_get("/api/tokens")
        
        # Fallback reading tokens directly from file if offline
        raw_tokens = []
        if os.path.exists(TOKENS_FILE):
            with open(TOKENS_FILE, "r", encoding="utf-8") as f:
                for line in f:
                    t = line.strip()
                    if t and not t.startswith("#"):
                        raw_tokens.append(t)

        tokens_list = (tokens_info.get("tokens", []) if tokens_info else [])
        count = len(tokens_list) if tokens_list else len(raw_tokens)

        print(f"{BOLD}Loaded Tokens ({count} total):{RESET}")
        if tokens_list:
            for t in tokens_list:
                aid = t.get("id")
                name = t.get("name") or "Connecting..."
                mask = t.get("token_masked")
                st = f"{GREEN}● Active{RESET}" if t.get("online") else f"{GRAY}○ Idle{RESET}"
                print(f"  [{aid}] {BOLD}{name:<14}{RESET} {st}  {GRAY}{mask}{RESET}")
        elif raw_tokens:
            for idx, tok in enumerate(raw_tokens, 1):
                masked = tok[:6] + "..." + tok[-4:] if len(tok) > 12 else "******"
                print(f"  [{idx}] {GRAY}{masked}{RESET} (Offline file cache)")
        else:
            print(f"  {YELLOW}No tokens added yet.{RESET}")

        print(f"\n{BOLD}Token Actions:{RESET}")
        print(f"  {CYAN}[1]{RESET} ➕ Add Single Token (Fast Paste)")
        print(f"  {CYAN}[2]{RESET} 📋 Bulk Paste Multiple Tokens")
        print(f"  {CYAN}[3]{RESET} 🗑️  Delete a Token")
        print(f"  {CYAN}[4]{RESET} 💾 Backup tokens.txt to ~/tokens_backup.txt")
        print(f"  {CYAN}[5]{RESET} 🔄 Reload Tokens from tokens.txt")
        print(f"  {CYAN}[0]{RESET} ⬅️ Return to Main Menu")

        c = input(f"\nChoice [0-5]: ").strip()
        if c == "0":
            break
        elif c == "1":
            new_tok = input(f"\n{BOLD}Paste Discord Token: {RESET}").strip()
            if new_tok:
                if is_running():
                    res = api_post("/api/action", {"action": "add_token", "token": new_tok})
                    print(f"{GREEN}[✓]{RESET} {res.get('message', 'Token added')}")
                else:
                    with open(TOKENS_FILE, "a", encoding="utf-8") as f:
                        f.write(new_tok + "\n")
                    print(f"{GREEN}[✓] Token appended to tokens.txt!{RESET}")
                pause()
        elif c == "2":
            print(f"\n{CYAN}Paste your tokens below (one per line).{RESET}")
            print(f"{GRAY}When finished, type 'DONE' on a new line and press Enter:{RESET}")
            lines = []
            while True:
                line = input()
                if line.strip().upper() == "DONE":
                    break
                if line.strip():
                    lines.append(line.strip())
            if lines:
                if is_running():
                    res = api_post("/api/tokens", {"tokens": lines})
                    print(f"{GREEN}[✓] {res.get('message', 'Bulk tokens added')}{RESET}")
                else:
                    with open(TOKENS_FILE, "a", encoding="utf-8") as f:
                        for tok in lines:
                            f.write(tok + "\n")
                    print(f"{GREEN}[✓] Saved {len(lines)} tokens to tokens.txt!{RESET}")
            pause()
        elif c == "3":
            del_id = input(f"\nEnter Token ID to delete (or 'all'): ").strip()
            if del_id:
                if is_running():
                    res = api_post("/api/action", {"action": "remove_token", "target": del_id})
                    print(f"{GREEN}[✓]{RESET} {res.get('message')}")
                else:
                    # Offline file delete
                    if del_id.lower() == "all":
                        open(TOKENS_FILE, "w").close()
                        print(f"{GREEN}[✓] Cleared all tokens from tokens.txt.{RESET}")
                    elif del_id.isdigit():
                        idx = int(del_id) - 1
                        if 0 <= idx < len(raw_tokens):
                            removed = raw_tokens.pop(idx)
                            with open(TOKENS_FILE, "w", encoding="utf-8") as f:
                                for t in raw_tokens:
                                    f.write(t + "\n")
                            print(f"{GREEN}[✓] Removed token #{del_id} from tokens.txt.{RESET}")
                pause()
        elif c == "4":
            backup_path = os.path.expanduser("~/tokens_backup.txt")
            if os.path.exists(TOKENS_FILE):
                with open(TOKENS_FILE, "r", encoding="utf-8") as src, open(backup_path, "w", encoding="utf-8") as dst:
                    dst.write(src.read())
                print(f"{GREEN}[✓] Backup saved to {backup_path}!{RESET}")
            else:
                print(f"{RED}[!] tokens.txt not found.{RESET}")
            pause()
        elif c == "5":
            if is_running():
                res = api_post("/api/action", {"action": "reload_tokens"})
                print(f"{GREEN}[✓]{RESET} {res.get('message', 'Reloaded')}")
            else:
                print(f"{GREEN}[✓] tokens.txt will be loaded when engine starts.{RESET}")
            pause()


# ---------------------------------------------------------------------------
# Section 5: Server & Channel Configuration + Auto-Detect
# ---------------------------------------------------------------------------
def menu_config():
    while True:
        print_header("Server & Channel Configuration")
        env = read_env()
        status = api_get("/api/status")

        curr_g = (status.get("default_guild") if status else env.get("GUILD_ID")) or "Not set"
        curr_c = (status.get("default_channel") if status else env.get("CHANNEL_ID")) or "Not set"

        print(f"Current Voice Channel ID: {GREEN}{BOLD}{curr_c}{RESET}")
        print(f"Current Server (Guild) ID: {GREEN}{BOLD}{curr_g}{RESET}")

        print(f"\n{BOLD}Options:{RESET}")
        print(f"  {CYAN}[1]{RESET} ✏️  Edit Voice Channel ID")
        print(f"  {CYAN}[2]{RESET} ✏️  Edit Server (Guild) ID")
        print(f"  {CYAN}[3]{RESET} ✨ Auto-Detect Server ID (via Channel ID)")
        print(f"  {CYAN}[0]{RESET} ⬅️ Return to Main Menu")

        c = input(f"\nChoice [0-3]: ").strip()
        if c == "0":
            break
        elif c == "1":
            new_c = input(f"\nEnter Voice Channel ID: ").strip()
            if new_c:
                update_env({"CHANNEL_ID": new_c})
                if is_running():
                    api_post("/api/action", {"action": "set_default_channel", "channel_id": new_c})
                print(f"{GREEN}[✓] Voice Channel ID set to {new_c} and saved to .env!{RESET}")
                pause()
        elif c == "2":
            new_g = input(f"\nEnter Server (Guild) ID: ").strip()
            if new_g:
                update_env({"GUILD_ID": new_g})
                if is_running():
                    api_post("/api/action", {"action": "set_guild_id", "guild_id": new_g})
                print(f"{GREEN}[✓] Server ID set to {new_g} and saved to .env!{RESET}")
                pause()
        elif c == "3":
            print(f"{CYAN}[+] Auto-detecting Server ID via Discord API...{RESET}")
            if is_running():
                res = api_post("/api/action", {"action": "resolve_guild"})
                if res.get("status") == "ok":
                    print(f"{GREEN}[✓] Auto-detected Server ID: {res.get('guild_id')}!{RESET}")
                else:
                    print(f"{RED}[!] Auto-detect error: {res.get('message')}{RESET}")
            else:
                # Run offline detection
                ch = curr_c if curr_c != "Not set" else None
                if not ch:
                    ch = input("Enter Voice Channel ID first: ").strip()
                if ch:
                    tok = None
                    if os.path.exists(TOKENS_FILE):
                        with open(TOKENS_FILE) as f:
                            for l in f:
                                if l.strip(): tok = l.strip(); break
                    if tok:
                        try:
                            req = urllib.request.Request(f"https://discord.com/api/v9/channels/{ch}", headers={"Authorization": tok})
                            with urllib.request.urlopen(req, timeout=5) as r:
                                ch_info = json.loads(r.read().decode())
                                g_id = ch_info.get("guild_id")
                                if g_id:
                                    update_env({"GUILD_ID": g_id})
                                    print(f"{GREEN}[✓] Detected Server ID: {g_id}! Saved to .env.{RESET}")
                                else:
                                    print(f"{RED}[!] Guild ID not found in channel metadata.{RESET}")
                        except Exception as e:
                            print(f"{RED}[!] API Error: {e}{RESET}")
                    else:
                        print(f"{RED}[!] No token available to query Discord API.{RESET}")
            pause()


# ---------------------------------------------------------------------------
# Section 6: Voice Presets Manager
# ---------------------------------------------------------------------------
def menu_presets():
    while True:
        print_header("Voice Presets Manager")
        presets_data = api_get("/api/presets") or {}
        presets = presets_data.get("presets", [])

        print(f"{BOLD}Existing Presets:{RESET}")
        if presets:
            for idx, p in enumerate(presets, 1):
                print(f"  {CYAN}[{idx}]{RESET} {p.get('name'):<12} {GRAY}(ID: {p.get('channel_id')}){RESET}")
        else:
            print(f"  {YELLOW}No presets defined yet.{RESET}")

        print(f"\n{BOLD}Options:{RESET}")
        print(f"  {CYAN}[1]{RESET} ➕ Add New Channel Preset")
        print(f"  {CYAN}[2]{RESET} 🗑️  Delete a Preset")
        print(f"  {CYAN}[0]{RESET} ⬅️ Return to Main Menu")

        c = input(f"\nChoice [0-2]: ").strip()
        if c == "0":
            break
        elif c == "1":
            p_name = input("Preset Name (e.g. Lounge, Music): ").strip()
            p_id = input("Voice Channel ID: ").strip()
            if p_name and p_id:
                res = api_post("/api/presets", {"action": "add", "name": p_name, "channel_id": p_id})
                print(f"{GREEN}[✓] {res.get('message', 'Preset added')}{RESET}")
            pause()
        elif c == "2":
            p_del = input("Enter Preset Name or Number to delete: ").strip()
            if p_del:
                res = api_post("/api/presets", {"action": "delete", "name": p_del})
                print(f"{GREEN}[✓] {res.get('message')}{RESET}")
            pause()


# ---------------------------------------------------------------------------
# Section 7: Device Appearance (📱 vs 💻)
# ---------------------------------------------------------------------------
def menu_device_mode():
    print_header("Device Appearance")
    status = api_get("/api/status")
    env = read_env()
    curr_mode = (status.get("device_mode") if status else env.get("DISCORD_DEVICE", "mobile")).lower()

    print(f"Current Device Identity: {GREEN}{BOLD}{'📱 Discord Android (Green Phone Icon)' if curr_mode == 'mobile' else '💻 Desktop Browser'}{RESET}\n")
    print(f"  {CYAN}[1]{RESET} 📱 Set to Discord Android (Shows green mobile badge on Discord)")
    print(f"  {CYAN}[2]{RESET} 💻 Set to Desktop Browser (Standard Discord web)")
    print(f"  {CYAN}[0]{RESET} Cancel")

    c = input(f"\nChoice [0-2]: ").strip()
    if c == "1":
        update_env({"DISCORD_DEVICE": "mobile"})
        if is_running():
            api_post("/api/action", {"action": "set_device_mode", "device_mode": "mobile"})
        print(f"{GREEN}[✓] Device identity set to Android Mobile (📱)! Saved to .env.{RESET}")
        pause()
    elif c == "2":
        update_env({"DISCORD_DEVICE": "desktop"})
        if is_running():
            api_post("/api/action", {"action": "set_device_mode", "device_mode": "desktop"})
        print(f"{GREEN}[✓] Device identity set to Desktop Browser (💻)! Saved to .env.{RESET}")
        pause()


# ---------------------------------------------------------------------------
# Section 8: Live Real-Time Logs Stream
# ---------------------------------------------------------------------------
def view_live_logs():
    clear()
    print(f"{CYAN}{BOLD}Streaming Live Logs (Press Ctrl+C to return to menu)...{RESET}\n")
    if os.path.exists(LOG_FILE):
        if os.name != "nt":
            try:
                subprocess.run(["tail", "-n", "30", "-f", LOG_FILE])
            except KeyboardInterrupt:
                pass
        else:
            with open(LOG_FILE, "r", encoding="utf-8", errors="replace") as f:
                f.seek(0, 2)
                try:
                    while True:
                        line = f.readline()
                        if line:
                            sys.stdout.write(line)
                            sys.stdout.flush()
                        else:
                            time.sleep(0.5)
                except KeyboardInterrupt:
                    pass
    else:
        print(f"{YELLOW}No log file found at {LOG_FILE}.{RESET}")
        pause()


def toggle_app_mode():
    bashrc = os.path.expanduser("~/.bashrc")
    line_to_add = "cd ~/dc- 2>/dev/null || cd \"$HOME/dc-\" && dc\n"
    enabled = False
    lines = []
    if os.path.exists(bashrc):
        with open(bashrc, "r", encoding="utf-8") as f:
            for line in f:
                if "&& dc" in line or "termux.sh" in line:
                    enabled = True
                else:
                    lines.append(line)
    
    if enabled:
        with open(bashrc, "w", encoding="utf-8") as f:
            f.writelines(lines)
        print(f"\n{YELLOW}[✓] App Mode Disabled. Termux will now open normal shell prompt.{RESET}")
    else:
        with open(bashrc, "a", encoding="utf-8") as f:
            f.write(line_to_add)
        print(f"\n{GREEN}[✓] App Mode Enabled!{RESET}")
        print(f"{CYAN}Now whenever you open the Termux app on your phone, dc- will launch immediately as an App!{RESET}")
    pause()


# ---------------------------------------------------------------------------
# Main Interactive TUI Loop
# ---------------------------------------------------------------------------
def main_menu():
    while True:
        print_header()
        running = is_running()

        print(f" {BOLD}[Quick Voice Controls]{RESET}")
        print(f"   {CYAN}1){RESET} 🔊 Join / Move to Default Channel")
        print(f"   {CYAN}2){RESET} 🔇 Mute All Accounts")
        print(f"   {CYAN}3){RESET} 🔊 Unmute All Accounts")
        print(f"   {CYAN}4){RESET} ✕  Disconnect All from Voice")
        print(f"   {CYAN}v){RESET} 🎛️ More Voice Controls (% Slices, Presets, Deafen)")
        print(f"")
        print(f" {BOLD}[Bot & Token Management]{RESET}")
        print(f"   {CYAN}b){RESET} 🤖 Live Accounts List & Single Bot Control")
        print(f"   {CYAN}t){RESET} 🔑 Token Manager (Add, Bulk Paste, View Masked, Delete)")
        print(f"")
        print(f" {BOLD}[Configuration & Settings]{RESET}")
        print(f"   {CYAN}c){RESET} ⚙️  Edit Channel & Server ID (Auto-Detect)")
        print(f"   {CYAN}p){RESET} 📑 Voice Channel Presets")
        print(f"   {CYAN}d){RESET} 📱 Device Appearance (📱 Mobile vs 💻 Desktop)")
        print(f"   {CYAN}a){RESET} 📲 App Mode: Auto-launch dc- when opening Termux")
        print(f"")
        print(f" {BOLD}[System & Power]{RESET}")
        if running:
            print(f"   {CYAN}s){RESET} 🛑 Stop Engine")
            print(f"   {CYAN}r){RESET} 🔄 Restart Engine")
        else:
            print(f"   {CYAN}s){RESET} 🚀 {GREEN}{BOLD}Start Engine (Background + Wake-Lock){RESET}")
        print(f"   {CYAN}l){RESET} 📜 Live Logs Stream")
        print(f"   {CYAN}0){RESET} 🚪 Exit to Termux Prompt")

        choice = input(f"\n{BOLD}Select an option: {RESET}").strip().lower()

        if choice == "0" or choice == "q":
            print(f"\n{CYAN}Exiting TUI. (If bot is running in background, it stays online!){RESET}")
            break
        elif choice == "1":
            if running:
                res = api_post("/api/action", {"action": "move_default", "percent": 100})
                print(f"{GREEN}[✓]{RESET} {res.get('message')}")
            else:
                print(f"{RED}[!] Engine offline. Start engine first.{RESET}")
            pause()
        elif choice == "2":
            if running:
                res = api_post("/api/action", {"action": "mute", "percent": 100})
                print(f"{GREEN}[✓]{RESET} {res.get('message')}")
            else:
                print(f"{RED}[!] Engine offline.{RESET}")
            pause()
        elif choice == "3":
            if running:
                res = api_post("/api/action", {"action": "unmute", "percent": 100})
                print(f"{GREEN}[✓]{RESET} {res.get('message')}")
            else:
                print(f"{RED}[!] Engine offline.{RESET}")
            pause()
        elif choice == "4":
            if running:
                res = api_post("/api/action", {"action": "disconnect", "percent": 100})
                print(f"{GREEN}[✓]{RESET} {res.get('message')}")
            else:
                print(f"{RED}[!] Engine offline.{RESET}")
            pause()
        elif choice == "v":
            menu_voice_actions()
        elif choice == "b":
            menu_bot_accounts()
        elif choice == "t":
            menu_token_manager()
        elif choice == "c":
            menu_config()
        elif choice == "p":
            menu_presets()
        elif choice == "d":
            menu_device_mode()
        elif choice == "a":
            toggle_app_mode()
        elif choice == "s":
            if running:
                stop_engine()
            else:
                start_engine_background()
            pause()
        elif choice == "r":
            restart_engine()
            pause()
        elif choice == "l":
            view_live_logs()
        else:
            time.sleep(0.3)


if __name__ == "__main__":
    try:
        main_menu()
    except KeyboardInterrupt:
        print(f"\n{CYAN}Interrupted. Goodbye!{RESET}")
