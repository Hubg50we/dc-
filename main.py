import asyncio
import http.server
import json
import os
import random
import signal
import socket
import socketserver
import sys
import threading
import time
import requests
import websockets

# Ensure UTF-8 standard output encoding
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# Try loading .env if python-dotenv is installed
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

# Discord Guild (Server) and default Voice Channel IDs
GUILD_ID = os.environ.get("GUILD_ID", "YOUR_GUILD_ID")
CHANNEL_ID = os.environ.get("CHANNEL_ID", "YOUR_CHANNEL_ID")
COMMAND_FILE = os.environ.get("COMMAND_FILE", "commands.txt")
WEB_PORT = int(os.environ.get("PORT", "5000"))
WEB_HOST = os.environ.get("HOST", "0.0.0.0")
MAIN_LOOP = None

# Device appearance: "mobile" (shows 📱 phone icon on Discord) or "desktop" (shows 🟢 dot)
DEVICE_TYPE = os.environ.get("DEVICE_TYPE", "mobile").lower()
if DEVICE_TYPE == "desktop":
    GATEWAY_PROPERTIES = {"$os": "windows", "$browser": "chrome", "$device": "pc"}
else:
    GATEWAY_PROPERTIES = {"$os": "Android", "$browser": "Discord Android", "$device": "Android"}

API = "https://discord.com/api/v10"

# Account registry: id -> dict
ACCOUNTS = {}
NEXT_ID = 1
RUNNING = True
START_TIME = None

# Optional seed tokens (placeholders are ignored)
TOKENS = []
_env_tokens = os.environ.get("TOKENS", "")
if _env_tokens:
    TOKENS = [t.strip() for t in _env_tokens.split(",") if t.strip()]

MENU_TEXT = """\
================ [MOBILE QUICK KEYPAD] ================
  1  -> Move All to Default Channel
  2  -> Move All to Custom Channel (e.g. 2 123456789)
  3  -> Mute All in Voice
  4  -> Unmute All in Voice
  5  -> Deafen All in Voice
  6  -> Undeafen All in Voice
  7  -> Disconnect All from Voice
  8  -> Show Connected Accounts & Channels
  9  -> Add Token (e.g. 9 <token>)
  0  -> Safely Disconnect & Exit
  menu -> Show this keypad again
========================================================
"""

HELP = """\
======================= COMMAND MENU =======================
[Mobile Keypad Shortcuts] (just type the number):
  1: Join/Move all to default channel   5: Deafen all
  2: Move all (2 <channel_id>)          6: Undeafen all
  3: Mute all                           7: Disconnect all
  4: Unmute all                         8: Status (list accounts)
  9: Add token (9 <token>)              0: Quit / Shutdown
  Type 'menu' to show the keypad options.

Full Commands:
  status                        Show connected accounts and voice state
  stats                         Show uptime and count of active accounts
  move <channel_id> [percent]   Move accounts to a voice channel (e.g. move 1234 50%)
  join <channel_id> [percent]   Alias of move
  mute [percent]                Self-mute accounts in voice
  unmute [percent]              Un-mute accounts in voice
  deafen [percent]              Self-deafen accounts in voice
  undeafen [percent]            Un-deafen accounts in voice
  disconnect [percent]          Disconnect accounts from voice

Account & Token Management:
  token add <token> [tok2...]   Add one or more tokens at runtime
  token remove <id|name|all>    Remove account(s)
  token load <file>             Load tokens from file (one per line)
  token save [file]             Save all current tokens to file
  token status (or: tokens)     List all accounts and status
  account <id|name> <action>    Control a single account (move, mute, etc.)

General:
  help / ?                      Show this help text
  shutdown / quit / exit        Safely disconnect and exit
============================================================
"""


def parse_percent(arg):
    value = int(arg.rstrip("%"))
    if not 0 <= value <= 100:
        raise ValueError
    return value


def is_placeholder_token(token):
    if not token or len(token) < 20:
        return True
    lower = token.lower()
    return lower.startswith("token_") or lower.startswith("your_") or "placeholder" in lower or "example" in lower


def sync_tokens_file():
    """Keep tokens.txt synchronized when accounts are added or removed."""
    try:
        seen = set()
        with open("tokens.txt", "w", encoding="utf-8") as f:
            for a in ACCOUNTS.values():
                tok = a.get("token", "").strip()
                if tok and not is_placeholder_token(tok) and tok not in seen:
                    f.write(tok + "\n")
                    seen.add(tok)
    except Exception as e:
        print(f"[!] Could not sync tokens.txt: {e}")


CHANNELS_PRESETS_FILE = "channels.json"


def get_channel_presets():
    if os.path.exists(CHANNELS_PRESETS_FILE):
        try:
            with open(CHANNELS_PRESETS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []
    return []


def save_channel_presets(presets):
    try:
        with open(CHANNELS_PRESETS_FILE, "w", encoding="utf-8") as f:
            json.dump(presets, f, indent=2)
    except Exception as e:
        print(f"[!] Could not save channel presets: {e}")


def save_env_config():
    """Persist current GUILD_ID, CHANNEL_ID, and DEVICE_TYPE to .env file so changes survive restarts."""
    try:
        env_path = ".env"
        lines = []
        if os.path.exists(env_path):
            with open(env_path, "r", encoding="utf-8") as f:
                lines = f.readlines()

        guild_found = False
        channel_found = False
        device_found = False
        new_lines = []
        for line in lines:
            if line.startswith("GUILD_ID="):
                new_lines.append(f"GUILD_ID={GUILD_ID}\n")
                guild_found = True
            elif line.startswith("CHANNEL_ID="):
                new_lines.append(f"CHANNEL_ID={CHANNEL_ID}\n")
                channel_found = True
            elif line.startswith("DEVICE_TYPE="):
                new_lines.append(f"DEVICE_TYPE={DEVICE_TYPE}\n")
                device_found = True
            else:
                new_lines.append(line)

        if not guild_found:
            new_lines.append(f"GUILD_ID={GUILD_ID}\n")
        if not channel_found:
            new_lines.append(f"CHANNEL_ID={CHANNEL_ID}\n")
        if not device_found:
            new_lines.append(f"DEVICE_TYPE={DEVICE_TYPE}\n")

        with open(env_path, "w", encoding="utf-8") as f:
            f.writelines(new_lines)
    except Exception as e:
        print(f"[!] Could not persist .env config: {e}")


async def auto_resolve_guild(channel_id):
    """If GUILD_ID is not configured, automatically look it up via Discord channel endpoint."""
    global GUILD_ID
    if GUILD_ID and GUILD_ID != "YOUR_GUILD_ID":
        return GUILD_ID
    if not str(channel_id).isdigit():
        return GUILD_ID

    valid_token = next((a["token"] for a in ACCOUNTS.values() if a.get("online") and a.get("token")), None)
    if not valid_token:
        valid_token = next((a["token"] for a in ACCOUNTS.values() if a.get("token") and not is_placeholder_token(a["token"])), None)
    if not valid_token:
        return GUILD_ID

    loop = asyncio.get_running_loop()
    def _fetch():
        try:
            return requests.get(f"{API}/channels/{channel_id}", headers={"Authorization": valid_token, "User-Agent": "Mozilla/5.0"}, timeout=6)
        except Exception:
            return None

    res = await loop.run_in_executor(None, _fetch)
    if res and res.status_code == 200:
        gid = res.json().get("guild_id")
        if gid:
            GUILD_ID = gid
            save_env_config()
            print(f"[+] Automatically resolved & saved Server (Guild) ID: {GUILD_ID}")
            return GUILD_ID
    return GUILD_ID


async def _validate_token(token):
    loop = asyncio.get_running_loop()

    def _do():
        return requests.get(
            f"{API}/users/@me",
            headers={"Authorization": token, "User-Agent": "Mozilla/5.0"},
            timeout=10,
        )

    try:
        res = await loop.run_in_executor(None, _do)
    except Exception as e:
        return None, str(e)
    if res.status_code != 200:
        return None, f"HTTP {res.status_code}"
    try:
        return res.json(), None
    except Exception as e:
        return None, str(e)


def find_account(identifier):
    if str(identifier).isdigit():
        aid = int(identifier)
        if aid in ACCOUNTS:
            return ACCOUNTS[aid]
    for acc in ACCOUNTS.values():
        if acc.get("name") == identifier or acc.get("user_id") == identifier:
            return acc
    for acc in ACCOUNTS.values():
        if acc.get("token", "").startswith(identifier):
            return acc
    return None


def online_accounts():
    return [a for a in ACCOUNTS.values() if a.get("online") and a.get("ws") is not None]


def voice_accounts():
    return [a for a in ACCOUNTS.values() if a.get("online") and a.get("voice_channel") is not None]


def pick_targets(percent, pool):
    if not pool or percent <= 0:
        return []
    if percent >= 100:
        return list(pool)
    count = max(1, int(len(pool) * percent / 100 + 0.5))
    return random.sample(pool, min(count, len(pool)))


async def apply(targets, channel="keep", mute="keep", deaf="keep"):
    done = 0
    if channel not in ("keep", None):
        await auto_resolve_guild(channel)
    for conn in targets:
        for attempt in range(2):
            try:
                ws = conn.get("ws")
                if ws is None:
                    print(f"  ! {conn.get('name')}: not connected")
                    break
                new_channel = conn.get("voice_channel") if channel == "keep" else channel
                new_mute = conn.get("mute", False) if mute == "keep" else mute
                new_deaf = conn.get("deaf", False) if deaf == "keep" else deaf
                await ws.send(
                    json.dumps({
                        "op": 4,
                        "d": {
                            "guild_id": GUILD_ID,
                            "channel_id": new_channel,
                            "self_mute": new_mute,
                            "self_deaf": new_deaf,
                        },
                    })
                )
                conn["voice_channel"] = new_channel
                conn["mute"] = new_mute
                conn["deaf"] = new_deaf
                done += 1
                await asyncio.sleep(0.1)
                break
            except Exception as e:
                if attempt == 0:
                    await asyncio.sleep(0.5)
                else:
                    print(f"  ! {conn.get('name')}: {e}")
    return done


async def add_account(token):
    global NEXT_ID
    for a in ACCOUNTS.values():
        if a.get("token") == token:
            print(f"[Account {a['id']}] Already added ({a.get('name')}).")
            return a["id"]

    user, err = await _validate_token(token)
    aid = NEXT_ID
    NEXT_ID += 1
    if user is None:
        acc = {
            "id": aid, "token": token, "task": None, "ws": None, "hb": None,
            "user_id": None, "name": None, "online": False, "voice_channel": None,
            "mute": False, "deaf": False, "removed": False, "error": err,
        }
        ACCOUNTS[aid] = acc
        print(f"[Account {aid}] Invalid token ({err}): {token[:10]}... (added as offline)")
        return aid

    acc = {
        "id": aid, "token": token, "task": None, "ws": None, "hb": None,
        "user_id": user["id"], "name": user.get("username", "Unknown"), "online": False,
        "voice_channel": None, "mute": False, "deaf": False, "removed": False, "error": None,
    }
    ACCOUNTS[aid] = acc
    task = asyncio.create_task(run_account(aid))
    acc["task"] = task
    print(f"[Account {aid}] Added: {acc['name']} ({acc['user_id']})")
    sync_tokens_file()
    return aid


async def remove_account(identifier):
    acc = find_account(identifier)
    if not acc:
        print("Account not found.")
        return
    aid = acc["id"]
    acc["removed"] = True
    ws = acc.get("ws")
    if ws is not None and acc.get("voice_channel") is not None:
        try:
            await ws.send(
                json.dumps({
                    "op": 4,
                    "d": {
                        "guild_id": GUILD_ID,
                        "channel_id": None,
                        "self_mute": acc.get("mute", False),
                        "self_deaf": acc.get("deaf", False),
                    },
                })
            )
        except Exception:
            pass
    task = acc.get("task")
    if task is not None:
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass
    name = acc.get("name") or acc.get("token", "")[:10]
    del ACCOUNTS[aid]
    sync_tokens_file()
    print(f"[Account {aid}] Removed {name}.")


async def run_account(aid):
    acc = ACCOUNTS.get(aid)
    if acc is None:
        return
    token = acc["token"]
    uri = "wss://gateway.discord.gg/?v=10&encoding=json"
    delay = 5

    while RUNNING and not acc.get("removed", False):
        hb = None
        ws = None
        try:
            async with websockets.connect(uri, max_size=10 * 1024 * 1024) as ws:
                hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
                heartbeat_interval = hello["d"]["heartbeat_interval"]
                hb = asyncio.create_task(heartbeat(ws, heartbeat_interval))

                await ws.send(
                    json.dumps({
                        "op": 2,
                        "d": {
                            "token": token,
                            "properties": GATEWAY_PROPERTIES,
                            "presence": {"status": "online", "afk": False},
                        },
                    })
                )

                event = None
                while True:
                    event = json.loads(await asyncio.wait_for(ws.recv(), timeout=heartbeat_interval / 1000 + 15))
                    if event.get("t") == "READY":
                        break

                if isinstance(event, dict) and event.get("d"):
                    u = event["d"].get("user")
                    if u:
                        acc["user_id"] = u.get("id", acc.get("user_id"))
                        acc["name"] = u.get("username", acc.get("name"))

                acc["ws"] = ws
                acc["online"] = True
                acc["error"] = None
                delay = 5
                print(f"[Account {aid}] {acc['name']} connected (gateway).")

                while True:
                    await asyncio.wait_for(ws.recv(), timeout=max(30, heartbeat_interval / 1000 + 15))

        except asyncio.CancelledError:
            raise
        except Exception as e:
            close_code = getattr(e, "code", None)
            if close_code == 4004:
                print(f"[Account {aid}] Invalid or expired token (Discord error 4004). Disabling auto-reconnect.")
                acc["error"] = "Invalid token (4004)"
                break
            print(f"[Account {aid}] Error for {acc.get('name')}: {e}. Reconnecting in {delay}s...")
            acc["error"] = str(e)
        finally:
            if acc.get("ws") is ws:
                acc["ws"] = None
            acc["online"] = False
            acc["voice_channel"] = None
            if hb is not None:
                hb.cancel()
        if acc.get("removed", False) or not RUNNING:
            break
        await asyncio.sleep(delay)
        delay = min(delay * 2, 60)


async def restart_account(identifier):
    acc = find_account(identifier)
    if not acc:
        print("Account not found.")
        return
    aid = acc["id"]
    task = acc.get("task")
    if task is not None:
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass
    acc["removed"] = False
    acc["ws"] = None
    acc["online"] = False
    acc["voice_channel"] = None
    acc["error"] = None
    new_task = asyncio.create_task(run_account(aid))
    acc["task"] = new_task
    print(f"[Account {aid}] Restarted {acc.get('name') or acc.get('token', '')[:10]}.")


async def load_tokens_from_file(path):
    if not os.path.exists(path):
        print(f"File not found: {path}")
        return 0
    count = 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            tok = line.strip()
            if not tok or tok.startswith("#") or is_placeholder_token(tok):
                continue
            await add_account(tok)
            count += 1
    print(f"Loaded {count} token(s) from {path}.")
    return count


async def save_tokens_to_file(path):
    with open(path, "w", encoding="utf-8") as f:
        for acc in ACCOUNTS.values():
            tok = acc.get("token", "")
            if tok:
                f.write(tok + "\n")
    print(f"Saved {len(ACCOUNTS)} token(s) to {path}.")


async def heartbeat(ws, interval):
    while True:
        await asyncio.sleep(interval / 1000)
        await ws.send(json.dumps({"op": 1, "d": None}))


def token_status():
    if not ACCOUNTS:
        print("No accounts/tokens loaded.")
        return
    print(f"Total Accounts: {len(ACCOUNTS)}")
    for aid in sorted(ACCOUNTS):
        acc = ACCOUNTS[aid]
        state = "online" if acc.get("online") else "offline"
        voice = acc.get("voice_channel") or "not in voice"
        flags = [label for label, on in (("muted", acc.get("mute")), ("deafened", acc.get("deaf"))) if on]
        suffix = f" ({', '.join(flags)})" if flags else ""
        err = f"  err={acc['error']}" if acc.get("error") else ""
        print(
            f"  [{aid}] {acc.get('name') or '?'} ({acc.get('user_id') or '?'}) "
            f"token={acc.get('token', '')[:10]}... status={state} voice={voice}{suffix}{err}"
        )


def show_status():
    if not ACCOUNTS:
        print("No accounts connected yet.")
        return
    for aid in sorted(ACCOUNTS):
        acc = ACCOUNTS[aid]
        state = "online" if acc.get("online") else "offline"
        voice = acc.get("voice_channel") or "not in voice"
        flags = [label for label, on in (("muted", acc.get("mute")), ("deafened", acc.get("deaf"))) if on]
        suffix = f" ({', '.join(flags)})" if flags else ""
        print(f"  [{aid}] {acc.get('name') or '?'} -> {voice} [{state}]{suffix}")


async def handle_command(raw):
    parts = raw.split()
    if not parts:
        return
    cmd = parts[0].lower()

    try:
        # 📱 Mobile Keypad Shortcuts (0-9, menu)
        if cmd == "menu":
            print(MENU_TEXT)
            return

        if cmd == "1":
            targets = pick_targets(100, online_accounts())
            done = await apply(targets, channel=CHANNEL_ID)
            print(f"📱 [1] Move All -> {CHANNEL_ID}: {done}/{len(targets)} account(s)")
            return

        if cmd == "2":
            if len(parts) > 1:
                channel_id = parts[1]
                targets = pick_targets(100, online_accounts())
                done = await apply(targets, channel=channel_id)
                print(f"📱 [2] Move All -> {channel_id}: {done}/{len(targets)} account(s)")
            else:
                print("Usage on mobile: 2 <channel_id> (Example: 2 123456789012345678)")
            return

        if cmd == "3":
            targets = pick_targets(100, voice_accounts())
            done = await apply(targets, mute=True)
            print(f"📱 [3] Mute All: {done}/{len(targets)} account(s)")
            return

        if cmd == "4":
            targets = pick_targets(100, voice_accounts())
            done = await apply(targets, mute=False)
            print(f"📱 [4] Unmute All: {done}/{len(targets)} account(s)")
            return

        if cmd == "5":
            targets = pick_targets(100, voice_accounts())
            done = await apply(targets, deaf=True)
            print(f"📱 [5] Deafen All: {done}/{len(targets)} account(s)")
            return

        if cmd == "6":
            targets = pick_targets(100, voice_accounts())
            done = await apply(targets, deaf=False)
            print(f"📱 [6] Undeafen All: {done}/{len(targets)} account(s)")
            return

        if cmd == "7":
            targets = pick_targets(100, voice_accounts())
            done = await apply(targets, channel=None)
            print(f"📱 [7] Disconnect All: {done}/{len(targets)} account(s)")
            return

        if cmd == "8":
            show_status()
            return

        if cmd == "9":
            if len(parts) > 1:
                for tok in parts[1:]:
                    await add_account(tok)
            else:
                print("Usage on mobile: 9 <token>")
            return

        if cmd == "0":
            await shutdown()
            return

        if cmd in ("guild", "server"):
            if len(parts) > 1:
                GUILD_ID = parts[1]
                save_env_config()
                print(f"[✓] Server (Guild) ID set to: {GUILD_ID} (saved to .env)")
            else:
                print(f"Current Server (Guild) ID: {GUILD_ID}")
            return

        if cmd == "channel":
            if len(parts) > 1:
                CHANNEL_ID = parts[1]
                save_env_config()
                print(f"[✓] Default Channel ID set to: {CHANNEL_ID} (saved to .env)")
            else:
                print(f"Current Default Channel ID: {CHANNEL_ID}")
            return

        if cmd in ("config", "settings"):
            print(f"Server (Guild) ID: {GUILD_ID}")
            print(f"Default Channel ID: {CHANNEL_ID}")
            print(f"Device Type: {DEVICE_TYPE}")
            return

        if cmd in ("help", "?"):
            print(HELP)
            return

        if cmd == "token":
            if len(parts) < 2:
                print("Usage: token add|remove|status|load|save ...")
                return
            sub = parts[1].lower()
            if sub == "add":
                if len(parts) < 3:
                    print("Usage: token add <token> [token2 ...]")
                    return
                for tok in parts[2:]:
                    await add_account(tok)
            elif sub == "remove":
                if len(parts) < 3:
                    print("Usage: token remove <id|name|token|all>")
                    return
                if parts[2].lower() == "all":
                    for aid in list(ACCOUNTS.keys()):
                        await remove_account(aid)
                else:
                    await remove_account(parts[2])
            elif sub in ("status", "list"):
                token_status()
            elif sub == "load":
                target_file = parts[2] if len(parts) > 2 else "tokens.txt"
                await load_tokens_from_file(target_file)
            elif sub == "save":
                path = parts[2] if len(parts) > 2 else "tokens.txt"
                await save_tokens_to_file(path)
            else:
                print("Unknown token subcommand. Type 'help' for guidance.")
            return

        if cmd in ("tokens",):
            token_status()
            return

        if cmd == "status":
            show_status()
            return

        if cmd == "account":
            if len(parts) < 3:
                print("Usage: account <id|name|uid> <action> [args]")
                return
            acc = find_account(parts[1])
            if not acc:
                print("Account not found.")
                return
            action = parts[2].lower()
            if action in ("move", "join"):
                channel_id = parts[3] if len(parts) >= 4 else CHANNEL_ID
                if not str(channel_id).isdigit():
                    print("channel_id must be the numeric channel ID")
                    return
                done = await apply([acc], channel=channel_id)
                print(f"move: {done}/1 account(s) -> {channel_id} ({acc.get('name')})")
            elif action == "disconnect":
                done = await apply([acc], channel=None)
                print(f"disconnect: {done}/1 ({acc.get('name')})")
            elif action == "mute":
                done = await apply([acc], mute=True)
                print(f"mute: {done}/1 ({acc.get('name')})")
            elif action == "unmute":
                done = await apply([acc], mute=False)
                print(f"unmute: {done}/1 ({acc.get('name')})")
            elif action == "deafen":
                done = await apply([acc], deaf=True)
                print(f"deafen: {done}/1 ({acc.get('name')})")
            elif action == "undeafen":
                done = await apply([acc], deaf=False)
                print(f"undeafen: {done}/1 ({acc.get('name')})")
            elif action == "reconnect":
                await restart_account(parts[1])
            elif action == "status":
                aid = acc["id"]
                state = "online" if acc.get("online") else "offline"
                voice = acc.get("voice_channel") or "not in voice"
                flags = [l for l, on in (("muted", acc.get("mute")), ("deafened", acc.get("deaf"))) if on]
                suffix = f" ({', '.join(flags)})" if flags else ""
                print(
                    f"  [{aid}] {acc.get('name') or acc.get('token', '')[:10]} "
                    f"({acc.get('user_id') or '?'}) status={state} voice={voice}{suffix}"
                )
            else:
                print(f"Unknown action: {action}")
            return

        if cmd in ("move", "moveall", "join"):
            if len(parts) < 2:
                channel_id = CHANNEL_ID
                percent = 100
            else:
                channel_id = parts[1]
                if not str(channel_id).isdigit():
                    print("channel_id must be the numeric channel ID (right-click channel > Copy ID)")
                    return
                percent = parse_percent(parts[2]) if len(parts) > 2 else 100
            targets = pick_targets(percent, online_accounts())
            done = await apply(targets, channel=channel_id)
            print(f"move: {done}/{len(targets)} account(s) -> {channel_id}")
            return

        if cmd in ("mute", "unmute", "deafen", "undeafen", "disconnect"):
            percent = parse_percent(parts[1]) if len(parts) > 1 else 100
            pool = voice_accounts()
            targets = pick_targets(percent, pool)
            if cmd == "mute":
                done = await apply(targets, mute=True)
            elif cmd == "unmute":
                done = await apply(targets, mute=False)
            elif cmd == "deafen":
                done = await apply(targets, deaf=True)
            elif cmd == "undeafen":
                done = await apply(targets, deaf=False)
            else:
                done = await apply(targets, channel=None)
            print(f"{cmd}: applied to {done}/{len(targets)} account(s)")
            return

        if cmd == "stats":
            uptime = time.time() - START_TIME if START_TIME else 0
            print(
                f"accounts={len(ACCOUNTS)} "
                f"online={sum(1 for a in ACCOUNTS.values() if a.get('online'))} "
                f"in_voice={len(voice_accounts())} uptime={int(uptime)}s"
            )
            return

        if cmd in ("shutdown", "quit", "exit"):
            await shutdown()
            return

        print(f"Unknown command: '{cmd}'. Type 'help' for command list.")

    except ValueError:
        print("Percent must be a number between 0 and 100 (e.g. 40 or 40%).")
    except Exception as e:
        print(f"Command error: {e}")


async def shutdown():
    global RUNNING
    RUNNING = False
    print("\nShutting down accounts cleanly...")
    for acc in list(ACCOUNTS.values()):
        acc["removed"] = True
        ws = acc.get("ws")
        if ws is not None and acc.get("voice_channel") is not None:
            try:
                await ws.send(
                    json.dumps({
                        "op": 4,
                        "d": {
                            "guild_id": GUILD_ID,
                            "channel_id": None,
                            "self_mute": acc.get("mute", False),
                            "self_deaf": acc.get("deaf", False),
                        },
                    })
                )
            except Exception:
                pass
        task = acc.get("task")
        if task is not None:
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass
    print("All accounts disconnected. Goodbye.")


async def command_loop():
    loop = asyncio.get_running_loop()
    while RUNNING:
        try:
            raw = await loop.run_in_executor(None, sys.stdin.readline)
            if not raw:
                # EOF reached (e.g. process backgrounded)
                await asyncio.sleep(1)
                continue
            await handle_command(raw.strip())
        except (EOFError, KeyboardInterrupt):
            return
        except Exception as e:
            if RUNNING:
                print(f"Console error: {e}")
            await asyncio.sleep(0.5)


async def command_file_loop():
    """Poll a file for commands (useful when running detached or via Termux background scripts)."""
    while RUNNING:
        try:
            if os.path.exists(COMMAND_FILE):
                with open(COMMAND_FILE, "r", encoding="utf-8") as f:
                    lines = [ln.strip() for ln in f if ln.strip()]
                if lines:
                    with open(COMMAND_FILE, "w", encoding="utf-8"):
                        pass
                    for line in lines:
                        print(f">>> (file command) {line}")
                        await handle_command(line)
        except Exception as e:
            print(f"Command file error: {e}")
        await asyncio.sleep(1)


class DashboardHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress periodic polling logs to keep terminal output clean
        return

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/?"):
            self.serve_file()
        elif self.path == "/manifest.json":
            self.serve_manifest()
        elif self.path == "/api/status":
            self.serve_status()
        elif self.path == "/api/tokens":
            tokens = [a.get("token") for a in ACCOUNTS.values() if a.get("token") and not is_placeholder_token(a.get("token"))]
            resp = json.dumps({"tokens": tokens, "count": len(tokens)}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
        else:
            self.send_error(404, "Not Found")

    def serve_manifest(self):
        data = {
            "name": "dc- Discord Manager",
            "short_name": "dc-",
            "start_url": "/",
            "display": "standalone",
            "background_color": "#111214",
            "theme_color": "#1e1f22",
            "description": "Discord Voice Multi-Account Manager"
        }
        resp = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/manifest+json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def serve_file(self):
        index_path = os.path.join(os.path.dirname(__file__), "web", "index.html")
        if not os.path.exists(index_path):
            index_path = "web/index.html"
        try:
            if os.path.exists(index_path):
                with open(index_path, "rb") as f:
                    content = f.read()
            else:
                content = b"<!DOCTYPE html><html><body style='background:#111;color:#fff;font-family:sans-serif;padding:20px'><h2>dc- Mobile Dashboard</h2><p>web/index.html not found. Please run git pull.</p></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self.send_error(500, f"Error reading UI file: {e}")

    def serve_status(self):
        data = {
            "total_accounts": len(ACCOUNTS),
            "online_accounts": sum(1 for a in ACCOUNTS.values() if a.get("online")),
            "voice_accounts": len(voice_accounts()),
            "uptime": int(time.time() - START_TIME) if START_TIME else 0,
            "default_guild": GUILD_ID,
            "default_channel": CHANNEL_ID,
            "device_type": DEVICE_TYPE,
            "presets": get_channel_presets(),
            "accounts": [
                {
                    "id": a["id"],
                    "name": a.get("name") or "Unknown",
                    "user_id": a.get("user_id"),
                    "token_masked": (a.get("token")[:10] + "..." + a.get("token")[-4:]) if a.get("token") else "",
                    "online": a.get("online", False),
                    "voice_channel": a.get("voice_channel"),
                    "mute": a.get("mute", False),
                    "deaf": a.get("deaf", False),
                    "error": a.get("error"),
                }
                for a in sorted(ACCOUNTS.values(), key=lambda x: x["id"])
            ],
        }
        resp = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def do_POST(self):
        if self.path == "/api/action":
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8")
            try:
                payload = json.loads(body)
                result = self.execute_action(payload)
                resp = json.dumps(result).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)
            except Exception as e:
                err = json.dumps({"success": False, "error": str(e)}).encode("utf-8")
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(err)))
                self.end_headers()
                self.wfile.write(err)
        else:
            self.send_error(404, "Not Found")

    def execute_action(self, payload):
        global CHANNEL_ID, GUILD_ID, DEVICE_TYPE, GATEWAY_PROPERTIES
        action = payload.get("action", "")

        if action == "set_device_type":
            new_dev = payload.get("device_type", "mobile").lower()
            if new_dev in ("mobile", "desktop"):
                DEVICE_TYPE = new_dev
                if DEVICE_TYPE == "desktop":
                    GATEWAY_PROPERTIES = {"$os": "windows", "$browser": "chrome", "$device": "pc"}
                else:
                    GATEWAY_PROPERTIES = {"$os": "Android", "$browser": "Discord Android", "$device": "Android"}
                save_env_config()
                return {"success": True, "message": f"Device appearance set to {DEVICE_TYPE.upper()} (saved to .env)"}
            return {"success": False, "error": "Invalid device_type (use 'mobile' or 'desktop')"}

        if action == "save_preset":
            name = payload.get("name", "").strip()
            ch = payload.get("channel_id", "").strip()
            gid = payload.get("guild_id", "").strip()
            if not name or not ch:
                return {"success": False, "error": "Name and Channel ID required"}
            presets = [p for p in get_channel_presets() if p.get("name") != name]
            presets.append({"name": name, "channel_id": ch, "guild_id": gid})
            save_channel_presets(presets)
            return {"success": True, "message": f"Saved channel preset '{name}'", "presets": presets}

        if action == "delete_preset":
            name = payload.get("name", "").strip()
            presets = [p for p in get_channel_presets() if p.get("name") != name]
            save_channel_presets(presets)
            return {"success": True, "message": f"Deleted preset '{name}'", "presets": presets}

        if action == "restart_all":
            if MAIN_LOOP and MAIN_LOOP.is_running():
                for aid in list(ACCOUNTS.keys()):
                    asyncio.run_coroutine_threadsafe(restart_account(aid), MAIN_LOOP)
                return {"success": True, "message": "Reconnecting all accounts..."}
            return {"success": False, "error": "Bot engine not running"}

        if action == "shutdown":
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(shutdown(), MAIN_LOOP)
                return {"success": True, "message": "Bot is safely shutting down..."}
            return {"success": False, "error": "Bot engine not running"}

        if action == "exec_command":
            raw_cmd = payload.get("command", "").strip()
            if not raw_cmd:
                return {"success": False, "error": "Command empty"}
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(handle_command(raw_cmd), MAIN_LOOP)
                return {"success": True, "message": f"Command executed: {raw_cmd}"}
            return {"success": False, "error": "Bot engine not running"}

        if action == "set_default_channel":
            new_ch = payload.get("channel_id")
            if new_ch:
                CHANNEL_ID = str(new_ch)
                save_env_config()
                return {"success": True, "message": f"Default Channel set to {CHANNEL_ID} (saved to .env)"}
            return {"success": False, "error": "Missing channel_id"}

        if action == "set_guild_id":
            new_g = payload.get("guild_id")
            if new_g:
                GUILD_ID = str(new_g)
                save_env_config()
                return {"success": True, "message": f"Server (Guild) ID set to {GUILD_ID} (saved to .env)"}
            return {"success": False, "error": "Missing guild_id"}

        if action == "set_config":
            new_ch = payload.get("channel_id")
            new_g = payload.get("guild_id")
            if new_ch:
                CHANNEL_ID = str(new_ch)
            if new_g:
                GUILD_ID = str(new_g)
            save_env_config()
            return {
                "success": True,
                "message": f"Saved to .env! Server: {GUILD_ID}, Channel: {CHANNEL_ID}",
                "guild_id": GUILD_ID,
                "channel_id": CHANNEL_ID,
            }

        if action == "detect_guild":
            target_ch = payload.get("channel_id") or CHANNEL_ID
            if not target_ch:
                return {"success": False, "error": "Channel ID required"}
            coro = auto_resolve_guild(target_ch)
            fut = asyncio.run_coroutine_threadsafe(coro, MAIN_LOOP)
            try:
                res_gid = fut.result(timeout=10)
                save_env_config()
                return {"success": True, "message": f"Auto-detected Server ID: {res_gid}", "guild_id": res_gid}
            except Exception as e:
                return {"success": False, "error": str(e)}

        if MAIN_LOOP is None or not MAIN_LOOP.is_running():
            return {"success": False, "error": "Main loop not running"}

        percent = int(payload.get("percent", 100))
        ch = payload.get("channel_id") or CHANNEL_ID

        if action in ("move", "join"):
            coro = self._run_move(ch, percent)
        elif action == "disconnect":
            coro = self._run_disconnect(percent)
        elif action == "mute":
            coro = self._run_mute(percent, True)
        elif action == "unmute":
            coro = self._run_mute(percent, False)
        elif action == "deafen":
            coro = self._run_deaf(percent, True)
        elif action == "undeafen":
            coro = self._run_deaf(percent, False)
        elif action == "token_add":
            tok = payload.get("token", "").strip()
            if not tok:
                return {"success": False, "error": "Token is empty"}
            coro = add_account(tok)
        elif action == "tokens_bulk_add":
            token_list = payload.get("tokens", [])
            if not token_list:
                return {"success": False, "error": "No tokens provided"}
            coro = self._run_bulk_add(token_list)
        elif action == "batch_action":
            aids = [int(x) for x in payload.get("ids", []) if str(x).isdigit()]
            sub = payload.get("sub_action", "")
            ch = payload.get("channel_id") or CHANNEL_ID
            targets = [ACCOUNTS[aid] for aid in aids if aid in ACCOUNTS and ACCOUNTS[aid].get("online")]
            if sub in ("move", "join"):
                coro = apply(targets, channel=ch)
            elif sub == "disconnect":
                coro = apply(targets, channel=None)
            elif sub == "mute":
                coro = apply(targets, mute=True)
            elif sub == "unmute":
                coro = apply(targets, mute=False)
            elif sub == "deafen":
                coro = apply(targets, deaf=True)
            elif sub == "undeafen":
                coro = apply(targets, deaf=False)
            else:
                return {"success": False, "error": f"Unknown batch sub_action: {sub}"}
        elif action == "token_remove":
            aid = payload.get("id")
            coro = remove_account(aid)
        elif action.startswith("account_"):
            aid = payload.get("id")
            sub = action.replace("account_", "")
            coro = self._run_account_action(aid, sub, payload.get("channel_id"))
        else:
            return {"success": False, "error": f"Unknown action: {action}"}

        fut = asyncio.run_coroutine_threadsafe(coro, MAIN_LOOP)
        try:
            res = fut.result(timeout=10)
            return {"success": True, "message": f"Action '{action}' executed.", "result": str(res)}
        except Exception as e:
            return {"success": False, "error": str(e)}

    async def _run_bulk_add(self, tokens):
        count = 0
        for raw in tokens:
            t = str(raw).strip()
            if t and not is_placeholder_token(t):
                await add_account(t)
                count += 1
        return f"Loaded {count} token(s)"

    async def _run_move(self, channel_id, percent):
        targets = pick_targets(percent, online_accounts())
        done = await apply(targets, channel=channel_id)
        return f"{done}/{len(targets)} account(s) moved to {channel_id}"

    async def _run_disconnect(self, percent):
        targets = pick_targets(percent, voice_accounts())
        done = await apply(targets, channel=None)
        return f"{done}/{len(targets)} account(s) disconnected"

    async def _run_mute(self, percent, state):
        targets = pick_targets(percent, voice_accounts())
        done = await apply(targets, mute=state)
        return f"{done}/{len(targets)} account(s) mute={state}"

    async def _run_deaf(self, percent, state):
        targets = pick_targets(percent, voice_accounts())
        done = await apply(targets, deaf=state)
        return f"{done}/{len(targets)} account(s) deafen={state}"

    async def _run_account_action(self, aid, sub, ch):
        acc = find_account(aid)
        if not acc:
            raise ValueError("Account not found")
        if sub in ("move", "join"):
            target_ch = ch or CHANNEL_ID
            await apply([acc], channel=target_ch)
            return f"Moved to {target_ch}"
        elif sub == "disconnect":
            await apply([acc], channel=None)
            return "Disconnected"
        elif sub == "mute":
            await apply([acc], mute=True)
            return "Muted"
        elif sub == "unmute":
            await apply([acc], mute=False)
            return "Unmuted"
        elif sub == "deafen":
            await apply([acc], deaf=True)
            return "Deafened"
        elif sub == "undeafen":
            await apply([acc], deaf=False)
            return "Undeafened"
        raise ValueError(f"Unknown subaction: {sub}")


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def start_web_server():
    global WEB_PORT
    class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    ports_to_try = [WEB_PORT, 5001, 5002, 8080, 8000]
    seen = set()
    ports_to_try = [p for p in ports_to_try if not (p in seen or seen.add(p))]

    server = None
    for port in ports_to_try:
        try:
            server = ThreadingHTTPServer((WEB_HOST, port), DashboardHandler)
            WEB_PORT = port
            break
        except OSError:
            continue

    if server is None:
        print("[!] Warning: Could not bind Web UI to any available port. CLI controls are still active.")
        return

    try:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        local_ip = get_local_ip()
        print(f"\n[WEB UI] Local Mobile link: http://localhost:{WEB_PORT}")
        if local_ip not in ("127.0.0.1", "0.0.0.0"):
            print(f"[WEB UI] Wi-Fi Network link: http://{local_ip}:{WEB_PORT}")
    except Exception as e:
        print(f"[!] Warning: Could not start Web UI thread: {e}")


async def main():
    global START_TIME, MAIN_LOOP
    START_TIME = time.time()
    MAIN_LOOP = asyncio.get_running_loop()

    # Start built-in Web UI dashboard
    start_web_server()

    # Register OS signal handlers for clean SIGINT / SIGTERM
    try:
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, lambda: asyncio.create_task(shutdown()))
            except (NotImplementedError, AttributeError):
                pass
    except Exception:
        pass

    # 1. Load initial tokens from environment or TOKENS list
    for token in TOKENS:
        if not is_placeholder_token(token):
            await add_account(token)

    # 2. Auto-load from tokens.txt if present
    if os.path.exists("tokens.txt"):
        await load_tokens_from_file("tokens.txt")

    if not ACCOUNTS:
        print("\n[!] No tokens loaded yet.")
        print("[!] Add tokens using: '9 <your_token>' or put them in 'tokens.txt'")
    print(MENU_TEXT)

    try:
        await asyncio.gather(command_loop(), command_file_loop())
    except (asyncio.CancelledError, Exception) as e:
        print(f"Exiting loop: {e}")
    finally:
        if RUNNING:
            await shutdown()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        try:
            asyncio.run(shutdown())
        except Exception:
            pass
