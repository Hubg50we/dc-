# dc- (Discord Multi-Account Voice Manager)

A lightweight, asynchronous Python tool designed to manage and control multiple Discord accounts (voice join, move, mute, deafen, disconnect) via console commands or detached background file polling.

Optimized for **Termux on Android**, Linux VPS, macOS, and Windows.

---

## ⚡ Quick Setup for Termux (Android)

Open **Termux** and run this single command line to install all packages and set up the repository:

```bash
pkg update -y && pkg install -y git python && git clone https://github.com/Hubg50we/dc-.git && cd dc- && bash install.sh
```

Once installed, **simply type**:
```bash
dc
```
*(This immediately opens the **interactive full-screen Terminal Control Center (TUI)** inside Termux. Zero browser popups, zero hassle!)*

---

## 📱 100% Termux Interactive Command Center (No Browser Needed)

Everything is managed **100% inside Termux** without ever opening a browser or switching apps:

| Command | Action |
|---|---|
| `dc` | Launches the **interactive full-screen Terminal UI (TUI)** directly in Termux |
| `dc status` | Displays a live colored status table of all connected Discord accounts |
| `dc start` | Starts the bot in background and enables Android wake-lock (no browser popup) |
| `dc stop` | Cleanly stops the bot and releases the Android wake-lock |
| `dc restart` | Restarts the bot background engine |
| `dc logs` | Live streams real-time activity logs in Termux (`tail -f`) |
| `dc join <channel_id>` | Commands all accounts to join/move to a voice channel |
| `dc mute` | Mutes all accounts in voice |
| `dc unmute` | Unmutes all accounts in voice |
| `dc leave` | Disconnects all accounts from voice |
| `dc channel <id>` | Sets default Voice Channel ID (saves to `.env`) |
| `dc guild <id>` | Sets Discord Server (Guild) ID (saves to `.env`) |
| `dc mobile` | Toggles Discord identity to 📱 Android Mobile (Green Phone badge) |
| `dc desktop` | Toggles Discord identity to 💻 Desktop Browser |
| `dc token add <token>` | Add an account token instantly from terminal |
| `dc token list` | List all loaded tokens (masked for security) |
| `dc web` | *(Optional)* Opens Web Dashboard in browser only when explicitly asked |

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

## 🌐 All-in-One Web UI Dashboard (100% Mobile & PC Controlled)

`dc-` includes an advanced, zero-dependency **Web Control Panel** running directly on port `5000`. You can configure, manage, and monitor everything **100% within the UI** without ever touching terminal commands:

- **On Mobile (Termux)**: While running, open `http://localhost:5000` (or `http://127.0.0.1:5000`) in Chrome / Firefox on your phone.
- **On PC**: Open `http://localhost:5000` in any browser.
- **On Local WiFi**: Access it from another phone or PC via `http://<YOUR_IP>:5000`.
- **Installable PWA**: Add to Home Screen as a native mobile app with AMOLED dark theme!

### 🎯 5 Dedicated Tabs (Full UI Management):

1. **🎛️ Controls Tab**:
   - **Editable Guild & Channel ID**: Edit your Discord Server ID & Voice Channel ID directly in input fields with 1-tap Save.
   - **Auto-Detect Server ID**: Don't know your Server ID? Just enter your Voice Channel ID, click **Auto-Detect**, and the bot looks up your Server ID automatically via Discord's API!
   - **Quick Channel Presets**: 1-tap chips to switch between preset rooms (Lounge, Gaming, Music, General, AFK).
   - **Batch Percentage Slider**: Move, mute, deafen, or disconnect 10%, 25%, 50%, 75%, or 100% of your bots.
   - **Floating Mobile Thumb Bar**: Bottom-docked quick buttons for instantaneous thumb control on phones.

2. **🤖 Bots Tab**:
   - **Live Account Cards**: Real-time status indicators (🟢 Online, 🔊 In Voice, 🔇 Muted, 🎧 Deafened, 📱 Mobile).
   - **Batch Selection Bar**: Select specific bots to perform targeted voice actions.
   - **Detailed Account Modal**: Tap any bot card to see Ping, Gateway ID, Voice State, and per-bot controls.

3. **🔑 Tokens Tab (100% In-Browser Management)**:
   - **Upload `tokens.txt`**: Pick and upload token files directly from your phone's file storage.
   - **Bulk Multi-Line Paste**: Paste multiple tokens at once with instant live validation.
   - **Single Token Add**: Add new tokens dynamically on the fly without restarting.
   - **Backup & Download**: 1-click download of your current `tokens.txt` backup.
   - **Masked Token Security**: Displays masked tokens (`MTAx...w0wQ`) with 1-tap delete.

4. **⚙️ Settings Tab**:
   - **Device Appearance Toggle**: Choose between **📱 Discord Android (Green Mobile Icon)** or **💻 Desktop/Browser**.
   - **Channel Presets Manager**: Create, rename, edit, and delete custom voice channel shortcuts.
   - **Power Controls**: Cleanly **Restart Engine** or **Shut Down** all Discord instances straight from the browser.

5. **📜 Logs Tab**:
   - **Live Streaming Terminal**: Real-time log stream showing Opcode handshakes, reconnects, and actions.
   - **Terminal Input Bar**: Execute console commands directly from the UI.
   - **Copy & Clear**: 1-tap copy logs to clipboard or clear screen.

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
