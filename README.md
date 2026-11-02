# dc- (Discord Multi-Account Voice Manager)

A lightweight, asynchronous Python tool designed to manage and control multiple Discord accounts (voice join, move, mute, deafen, disconnect) via console commands or detached background file polling.

Optimized for **Termux on Android**, Linux VPS, macOS, and Windows.

---

## ⚡ Quick Setup for Termux (Android)

Open **Termux** and run this single command line to install all packages and clone the repository:

```bash
pkg update -y && pkg install -y git python && git clone https://github.com/Hubg50we/dc-.git && cd dc- && bash install.sh
```

---

## 🚀 Manual Step-by-Step Setup

### 1. Update Termux & Install Prerequisites
```bash
pkg update -y
pkg install -y git python
```

### 2. Clone the Repository
```bash
git clone https://github.com/Hubg50we/dc-.git
cd dc-
```

### 3. Install Python Dependencies
```bash
pip install -r requirements.txt
```

---

## ⚙️ Configuration

### Option A: Edit `.env` file (Recommended)
Copy the example environment file:
```bash
cp .env.example .env
nano .env
```
Fill in your details:
- `GUILD_ID`: Your Discord Server (Guild) ID
- `CHANNEL_ID`: Default Voice Channel ID
- `TOKENS`: Comma-separated Discord tokens (optional)

*(To exit nano: press `Ctrl + X`, then `Y`, then `Enter`)*

### Option B: Use `tokens.txt`
You can also put your Discord account tokens directly in `tokens.txt` (one token per line):
```bash
cp tokens.txt.example tokens.txt
nano tokens.txt
```
Any tokens in `tokens.txt` are **automatically loaded** when the script starts!

---

## ▶️ Running the Bot

Run directly using Python:
```bash
python main.py
```
Or use the provided launcher (which automatically requests `termux-wake-lock` to keep it running with screen turned off):
```bash
bash start.sh
```

---

## 📱 Running 24/7 in Background on Android (Termux)

To prevent Android from killing Termux when you minimize the app or turn off the screen:

1. **Acquire Wake Lock**:
   ```bash
   termux-wake-lock
   ```
2. **Disable Battery Optimization**:
   Go to your Android phone's **Settings > Apps > Termux > Battery** and select **Unrestricted**.
3. **Run in Background with `nohup` or `tmux`**:
   ```bash
   # Option 1: tmux (Recommended)
   pkg install -y tmux
   tmux new -s dc
   python main.py
   # (Press Ctrl+B then D to detach)
   # To reconnect later: tmux attach -t dc

   # Option 2: nohup
   nohup python main.py > bot.log 2>&1 &
   ```
4. **Sending Commands while Running in Background**:
   Write commands directly to `commands.txt`:
   ```bash
   echo "move 123456789012345678" >> commands.txt
   echo "mute 100" >> commands.txt
   echo "disconnect" >> commands.txt
   ```

---

## 🎮 Command Reference

You can type these commands into the running console:

| Command | Description | Example |
|---|---|---|
| `status` | View status and current voice channel of all connected accounts | `status` |
| `stats` | View total accounts, online count, in-voice count, and uptime | `stats` |
| `move <channel_id> [percent]` | Move accounts into a voice channel (joins if not connected) | `move 987654321012345678` or `move 987654321012345678 50%` |
| `join <channel_id> [percent]` | Alias of `move` | `join 987654321012345678` |
| `mute [percent]` | Self-mute accounts in voice | `mute` or `mute 30` |
| `unmute [percent]` | Unmute accounts in voice | `unmute` |
| `deafen [percent]` | Self-deafen accounts in voice | `deafen` |
| `undeafen [percent]` | Undeafen accounts in voice | `undeafen` |
| `disconnect [percent]` | Disconnect accounts from voice channel | `disconnect` |
| `token add <token>` | Add an account token dynamically at runtime | `token add <your_token>` |
| `token remove <id\|name\|all>` | Remove account(s) | `token remove 1` or `token remove all` |
| `token status` (or `tokens`) | List all loaded accounts and their connection state | `tokens` |
| `token load <file>` | Load tokens from a file | `token load tokens.txt` |
| `token save [file]` | Save current tokens to a file | `token save tokens.txt` |
| `account <id\|name> <action>` | Control a single account (`move`, `mute`, `deafen`, etc.) | `account 1 mute` |
| `shutdown` (or `quit` / `exit`) | Safely disconnect all accounts and shut down | `quit` |

---

## 📤 How to Push to GitHub (`https://github.com/Hubg50we/dc-`)

If you want to push this project to your GitHub repository from your computer:

```bash
cd "C:\Users\Administrator\.gemini\antigravity\scratch\dc-"
git init
git add .
git commit -m "Initial commit for dc- Termux setup"
git branch -M main
git remote add origin https://github.com/Hubg50we/dc-.git
git push -u origin main
```
*(GitHub will prompt you for your GitHub username and Personal Access Token (PAT) as password).*
