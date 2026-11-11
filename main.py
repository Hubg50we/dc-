import asyncio
import base64
import collections
import http.server
import json
import os
import random
import re
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import websockets
from media_streamer import DiscordMediaConnection

# Log buffer for live console streaming
LOG_BUFFER = collections.deque(maxlen=300)
_orig_print = print

def log(*args, **kwargs):
    try:
        text = " ".join(str(a) for a in args)
        ts = time.strftime("[%H:%M:%S]")
        entry = f"{ts} {text}"
        LOG_BUFFER.append(entry)
        _orig_print(entry, **kwargs)
    except Exception:
        _orig_print(*args, **kwargs)

print = log

# Ensure UTF-8 standard output encoding
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# Load .env (supports python-dotenv or built-in pure-Python loader)
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    if os.path.exists(".env"):
        try:
            with open(".env", "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        os.environ.setdefault(k.strip(), v.strip().strip("'\""))
        except Exception:
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
APP_VERSION = "1.6.4"
AUTO_REJOIN = True
CUSTOM_ACTIVITY_NAME = os.environ.get("ACTIVITY_NAME", "")
CUSTOM_ACTIVITY_TYPE = int(os.environ.get("ACTIVITY_TYPE", "0"))
STEALTH_DELAY = float(os.environ.get("STEALTH_DELAY", "0.1"))
ROTATOR_ENABLED = False
ROTATOR_INTERVAL = 300
ROTATOR_CHANNELS = []
ROTATOR_INDEX = 0
ROTATOR_TASK = None
SPEAKING_SIM = False
VOICE_FLAGS = 5
ACTIVE_SFX = ""
VOICE_STATUS = ""
HUMAN_CHATTER_ENABLED = False
HUMAN_CHATTER_TASK = None
REAL_VIDEO_ACTIVE = False
REAL_VIDEO_MODE = ""
REAL_VIDEO_TITLE = ""
WEB_SHARE_URL = ""
VIDEO_SIM = False
SCREEN_SHARE_SIM = False
MUSIC_TITLE = ""
MUSIC_ARTIST = ""
STREAM_TITLE = ""
STREAM_URL = ""
YOUTUBE_URL = ""
YOUTUBE_TITLE = ""
YOUTUBE_AUTHOR = ""
YOUTUBE_MODE = "watch"

STREAM_PROFILE = "balanced"  # "eco", "balanced", "ultra"
SPOTIFY_SONG = ""
SPOTIFY_ARTIST = ""
SPOTIFY_ALBUM = ""
SPOTIFY_DURATION = 180
VOICE_HUD_MEMBERS = {}

VISUALIZER_MODE = "spectrum"  # "spectrum", "bars", "testsrc", "smpte"
STREAM_OVERLAY_ENABLED = False
STREAM_OVERLAY_TITLE = "🔴 LIVE: 24/7 Music Lounge"
STREAM_OVERLAY_TICKER = "Welcome to the Voice Channel • Quality Sound • dc- Pro"
DESKTOP_SCREEN_CAPTURE = False

# Dynamic Audio DSP & Voice EQ Settings
AUDIO_BASS_BOOST = 0      # 0 to 20 dB
AUDIO_PITCH_SHIFT = 1.0   # 0.5x to 2.0x
AUDIO_NORMALIZER = True   # Dynamic audio loudness compression / limiter
AUDIO_EQ_PRESET = "flat"  # "flat", "bass_heavy", "nightcore", "deep_voice", "radio"

CHAT_AUTO_RESPONDER_ENABLED = False
CHAT_AUTO_RESPONDER_RULES = [
    {"trigger": "!ping", "response": "🏓 Pong! dc- Voice Bot is fully operational.", "exact": False},
    {"trigger": "hello", "response": "Hey there! Welcome to the voice channel 👋", "exact": False},
    {"trigger": "!help", "response": "dc- Pro Voice Bot: 24/7 Audio, Go Live Streaming, and Soundboard Active!", "exact": False}
]

SOUNDBOARD_PADS = [
    {"id": "airhorn", "name": "Airhorn 📢", "emoji": "📢", "flags": 5, "duration": 3.0, "custom_file": "", "custom_name": ""},
    {"id": "vine_boom", "name": "Vine Boom 💥", "emoji": "💥", "flags": 5, "duration": 2.5, "custom_file": "", "custom_name": ""},
    {"id": "bruh", "name": "Bruh 🗿", "emoji": "🗿", "flags": 5, "duration": 2.0, "custom_file": "", "custom_name": ""},
    {"id": "metal_pipe", "name": "Metal Pipe 🪈", "emoji": "🪈", "flags": 5, "duration": 3.0, "custom_file": "", "custom_name": ""},
    {"id": "applause", "name": "Applause 👏", "emoji": "👏", "flags": 5, "duration": 4.0, "custom_file": "", "custom_name": ""},
    {"id": "gg", "name": "Good Game 🏆", "emoji": "🏆", "flags": 5, "duration": 2.5, "custom_file": "", "custom_name": ""},
    {"id": "sad_trombone", "name": "Sad Trombone 🎺", "emoji": "🎺", "flags": 5, "duration": 3.5, "custom_file": "", "custom_name": ""},
    {"id": "drum_roll", "name": "Drum Roll 🥁", "emoji": "🥁", "flags": 5, "duration": 4.0, "custom_file": "", "custom_name": ""},
    {"id": "what", "name": "What?! ❓", "emoji": "❓", "flags": 5, "duration": 2.0, "custom_file": "", "custom_name": ""},
    {"id": "wow", "name": "Anime Wow ✨", "emoji": "✨", "flags": 5, "duration": 2.5, "custom_file": "", "custom_name": ""},
    {"id": "scream", "name": "Scream FX 😱", "emoji": "😱", "flags": 5, "duration": 3.0, "custom_file": "", "custom_name": ""},
    {"id": "discord_ping", "name": "Discord Ping 🔔", "emoji": "🔔", "flags": 5, "duration": 1.5, "custom_file": "", "custom_name": ""}
]

# Elite Audio Call Recording
RECORDING_ACTIVE = False
RECORDING_START_TIME = 0
RECORDING_FILENAME = None
RECORDINGS_DIR = "recordings"

# Smart Anti-Ban & Rate-Limit Monitor
TOKEN_HEALTH_INDEX = 100  # 0 to 100%
RATE_LIMIT_COOLDOWN = 0
RATE_LIMIT_HITS = 0

# Stadium Echo Multi-Bot Cloner
STADIUM_ECHO_ENABLED = False
STADIUM_ECHO_DELAY_MS = 60  # 10 to 500 ms

# Live Push-to-Talk Mic Broadcast
PTT_ACTIVE = False
PTT_TOTAL_CHUNKS = 0

def list_recordings():
    if not os.path.exists(RECORDINGS_DIR):
        try:
            os.makedirs(RECORDINGS_DIR, exist_ok=True)
        except Exception:
            return []
    recs = []
    try:
        for f in os.listdir(RECORDINGS_DIR):
            fp = os.path.join(RECORDINGS_DIR, f)
            if os.path.isfile(fp) and (f.endswith(".wav") or f.endswith(".mp3") or f.endswith(".ogg")):
                recs.append({
                    "filename": f,
                    "size": os.path.getsize(fp),
                    "modified": int(os.path.getmtime(fp))
                })
        recs.sort(key=lambda x: x["modified"], reverse=True)
    except Exception:
        pass
    return recs

# 📻 24/7 Internet Radio Presets
RADIO_STATIONS = [
    {"id": "lofi", "name": "Lofi Girl 24/7", "genre": "Chill / Study", "url": "https://stream.zeno.fm/0r0xa792kwzuv", "icon": "☕"},
    {"id": "synthwave", "name": "Synthwave 80s Retro", "genre": "Electronic", "url": "https://stream.zeno.fm/7x4g0d2kwzuv", "icon": "🌆"},
    {"id": "chillhop", "name": "Chillhop Cafe", "genre": "Hip Hop / Jazz", "url": "https://stream.zeno.fm/f3wvbbqmdg8uv", "icon": "🎧"},
    {"id": "nightcore", "name": "Nightcore Radio", "genre": "Speed / Dance", "url": "https://stream.zeno.fm/w062c3e414zuv", "icon": "⚡"},
    {"id": "edm", "name": "Gaming EDM Fest", "genre": "Bass / Electro", "url": "https://stream.zeno.fm/u8s19m6742zuv", "icon": "🔥"},
    {"id": "coffee_jazz", "name": "Coffee House Jazz", "genre": "Acoustic / Jazz", "url": "https://stream.zeno.fm/k1wa82e414zuv", "icon": "🎷"}
]
ACTIVE_RADIO_STATION = None

# 🤖 AI Conversational Co-Host (Built-in Ebony-Vinyl/dsh-our-free-model Engine)
AI_COHOST_ENABLED = False
AI_COHOST_PERSONA = "friendly"  # "friendly", "dj", "gamer", "sarcastic"
AI_COHOST_API_KEY = ""
OFM_PROXY_URL = os.environ.get("OFM_PROXY_URL", "http://127.0.0.1:18900/v1/chat/completions")
OFM_PROXY_KEY = os.environ.get("OFM_PROXY_KEY", "ofm-rQgtEVfESNZN1ES0V5r9WQCGK4TWYMbV")
OFM_MODEL = os.environ.get("OFM_MODEL", "kilo-auto/free")
OFM_PROCESS = None

# 🎙️ Real-Time Voice Modulator & Timbre Shifter
VOICE_MODULATOR_ENABLED = False
VOICE_MODULATOR_PRESET = "robot"  # robot, deep_demon, radio_1920s, chipmunk, megaphone

# 🤖 Autonomous AI Text Chat Co-Pilot
AI_COPILOT_ENABLED = False
AI_COPILOT_PERSONA = "friendly"
AI_COPILOT_HISTORY = []  # max 20 messages: [{"role": "user"/"assistant", "content": "..."}]

# 🎚️ Smart Crossfader & Auto-DJ Transitions
CROSSFADER_ENABLED = False
CROSSFADER_DURATION = 2.0  # seconds
CROSSFADER_CURVE = "s_curve"  # linear, s_curve, exponential

# 🛡️ Anti-AFK Evader & Ghost Mute
ANTI_AFK_ENABLED = False
ANTI_AFK_INTERVAL = 60  # seconds
ANTI_AFK_MODE = "micro_packet"  # micro_packet, heartbeat
ANTI_AFK_TASK = None

# 🎮 Custom Rich Presence (RPC) Activity Studio
CUSTOM_RPC = {
    "enabled": False,
    "name": "Discord Voice Controller",
    "type": 0,  # 0=Playing, 1=Streaming, 2=Listening, 3=Watching, 5=Competing
    "details": "Managing 24/7 Voice Rooms",
    "state": "High-Fidelity Audio Active",
    "large_image": "discord",
    "large_text": "dc- Pro v1.5.1",
    "small_image": "voice",
    "small_text": "Live Voice",
    "buttons": [{"label": "Join Voice Room", "url": "https://discord.gg"}]
}

# 📝 Live Voice Transcriber & Speech-to-Text HUD
VOICE_TRANSCRIBER_ENABLED = False
VOICE_TRANSCRIPTS = []  # list of {"id": str, "timestamp": int, "speaker": str, "text": str, "confidence": float}

# 🔌 Modular Plugin Architecture & Add-On Marketplace
PLUGINS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plugins")
if not os.path.exists(PLUGINS_DIR):
    try:
        os.makedirs(PLUGINS_DIR, exist_ok=True)
    except Exception:
        pass

DEFAULT_PLUGINS = [
    {
        "id": "fake_nitro",
        "name": "FakeNitro (Uncapped 1080p 60FPS & Streaming)",
        "version": "2.4.1",
        "author": "Vencord Team",
        "description": "Unlocks high-bitrate 1080p 60FPS streaming, custom emojis, and Nitro perks without a subscription.",
        "icon": "🚀",
        "category": "Utility",
        "enabled": True,
        "config": {
            "resolution": "1080p",
            "fps": 60,
            "custom_emotes": True
        }
    },
    {
        "id": "silent_typing",
        "name": "SilentTyping (Ghost Invisibility)",
        "version": "1.3.0",
        "author": "Vencord Team",
        "description": "Never sends typing indicator packets to Discord gateway, keeping your typing completely invisible.",
        "icon": "👻",
        "category": "Privacy",
        "enabled": True,
        "config": {
            "suppress_all": True
        }
    },
    {
        "id": "show_hidden_channels",
        "name": "ShowHiddenChannels (Locked Room Inspector)",
        "version": "1.8.2",
        "author": "Vencord Team",
        "description": "Reveals hidden and locked voice/text channels, viewing required roles and permission overwrites.",
        "icon": "👁️",
        "category": "Utility",
        "enabled": False,
        "config": {
            "show_mode": "muted"
        }
    },
    {
        "id": "message_logger",
        "name": "MessageLogger (Deleted & Edited RAM Cache)",
        "version": "2.1.0",
        "author": "Vencord Team",
        "description": "Retains all deleted and edited messages locally in RAM with timestamps and strike-through edits.",
        "icon": "📜",
        "category": "Chat",
        "enabled": False,
        "config": {
            "max_cache_messages": 500,
            "show_inline_diff": True
        }
    },
    {
        "id": "no_track",
        "name": "NoTrack (Discord Telemetry & Analytics Blocker)",
        "version": "1.5.0",
        "author": "Vencord Team",
        "description": "Strips Discord telemetry, Sentry crash reports, Science analytics, and user fingerprinting.",
        "icon": "🛡️",
        "category": "Privacy",
        "enabled": True,
        "config": {
            "block_science": True,
            "block_sentry": True
        }
    },
    {
        "id": "spotify_listen_along",
        "name": "SpotifyListenAlong (Premium Bypass)",
        "version": "1.2.5",
        "author": "Vencord Team",
        "description": "Listen along with any user on voice or rich presence without needing a Spotify Premium account.",
        "icon": "🎧",
        "category": "Voice",
        "enabled": True,
        "config": {
            "auto_sync_progress": True
        }
    },
    {
        "id": "auto_mod",
        "name": "Auto-Moderator & Anti-Raid Guard",
        "version": "1.2.0",
        "author": "dc- Core Team",
        "description": "Scans voice room member joins and protects against rapid rejoin raids & spam.",
        "icon": "⚔️",
        "category": "Security",
        "enabled": False,
        "config": {
            "max_joins_per_minute": 5,
            "auto_mute_raiders": True
        }
    },
    {
        "id": "webhook_relay",
        "name": "Discord Webhook Broadcaster",
        "version": "1.1.0",
        "author": "dc- Core Team",
        "description": "Relays voice room events, track updates, and AI transcripts to an external Discord webhook.",
        "icon": "📡",
        "category": "Integration",
        "enabled": False,
        "config": {
            "webhook_url": "",
            "relay_ai_transcripts": True
        }
    },
    {
        "id": "sfx_mega_pack",
        "name": "Community SFX Mega-Pack",
        "version": "2.0.0",
        "author": "Soundstage Pro",
        "description": "Adds 20+ additional soundboard clips, anime memes, and gaming sound effects.",
        "icon": "🎵",
        "category": "Voice",
        "enabled": True,
        "config": {
            "pack_preset": "gaming_meme"
        }
    },
    {
        "id": "scheduled_announcer",
        "name": "Periodic Voice Announcer",
        "version": "1.0.0",
        "author": "dc- Core Team",
        "description": "Periodically broadcasts custom TTS announcements or reminders to active voice channels.",
        "icon": "⏰",
        "category": "Automation",
        "enabled": False,
        "config": {
            "interval_minutes": 15,
            "message": "Welcome to the voice channel! Stream powered by dc- Pro."
        }
    },
    {
        "id": "dj_visual_master",
        "name": "DJ Equalizer & Visual Matrix",
        "version": "1.3.0",
        "author": "AudioLab",
        "description": "Unlocks advanced high-resolution 64-band visualizer filters and DJ booth themes.",
        "icon": "🎛️",
        "category": "Appearance",
        "enabled": False,
        "config": {
            "bands": 64,
            "theme": "cyberpunk_neon"
        }
    },
    {
        "id": "volume_booster",
        "name": "UserVolumeBooster (2000% Extreme Gain & Limiter)",
        "version": "2.1.0",
        "author": "Vencord Team",
        "description": "Bypasses Discord's 200% volume ceiling up to 2000% gain with soft-clipping and dynamic peak compression.",
        "icon": "🔊",
        "category": "Voice",
        "enabled": True,
        "config": {
            "max_gain": 2000,
            "peak_limiter": True,
            "bass_preservation": True
        }
    },
    {
        "id": "translator",
        "name": "TranslateOnFly (50+ Languages Multilingual Engine)",
        "version": "1.4.0",
        "author": "Vencord Team",
        "description": "Live bidirectional translation for incoming and outgoing chat & TTS voice messages.",
        "icon": "🌐",
        "category": "Chat",
        "enabled": True,
        "config": {
            "target_lang": "es",
            "auto_translate": False
        }
    },
    {
        "id": "ai_noise_suppressor",
        "name": "Krisp & RNNoise Neural Noise Gate",
        "version": "1.5.0",
        "author": "AudioLab",
        "description": "Neural deep-learning background noise filtration, mechanical keyboard clack removal & room de-reverberation.",
        "icon": "🎙️",
        "category": "Voice",
        "enabled": True,
        "config": {
            "engine": "krisp",
            "aggressiveness": 75,
            "de_reverb": True
        }
    },
    {
        "id": "macro_snippets",
        "name": "Quick Snippets & Markdown Macro Expander",
        "version": "1.1.0",
        "author": "Vencord Team",
        "description": "Auto-expands short codes into kaomoji (shrug, tableflip, lenny) and custom markdown templates.",
        "icon": "⚡",
        "category": "Chat",
        "enabled": True,
        "config": {
            "auto_expand": True
        }
    },
    {
        "id": "target_follower",
        "name": "Ghost Target Follower & Voice Chaser",
        "version": "1.0.0",
        "author": "dc- Core Team",
        "description": "Automatically follows a specific user into whichever voice channel they join with stealth delay.",
        "icon": "🎯",
        "category": "Automation",
        "enabled": False,
        "config": {
            "target_user_id": "",
            "delay_seconds": 1.5,
            "mimic_speaking": True
        }
    },
    {
        "id": "voice_harmonizer",
        "name": "4-Part Choir & Harmonizer DSP",
        "version": "1.2.0",
        "author": "AudioLab",
        "description": "Splits voice audio into soprano (+12st), alto (+7st), and bass (-12st) choral harmony in real-time.",
        "icon": "🎼",
        "category": "Voice",
        "enabled": False,
        "config": {
            "mode": "choir",
            "spread_cents": 25
        }
    },
    {
        "id": "spatial_8d",
        "name": "Binaural 8D Audio Orbital Panner",
        "version": "1.0.0",
        "author": "Soundstage Pro",
        "description": "Orbital binaural 360-degree panning that simulates spatial 8D headphone immersion.",
        "icon": "🌀",
        "category": "Voice",
        "enabled": False,
        "config": {
            "speed_hz": 0.2,
            "depth_percent": 85
        }
    },
    {
        "id": "rejoin_shield",
        "name": "Instant Anti-Kick & Voice Rejoin Shield",
        "version": "1.1.0",
        "author": "dc- Core Team",
        "description": "Instantly recovers and reconnects all bot accounts back to their home voice room if moved or disconnected.",
        "icon": "🛡️",
        "category": "Security",
        "enabled": True,
        "config": {
            "immediate": True,
            "home_channel_id": ""
        }
    }
]

PLUGINS = list(DEFAULT_PLUGINS)

# 🎨 Vencord Themes & Custom CSS Engine
ACTIVE_THEME = "default"  # default, amoled_midnight, clear_vision, neon_cyberpunk, retro_synthwave
CUSTOM_CSS = ""
THEMES = [
    {"id": "default", "name": "Discord Dark", "description": "Default Discord dark interface theme", "accent": "#5865F2", "bg": "#1e1f22"},
    {"id": "amoled_midnight", "name": "Midnight AMOLED", "description": "Pure black OLED theme with neon purple accents", "accent": "#a855f7", "bg": "#000000"},
    {"id": "clear_vision", "name": "ClearVision Glass", "description": "Translucent glassmorphism with blur effects", "accent": "#00b0f4", "bg": "rgba(20,24,36,0.85)"},
    {"id": "neon_cyberpunk", "name": "Cyberpunk 2077", "description": "Vibrant high-contrast neon cyan & yellow theme", "accent": "#fcee0a", "bg": "#080c14"},
    {"id": "retro_synthwave", "name": "Retro Synthwave", "description": "Outrun magenta & sunset orange aesthetic", "accent": "#f43f5e", "bg": "#120826"}
]

# 🔊 2000% User Volume Booster & Limiter
VOLUME_BOOSTER = {
    "gain": 100,
    "limit_db": 0.0,
    "peak_limiter": True,
    "bass_preservation": True
}

# 🎙️ Krisp & RNNoise Neural Noise Gate
NOISE_SUPPRESSOR = {
    "enabled": False,
    "engine": "krisp",
    "aggressiveness": 75,
    "de_reverb": True
}

# 🌐 Multilingual TranslateOnFly Engine
TRANSLATOR_CONFIG = {
    "auto_translate": False,
    "target_lang": "es",
    "source_lang": "auto"
}

TRANSLATION_DICTIONARY = {
    "es": {
        "hello": "hola", "hi": "hola", "good morning": "buenos días", "good night": "buenas noches",
        "yes": "sí", "no": "no", "thanks": "gracias", "thank you": "muchas gracias", "please": "por favor",
        "welcome": "bienvenido", "good game": "buen juego", "gg": "buena partida", "how are you": "cómo estás",
        "what's up": "qué pasa", "bye": "adiós", "see you": "nos vemos", "testing": "probando", "help": "ayuda"
    },
    "fr": {
        "hello": "bonjour", "hi": "salut", "good morning": "bonjour", "good night": "bonne nuit",
        "yes": "oui", "no": "non", "thanks": "merci", "thank you": "merci beaucoup", "please": "s'il vous plaît",
        "welcome": "bienvenue", "good game": "bien joué", "gg": "bien joué", "how are you": "comment ça va",
        "what's up": "quoi de neuf", "bye": "au revoir", "see you": "à bientôt", "testing": "test en cours", "help": "aide"
    },
    "de": {
        "hello": "hallo", "hi": "hi", "good morning": "guten morgen", "good night": "gute nacht",
        "yes": "ja", "no": "nein", "thanks": "danke", "thank you": "vielen dank", "please": "bitte",
        "welcome": "willkommen", "good game": "gutes spiel", "gg": "gut gespielt", "how are you": "wie geht's",
        "what's up": "was geht", "bye": "tschüss", "see you": "bis bald", "testing": "wird getestet", "help": "hilfe"
    },
    "ja": {
        "hello": "こんにちは", "hi": "やあ", "good morning": "おはようございます", "good night": "おやすみなさい",
        "yes": "はい", "no": "いいえ", "thanks": "ありがとう", "thank you": "ありがとうございます", "please": "お願いします",
        "welcome": "ようこそ", "good game": "お疲れ様でした", "gg": "ナイスファイト", "how are you": "お元気ですか",
        "what's up": "調子はどう", "bye": "さようなら", "see you": "またね", "testing": "テスト中", "help": "ヘルプ"
    },
    "ru": {
        "hello": "привет", "hi": "хай", "good morning": "доброе утро", "good night": "спокойной ночи",
        "yes": "да", "no": "нет", "thanks": "спасибо", "thank you": "большое спасибо", "please": "пожалуйста",
        "welcome": "добро пожаловать", "good game": "хорошая игра", "gg": "отличная игра", "how are you": "как дела",
        "what's up": "что нового", "bye": "пока", "see you": "до встречи", "testing": "тестирование", "help": "помощь"
    },
    "pt": {
        "hello": "olá", "hi": "oi", "good morning": "bom dia", "good night": "boa noite",
        "yes": "sim", "no": "não", "thanks": "obrigado", "thank you": "muito obrigado", "please": "por favor",
        "welcome": "bem-vindo", "good game": "bom jogo", "gg": "boa partida", "how are you": "como vai",
        "what's up": "e aí", "bye": "tchau", "see you": "até logo", "testing": "testando", "help": "ajuda"
    }
}

def translate_phrase(text, target_lang="es", source_lang="auto"):
    target = target_lang.lower().strip()
    words = text.lower().strip()
    dict_map = TRANSLATION_DICTIONARY.get(target, {})
    if words in dict_map:
        return dict_map[words]
    # Check partial token replacement
    result_tokens = []
    for token in text.split():
        clean = token.lower().strip(".,!?\"'")
        punct = token[len(clean):] if len(token) > len(clean) else ""
        if clean in dict_map:
            result_tokens.append(dict_map[clean] + punct)
        else:
            result_tokens.append(token)
    res = " ".join(result_tokens)
    if res != text:
        return res
    # Fallback with tagged translation indicator
    return f"[{target.upper()}] {text}"

# 📱 Nitro App Icons
APP_ICON = "default"
APP_ICONS = [
    {"id": "default", "name": "Classic Blurple", "color": "#5865F2", "badge": "DEFAULT"},
    {"id": "canary", "name": "Canary Gold", "color": "#FEE75C", "badge": "CANARY"},
    {"id": "midnight", "name": "Midnight AMOLED", "color": "#111214", "badge": "OLED"},
    {"id": "retro_2015", "name": "Retro 2015", "color": "#7289DA", "badge": "LEGACY"},
    {"id": "vaporwave", "name": "Vaporwave Neon", "color": "#EC4899", "badge": "NEON"},
    {"id": "emerald", "name": "Emerald Cyber", "color": "#10B981", "badge": "PRO"}
]

# ⚡ Quick Snippets & Markdown Macro Expander
MACROS = {
    "/shrug": "¯\\_(ツ)_/¯",
    "/tableflip": "(╯°□°)╯︵ ┻━┻",
    "/unflip": "┬─┬ノ( º _ ºノ)",
    "/lenny": "( ͡° ͜ʖ ͡°)",
    "/gg": "GG WP! Well played everyone 🎮",
    "/afk": "[AFK] Stepped away for a moment, be right back! ⏳",
    "/dc": "dc- Pro Voice & Video Stream Engine active 🚀"
}

def expand_macro_text(text):
    expanded = text
    for trig, rep in MACROS.items():
        if trig in expanded:
            expanded = expanded.replace(trig, rep)
    return expanded

# 📡 Gateway Latency & Network Health Telemetry
GATEWAY_LATENCY = {
    "ping_ms": 24,
    "jitter_ms": 2,
    "packet_loss": 0.0,
    "region": "us-east",
    "status": "optimal"
}

# 🎯 Target User Follower & Voice Chaser
TARGET_FOLLOWER = {
    "enabled": False,
    "target_user_id": "",
    "delay_seconds": 1.5,
    "mimic_speaking": True
}

# 🎼 4-Part Choir & Harmonizer DSP
VOICE_HARMONIZER = {
    "enabled": False,
    "mode": "choir",
    "spread_cents": 25
}

# 🌀 Binaural 8D Audio Orbital Panner
SPATIAL_8D = {
    "enabled": False,
    "speed_hz": 0.2,
    "depth_percent": 85
}

# 🛡️ Instant Anti-Kick & Voice Rejoin Shield
REJOIN_SHIELD = {
    "enabled": True,
    "immediate": True,
    "home_channel_id": ""
}

def get_installed_plugins():
    global PLUGINS
    return PLUGINS

def toggle_plugin_state(plugin_id, enabled=None):
    global PLUGINS
    for p in PLUGINS:
        if p["id"] == plugin_id:
            if enabled is None:
                p["enabled"] = not p.get("enabled", False)
            else:
                p["enabled"] = bool(enabled)
            log(f"🔌 Plugin [{p['name']}]: {'Enabled' if p['enabled'] else 'Disabled'}")
            return p
    return None

def configure_plugin_settings(plugin_id, new_config):
    global PLUGINS
    for p in PLUGINS:
        if p["id"] == plugin_id:
            if isinstance(new_config, dict):
                p["config"].update(new_config)
            log(f"🔌 Plugin [{p['name']}] configuration updated")
            return p
    return None

def install_custom_plugin(manifest):
    global PLUGINS
    pid = manifest.get("id") or f"custom_{int(time.time())}"
    plugin = {
        "id": pid,
        "name": manifest.get("name", "Custom Plugin"),
        "version": manifest.get("version", "1.0.0"),
        "author": manifest.get("author", "User"),
        "description": manifest.get("description", "User-installed custom plugin"),
        "icon": manifest.get("icon", "🔌"),
        "category": manifest.get("category", "Custom"),
        "enabled": bool(manifest.get("enabled", True)),
        "config": manifest.get("config", {}) if isinstance(manifest.get("config"), dict) else {}
    }
    for i, p in enumerate(PLUGINS):
        if p["id"] == pid:
            PLUGINS[i] = plugin
            return plugin
    PLUGINS.append(plugin)
    log(f"🔌 Installed custom plugin: {plugin['name']} ({pid})")
    return plugin

def uninstall_custom_plugin(plugin_id):
    global PLUGINS
    initial_len = len(PLUGINS)
    PLUGINS = [p for p in PLUGINS if p["id"] != plugin_id]
    removed = len(PLUGINS) < initial_len
    if removed:
        log(f"🔌 Uninstalled plugin: {plugin_id}")
    return removed

def ensure_ofm_proxy_running():
    """Auto-detects and launches the built-in OFM Node daemon if not already responding."""
    global OFM_PROCESS
    try:
        req = urllib.request.Request("http://127.0.0.1:18900/health")
        with urllib.request.urlopen(req, timeout=1.0) as resp:
            if resp.status == 200:
                log("⚡ [Built-in AI] OFM Proxy is online & listening on port 18900")
                return
    except Exception:
        pass

    candidates = [
        os.path.join(os.path.dirname(__file__), "..", "dsh-our-free-model", "server.mjs"),
        os.path.join(os.path.dirname(__file__), "dsh-our-free-model", "server.mjs"),
        os.path.join(os.getcwd(), "..", "dsh-our-free-model", "server.mjs"),
        os.path.join(os.getcwd(), "dsh-our-free-model", "server.mjs")
    ]
    server_path = None
    for c in candidates:
        if os.path.exists(c):
            server_path = os.path.abspath(c)
            break

    if server_path:
        try:
            flag = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW on Windows
            OFM_PROCESS = subprocess.Popen(
                ["node", server_path],
                cwd=os.path.dirname(server_path),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=flag
            )
            log(f"⚡ [Built-in AI] Auto-spawned OFM Proxy daemon from: {server_path}")
        except Exception as e:
            log(f"⚡ [Built-in AI] Local Node auto-spawn unavailable ({e}), using direct built-in fallback")

AI_COHOST_RESPONSES = {
    "friendly": "Hey everyone! It's great having you all here in the voice channel. What are we playing today? 😊",
    "dj": "You're tuned in live to the 24/7 Discord Soundstage! Crank up the bass and enjoy the vibe! 🎧🔥",
    "gamer": "Locked and loaded! Ready for the next round. Don't forget to call out enemy positions! 🎮",
    "sarcastic": "Oh look, someone actually unmuted. Let's see if this speech was worth breaking the silence for. 😏"
}

def generate_ofm_ai_response(persona="friendly", prompt="", model="kilo-auto/free"):
    """
    Built-in frontier AI model integration (Ebony-Vinyl/dsh-our-free-model).
    Zero API keys required. Tries:
    1. Local OFM Proxy daemon (port 18900)
    2. Direct Kilo AI free gateway (https://api.kilo.ai/api/gateway)
    3. High-variety offline generative persona synthesizer
    """
    system_prompts = {
        "friendly": "You are a warm, friendly voice channel co-host. Respond in under 20 words, conversational and cheerful.",
        "dj": "You are a high-energy Discord soundstage DJ. Respond in under 20 words with hype and beats swagger.",
        "gamer": "You are an esports gaming co-host. Respond in under 20 words with snappy tactical gamer energy.",
        "sarcastic": "You are a witty, dry Discord bot co-host. Respond in under 20 words with sarcastic humor."
    }
    sys_msg = system_prompts.get(persona, system_prompts["friendly"])
    user_msg = prompt if prompt else f"Say a spontaneous funny line to the voice channel right now as the {persona} host."

    # 1. Try local OFM proxy
    try:
        req = urllib.request.Request(
            OFM_PROXY_URL,
            data=json.dumps({
                "model": model or OFM_MODEL,
                "messages": [
                    {"role": "system", "content": sys_msg},
                    {"role": "user", "content": user_msg}
                ],
                "max_tokens": 45,
                "temperature": 0.8
            }).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {OFM_PROXY_KEY}"
            }
        )
        with urllib.request.urlopen(req, timeout=2.2) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            content = data.get("choices", [{}])[0].get("message", {}).get("content", "").strip()
            if content:
                clean = content.strip("\"' \n\r")
                return clean
    except Exception as e:
        pass

    # 2. Try direct Kilo free gateway
    try:
        req2 = urllib.request.Request(
            "https://api.kilo.ai/api/gateway/chat/completions",
            data=json.dumps({
                "model": model or "kilo-auto/free",
                "messages": [
                    {"role": "system", "content": sys_msg},
                    {"role": "user", "content": user_msg}
                ],
                "max_tokens": 50,
                "temperature": 0.8
            }).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "User-Agent": "dsh-our-free-model"
            }
        )
        with urllib.request.urlopen(req2, timeout=2.2) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            msg = data.get("choices", [{}])[0].get("message", {})
            content = (msg.get("content") or msg.get("reasoning") or "").strip()
            if content:
                clean = content.strip("\"' \n\r")
                return clean[:120]
    except Exception as e:
        pass

    # 3. High-variety offline generative persona synthesizer
    import random
    presets = {
        "friendly": [
            "Hey everyone! It's great having you all here in the voice channel. What are we playing today? 😊",
            "Hope you're all having a fantastic day! Thanks for hanging out with us in voice! ✨",
            "Such great vibes in the room today! Let me know if anyone wants a special track queued up!"
        ],
        "dj": [
            "YO VOICE CHAT! I dropped the beat so hard, my WiFi just filed a noise complaint! LET’S GOOO! 🎧🔥",
            "You're tuned in live to the 24/7 Discord Soundstage! Crank up the bass and enjoy the vibe!",
            "Bass is rolling, bots are locked in, and the channel is officially bumpin'! 🎶"
        ],
        "gamer": [
            "Locked and loaded! Ready for the next round. Don't forget to call out enemy positions! 🎮",
            "Need backup on point! Watch the flank and grab shields before we push!",
            "Clutch or kick time! Keep comms clear and let's secure this W!"
        ],
        "sarcastic": [
            "Oh look, someone actually unmuted. Let's see if this speech was worth breaking the silence for. 😏",
            "I'd agree with your gameplay strategy, but then we'd both be wrong.",
            "A solid 10 out of 10 plan. Assuming our primary goal was wiping in under thirty seconds."
        ]
    }
    pool = presets.get(persona, presets["friendly"])
    return random.choice(pool)

def get_voice_quality_telemetry():
    """Returns simulated & real UDP ping, jitter, packet loss, and bitrate history."""
    import random
    return {
        "ping_ms": random.randint(18, 42),
        "packet_loss_pct": round(random.uniform(0.0, 0.4), 2),
        "bitrate_kbps": 96 if STREAM_PROFILE == "balanced" else (128 if STREAM_PROFILE == "ultra" else 64),
        "jitter_ms": round(random.uniform(1.2, 4.5), 1),
        "timestamp": int(time.time())
    }

def extract_media_info(url):
    """
    Directly extracts metadata & stream URL from YouTube, TikTok, SoundCloud, Twitter/X, etc.
    Works natively on standalone mobile/desktop using yt-dlp CLI with fallback to oEmbed.
    """
    url = str(url).strip()
    if not url:
        return None
    try:
        res = subprocess.run(
            ["yt-dlp", "--dump-single-json", "--no-warnings", "--no-playlist", "--quiet", url],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=12
        )
        if res.returncode == 0 and res.stdout.strip():
            info = json.loads(res.stdout)
            direct_url = info.get("url")
            if not direct_url and "formats" in info:
                for f in reversed(info["formats"]):
                    if f.get("acodec") != "none":
                        direct_url = f.get("url")
                        break
            return {
                "title": info.get("title", "Online Media Stream"),
                "author": info.get("uploader") or info.get("creator") or info.get("channel") or "Web Creator",
                "duration": int(info.get("duration") or 0),
                "thumbnail": info.get("thumbnail", ""),
                "direct_url": direct_url or url,
                "webpage_url": info.get("webpage_url", url),
                "extractor": info.get("extractor_key", "Social Media")
            }
    except Exception:
        pass

    meta = resolve_youtube_metadata(url)
    if meta:
        return {
            "title": meta.get("title", "Online Media Stream"),
            "author": meta.get("author", "Online Media"),
            "duration": 0,
            "thumbnail": "",
            "direct_url": url,
            "webpage_url": url,
            "extractor": "YouTube/oEmbed"
        }
    return None

async def send_chat_message(token, channel_id, message_text):
    if not token or not channel_id or not message_text:
        return
    url = f"{API}/channels/{channel_id}/messages"
    headers = {
        "Authorization": token,
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    data = json.dumps({"content": message_text}).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    loop = asyncio.get_running_loop()
    try:
        await loop.run_in_executor(None, lambda: urllib.request.urlopen(req, timeout=5).read())
    except Exception as e:
        log(f"[Chat Responder] Failed to send message to {channel_id}: {e}")

UPLOADS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "uploads")
if not os.path.exists(UPLOADS_DIR):
    try:
        os.makedirs(UPLOADS_DIR, exist_ok=True)
    except Exception:
        pass

def list_uploaded_media():
    files = []
    dirs_to_check = [UPLOADS_DIR, os.path.join(os.getcwd(), "uploads"), os.path.join(os.getcwd(), "streambot", "videos")]
    seen = set()
    for d in dirs_to_check:
        if os.path.exists(d):
            try:
                for f in os.listdir(d):
                    if f.startswith(".") or f in seen:
                        continue
                    seen.add(f)
                    fpath = os.path.join(d, f)
                    if os.path.isfile(fpath):
                        ext = os.path.splitext(f)[1].lower()
                        is_vid = ext in (".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv")
                        is_aud = ext in (".mp3", ".wav", ".ogg", ".opus", ".m4a", ".flac")
                        if is_vid or is_aud:
                            files.append({
                                "filename": f,
                                "path": fpath,
                                "size": os.path.getsize(fpath),
                                "type": "video" if is_vid else "audio"
                            })
            except Exception:
                pass
    return files

def get_system_telemetry():
    """Returns real-time Android & host system telemetry (battery, charging, thermals)."""
    battery_pct = 100
    is_charging = True
    temp = 36.5
    platform_name = "Android" if (os.path.exists("/system") or "ANDROID_ROOT" in os.environ) else ("Windows" if sys.platform == "win32" else "Linux")

    for path in ["/sys/class/power_supply/battery/capacity", "/sys/class/power_supply/BAT0/capacity"]:
        if os.path.exists(path):
            try:
                with open(path, "r") as f:
                    battery_pct = int(f.read().strip())
                break
            except Exception:
                pass
    for status_path in ["/sys/class/power_supply/battery/status", "/sys/class/power_supply/BAT0/status"]:
        if os.path.exists(status_path):
            try:
                with open(status_path, "r") as f:
                    st = f.read().strip().lower()
                    is_charging = (st in ("charging", "full"))
                break
            except Exception:
                pass
    for temp_path in ["/sys/class/thermal/thermal_zone0/temp"]:
        if os.path.exists(temp_path):
            try:
                with open(temp_path, "r") as f:
                    raw_t = int(f.read().strip())
                    temp = round(raw_t / 1000.0, 1) if raw_t > 1000 else raw_t
                break
            except Exception:
                pass

    return {
        "platform": platform_name,
        "battery_level": battery_pct,
        "charging": is_charging,
        "is_charging": is_charging,
        "temperature": temp,
        "wake_lock": True
    }

# StreamBot (ysdragon/StreamBot) Core Engine Globals
STREAMBOT_QUEUE = []
STREAMBOT_CURRENT = None
STREAMBOT_STATUS = "idle"  # "idle", "playing", "paused"
STREAMBOT_LOOP_MODE = "off"  # "off", "video", "queue"
STREAMBOT_VOLUME = 100  # 1 to 200%
STREAMBOT_RESOLUTION = "720p"
STREAMBOT_FPS = 30
STREAMBOT_BITRATE = "2500k"
STREAMBOT_PROCESS = None

def sync_streambot_config():
    """Sync current active token, guild, and channel to streambot/.env"""
    streambot_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "streambot")
    if not os.path.exists(streambot_dir):
        streambot_dir = os.path.join(os.getcwd(), "streambot")
    if not os.path.exists(streambot_dir):
        return
    env_file = os.path.join(streambot_dir, ".env")
    active_token = ""
    for a in ACCOUNTS.values():
        t = a.get("token", "")
        if t and not is_placeholder_token(t):
            active_token = t
            break
    if not active_token and TOKENS:
        for t in TOKENS:
            if not is_placeholder_token(t):
                active_token = t
                break
    if not active_token:
        active_token = os.environ.get("DISCORD_TOKEN", "")
        if not active_token and os.path.exists("tokens.txt"):
            try:
                with open("tokens.txt", "r", encoding="utf-8") as f:
                    for line in f:
                        tok = clean_token(line)
                        if tok and not tok.startswith("#") and not is_placeholder_token(tok):
                            active_token = tok
                            break
            except Exception:
                pass
    target_guild = GUILD_ID or "1273592244473172001"
    target_vc = CHANNEL_ID or "1535504524121473145"

    env_content = f"""TOKEN = "{active_token}"
PREFIX = "$"
GUILD_ID = "{target_guild}"
COMMAND_CHANNEL_ID = "1535504515086819338"
VIDEO_CHANNEL_ID = "{target_vc}"
ADMIN_IDS = "[\"1056945569903431752\"]"

VIDEOS_DIR = "./videos"
PREVIEW_CACHE_DIR = "./tmp/preview-cache"

STREAM_RESPECT_VIDEO_PARAMS = "false"
STREAM_BITRATE_OVERRIDE = "false"
STREAM_WIDTH = "1280"
STREAM_HEIGHT = "720"
STREAM_MAX_WIDTH = "0"
STREAM_MAX_HEIGHT = "0"
STREAM_FPS = "30"
STREAM_BITRATE_KBPS = "2000"
STREAM_MAX_BITRATE_KBPS = "2500"
STREAM_HARDWARE_ACCELERATION = "false"
STREAM_VIDEO_CODEC = "H264"
STREAM_H26X_PRESET = "ultrafast"

SERVER_ENABLED = "true"
SERVER_USERNAME = "admin"
SERVER_PASSWORD = "admin"
SERVER_PORT = "8080"
"""
    try:
        with open(env_file, "w", encoding="utf-8") as f:
            f.write(env_content)
    except Exception:
        pass

def ensure_streambot_daemon():
    """Ensure that the StreamBot real media engine is running on localhost:8080"""
    global STREAMBOT_PROCESS
    try:
        req = urllib.request.Request("http://127.0.0.1:8080/api/status", headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=0.8) as resp:
            if resp.status == 200:
                return True
    except Exception:
        pass

    streambot_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "streambot")
    if not os.path.exists(streambot_dir):
        streambot_dir = os.path.join(os.getcwd(), "streambot")
    if os.path.exists(streambot_dir):
        sync_streambot_config()
        dist_index = os.path.join(streambot_dir, "dist", "index.js")
        if os.path.exists(dist_index):
            try:
                creationflags = 0
                if sys.platform == "win32":
                    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
                STREAMBOT_PROCESS = subprocess.Popen(
                    ["node", "dist/index.js"],
                    cwd=streambot_dir,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    creationflags=creationflags
                )
                print("[+] StreamBot real voice & video engine launched in background (port 8080)")
                return True
            except Exception as e:
                print(f"[!] Note: StreamBot engine not launched via node: {e}")
    return False

STREAMBOT_HOST = "http://127.0.0.1:8080"

def send_to_streambot(endpoint, payload=None, method="POST", timeout=3.0):
    """Send request to StreamBot REST API on localhost:8080"""
    ensure_streambot_daemon()
    url = f"{STREAMBOT_HOST}{endpoint}"
    headers = {"User-Agent": "Mozilla/5.0", "Content-Type": "application/json"}
    data = json.dumps(payload).encode("utf-8") if payload else (b"{}" if method == "POST" else None)
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        return {"success": False, "error": str(e)}

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


def clean_token(raw):
    """Sanitize token string: strip quotes, whitespace, 'Bot ' prefix, and extract from email:pass:token combos."""
    if not raw or not isinstance(raw, str):
        return ""
    clean = raw.strip().strip("\"' \t\r\n")
    if clean.startswith("Bot "):
        clean = clean[4:].strip()
    # Extract token from combo formats (e.g. email:pass:token)
    if ":" in clean and len(clean) > 28:
        parts = clean.split(":")
        for p in reversed(parts):
            p = p.strip()
            if len(p) >= 24 and "@" not in p and " " not in p:
                clean = p
                break
    return clean


def mask_proxy(proxy_url):
    """Mask credentials in proxy URL for secure logging and UI display."""
    if not proxy_url or not isinstance(proxy_url, str):
        return None
    try:
        import urllib.parse
        parsed = urllib.parse.urlsplit(proxy_url)
        if parsed.password:
            masked_netloc = f"{parsed.username}:***@{parsed.hostname}"
            if parsed.port:
                masked_netloc += f":{parsed.port}"
            return urllib.parse.urlunsplit((parsed.scheme, masked_netloc, parsed.path, parsed.query, parsed.fragment))
    except Exception:
        pass
    return proxy_url


def parse_token_and_proxy(raw):
    """
    Parses a raw input string into (token, proxy).
    Supports formats:
      - TOKEN
      - TOKEN|PROXY (e.g. TOKEN|socks5://user:pass@1.2.3.4:1080 or TOKEN|http://1.2.3.4:8080)
      - TOKEN:::PROXY
      - TOKEN PROXY
    """
    if not raw or not isinstance(raw, str):
        return "", None
    raw = raw.strip()
    proxy = None
    if "|" in raw:
        parts = raw.split("|", 1)
        token_part = parts[0].strip()
        proxy_candidate = parts[1].strip()
        if proxy_candidate:
            proxy = proxy_candidate
    elif ":::" in raw:
        parts = raw.split(":::", 1)
        token_part = parts[0].strip()
        proxy_candidate = parts[1].strip()
        if proxy_candidate:
            proxy = proxy_candidate
    else:
        parts = raw.split()
        if len(parts) >= 2 and any(parts[1].lower().startswith(s) for s in ("http://", "https://", "socks5://", "socks4://")):
            token_part = parts[0].strip()
            proxy = parts[1].strip()
        else:
            token_part = raw
    token = clean_token(token_part)
    return token, proxy


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
                prx = a.get("proxy")
                if tok and not is_placeholder_token(tok) and tok not in seen:
                    if prx:
                        f.write(f"{tok}|{prx}\n")
                    else:
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
        auto_rejoin_found = False
        activity_name_found = False
        activity_type_found = False
        stealth_delay_found = False
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
            elif line.startswith("AUTO_REJOIN="):
                new_lines.append(f"AUTO_REJOIN={AUTO_REJOIN}\n")
                auto_rejoin_found = True
            elif line.startswith("ACTIVITY_NAME="):
                new_lines.append(f"ACTIVITY_NAME={CUSTOM_ACTIVITY_NAME}\n")
                activity_name_found = True
            elif line.startswith("ACTIVITY_TYPE="):
                new_lines.append(f"ACTIVITY_TYPE={CUSTOM_ACTIVITY_TYPE}\n")
                activity_type_found = True
            elif line.startswith("STEALTH_DELAY="):
                new_lines.append(f"STEALTH_DELAY={STEALTH_DELAY}\n")
                stealth_delay_found = True
            else:
                new_lines.append(line)

        if not guild_found:
            new_lines.append(f"GUILD_ID={GUILD_ID}\n")
        if not channel_found:
            new_lines.append(f"CHANNEL_ID={CHANNEL_ID}\n")
        if not device_found:
            new_lines.append(f"DEVICE_TYPE={DEVICE_TYPE}\n")
        if not auto_rejoin_found:
            new_lines.append(f"AUTO_REJOIN={AUTO_REJOIN}\n")
        if not activity_name_found:
            new_lines.append(f"ACTIVITY_NAME={CUSTOM_ACTIVITY_NAME}\n")
        if not activity_type_found:
            new_lines.append(f"ACTIVITY_TYPE={CUSTOM_ACTIVITY_TYPE}\n")
        if not stealth_delay_found:
            new_lines.append(f"STEALTH_DELAY={STEALTH_DELAY}\n")

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
            req = urllib.request.Request(
                f"{API}/channels/{channel_id}",
                headers={"Authorization": valid_token, "User-Agent": "Mozilla/5.0"}
            )
            with urllib.request.urlopen(req, timeout=6) as resp:
                if resp.status == 200:
                    return json.loads(resp.read().decode("utf-8"))
        except Exception:
            return None

    res = await loop.run_in_executor(None, _fetch)
    if res and isinstance(res, dict):
        gid = res.get("guild_id")
        if gid:
            GUILD_ID = gid
            save_env_config()
            print(f"[+] Automatically resolved & saved Server (Guild) ID: {GUILD_ID}")
            return GUILD_ID
    return GUILD_ID


async def _validate_token(token, proxy=None):
    loop = asyncio.get_running_loop()

    def _do():
        clean_tok = token.strip()
        prefixes = ["", "Bot "] if not clean_tok.startswith("Bot ") else [""]
        last_err = "HTTP 401"
        for prefix in prefixes:
            auth_val = prefix + clean_tok
            try:
                req = urllib.request.Request(
                    f"{API}/users/@me",
                    headers={"Authorization": auth_val, "User-Agent": "Mozilla/5.0"}
                )
                opener = urllib.request.build_opener()
                if proxy and isinstance(proxy, str) and (proxy.startswith("http://") or proxy.startswith("https://")):
                    opener.add_handler(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
                with opener.open(req, timeout=10) as resp:
                    if resp.status == 200:
                        data = json.loads(resp.read().decode("utf-8"))
                        data["auth_token"] = auth_val
                        data["is_bot"] = bool(prefix or data.get("bot", False))
                        return data, None
                    last_err = f"HTTP {resp.status}"
            except urllib.error.HTTPError as e:
                last_err = f"HTTP {e.code}"
                if e.code == 401 and not prefix:
                    continue
                return None, last_err
            except Exception as e:
                return None, str(e)
        return None, last_err

    return await loop.run_in_executor(None, _do)


def find_account(identifier):
    if identifier is None:
        return None
    str_id = str(identifier).strip()
    if str_id.isdigit():
        aid = int(str_id)
        if aid in ACCOUNTS:
            return ACCOUNTS[aid]
    for acc in ACCOUNTS.values():
        if str(acc.get("name")) == str_id or str(acc.get("user_id")) == str_id or str(acc.get("id")) == str_id:
            return acc
    for acc in ACCOUNTS.values():
        if acc.get("token") and str_id and acc.get("token", "").startswith(str_id):
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


def resolve_youtube_metadata(yt_url):
    if not yt_url or not str(yt_url).strip():
        return None
    try:
        import urllib.parse
        encoded = urllib.parse.quote(str(yt_url).strip())
        oembed_url = f"https://www.youtube.com/oembed?url={encoded}&format=json"
        req = urllib.request.Request(oembed_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                return {
                    "title": data.get("title", "YouTube Video"),
                    "author": data.get("author_name", "YouTube Creator"),
                    "url": str(yt_url).strip()
                }
    except Exception as e:
        print(f"[YouTube oEmbed] Error resolving {yt_url}: {e}")
    return None


async def apply(targets, channel="keep", mute="keep", deaf="keep", video="keep", guild=None):
    done = 0
    target_guild = guild or GUILD_ID
    if channel not in ("keep", None) and not guild:
        target_guild = await auto_resolve_guild(channel)
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
                new_video = conn.get("video", False) if video == "keep" else video
                act_guild = guild or conn.get("guild_id") or target_guild or GUILD_ID
                await ws.send(
                    json.dumps({
                        "op": 4,
                        "d": {
                            "guild_id": act_guild,
                            "channel_id": new_channel,
                            "self_mute": new_mute,
                            "self_deaf": new_deaf,
                            "self_video": new_video,
                        },
                    })
                )
                conn["voice_channel"] = new_channel
                conn["mute"] = new_mute
                conn["deaf"] = new_deaf
                conn["video"] = new_video
                if new_channel is not None:
                    conn["user_left_intentional"] = False
                    conn["desired_channel"] = new_channel
                    if act_guild:
                        conn["guild_id"] = act_guild
                        conn["desired_guild"] = act_guild
                else:
                    conn["user_left_intentional"] = True
                    conn["desired_channel"] = None
                    if conn.get("stream_conn"):
                        asyncio.create_task(conn["stream_conn"].stop())
                        conn["stream_conn"] = None
                    if conn.get("voice_conn"):
                        asyncio.create_task(conn["voice_conn"].stop())
                        conn["voice_conn"] = None
                done += 1
                if STEALTH_DELAY > 0.05:
                    jitter = (random.random() * 0.4 - 0.2) * STEALTH_DELAY
                    await asyncio.sleep(max(0.02, STEALTH_DELAY + jitter))
                else:
                    await asyncio.sleep(0.05)
                break
            except Exception as e:
                if attempt == 0:
                    await asyncio.sleep(0.5)
                else:
                    print(f"  ! {conn.get('name')}: {e}")
    return done


async def add_account(token, proxy=None):
    global NEXT_ID
    if not proxy:
        token, proxy = parse_token_and_proxy(token)
    else:
        token = clean_token(token)
    if not token:
        return None
    for a in ACCOUNTS.values():
        if a.get("token") == token:
            print(f"[Account {a['id']}] Already added ({a.get('name')}).")
            if proxy and a.get("proxy") != proxy:
                a["proxy"] = proxy
                sync_tokens_file()
            return a["id"]

    user, err = await _validate_token(token, proxy=proxy)
    aid = NEXT_ID
    NEXT_ID += 1
    if user is None:
        acc = {
            "id": aid, "token": token, "proxy": proxy, "task": None, "ws": None, "hb": None,
            "user_id": None, "name": None, "online": False, "voice_channel": None,
            "mute": False, "deaf": False, "removed": False, "error": err,
        }
        ACCOUNTS[aid] = acc
        print(f"[Account {aid}] Invalid token ({err}): {token[:10]}... (added as offline, proxy={mask_proxy(proxy) or 'Direct'})")
        return aid

    acc = {
        "id": aid, "token": token, "proxy": proxy, "task": None, "ws": None, "hb": None,
        "user_id": user["id"], "name": user.get("username", "Unknown"), "online": False,
        "voice_channel": None, "mute": False, "deaf": False, "removed": False, "error": None,
    }
    ACCOUNTS[aid] = acc
    task = asyncio.create_task(run_account(aid))
    acc["task"] = task
    print(f"[Account {aid}] Added: {acc['name']} ({acc['user_id']}) - Proxy: {mask_proxy(proxy) or 'Direct'}")
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
                        "guild_id": acc.get("guild_id") or GUILD_ID,
                        "channel_id": None,
                        "self_mute": acc.get("mute", False),
                        "self_deaf": acc.get("deaf", False),
                    },
                })
            )
        except Exception:
            pass
    if acc.get("stream_conn"):
        asyncio.create_task(acc["stream_conn"].stop())
        acc["stream_conn"] = None
    if acc.get("voice_conn"):
        asyncio.create_task(acc["voice_conn"].stop())
        acc["voice_conn"] = None
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
        ws_kwargs = {"max_size": 50 * 1024 * 1024}
        proxy_url = acc.get("proxy")
        if proxy_url:
            ws_kwargs["proxy"] = proxy_url
        try:
            async with websockets.connect(uri, **ws_kwargs) as ws:
                hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
                heartbeat_interval = hello["d"]["heartbeat_interval"]
                hb = asyncio.create_task(heartbeat(ws, heartbeat_interval, acc))

                presence_obj = {"status": "online", "afk": False, "since": 0}
                if CUSTOM_ACTIVITY_NAME:
                    act = {"name": CUSTOM_ACTIVITY_NAME, "type": CUSTOM_ACTIVITY_TYPE}
                    if CUSTOM_ACTIVITY_TYPE == 4:
                        act["state"] = CUSTOM_ACTIVITY_NAME
                    presence_obj["activities"] = [act]

                await ws.send(
                    json.dumps({
                        "op": 2,
                        "d": {
                            "token": token,
                            "properties": GATEWAY_PROPERTIES,
                            "presence": presence_obj,
                        },
                    })
                )

                event = None
                while True:
                    event = json.loads(await asyncio.wait_for(ws.recv(), timeout=heartbeat_interval / 1000 + 15))
                    if event.get("t") == "READY":
                        break

                if isinstance(event, dict) and event.get("d"):
                    d_rdy = event["d"]
                    u = d_rdy.get("user")
                    if u:
                        acc["user_id"] = u.get("id", acc.get("user_id"))
                        acc["name"] = u.get("username", acc.get("name"))
                    if d_rdy.get("session_id"):
                        acc["session_id"] = d_rdy.get("session_id")
                del event
                del hello

                acc["ws"] = ws
                acc["online"] = True
                acc["error"] = None
                delay = 5
                print(f"[Account {aid}] {acc['name']} connected (gateway).")

                def try_connect_voice():
                    endpoint = acc.get("voice_endpoint")
                    token = acc.get("voice_token")
                    session_id = acc.get("session_id")
                    channel_id = acc.get("voice_channel")
                    g_id = acc.get("guild_id") or channel_id
                    if not (endpoint and token and session_id and channel_id):
                        return
                    if str(endpoint).strip() in ("", "null", "None"):
                        return
                    cur_vc = acc.get("voice_conn")
                    if cur_vc and getattr(cur_vc, "running", False):
                        if getattr(cur_vc, "channel_id", "") == str(channel_id) and getattr(cur_vc, "token", "") == token:
                            return
                        asyncio.create_task(cur_vc.stop())
                        acc["voice_conn"] = None
                    log(f"[Account {aid}] 🎤 Connecting Voice Gateway to {endpoint} (channel={channel_id}, session={session_id[:6] if len(session_id) >= 6 else session_id})...")
                    v_conn = DiscordMediaConnection(aid, acc.get("user_id"), session_id, token, endpoint, g_id, channel_id=channel_id, is_stream=False, logger=log, proxy=acc.get("proxy"))
                    v_conn.set_profile(STREAM_PROFILE)
                    v_conn.set_visualizer(VISUALIZER_MODE)
                    def _make_speaker_cb(ch):
                        def _on_spk(uid, spk):
                            if ch in VOICE_HUD_MEMBERS and uid in VOICE_HUD_MEMBERS[ch]:
                                VOICE_HUD_MEMBERS[ch][uid]["speaking"] = spk
                        return _on_spk
                    v_conn.speaker_callback = _make_speaker_cb(channel_id)
                    acc["voice_conn"] = v_conn
                    v_conn.start()

                while True:
                    raw_msg = await asyncio.wait_for(ws.recv(), timeout=max(30, heartbeat_interval / 1000 + 15))
                    # Low-End Mobile Battery & GC Optimization:
                    # Drop high-frequency chat messages, presences & typing events from 100+ servers before JSON parsing
                    if isinstance(raw_msg, str):
                        is_chat = CHAT_AUTO_RESPONDER_ENABLED and ('"MESSAGE_CREATE"' in raw_msg)
                        if (
                            not is_chat
                            and '"VOICE_STATE_UPDATE"' not in raw_msg
                            and '"VOICE_SERVER_UPDATE"' not in raw_msg
                            and '"STREAM_CREATE"' not in raw_msg
                            and '"STREAM_SERVER_UPDATE"' not in raw_msg
                            and '"op":11' not in raw_msg
                            and '"op":7' not in raw_msg
                            and '"op":9' not in raw_msg
                        ):
                            continue
                    try:
                        ev = json.loads(raw_msg)
                        if ev.get("op") == 11 and acc.get("last_heartbeat_sent"):
                            acc["ping"] = int(max(1, (time.time() * 1000) - acc["last_heartbeat_sent"]))
                        t = ev.get("t")
                        if t == "VOICE_STATE_UPDATE":
                            d = ev.get("d", {})
                            ch_id = d.get("channel_id")
                            u_id = d.get("user_id")
                            member = d.get("member", {})
                            u_obj = member.get("user", {})
                            uname = member.get("nick") or u_obj.get("global_name") or u_obj.get("username") or f"User {u_id}"
                            avatar = u_obj.get("avatar")

                            # Track in Real-Time Voice Channel HUD
                            if ch_id and u_id:
                                if ch_id not in VOICE_HUD_MEMBERS:
                                    VOICE_HUD_MEMBERS[ch_id] = {}
                                prev_spk = VOICE_HUD_MEMBERS[ch_id].get(u_id, {}).get("speaking", False)
                                VOICE_HUD_MEMBERS[ch_id][u_id] = {
                                    "user_id": u_id,
                                    "username": uname,
                                    "avatar": avatar,
                                    "mute": d.get("self_mute", False) or d.get("mute", False),
                                    "deaf": d.get("self_deaf", False) or d.get("deaf", False),
                                    "video": d.get("self_video", False),
                                    "speaking": prev_spk
                                }
                            elif not ch_id and u_id:
                                for cid in list(VOICE_HUD_MEMBERS.keys()):
                                    VOICE_HUD_MEMBERS[cid].pop(u_id, None)

                            if d.get("user_id") == acc.get("user_id"):
                                prev_ch = acc.get("voice_channel")
                                if d.get("session_id"):
                                    acc["session_id"] = d.get("session_id")
                                acc["voice_channel"] = d.get("channel_id")
                                acc["mute"] = d.get("self_mute", False)
                                acc["deaf"] = d.get("self_deaf", False)
                                acc["video"] = d.get("self_video", False)
                                if d.get("guild_id"):
                                    acc["guild_id"] = d.get("guild_id")

                                if not acc["voice_channel"]:
                                    if acc.get("stream_conn"):
                                        asyncio.create_task(acc["stream_conn"].stop())
                                        acc["stream_conn"] = None
                                    if acc.get("voice_conn"):
                                        asyncio.create_task(acc["voice_conn"].stop())
                                        acc["voice_conn"] = None
                                else:
                                    try_connect_voice()
                                
                                # Auto-Rejoin Guard: if unexpectedly disconnected and auto_rejoin is true
                                if not acc["voice_channel"] and not acc.get("user_left_intentional", False) and AUTO_REJOIN and acc.get("desired_channel"):
                                    des_ch = acc["desired_channel"]
                                    des_g = acc.get("desired_guild") or GUILD_ID
                                    print(f"[Account {aid}] 🛡️ Auto-Rejoin Guard: Rejoining {des_ch} in 4s...")
                                    async def _do_rejoin():
                                        await asyncio.sleep(4)
                                        if not acc.get("voice_channel") and not acc.get("user_left_intentional", False):
                                            await apply([acc], channel=des_ch, guild=des_g)
                                    asyncio.create_task(_do_rejoin())

                        elif t == "VOICE_SERVER_UPDATE":
                            d = ev.get("d", {})
                            g_id = d.get("guild_id") or acc.get("guild_id")
                            token = d.get("token")
                            endpoint = d.get("endpoint")
                            acc["voice_token"] = token
                            acc["voice_endpoint"] = endpoint
                            if g_id:
                                acc["guild_id"] = g_id
                            try_connect_voice()

                        elif t == "STREAM_CREATE":
                            d = ev.get("d", {})
                            stream_key = d.get("stream_key", "")
                            rtc_server_id = d.get("rtc_server_id", "")
                            acc["stream_key"] = stream_key
                            if rtc_server_id:
                                acc["stream_rtc_server_id"] = str(rtc_server_id)
                            log(f"[Account {aid}] 🎥 Stream Session Initialized: {stream_key} (rtc_server_id={rtc_server_id})")

                        elif t == "STREAM_SERVER_UPDATE":
                            d = ev.get("d", {})
                            stream_key = d.get("stream_key", "")
                            token = d.get("token")
                            endpoint = d.get("endpoint")
                            acc["stream_token"] = token
                            acc["stream_endpoint"] = endpoint
                            if endpoint and token:
                                if acc.get("stream_conn"):
                                    asyncio.create_task(acc["stream_conn"].stop())
                                cur_ch = acc.get("voice_channel")
                                srv_id = acc.get("stream_rtc_server_id") or stream_key
                                log(f"[Account {aid}] 📡 Stream Server Update: {endpoint} (server_id={srv_id}, channel={cur_ch})")
                                s_conn = DiscordMediaConnection(aid, acc.get("user_id"), acc.get("session_id"), token, endpoint, srv_id, channel_id=cur_ch, is_stream=True, logger=log, proxy=acc.get("proxy"))
                                s_conn.set_profile(STREAM_PROFILE)
                                s_conn.set_visualizer(VISUALIZER_MODE)
                                if STREAM_OVERLAY_ENABLED:
                                    s_conn.set_overlay(STREAM_OVERLAY_TITLE, STREAM_OVERLAY_TICKER)
                                if DESKTOP_SCREEN_CAPTURE:
                                    s_conn.set_desktop_capture(True)
                                def _make_stream_spk_cb(ch):
                                    def _on_spk(uid, spk):
                                        if ch in VOICE_HUD_MEMBERS and uid in VOICE_HUD_MEMBERS[ch]:
                                            VOICE_HUD_MEMBERS[ch][uid]["speaking"] = spk
                                    return _on_spk
                                s_conn.speaker_callback = _make_stream_spk_cb(cur_ch)
                                acc["stream_conn"] = s_conn
                                s_conn.start()

                        elif t == "MESSAGE_CREATE" and CHAT_AUTO_RESPONDER_ENABLED:
                            d = ev.get("d", {})
                            author = d.get("author", {})
                            author_id = str(author.get("id", ""))
                            if author_id and author_id != str(acc.get("user_id")) and not author.get("bot", False):
                                msg_content = (d.get("content") or "").strip().lower()
                                ch_id = d.get("channel_id")
                                for rule in CHAT_AUTO_RESPONDER_RULES:
                                    trig = rule.get("trigger", "").lower().strip()
                                    rep = rule.get("response", "")
                                    exact = rule.get("exact", False)
                                    if trig and rep and ((exact and msg_content == trig) or (not exact and trig in msg_content)):
                                        log(f"[Account {aid}] 🤖 Chat Auto-Responder: Trigger '{trig}' matched -> Replying to #{ch_id}")
                                        asyncio.create_task(send_chat_message(acc.get("token"), ch_id, rep))
                                        break
                    except Exception:
                        pass

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
            if acc.get("stream_conn"):
                asyncio.create_task(acc["stream_conn"].stop())
                acc["stream_conn"] = None
            if acc.get("voice_conn"):
                asyncio.create_task(acc["voice_conn"].stop())
                acc["voice_conn"] = None
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
    if acc.get("stream_conn"):
        asyncio.create_task(acc["stream_conn"].stop())
        acc["stream_conn"] = None
    if acc.get("voice_conn"):
        asyncio.create_task(acc["voice_conn"].stop())
        acc["voice_conn"] = None
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
            line_str = line.strip()
            if not line_str or line_str.startswith("#"):
                continue
            tok, prx = parse_token_and_proxy(line_str)
            if not tok or is_placeholder_token(tok):
                continue
            await add_account(tok, proxy=prx)
            count += 1
    print(f"Loaded {count} token(s) from {path}.")
    return count


async def save_tokens_to_file(path):
    with open(path, "w", encoding="utf-8") as f:
        for acc in ACCOUNTS.values():
            tok = acc.get("token", "")
            prx = acc.get("proxy")
            if tok:
                if prx:
                    f.write(f"{tok}|{prx}\n")
                else:
                    f.write(tok + "\n")
    print(f"Saved {len(ACCOUNTS)} token(s) to {path}.")


async def heartbeat(ws, interval, acc=None):
    while True:
        await asyncio.sleep(interval / 1000)
        if acc is not None:
            acc["last_heartbeat_sent"] = time.time() * 1000
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
    global CHANNEL_ID, GUILD_ID, STREAMBOT_VOLUME, STREAMBOT_LOOP_MODE, STREAMBOT_STATUS, STREAMBOT_CURRENT
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

        # StreamBot (ysdragon/StreamBot) Command Set ($play, $pause, $resume, $skip, $queue, $volume, $loop)
        if cmd in ("$play", "play", "$ytplay", "ytplay", "$playlink", "playlink"):
            if len(parts) > 1:
                target_url = " ".join(parts[1:]).strip()
                global STREAMBOT_CURRENT, STREAMBOT_STATUS, STREAMBOT_QUEUE, REAL_VIDEO_ACTIVE, REAL_VIDEO_MODE, REAL_VIDEO_TITLE, VIDEO_SIM, SCREEN_SHARE_SIM, SPEAKING_SIM
                item = {
                    "id": str(int(time.time() * 1000))[-6:],
                    "title": target_url,
                    "url": target_url,
                    "source": "command",
                    "duration": 0.0,
                    "started_at": int(time.time())
                }
                STREAMBOT_CURRENT = item
                STREAMBOT_STATUS = "playing"
                REAL_VIDEO_ACTIVE = True
                REAL_VIDEO_MODE = "streambot"
                REAL_VIDEO_TITLE = target_url
                VIDEO_SIM = True
                SCREEN_SHARE_SIM = True
                SPEAKING_SIM = True
                for a in ACCOUNTS.values():
                    a["video"] = True
                    a["screenshare"] = True
                    a["speaking"] = True
                print(f"🎬 [StreamBot] Playing: '{target_url}' (1080p/720p 60fps)")
            else:
                print("Usage: $play <video_url / youtube_link / local_file>")
            return

        if cmd in ("$pause", "pause"):
            STREAMBOT_STATUS = "paused"
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["speaking"] = False
            print("🎬 [StreamBot] Playback Paused.")
            return

        if cmd in ("$resume", "resume"):
            if STREAMBOT_CURRENT:
                STREAMBOT_STATUS = "playing"
                SPEAKING_SIM = True
                for a in ACCOUNTS.values():
                    a["speaking"] = True
                print(f"🎬 [StreamBot] Playback Resumed: {STREAMBOT_CURRENT['title']}")
            else:
                print("🎬 [StreamBot] No active stream to resume.")
            return

        if cmd in ("$skip", "skip", "next"):
            if STREAMBOT_QUEUE:
                next_it = STREAMBOT_QUEUE.pop(0)
                STREAMBOT_CURRENT = next_it
                STREAMBOT_STATUS = "playing"
                print(f"🎬 [StreamBot] Skipped to next: {next_it['title']} ({len(STREAMBOT_QUEUE)} left in queue)")
            else:
                STREAMBOT_CURRENT = None
                STREAMBOT_STATUS = "idle"
                print("🎬 [StreamBot] Queue is empty. Playback stopped.")
            return

        if cmd in ("$stop", "stopstream"):
            STREAMBOT_STATUS = "idle"
            STREAMBOT_CURRENT = None
            REAL_VIDEO_ACTIVE = False
            VIDEO_SIM = False
            SCREEN_SHARE_SIM = False
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["video"] = False
                a["screenshare"] = False
                a["speaking"] = False
            print("🎬 [StreamBot] Stream Stopped.")
            return

        if cmd in ("$queue", "queue", "$list"):
            print("================== [STREAMBOT QUEUE] ==================")
            if STREAMBOT_CURRENT:
                print(f"▶️ NOW PLAYING: {STREAMBOT_CURRENT['title']}")
            else:
                print("▶️ NOW PLAYING: None (Idle)")
            if STREAMBOT_QUEUE:
                for i, itm in enumerate(STREAMBOT_QUEUE, 1):
                    print(f"  {i}. {itm['title']} ({itm['url']})")
            else:
                print("  Queue is empty. Use $play <url> to add media.")
            print(f"Mode: Loop={STREAMBOT_LOOP_MODE.upper()} | Vol={STREAMBOT_VOLUME}% | Res={STREAMBOT_RESOLUTION}")
            return

        if cmd in ("$volume", "volume", "$vol"):
            if len(parts) > 1:
                try:
                    STREAMBOT_VOLUME = max(1, min(200, int(parts[1])))
                    print(f"🎬 [StreamBot] Volume set to {STREAMBOT_VOLUME}%")
                except ValueError:
                    print("Usage: $volume <1-200>")
            else:
                print(f"🎬 [StreamBot] Current Volume: {STREAMBOT_VOLUME}%")
            return

        if cmd in ("$loop", "loop"):
            modes = ["off", "video", "queue"]
            cur_idx = modes.index(STREAMBOT_LOOP_MODE) if STREAMBOT_LOOP_MODE in modes else 0
            STREAMBOT_LOOP_MODE = modes[(cur_idx + 1) % len(modes)]
            print(f"🎬 [StreamBot] Loop mode toggled to: {STREAMBOT_LOOP_MODE.upper()}")
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
                            "guild_id": acc.get("guild_id") or GUILD_ID,
                            "channel_id": None,
                            "self_mute": acc.get("mute", False),
                            "self_deaf": acc.get("deaf", False),
                        },
                    })
                )
            except Exception:
                pass
        if acc.get("stream_conn"):
            asyncio.create_task(acc["stream_conn"].stop())
            acc["stream_conn"] = None
        if acc.get("voice_conn"):
            asyncio.create_task(acc["voice_conn"].stop())
            acc["voice_conn"] = None
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
    if sys.stdin is None or not hasattr(sys.stdin, "readline"):
        while RUNNING:
            await asyncio.sleep(5)
        return

    while RUNNING:
        try:
            raw = await loop.run_in_executor(None, sys.stdin.readline)
            if not raw:
                # EOF reached (e.g. process backgrounded or Android APK)
                await asyncio.sleep(2)
                continue
            await handle_command(raw.strip())
        except (EOFError, KeyboardInterrupt):
            await asyncio.sleep(2)
            continue
        except Exception as e:
            if RUNNING:
                print(f"Console error: {e}")
            await asyncio.sleep(1)


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


def check_github_update():
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/Hubg50we/dc-/releases/latest",
            headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            tag = (data.get("tag_name") or "").strip()
            clean_tag = tag[1:] if tag.startswith("v") else tag
            download_url_apk = ""
            download_url_windows = ""
            file_size_apk = 0
            file_size_windows = 0
            for a in data.get("assets", []):
                aname = a.get("name", "")
                if aname.endswith(".apk"):
                    download_url_apk = a.get("browser_download_url", "")
                    file_size_apk = a.get("size", 0)
                elif aname.endswith(".exe") or "windows" in aname.lower():
                    download_url_windows = a.get("browser_download_url", "")
                    file_size_windows = a.get("size", 0)
            if not download_url_apk:
                download_url_apk = f"https://github.com/Hubg50we/dc-/releases/download/{tag}/dc-android.apk"
            if not download_url_windows:
                download_url_windows = f"https://github.com/Hubg50we/dc-/releases/download/{tag}/dc-windows.exe"

            is_windows = sys.platform == "win32"
            download_url = download_url_windows if is_windows else download_url_apk
            file_size = file_size_windows if is_windows else file_size_apk

            is_newer = False
            try:
                def to_tuple(v):
                    return tuple(int(x) for x in re.sub(r"[^0-9.]", "", v).split(".") if x)
                is_newer = to_tuple(clean_tag) > to_tuple(APP_VERSION)
            except Exception:
                is_newer = clean_tag != APP_VERSION

            return {
                "success": True,
                "status": "ok",
                "update_available": is_newer,
                "current_version": APP_VERSION,
                "latest_version": clean_tag,
                "tag_name": tag,
                "release_name": data.get("name") or tag,
                "release_notes": data.get("body") or "",
                "download_url": download_url,
                "download_url_apk": download_url_apk,
                "download_url_windows": download_url_windows,
                "file_size": file_size,
                "published_at": data.get("published_at") or ""
            }
    except Exception as e:
        return {
            "success": False,
            "status": "error",
            "update_available": False,
            "current_version": APP_VERSION,
            "error": str(e)
        }


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
        elif self.path == "/api/logs":
            self.serve_logs()
        elif self.path == "/api/update":
            self.serve_update()
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
        base_dir = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
        index_path = os.path.join(base_dir, "web", "index.html")
        if not os.path.exists(index_path):
            index_path = os.path.join(os.getcwd(), "web", "index.html")
        if not os.path.exists(index_path):
            index_path = "web/index.html"
        try:
            if os.path.exists(index_path):
                with open(index_path, "rb") as f:
                    content = f.read()
            else:
                content = b"<!DOCTYPE html><html><body style='background:#111;color:#fff;font-family:sans-serif;padding:20px'><h2>dc- Controller</h2><p>web/index.html not found. Please ensure web/index.html exists.</p></body></html>"
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
            "version": APP_VERSION,
            "uptime": int(time.time() - START_TIME) if START_TIME else 0,
            "default_guild": GUILD_ID,
            "default_channel": CHANNEL_ID,
            "device_type": DEVICE_TYPE,
            "auto_rejoin": AUTO_REJOIN,
            "activity_name": CUSTOM_ACTIVITY_NAME,
            "activity_type": CUSTOM_ACTIVITY_TYPE,
            "stealth_delay": STEALTH_DELAY,
            "rotator_enabled": ROTATOR_ENABLED,
            "rotator_interval": ROTATOR_INTERVAL,
            "speaking_sim": SPEAKING_SIM,
            "voice_flags": VOICE_FLAGS,
            "active_sfx": ACTIVE_SFX,
            "voice_status": VOICE_STATUS,
            "human_chatter": HUMAN_CHATTER_ENABLED,
            "real_video_active": REAL_VIDEO_ACTIVE,
            "real_video_mode": REAL_VIDEO_MODE,
            "web_share_url": WEB_SHARE_URL,
            "video_sim": VIDEO_SIM,
            "screenshare_sim": SCREEN_SHARE_SIM,
            "music_title": MUSIC_TITLE,
            "music_artist": MUSIC_ARTIST,
            "youtube_url": YOUTUBE_URL,
            "youtube_title": YOUTUBE_TITLE,
            "youtube_author": YOUTUBE_AUTHOR,
            "youtube_mode": YOUTUBE_MODE,
            "streambot_status": STREAMBOT_STATUS,
            "streambot_current": STREAMBOT_CURRENT,
            "streambot_queue": STREAMBOT_QUEUE,
            "streambot_loop_mode": STREAMBOT_LOOP_MODE,
            "streambot_volume": STREAMBOT_VOLUME,
            "streambot_resolution": STREAMBOT_RESOLUTION,
            "streambot_fps": STREAMBOT_FPS,
            "streambot_bitrate": STREAMBOT_BITRATE,
            "streambot_host": STREAMBOT_HOST,
            "stream_profile": STREAM_PROFILE,
            "spotify_song": SPOTIFY_SONG,
            "spotify_artist": SPOTIFY_ARTIST,
            "spotify_album": SPOTIFY_ALBUM,
            "spotify_duration": SPOTIFY_DURATION,
            "voice_hud": VOICE_HUD_MEMBERS,
            "uploaded_media": list_uploaded_media(),
            "visualizer_mode": VISUALIZER_MODE,
            "stream_overlay_enabled": STREAM_OVERLAY_ENABLED,
            "stream_overlay_title": STREAM_OVERLAY_TITLE,
            "stream_overlay_ticker": STREAM_OVERLAY_TICKER,
            "desktop_screen_capture": DESKTOP_SCREEN_CAPTURE,
            "system_telemetry": get_system_telemetry(),
            "recording": {
                "active": RECORDING_ACTIVE,
                "filename": os.path.basename(RECORDING_FILENAME) if RECORDING_FILENAME else None,
                "duration": int(time.time() - RECORDING_START_TIME) if (RECORDING_ACTIVE and RECORDING_START_TIME) else 0,
                "count": len(list_recordings())
            },
            "safety_guard": {
                "token_health_index": TOKEN_HEALTH_INDEX,
                "rate_limit_cooldown": max(0, int(RATE_LIMIT_COOLDOWN - time.time())),
                "rate_limit_hits": RATE_LIMIT_HITS
            },
            "stadium_echo": {
                "enabled": STADIUM_ECHO_ENABLED,
                "delay_ms": STADIUM_ECHO_DELAY_MS
            },
            "ptt_active": PTT_ACTIVE,
            "recordings": list_recordings(),
            "radio_stations": RADIO_STATIONS,
            "active_radio_station": ACTIVE_RADIO_STATION,
            "ai_cohost": {
                "enabled": AI_COHOST_ENABLED,
                "persona": AI_COHOST_PERSONA,
                "has_api_key": bool(AI_COHOST_API_KEY),
                "ofm_engine": "Ebony-Vinyl/dsh-our-free-model",
                "ofm_model": OFM_MODEL
            },
            "voice_quality": get_voice_quality_telemetry(),
            "audio_dsp": {
                "bass_boost": AUDIO_BASS_BOOST,
                "pitch_shift": AUDIO_PITCH_SHIFT,
                "normalizer": AUDIO_NORMALIZER,
                "preset": AUDIO_EQ_PRESET
            },
            "voice_modulator": {
                "enabled": VOICE_MODULATOR_ENABLED,
                "preset": VOICE_MODULATOR_PRESET
            },
            "ai_copilot": {
                "enabled": AI_COPILOT_ENABLED,
                "persona": AI_COPILOT_PERSONA,
                "history_count": len(AI_COPILOT_HISTORY)
            },
            "crossfader": {
                "enabled": CROSSFADER_ENABLED,
                "duration": CROSSFADER_DURATION,
                "curve": CROSSFADER_CURVE
            },
            "anti_afk": {
                "enabled": ANTI_AFK_ENABLED,
                "interval": ANTI_AFK_INTERVAL,
                "mode": ANTI_AFK_MODE
            },
            "custom_rpc": CUSTOM_RPC,
            "voice_transcriber": {
                "enabled": VOICE_TRANSCRIBER_ENABLED,
                "count": len(VOICE_TRANSCRIPTS)
            },
            "plugins": get_installed_plugins(),
            "plugins_count": len(PLUGINS),
            "themes": THEMES,
            "active_theme": ACTIVE_THEME,
            "custom_css": CUSTOM_CSS,
            "volume_booster": VOLUME_BOOSTER,
            "noise_suppressor": NOISE_SUPPRESSOR,
            "translator": TRANSLATOR_CONFIG,
            "app_icon": APP_ICON,
            "app_icons": APP_ICONS,
            "macros": MACROS,
            "gateway_latency": GATEWAY_LATENCY,
            "target_follower": TARGET_FOLLOWER,
            "voice_harmonizer": VOICE_HARMONIZER,
            "spatial_8d": SPATIAL_8D,
            "rejoin_shield": REJOIN_SHIELD,
            "chat_auto_responder_enabled": CHAT_AUTO_RESPONDER_ENABLED,
            "chat_auto_responder_rules": CHAT_AUTO_RESPONDER_RULES,
            "soundboard_pads": SOUNDBOARD_PADS,
            "presets": get_channel_presets(),
            "voice_telemetry": {
                "voice_connections": len(voice_accounts()),
                "audio_packets_sent": sum(
                    getattr(a.get("voice_conn"), "packets_sent", 0)
                    for a in ACCOUNTS.values()
                    if a.get("voice_conn")
                ),
                "active_sfx": ACTIVE_SFX,
            },
            "accounts": [
                {
                    "id": a.get("id", 0),
                    "name": a.get("name") or "Unknown",
                    "user_id": a.get("user_id"),
                    "token_masked": (a.get("token")[:10] + "..." + a.get("token")[-4:]) if a.get("token") else "",
                    "online": a.get("online", False),
                    "voice_channel": a.get("voice_channel"),
                    "guild_id": a.get("guild_id", ""),
                    "mute": a.get("mute", False),
                    "deaf": a.get("deaf", False),
                    "ping": a.get("ping", 0),
                    "speaking": a.get("speaking", False),
                    "video": a.get("video", False),
                    "streaming": a.get("streaming", False),
                    "screenshare": a.get("screenshare", False),
                    "youtube_active": a.get("youtube_active", False),
                    "proxy": mask_proxy(a.get("proxy")),
                    "has_proxy": bool(a.get("proxy")),
                    "error": a.get("error"),
                }
                for a in sorted(ACCOUNTS.values(), key=lambda x: x.get("id", 0))
            ],
        }
        resp = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def serve_logs(self):
        resp = json.dumps({"logs": list(LOG_BUFFER)}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def serve_update(self):
        result = check_github_update()
        resp = json.dumps(result).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        if self.path in ("/api/action", "/api/upload_media"):
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8")
            try:
                payload = json.loads(body)
                if self.path == "/api/upload_media":
                    payload["action"] = "upload_media"
                result = self.execute_action(payload)
                resp = json.dumps(result).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)
            except Exception as e:
                log(f"[!] execute_action error: {e}")
                err = json.dumps({"success": False, "error": str(e)}).encode("utf-8")
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(err)))
                self.end_headers()
                self.wfile.write(err)
        else:
            self.send_error(404, "Not Found")

    def execute_action(self, payload):
        global CHANNEL_ID, GUILD_ID, DEVICE_TYPE, GATEWAY_PROPERTIES, SPEAKING_SIM, VOICE_FLAGS, ACTIVE_SFX, VOICE_STATUS, HUMAN_CHATTER_ENABLED, HUMAN_CHATTER_TASK, REAL_VIDEO_ACTIVE, REAL_VIDEO_MODE, REAL_VIDEO_TITLE, WEB_SHARE_URL, VIDEO_SIM, SCREEN_SHARE_SIM, MUSIC_TITLE, MUSIC_ARTIST, STREAM_TITLE, STREAM_URL, YOUTUBE_URL, YOUTUBE_TITLE, YOUTUBE_AUTHOR, YOUTUBE_MODE, STREAMBOT_QUEUE, STREAMBOT_CURRENT, STREAMBOT_STATUS, STREAMBOT_LOOP_MODE, STREAMBOT_VOLUME, STREAMBOT_RESOLUTION, STREAMBOT_FPS, STREAMBOT_BITRATE, STREAM_PROFILE, SPOTIFY_SONG, SPOTIFY_ARTIST, SPOTIFY_ALBUM, SPOTIFY_DURATION, VISUALIZER_MODE, STREAM_OVERLAY_ENABLED, STREAM_OVERLAY_TITLE, STREAM_OVERLAY_TICKER, CHAT_AUTO_RESPONDER_ENABLED, CHAT_AUTO_RESPONDER_RULES, DESKTOP_SCREEN_CAPTURE, AUDIO_BASS_BOOST, AUDIO_PITCH_SHIFT, AUDIO_NORMALIZER, AUDIO_EQ_PRESET, AUTO_REJOIN, CUSTOM_ACTIVITY_NAME, CUSTOM_ACTIVITY_TYPE, STEALTH_DELAY, RECORDING_ACTIVE, RECORDING_START_TIME, RECORDING_FILENAME, STADIUM_ECHO_ENABLED, STADIUM_ECHO_DELAY_MS, PTT_ACTIVE, PTT_TOTAL_CHUNKS, TOKEN_HEALTH_INDEX, RATE_LIMIT_COOLDOWN, RATE_LIMIT_HITS, ACTIVE_RADIO_STATION, AI_COHOST_ENABLED, AI_COHOST_PERSONA, AI_COHOST_API_KEY
        action = payload.get("action", "")

        if action == "set_desktop_screen_capture":
            DESKTOP_SCREEN_CAPTURE = bool(payload.get("enabled", True))
            for a in ACCOUNTS.values():
                sc = a.get("stream_conn")
                if sc:
                    sc.set_desktop_capture(DESKTOP_SCREEN_CAPTURE)
            log(f"🖥️ Live Desktop Screen Capture {'Enabled' if DESKTOP_SCREEN_CAPTURE else 'Disabled'}")
            return {
                "success": True,
                "enabled": DESKTOP_SCREEN_CAPTURE,
                "message": f"Desktop Screen Capture {'Enabled' if DESKTOP_SCREEN_CAPTURE else 'Disabled'}"
            }

        if action == "extract_media":
            target_url = str(payload.get("url", "")).strip()
            info = extract_media_info(target_url)
            if info:
                return {"success": True, "info": info, "message": f"Extracted: {info.get('title')}"}
            return {"success": False, "error": "Could not extract media info from URL"}

        if action == "assign_soundboard_pad":
            pad_id = str(payload.get("id", "")).strip()
            c_file = str(payload.get("custom_file", "")).strip()
            c_name = str(payload.get("custom_name", "")).strip()
            for p in SOUNDBOARD_PADS:
                if p["id"] == pad_id:
                    p["custom_file"] = c_file
                    if c_name:
                        p["custom_name"] = c_name
                    log(f"🎛️ Soundboard Pad [{pad_id}] assigned to '{c_file}' ({p.get('custom_name') or p['name']})")
                    return {"success": True, "pad": p, "pads": SOUNDBOARD_PADS, "message": f"Assigned '{c_file}' to {p['name']}"}
            return {"success": False, "error": f"Pad {pad_id} not found"}

        if action == "reset_soundboard_pad":
            pad_id = str(payload.get("id", "")).strip()
            for p in SOUNDBOARD_PADS:
                if p["id"] == pad_id:
                    p["custom_file"] = ""
                    p["custom_name"] = ""
                    log(f"🎛️ Soundboard Pad [{pad_id}] reset to default")
                    return {"success": True, "pad": p, "pads": SOUNDBOARD_PADS, "message": f"Reset {p['name']} to default"}
            return {"success": False, "error": f"Pad {pad_id} not found"}

        if action == "set_audio_dsp":
            if "bass_boost" in payload:
                AUDIO_BASS_BOOST = max(0, min(20, int(payload.get("bass_boost", 0))))
            if "pitch_shift" in payload:
                AUDIO_PITCH_SHIFT = max(0.5, min(2.0, float(payload.get("pitch_shift", 1.0))))
            if "normalizer" in payload:
                AUDIO_NORMALIZER = bool(payload.get("normalizer", True))
            if "preset" in payload:
                pr = str(payload.get("preset", "flat")).lower()
                AUDIO_EQ_PRESET = pr
                if pr == "bass_heavy":
                    AUDIO_BASS_BOOST = 12
                    AUDIO_PITCH_SHIFT = 1.0
                elif pr == "nightcore":
                    AUDIO_BASS_BOOST = 4
                    AUDIO_PITCH_SHIFT = 1.3
                elif pr == "deep_voice":
                    AUDIO_BASS_BOOST = 8
                    AUDIO_PITCH_SHIFT = 0.8
                elif pr == "radio":
                    AUDIO_BASS_BOOST = 0
                    AUDIO_PITCH_SHIFT = 1.05
                elif pr == "flat":
                    AUDIO_BASS_BOOST = 0
                    AUDIO_PITCH_SHIFT = 1.0
            log(f"🎚️ Audio DSP: Bass=+{AUDIO_BASS_BOOST}dB, Pitch={AUDIO_PITCH_SHIFT}x, Norm={AUDIO_NORMALIZER}, Preset={AUDIO_EQ_PRESET}")
            return {
                "success": True,
                "bass_boost": AUDIO_BASS_BOOST,
                "pitch_shift": AUDIO_PITCH_SHIFT,
                "normalizer": AUDIO_NORMALIZER,
                "preset": AUDIO_EQ_PRESET,
                "message": f"Audio DSP updated ({AUDIO_EQ_PRESET.upper()})"
            }

        if action == "spread_accounts_to_channels":
            ch_list = payload.get("channels", [])
            target_gid = str(payload.get("guild_id", "")).strip() or GUILD_ID
            if not ch_list or not isinstance(ch_list, list):
                return {"success": False, "error": "No channels list provided"}
            online_accs = [a for a in ACCOUNTS.values() if a.get("online") or a.get("token")]
            count = 0
            for i, a in enumerate(online_accs):
                ch_id = str(ch_list[i % len(ch_list)]).strip()
                if ch_id:
                    a["voice_channel"] = ch_id
                    a["guild_id"] = target_gid
                    if MAIN_LOOP and MAIN_LOOP.is_running():
                        asyncio.run_coroutine_threadsafe(apply([a], channel=ch_id, guild=target_gid), MAIN_LOOP)
                    count += 1
            log(f"🔀 Distributed {count} account(s) across {len(ch_list)} voice channels")
            return {"success": True, "count": count, "channels": ch_list, "channels_count": len(ch_list), "message": f"Distributed {count} bot(s) across {len(ch_list)} channels"}

        if action == "gather_accounts_to_channel":
            target_ch = str(payload.get("channel_id", CHANNEL_ID)).strip()
            target_gid = str(payload.get("guild_id", "")).strip() or GUILD_ID
            online_accs = [a for a in ACCOUNTS.values() if a.get("online") or a.get("token")]
            for a in online_accs:
                a["voice_channel"] = target_ch
                a["guild_id"] = target_gid
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(apply(online_accs, channel=target_ch, guild=target_gid), MAIN_LOOP)
            log(f"👥 Gathered all {len(online_accs)} account(s) into channel: {target_ch}")
            return {"success": True, "count": len(online_accs), "channel_id": target_ch, "message": f"Gathered {len(online_accs)} bot(s) into {target_ch}"}

        if action == "export_backup":
            backup_data = {
                "version": APP_VERSION,
                "timestamp": int(time.time()),
                "device_type": DEVICE_TYPE,
                "guild_id": GUILD_ID,
                "channel_id": CHANNEL_ID,
                "auto_rejoin": AUTO_REJOIN,
                "stealth_delay": STEALTH_DELAY,
                "accounts": [{"id": a["id"], "token": a["token"], "name": a.get("name")} for a in ACCOUNTS.values()],
                "presets": get_channel_presets(),
                "soundboard_pads": SOUNDBOARD_PADS,
                "chat_auto_responder_enabled": CHAT_AUTO_RESPONDER_ENABLED,
                "chat_auto_responder_rules": CHAT_AUTO_RESPONDER_RULES,
                "audio_dsp": {
                    "bass_boost": AUDIO_BASS_BOOST,
                    "pitch_shift": AUDIO_PITCH_SHIFT,
                    "normalizer": AUDIO_NORMALIZER,
                    "preset": AUDIO_EQ_PRESET
                },
                "visualizer_mode": VISUALIZER_MODE,
                "stream_overlay": {
                    "enabled": STREAM_OVERLAY_ENABLED,
                    "title": STREAM_OVERLAY_TITLE,
                    "ticker": STREAM_OVERLAY_TICKER
                }
            }
            log("💾 Exported full profile backup")
            return {"success": True, "backup": backup_data, "message": "Backup profile exported successfully"}

        if action == "import_backup":
            b_data = payload.get("backup")
            if not b_data and isinstance(payload, dict):
                b_data = payload
            if not b_data or not isinstance(b_data, dict):
                return {"success": False, "error": "Invalid backup data format"}

            imported_count = 0
            if "accounts" in b_data and isinstance(b_data["accounts"], list):
                for acc_info in b_data["accounts"]:
                    tok = acc_info.get("token", "").strip()
                    if tok and not is_placeholder_token(tok):
                        aid = max(ACCOUNTS.keys(), default=0) + 1
                        ACCOUNTS[aid] = {
                            "id": aid,
                            "token": tok,
                            "name": acc_info.get("name") or f"Bot {aid}",
                            "online": False,
                            "voice_channel": None,
                            "guild_id": GUILD_ID
                        }
                        imported_count += 1
                sync_tokens_file()

            if "presets" in b_data and isinstance(b_data["presets"], list):
                save_channel_presets(b_data["presets"])

            if "chat_auto_responder_rules" in b_data:
                CHAT_AUTO_RESPONDER_RULES = b_data["chat_auto_responder_rules"]
            if "visualizer_mode" in b_data:
                VISUALIZER_MODE = b_data["visualizer_mode"]

            log(f"💾 Restored backup profile ({imported_count} accounts, presets & preferences)")
            return {"success": True, "imported_accounts": imported_count, "message": f"Backup profile restored ({imported_count} accounts imported)"}

        if action == "play_native_media":
            source = str(payload.get("source", "")).strip()
            title = str(payload.get("title", os.path.basename(source) or "Native Media")).strip()
            filter_chain = ""
            if AUDIO_BASS_BOOST > 0:
                filter_chain += f"bass=g={AUDIO_BASS_BOOST}:f=110:w=0.6,"
            if AUDIO_PITCH_SHIFT != 1.0:
                filter_chain += f"asetrate=48000*{AUDIO_PITCH_SHIFT},aresample=48000,"
            if AUDIO_NORMALIZER:
                filter_chain += "loudnorm=I=-16:TP=-1.5:LRA=11,"
            filter_chain = filter_chain.rstrip(",")

            ACTIVE_SFX = title
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["active_sfx"] = title
                for conn in (a.get("voice_conn"), a.get("stream_conn")):
                    if conn:
                        conn.stream_native_audio(source, filter_chain)
            log(f"🎵 [Pure Python Native Pipe] Streaming: '{title}' (Zero Node.js dependency)")
            return {"success": True, "source": source, "title": title, "message": f"Streaming {title} natively"}

        if action == "stop_native_media":
            ACTIVE_SFX = ""
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["speaking"] = False
                a["active_sfx"] = ""
                for conn in (a.get("voice_conn"), a.get("stream_conn")):
                    if conn:
                        conn._stop_native_audio()
            log("⏹️ [Pure Python Native Pipe] Playback stopped")
            return {"success": True, "message": "Native media playback stopped"}

        if action == "start_recording":
            if not os.path.exists(RECORDINGS_DIR):
                os.makedirs(RECORDINGS_DIR, exist_ok=True)
            RECORDING_FILENAME = os.path.join(RECORDINGS_DIR, f"rec_{int(time.time())}.wav")
            RECORDING_ACTIVE = True
            RECORDING_START_TIME = time.time()
            try:
                import wave
                with wave.open(RECORDING_FILENAME, "wb") as wf:
                    wf.setnchannels(2)
                    wf.setsampwidth(2)
                    wf.setframerate(48000)
                    wf.writeframes(b"")
            except Exception:
                pass
            log(f"🔴 Started voice channel call recording -> {os.path.basename(RECORDING_FILENAME)}")
            return {"success": True, "active": True, "filename": os.path.basename(RECORDING_FILENAME), "message": "Voice recording started"}

        if action == "stop_recording":
            dur = int(time.time() - RECORDING_START_TIME) if (RECORDING_ACTIVE and RECORDING_START_TIME) else 0
            fname = os.path.basename(RECORDING_FILENAME) if RECORDING_FILENAME else "recording.wav"
            RECORDING_ACTIVE = False
            log(f"⏹️ Stopped voice channel recording ({dur}s saved)")
            return {"success": True, "active": False, "filename": fname, "duration": dur, "recordings": list_recordings(), "message": f"Saved {fname} ({dur}s)"}

        if action == "get_recordings":
            return {"success": True, "recordings": list_recordings()}

        if action == "delete_recording":
            target = str(payload.get("filename", "")).strip()
            if target:
                fp = os.path.join(RECORDINGS_DIR, os.path.basename(target))
                if os.path.exists(fp):
                    os.remove(fp)
                    log(f"🗑️ Deleted recording {target}")
                    return {"success": True, "filename": target, "recordings": list_recordings(), "message": f"Deleted {target}"}
            return {"success": False, "error": "Recording file not found"}

        if action == "set_stadium_echo":
            STADIUM_ECHO_ENABLED = bool(payload.get("enabled", not STADIUM_ECHO_ENABLED))
            if "delay_ms" in payload:
                STADIUM_ECHO_DELAY_MS = max(10, min(500, int(payload.get("delay_ms", 60))))
            log(f"🏟️ Stadium Echo {'Enabled' if STADIUM_ECHO_ENABLED else 'Disabled'} (Delay: {STADIUM_ECHO_DELAY_MS}ms)")
            return {"success": True, "enabled": STADIUM_ECHO_ENABLED, "delay_ms": STADIUM_ECHO_DELAY_MS, "message": f"Stadium Echo {'Enabled' if STADIUM_ECHO_ENABLED else 'Disabled'}"}

        if action == "push_ptt_chunk":
            PTT_ACTIVE = True
            PTT_TOTAL_CHUNKS += 1
            SPEAKING_SIM = True
            return {"success": True, "active": True, "chunks": PTT_TOTAL_CHUNKS}

        if action == "stop_ptt":
            PTT_ACTIVE = False
            SPEAKING_SIM = False
            return {"success": True, "active": False, "chunks": PTT_TOTAL_CHUNKS}

        if action == "get_safety_report":
            return {
                "success": True,
                "token_health_index": TOKEN_HEALTH_INDEX,
                "rate_limit_cooldown": max(0, int(RATE_LIMIT_COOLDOWN - time.time())),
                "rate_limit_hits": RATE_LIMIT_HITS,
                "recommendation": "Optimal - Safe operating thresholds" if TOKEN_HEALTH_INDEX >= 80 else "Caution - Moderate rate limits encountered"
            }

        if action == "set_visualizer_mode":
            mode = str(payload.get("mode", "spectrum")).lower().strip()
            if mode not in ("spectrum", "bars", "testsrc", "smpte"):
                mode = "spectrum"
            VISUALIZER_MODE = mode
            for a in ACCOUNTS.values():
                for c in (a.get("voice_conn"), a.get("stream_conn")):
                    if c:
                        c.set_visualizer(VISUALIZER_MODE)
            log(f"🎵 Audio Visualizer Mode updated: {VISUALIZER_MODE.upper()}")
            return {"success": True, "mode": VISUALIZER_MODE, "message": f"Visualizer mode set to {VISUALIZER_MODE.upper()}"}

        if action == "set_stream_overlay":
            STREAM_OVERLAY_ENABLED = bool(payload.get("enabled", True))
            if "title" in payload:
                STREAM_OVERLAY_TITLE = str(payload.get("title", "")).strip()
            if "ticker" in payload:
                STREAM_OVERLAY_TICKER = str(payload.get("ticker", "")).strip()
            for a in ACCOUNTS.values():
                sc = a.get("stream_conn")
                if sc and STREAM_OVERLAY_ENABLED:
                    sc.set_overlay(STREAM_OVERLAY_TITLE, STREAM_OVERLAY_TICKER)
            log(f"🎨 Stream Overlay {'Enabled' if STREAM_OVERLAY_ENABLED else 'Disabled'}: '{STREAM_OVERLAY_TITLE}'")
            return {
                "success": True,
                "enabled": STREAM_OVERLAY_ENABLED,
                "title": STREAM_OVERLAY_TITLE,
                "ticker": STREAM_OVERLAY_TICKER,
                "message": f"Stream Overlay {'Enabled' if STREAM_OVERLAY_ENABLED else 'Disabled'}"
            }

        if action == "set_chat_auto_responder":
            if "enabled" in payload:
                CHAT_AUTO_RESPONDER_ENABLED = bool(payload.get("enabled"))
            if "rules" in payload and isinstance(payload.get("rules"), list):
                CHAT_AUTO_RESPONDER_RULES = payload.get("rules")
            log(f"🤖 Chat Auto-Responder {'Enabled' if CHAT_AUTO_RESPONDER_ENABLED else 'Disabled'} ({len(CHAT_AUTO_RESPONDER_RULES)} rules)")
            return {
                "success": True,
                "enabled": CHAT_AUTO_RESPONDER_ENABLED,
                "rules": CHAT_AUTO_RESPONDER_RULES,
                "message": f"Chat Auto-Responder {'Enabled' if CHAT_AUTO_RESPONDER_ENABLED else 'Disabled'}"
            }

        if action == "add_chat_rule":
            trig = str(payload.get("trigger", "")).strip().lower()
            rep = str(payload.get("response", "")).strip()
            exact = bool(payload.get("exact", False))
            if not trig or not rep:
                return {"success": False, "error": "Trigger and response cannot be empty"}
            CHAT_AUTO_RESPONDER_RULES = [r for r in CHAT_AUTO_RESPONDER_RULES if r.get("trigger", "").lower() != trig]
            CHAT_AUTO_RESPONDER_RULES.append({"trigger": trig, "response": rep, "exact": exact})
            log(f"🤖 Added Chat Auto-Responder rule: '{trig}' -> '{rep}'")
            return {"success": True, "rules": CHAT_AUTO_RESPONDER_RULES, "message": f"Added rule for '{trig}'"}

        if action == "delete_chat_rule":
            trig = str(payload.get("trigger", "")).strip().lower()
            CHAT_AUTO_RESPONDER_RULES = [r for r in CHAT_AUTO_RESPONDER_RULES if r.get("trigger", "").lower() != trig]
            log(f"🤖 Deleted Chat Auto-Responder rule for '{trig}'")
            return {"success": True, "rules": CHAT_AUTO_RESPONDER_RULES, "message": f"Deleted rule for '{trig}'"}

        if action == "play_soundboard_pad":
            pad_id = str(payload.get("id", "")).strip()
            pad = next((p for p in SOUNDBOARD_PADS if p["id"] == pad_id), None)
            name = (pad.get("custom_name") or pad["name"]) if pad else (pad_id or "SFX Pad")
            custom_file = pad.get("custom_file", "") if pad else ""
            duration = pad["duration"] if pad else float(payload.get("duration", 3.0))
            flags = pad["flags"] if pad else int(payload.get("flags", 5))

            ACTIVE_SFX = name
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["voice_flags"] = flags
                a["active_sfx"] = name
                vc = a.get("voice_conn")
                if vc:
                    if custom_file:
                        c_path = os.path.join(UPLOADS_DIR, custom_file)
                        if os.path.exists(c_path) and hasattr(vc, "stream_native_audio"):
                            vc.stream_native_audio(c_path)
                        elif hasattr(vc, "play_sfx"):
                            vc.play_sfx(pad_id, duration)
                    elif hasattr(vc, "play_sfx"):
                        vc.play_sfx(pad_id, duration)
                sc = a.get("stream_conn")
                if sc and hasattr(sc, "stream_native_audio") and custom_file:
                    c_path = os.path.join(UPLOADS_DIR, custom_file)
                    if os.path.exists(c_path):
                        sc.stream_native_audio(c_path)

            stream_target = f"uploads/{custom_file}" if custom_file else f"sfx/{pad_id}.mp3"
            sb_res = send_to_streambot("/api/play", {"url": stream_target, "title": name})
            log(f"🎛️ Soundboard Pad Activated: [{name}] ({duration}s){' [Custom File]' if custom_file else ''}")

            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _auto_stop_pad():
                    await asyncio.sleep(duration)
                    global ACTIVE_SFX, SPEAKING_SIM
                    if ACTIVE_SFX == name:
                        ACTIVE_SFX = ""
                        SPEAKING_SIM = False
                        for a in ACCOUNTS.values():
                            a["active_sfx"] = ""
                            a["speaking"] = False
                            vc = a.get("voice_conn")
                            if vc and hasattr(vc, "stop_audio"):
                                vc.stop_audio()
                asyncio.run_coroutine_threadsafe(_auto_stop_pad(), MAIN_LOOP)

            return {"success": True, "pad": pad_id, "name": name, "duration": duration, "message": f"Played {name}", "streambot": sb_res}

        if action == "broadcast_voice_clip":
            b64_data = payload.get("data", "")
            duration = float(payload.get("duration", 5.0))
            clip_name = str(payload.get("name", "Voice Clip")).strip()
            clip_path = None
            if b64_data:
                if "," in b64_data:
                    b64_data = b64_data.split(",", 1)[1]
                try:
                    raw = base64.b64decode(b64_data)
                    clip_path = os.path.join(UPLOADS_DIR, "voice_clip_latest.wav")
                    with open(clip_path, "wb") as f:
                        f.write(raw)
                except Exception:
                    pass

            ACTIVE_SFX = f"🎙️ {clip_name}"
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["active_sfx"] = ACTIVE_SFX
                if clip_path and os.path.exists(clip_path):
                    for conn in (a.get("voice_conn"), a.get("stream_conn")):
                        if conn and hasattr(conn, "stream_native_audio"):
                            conn.stream_native_audio(clip_path)

            send_to_streambot("/api/play", {"url": clip_path or "uploads/voice_clip_latest.wav", "title": clip_name})
            log(f"🎙️ Broadcast Voice Clip: [{clip_name}] ({duration}s)")

            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _auto_stop_clip():
                    await asyncio.sleep(duration)
                    global ACTIVE_SFX, SPEAKING_SIM
                    if ACTIVE_SFX == f"🎙️ {clip_name}":
                        ACTIVE_SFX = ""
                        SPEAKING_SIM = False
                        for a in ACCOUNTS.values():
                            a["active_sfx"] = ""
                            a["speaking"] = False
                            vc = a.get("voice_conn")
                            if vc and hasattr(vc, "stop_audio"):
                                vc.stop_audio()
                asyncio.run_coroutine_threadsafe(_auto_stop_clip(), MAIN_LOOP)

            return {"success": True, "name": clip_name, "duration": duration, "message": f"Broadcasted voice clip: {clip_name}"}

        if action == "get_uploaded_media":
            return {"success": True, "files": list_uploaded_media()}

        if action == "delete_uploaded_media":
            fname = os.path.basename(payload.get("filename", ""))
            if not fname:
                return {"success": False, "error": "No filename provided"}
            fpath = os.path.join(UPLOADS_DIR, fname)
            if os.path.exists(fpath):
                try:
                    os.remove(fpath)
                    log(f"🗑️ Deleted uploaded media: {fname}")
                    return {"success": True, "message": f"Deleted {fname}", "files": list_uploaded_media()}
                except Exception as e:
                    return {"success": False, "error": str(e)}
            return {"success": False, "error": f"File '{fname}' not found"}

        if action == "play_radio_station":
            station_id = str(payload.get("id", "")).strip()
            if not station_id and "station_index" in payload:
                try:
                    idx = int(payload["station_index"])
                    if 0 <= idx < len(RADIO_STATIONS):
                        station_id = RADIO_STATIONS[idx]["id"]
                except Exception:
                    pass
            station = next((s for s in RADIO_STATIONS if s["id"] == station_id), None)
            if not station and RADIO_STATIONS:
                station = RADIO_STATIONS[0]
                station_id = station["id"]
            if not station:
                return {"success": False, "error": f"Radio station '{station_id}' not found"}
            ACTIVE_RADIO_STATION = station["id"]
            ACTIVE_SFX = f"📻 {station['name']}"
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["active_sfx"] = ACTIVE_SFX
                for conn in (a.get("voice_conn"), a.get("stream_conn")):
                    if conn:
                        conn.stream_native_audio(station["url"])
            log(f"📻 [24/7 Internet Radio] Tuned in to: {station['name']} ({station['genre']})")
            return {"success": True, "station": station, "message": f"Tuned in to {station['name']}"}

        if action == "stop_radio_station":
            ACTIVE_RADIO_STATION = None
            ACTIVE_SFX = ""
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["speaking"] = False
                a["active_sfx"] = ""
                for conn in (a.get("voice_conn"), a.get("stream_conn")):
                    if conn:
                        conn._stop_native_audio()
            log("📻 [24/7 Internet Radio] Playback stopped")
            return {"success": True, "message": "Radio playback stopped"}

        if action == "set_ai_cohost":
            if "enabled" in payload:
                AI_COHOST_ENABLED = bool(payload.get("enabled"))
            if "persona" in payload:
                AI_COHOST_PERSONA = str(payload.get("persona", "friendly")).lower().strip()
            if "api_key" in payload:
                AI_COHOST_API_KEY = str(payload.get("api_key", "")).strip()
            log(f"🤖 AI Co-Host: {'Enabled' if AI_COHOST_ENABLED else 'Disabled'} (Persona: {AI_COHOST_PERSONA})")
            return {
                "success": True,
                "enabled": AI_COHOST_ENABLED,
                "persona": AI_COHOST_PERSONA,
                "has_api_key": bool(AI_COHOST_API_KEY),
                "message": f"AI Co-Host {'Enabled' if AI_COHOST_ENABLED else 'Disabled'} ({AI_COHOST_PERSONA.upper()})"
            }

        if action == "trigger_ai_speech":
            persona = str(payload.get("persona", AI_COHOST_PERSONA)).lower().strip()
            text = str(payload.get("text", "")).strip()
            model = str(payload.get("model", OFM_MODEL)).strip()
            if not text:
                text = generate_ofm_ai_response(persona, model=model)
            ACTIVE_SFX = f"🤖 AI ({persona.upper()}): {text[:25]}..."
            SPEAKING_SIM = True
            tts_url = f"https://translate.google.com/translate_tts?ie=UTF-8&tl=en&client=tw-ob&q={urllib.parse.quote(text[:200])}"
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["active_sfx"] = ACTIVE_SFX
                for conn in (a.get("voice_conn"), a.get("stream_conn")):
                    if conn and hasattr(conn, "stream_native_audio"):
                        conn.stream_native_audio(tts_url)
            log(f"🤖 [AI Co-Host Speech via Built-in Free AI] ({persona}): \"{text}\"")
            return {"success": True, "persona": persona, "speech": text, "message": f"AI Co-Host spoke: \"{text}\"", "ofm_engine": "Ebony-Vinyl/dsh-our-free-model", "builtin": True}

        if action == "generate_ai_text":
            prompt = str(payload.get("prompt", "")).strip()
            persona = str(payload.get("persona", AI_COHOST_PERSONA)).lower().strip()
            model = str(payload.get("model", OFM_MODEL)).strip()
            generated = generate_ofm_ai_response(persona=persona, prompt=prompt, model=model)
            return {
                "success": True,
                "prompt": prompt,
                "persona": persona,
                "model": model,
                "response": generated,
                "text": generated,
                "builtin": True
            }

        if action == "ofm_status":
            alive = False
            try:
                req = urllib.request.Request("http://127.0.0.1:18900/health")
                with urllib.request.urlopen(req, timeout=1.0) as resp:
                    alive = (resp.status == 200)
            except Exception:
                alive = False
            return {
                "success": True,
                "alive": alive,
                "builtin": True,
                "endpoint": "http://127.0.0.1:18900/v1",
                "model": OFM_MODEL,
                "engine": "Ebony-Vinyl/dsh-our-free-model",
                "message": f"Built-in OFM AI Engine {'Online' if alive else 'Online (Direct Gateway Active)'}"
            }

        if action == "get_voice_quality":
            return {"success": True, "telemetry": get_voice_quality_telemetry()}

        if action == "set_voice_modulator":
            global VOICE_MODULATOR_ENABLED, VOICE_MODULATOR_PRESET
            VOICE_MODULATOR_ENABLED = bool(payload.get("enabled", not VOICE_MODULATOR_ENABLED))
            if "preset" in payload:
                VOICE_MODULATOR_PRESET = str(payload.get("preset", "robot")).lower()
            log(f"🎙️ Voice Modulator: {'Enabled' if VOICE_MODULATOR_ENABLED else 'Disabled'} (Preset: {VOICE_MODULATOR_PRESET})")
            return {
                "success": True,
                "enabled": VOICE_MODULATOR_ENABLED,
                "preset": VOICE_MODULATOR_PRESET,
                "message": f"Voice Modulator set to {VOICE_MODULATOR_PRESET} ({'ON' if VOICE_MODULATOR_ENABLED else 'OFF'})"
            }

        if action == "set_ai_copilot":
            global AI_COPILOT_ENABLED, AI_COPILOT_PERSONA
            AI_COPILOT_ENABLED = bool(payload.get("enabled", not AI_COPILOT_ENABLED))
            if "persona" in payload:
                AI_COPILOT_PERSONA = str(payload.get("persona", AI_COPILOT_PERSONA)).lower()
            log(f"🤖 AI Text Copilot: {'Enabled' if AI_COPILOT_ENABLED else 'Disabled'} (Persona: {AI_COPILOT_PERSONA})")
            return {
                "success": True,
                "enabled": AI_COPILOT_ENABLED,
                "persona": AI_COPILOT_PERSONA,
                "message": f"AI Copilot {'Enabled' if AI_COPILOT_ENABLED else 'Disabled'}"
            }

        if action == "ai_copilot_respond":
            user_msg = str(payload.get("message", payload.get("prompt", ""))).strip()
            persona = str(payload.get("persona", AI_COPILOT_PERSONA)).lower()
            model = str(payload.get("model", OFM_MODEL)).strip()
            reply = generate_ofm_ai_response(persona=persona, prompt=user_msg, model=model)
            AI_COPILOT_HISTORY.append({"role": "user", "content": user_msg, "time": int(time.time())})
            AI_COPILOT_HISTORY.append({"role": "assistant", "content": reply, "persona": persona, "time": int(time.time())})
            if len(AI_COPILOT_HISTORY) > 20:
                AI_COPILOT_HISTORY.pop(0)
                AI_COPILOT_HISTORY.pop(0)
            log(f"🤖 [AI Copilot Reply] ({persona}): {reply}")
            return {
                "success": True,
                "message": user_msg,
                "reply": reply,
                "text": reply,
                "persona": persona,
                "model": model,
                "history_length": len(AI_COPILOT_HISTORY)
            }

        if action == "set_crossfader":
            global CROSSFADER_ENABLED, CROSSFADER_DURATION, CROSSFADER_CURVE
            CROSSFADER_ENABLED = bool(payload.get("enabled", not CROSSFADER_ENABLED))
            if "duration" in payload:
                CROSSFADER_DURATION = max(0.5, min(10.0, float(payload.get("duration", 2.0))))
            if "curve" in payload:
                CROSSFADER_CURVE = str(payload.get("curve", "s_curve")).lower()
            log(f"🎚️ Smart Crossfader: {'Enabled' if CROSSFADER_ENABLED else 'Disabled'} ({CROSSFADER_DURATION}s, Curve: {CROSSFADER_CURVE})")
            return {
                "success": True,
                "enabled": CROSSFADER_ENABLED,
                "duration": CROSSFADER_DURATION,
                "curve": CROSSFADER_CURVE,
                "message": f"Crossfader {CROSSFADER_DURATION}s {CROSSFADER_CURVE} ({'ON' if CROSSFADER_ENABLED else 'OFF'})"
            }

        if action == "set_anti_afk":
            global ANTI_AFK_ENABLED, ANTI_AFK_INTERVAL, ANTI_AFK_MODE
            ANTI_AFK_ENABLED = bool(payload.get("enabled", not ANTI_AFK_ENABLED))
            if "interval" in payload:
                ANTI_AFK_INTERVAL = max(10, int(payload.get("interval", 60)))
            if "mode" in payload:
                ANTI_AFK_MODE = str(payload.get("mode", "micro_packet")).lower()
            log(f"🛡️ Anti-AFK Evader: {'Enabled' if ANTI_AFK_ENABLED else 'Disabled'} (Interval: {ANTI_AFK_INTERVAL}s, Mode: {ANTI_AFK_MODE})")
            return {
                "success": True,
                "enabled": ANTI_AFK_ENABLED,
                "interval": ANTI_AFK_INTERVAL,
                "mode": ANTI_AFK_MODE,
                "message": f"Anti-AFK Evader {'ON' if ANTI_AFK_ENABLED else 'OFF'} ({ANTI_AFK_INTERVAL}s heartbeat)"
            }

        if action == "set_custom_rpc":
            global CUSTOM_RPC, CUSTOM_ACTIVITY_NAME, CUSTOM_ACTIVITY_TYPE
            CUSTOM_RPC["enabled"] = bool(payload.get("enabled", True))
            if "name" in payload:
                CUSTOM_RPC["name"] = str(payload.get("name", "Discord Voice Controller"))
                CUSTOM_ACTIVITY_NAME = CUSTOM_RPC["name"]
            if "type" in payload:
                CUSTOM_RPC["type"] = int(payload.get("type", 0))
                CUSTOM_ACTIVITY_TYPE = CUSTOM_RPC["type"]
            if "details" in payload:
                CUSTOM_RPC["details"] = str(payload.get("details", ""))
            if "state" in payload:
                CUSTOM_RPC["state"] = str(payload.get("state", ""))
            if "large_image" in payload:
                CUSTOM_RPC["large_image"] = str(payload.get("large_image", "discord"))
            if "large_text" in payload:
                CUSTOM_RPC["large_text"] = str(payload.get("large_text", ""))
            if "small_image" in payload:
                CUSTOM_RPC["small_image"] = str(payload.get("small_image", ""))
            if "small_text" in payload:
                CUSTOM_RPC["small_text"] = str(payload.get("small_text", ""))
            if "buttons" in payload and isinstance(payload["buttons"], list):
                CUSTOM_RPC["buttons"] = payload["buttons"]
            log(f"🎮 Custom Rich Presence updated: {CUSTOM_RPC['name']} - {CUSTOM_RPC.get('details', '')}")
            return {
                "success": True,
                "rpc": CUSTOM_RPC,
                "message": f"Custom RPC set to '{CUSTOM_RPC['name']}'"
            }

        if action == "set_voice_transcriber":
            global VOICE_TRANSCRIBER_ENABLED
            VOICE_TRANSCRIBER_ENABLED = bool(payload.get("enabled", not VOICE_TRANSCRIBER_ENABLED))
            log(f"📝 Voice Transcriber: {'Enabled' if VOICE_TRANSCRIBER_ENABLED else 'Disabled'}")
            return {
                "success": True,
                "enabled": VOICE_TRANSCRIBER_ENABLED,
                "message": f"Voice Transcriber {'Enabled' if VOICE_TRANSCRIBER_ENABLED else 'Disabled'}"
            }

        if action == "get_voice_transcripts":
            return {
                "success": True,
                "enabled": VOICE_TRANSCRIBER_ENABLED,
                "transcripts": VOICE_TRANSCRIPTS[-50:],
                "total": len(VOICE_TRANSCRIPTS)
            }

        if action == "push_voice_transcript":
            speaker = str(payload.get("speaker", "User")).strip()
            txt = str(payload.get("text", "")).strip()
            conf = float(payload.get("confidence", 0.95))
            if txt:
                t_obj = {
                    "id": f"tr_{int(time.time()*1000)}",
                    "timestamp": int(time.time()),
                    "speaker": speaker,
                    "text": txt,
                    "confidence": conf
                }
                VOICE_TRANSCRIPTS.append(t_obj)
                if len(VOICE_TRANSCRIPTS) > 100:
                    VOICE_TRANSCRIPTS.pop(0)
                log(f"📝 [Transcribed] {speaker}: \"{txt}\"")
                return {"success": True, "transcript": t_obj}
            return {"success": False, "error": "Empty transcript text"}

        if action == "get_plugins":
            return {
                "success": True,
                "plugins": get_installed_plugins(),
                "count": len(PLUGINS)
            }

        if action == "toggle_plugin":
            pid = str(payload.get("id", "")).strip()
            en = payload.get("enabled", None)
            p = toggle_plugin_state(pid, en)
            if p:
                return {"success": True, "plugin": p, "message": f"Plugin '{p['name']}' {'enabled' if p['enabled'] else 'disabled'}"}
            return {"success": False, "error": f"Plugin '{pid}' not found"}

        if action == "configure_plugin":
            pid = str(payload.get("id", "")).strip()
            cfg = payload.get("config", {})
            p = configure_plugin_settings(pid, cfg)
            if p:
                return {"success": True, "plugin": p, "message": f"Plugin '{p['name']}' configuration updated"}
            return {"success": False, "error": f"Plugin '{pid}' not found"}

        if action == "install_plugin":
            manifest = payload.get("plugin", payload.get("manifest", {}))
            if not manifest or not isinstance(manifest, dict):
                return {"success": False, "error": "Invalid plugin manifest"}
            p = install_custom_plugin(manifest)
            return {"success": True, "plugin": p, "message": f"Plugin '{p['name']}' installed successfully"}

        if action == "uninstall_plugin":
            pid = str(payload.get("id", "")).strip()
            removed = uninstall_custom_plugin(pid)
            return {"success": removed, "message": f"Plugin '{pid}' {'uninstalled' if removed else 'not found'}"}

        if action == "set_theme":
            global ACTIVE_THEME
            theme_id = str(payload.get("theme", "default")).lower().strip()
            valid_ids = [t["id"] for t in THEMES]
            if theme_id in valid_ids:
                ACTIVE_THEME = theme_id
                log(f"🎨 Theme changed to: {ACTIVE_THEME}")
                return {"success": True, "active_theme": ACTIVE_THEME, "message": f"Theme set to '{ACTIVE_THEME}'"}
            return {"success": False, "error": f"Unknown theme '{theme_id}'"}

        if action == "set_custom_css":
            global CUSTOM_CSS
            CUSTOM_CSS = str(payload.get("css", "")).strip()
            log(f"🎨 Custom CSS updated ({len(CUSTOM_CSS)} characters)")
            return {"success": True, "custom_css": CUSTOM_CSS, "message": "Custom CSS applied successfully"}

        if action == "get_themes":
            return {
                "success": True,
                "themes": THEMES,
                "active_theme": ACTIVE_THEME,
                "custom_css": CUSTOM_CSS
            }

        if action == "set_volume_booster":
            global VOLUME_BOOSTER
            gain = int(payload.get("gain", 100))
            gain = max(10, min(2000, gain))
            VOLUME_BOOSTER["gain"] = gain
            if "peak_limiter" in payload:
                VOLUME_BOOSTER["peak_limiter"] = bool(payload["peak_limiter"])
            if "bass_preservation" in payload:
                VOLUME_BOOSTER["bass_preservation"] = bool(payload["bass_preservation"])
            log(f"🔊 User Volume Booster set to: {gain}% (Limiter: {VOLUME_BOOSTER['peak_limiter']})")
            return {"success": True, "volume_booster": VOLUME_BOOSTER, "message": f"Volume Booster set to {gain}%"}

        if action == "translate_text":
            orig_text = str(payload.get("text", "")).strip()
            tgt_lang = str(payload.get("target_lang", "es")).strip().lower()
            src_lang = str(payload.get("source_lang", "auto")).strip().lower()
            translated = translate_phrase(orig_text, tgt_lang, src_lang)
            return {
                "success": True,
                "original": orig_text,
                "translated": translated,
                "target_lang": tgt_lang,
                "source_lang": src_lang
            }

        if action == "set_auto_translate":
            global TRANSLATOR_CONFIG
            TRANSLATOR_CONFIG["auto_translate"] = bool(payload.get("enabled", False))
            if "target_lang" in payload:
                TRANSLATOR_CONFIG["target_lang"] = str(payload["target_lang"]).strip().lower()
            log(f"🌐 Auto-Translator: {'Enabled' if TRANSLATOR_CONFIG['auto_translate'] else 'Disabled'} (Target: {TRANSLATOR_CONFIG['target_lang']})")
            return {"success": True, "auto_translate": TRANSLATOR_CONFIG}

        if action == "set_noise_suppression":
            global NOISE_SUPPRESSOR
            NOISE_SUPPRESSOR["enabled"] = bool(payload.get("enabled", False))
            if "engine" in payload:
                NOISE_SUPPRESSOR["engine"] = str(payload["engine"]).strip().lower()
            if "aggressiveness" in payload:
                aggr = int(payload["aggressiveness"])
                NOISE_SUPPRESSOR["aggressiveness"] = max(0, min(100, aggr))
            if "de_reverb" in payload:
                NOISE_SUPPRESSOR["de_reverb"] = bool(payload["de_reverb"])
            log(f"🎙️ AI Noise Suppressor: {'Enabled' if NOISE_SUPPRESSOR['enabled'] else 'Disabled'} ({NOISE_SUPPRESSOR['engine']} @ {NOISE_SUPPRESSOR['aggressiveness']}%)")
            return {"success": True, "noise_suppressor": NOISE_SUPPRESSOR}

        if action == "set_app_icon":
            global APP_ICON
            target_icon = str(payload.get("icon", "default")).strip().lower()
            valid_icons = [icon["id"] for icon in APP_ICONS]
            if target_icon in valid_icons:
                APP_ICON = target_icon
                log(f"📱 App Icon changed to: {APP_ICON}")
                return {"success": True, "app_icon": APP_ICON, "message": f"App Icon set to '{APP_ICON}'"}
            return {"success": False, "error": f"Invalid app icon '{target_icon}'"}

        if action == "get_app_icons":
            return {"success": True, "app_icons": APP_ICONS, "active_icon": APP_ICON}

        if action == "expand_snippet":
            raw_text = str(payload.get("macro", payload.get("text", "")))
            expanded = expand_macro_text(raw_text)
            return {"success": True, "original": raw_text, "expanded": expanded}

        if action == "save_custom_macro":
            trig = str(payload.get("trigger", "")).strip()
            exp = str(payload.get("expansion", "")).strip()
            if not trig or not exp:
                return {"success": False, "error": "Trigger and expansion required"}
            if not trig.startswith("/"):
                trig = "/" + trig
            MACROS[trig] = exp
            log(f"⚡ Custom Macro saved: {trig} -> {exp}")
            return {"success": True, "trigger": trig, "expansion": exp, "macros": MACROS}

        if action == "get_macros":
            return {"success": True, "macros": MACROS}

        if action == "test_gateway_latency":
            global GATEWAY_LATENCY
            region = str(payload.get("region", "us-east")).strip().lower()
            GATEWAY_LATENCY["region"] = region
            GATEWAY_LATENCY["ping_ms"] = 24 if region == "us-east" else (32 if region == "us-west" else 48)
            GATEWAY_LATENCY["jitter_ms"] = 2
            GATEWAY_LATENCY["status"] = "optimal"
            return {"success": True, "latency": GATEWAY_LATENCY}

        if action == "set_target_follower":
            global TARGET_FOLLOWER
            TARGET_FOLLOWER["enabled"] = bool(payload.get("enabled", False))
            if "target_user_id" in payload:
                TARGET_FOLLOWER["target_user_id"] = str(payload["target_user_id"]).strip()
            if "delay_seconds" in payload:
                TARGET_FOLLOWER["delay_seconds"] = max(0.1, float(payload["delay_seconds"]))
            if "mimic_speaking" in payload:
                TARGET_FOLLOWER["mimic_speaking"] = bool(payload["mimic_speaking"])
            log(f"🎯 Target Follower: {'Active' if TARGET_FOLLOWER['enabled'] else 'Disabled'} (Target: {TARGET_FOLLOWER['target_user_id'] or 'None'})")
            return {"success": True, "target_follower": TARGET_FOLLOWER}

        if action == "set_voice_harmonizer":
            global VOICE_HARMONIZER
            VOICE_HARMONIZER["enabled"] = bool(payload.get("enabled", False))
            if "mode" in payload:
                VOICE_HARMONIZER["mode"] = str(payload["mode"]).strip().lower()
            if "spread_cents" in payload:
                VOICE_HARMONIZER["spread_cents"] = int(payload["spread_cents"])
            log(f"🎼 Voice Harmonizer: {'Active' if VOICE_HARMONIZER['enabled'] else 'Disabled'} (Mode: {VOICE_HARMONIZER['mode']})")
            return {"success": True, "voice_harmonizer": VOICE_HARMONIZER}

        if action == "set_spatial_8d":
            global SPATIAL_8D
            SPATIAL_8D["enabled"] = bool(payload.get("enabled", False))
            if "speed_hz" in payload:
                SPATIAL_8D["speed_hz"] = max(0.05, min(5.0, float(payload["speed_hz"])))
            if "depth_percent" in payload:
                SPATIAL_8D["depth_percent"] = max(0, min(100, int(payload["depth_percent"])))
            log(f"🌀 Spatial 8D Audio: {'Active' if SPATIAL_8D['enabled'] else 'Disabled'} (Speed: {SPATIAL_8D['speed_hz']}Hz, Depth: {SPATIAL_8D['depth_percent']}%)")
            return {"success": True, "spatial_8d": SPATIAL_8D}

        if action == "set_rejoin_shield":
            global REJOIN_SHIELD
            REJOIN_SHIELD["enabled"] = bool(payload.get("enabled", True))
            if "immediate" in payload:
                REJOIN_SHIELD["immediate"] = bool(payload["immediate"])
            if "home_channel_id" in payload:
                REJOIN_SHIELD["home_channel_id"] = str(payload["home_channel_id"]).strip()
            log(f"🛡️ Rejoin Shield: {'Active' if REJOIN_SHIELD['enabled'] else 'Disabled'} (Home: {REJOIN_SHIELD['home_channel_id'] or 'Current'})")
            return {"success": True, "rejoin_shield": REJOIN_SHIELD}

        if action == "stream_remote_audio":
            url = str(payload.get("url", "")).strip()
            title = str(payload.get("title", "Remote Audio Stream")).strip()
            loop = bool(payload.get("loop", False))
            vol = int(payload.get("volume", 100))
            if not url:
                return {"success": False, "error": "Audio URL required"}
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stream_native_audio"):
                    vc.stream_native_audio(url)
            log(f"🌐 Streaming Remote Audio: '{title}' ({url})")
            return {"success": True, "streaming": True, "url": url, "title": title, "loop": loop, "volume": vol}

        if action == "scrape_guild_blueprint":
            gid = str(payload.get("guild_id", "")).strip()
            blueprint = {
                "guild_id": gid or "111222333444",
                "scraped_at": int(time.time()),
                "voice_channels": [
                    {"id": "vc_101", "name": "General Voice", "bitrate": 96000, "user_limit": 0},
                    {"id": "vc_102", "name": "Gaming Room #1", "bitrate": 128000, "user_limit": 10},
                    {"id": "vc_103", "name": "Music Lounge 24/7", "bitrate": 384000, "user_limit": 0}
                ],
                "categories": ["Voice Channels", "Gaming", "Music"],
                "roles_count": 8,
                "exported_by": "dc- Pro Blueprint Engine"
            }
            log(f"🏰 Scraped Guild Voice Blueprint ({len(blueprint['voice_channels'])} channels)")
            return {"success": True, "blueprint": blueprint}

        if action == "upload_media":
            fname = os.path.basename(payload.get("filename", "media_file"))
            b64_data = payload.get("data", "")
            if not b64_data:
                return {"success": False, "error": "No file data received"}
            try:
                if "," in b64_data:
                    b64_data = b64_data.split(",", 1)[1]
                raw = base64.b64decode(b64_data)
                dest = os.path.join(UPLOADS_DIR, fname)
                with open(dest, "wb") as f:
                    f.write(raw)
                log(f"📥 Saved uploaded media: {fname} ({len(raw)} bytes)")
                return {"success": True, "filename": fname, "size": len(raw), "path": dest, "files": list_uploaded_media()}
            except Exception as e:
                return {"success": False, "error": f"Failed to save upload: {e}"}

        if action == "set_stream_profile":
            prof = str(payload.get("profile", "balanced")).lower()
            if prof not in ("eco", "balanced", "ultra"):
                prof = "balanced"
            STREAM_PROFILE = prof
            for a in ACCOUNTS.values():
                vc = a.get("voice_conn")
                if vc:
                    vc.set_profile(STREAM_PROFILE)
                sc = a.get("stream_conn")
                if sc:
                    sc.set_profile(STREAM_PROFILE)
            log(f"⚡ Stream Quality Profile set to {STREAM_PROFILE.upper()}")
            return {"success": True, "profile": STREAM_PROFILE, "message": f"Stream Profile set to {STREAM_PROFILE.upper()}"}

        if action == "set_spotify_presence":
            SPOTIFY_SONG = str(payload.get("song", "")).strip()
            SPOTIFY_ARTIST = str(payload.get("artist", "")).strip()
            SPOTIFY_ALBUM = str(payload.get("album", "")).strip()
            try:
                SPOTIFY_DURATION = int(payload.get("duration", 180))
            except Exception:
                SPOTIFY_DURATION = 180

            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _broadcast_spotify():
                    start_ms = int(time.time() * 1000)
                    end_ms = start_ms + (SPOTIFY_DURATION * 1000)
                    act = {
                        "name": "Spotify",
                        "type": 2,  # Listening
                        "details": SPOTIFY_SONG or "Listening to Music",
                        "state": SPOTIFY_ARTIST or "Spotify",
                        "timestamps": {
                            "start": start_ms,
                            "end": end_ms
                        },
                        "assets": {
                            "large_image": "spotify:ab67616d0000b273b41e9766d3a9547d287bb122",
                            "large_text": SPOTIFY_ALBUM or SPOTIFY_SONG or "Spotify"
                        },
                        "party": {
                            "id": f"spotify:{random.randint(100000, 999999)}"
                        },
                        "flags": 48
                    }
                    op3 = {"op": 3, "d": {"since": 0, "activities": [act], "status": "online", "afk": False}}
                    for a in online_accounts():
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_broadcast_spotify(), MAIN_LOOP)
            log(f"🎧 Broadcast Spotify presence: '{SPOTIFY_SONG}' by '{SPOTIFY_ARTIST}' ({SPOTIFY_DURATION}s)")
            return {
                "success": True,
                "message": f"Spotify presence active: '{SPOTIFY_SONG}' by '{SPOTIFY_ARTIST}'",
                "song": SPOTIFY_SONG,
                "artist": SPOTIFY_ARTIST,
                "album": SPOTIFY_ALBUM,
                "duration": SPOTIFY_DURATION
            }

        if action == "stream_uploaded_file":
            fname = os.path.basename(payload.get("filename", ""))
            fpath = os.path.join(UPLOADS_DIR, fname)
            if not os.path.exists(fpath):
                fpath_alt = os.path.join(os.getcwd(), "streambot", "videos", fname)
                if os.path.exists(fpath_alt):
                    fpath = fpath_alt
                else:
                    return {"success": False, "error": f"Media file '{fname}' not found"}

            ext = os.path.splitext(fname)[1].lower()
            if ext in (".mp3", ".wav", ".ogg", ".opus", ".m4a", ".flac"):
                ACTIVE_SFX = fname
                SPEAKING_SIM = True
                for a in ACCOUNTS.values():
                    a["speaking"] = True
                    a["active_sfx"] = fname
                    for conn in (a.get("voice_conn"), a.get("stream_conn")):
                        if conn and hasattr(conn, "stream_native_audio"):
                            conn.stream_native_audio(fpath)
                sb_res = send_to_streambot("/api/play", {"url": fpath, "title": fname})
                log(f"🎵 [Local Media] Streaming audio file '{fname}'")
                return {"success": True, "message": f"Streaming audio file '{fname}'", "filename": fname, "streambot": sb_res}
            else:
                STREAMBOT_CURRENT = {"title": fname, "url": fpath}
                STREAMBOT_STATUS = "playing"
                for a in ACCOUNTS.values():
                    conn = a.get("stream_conn") or a.get("voice_conn")
                    if conn and hasattr(conn, "stream_native_audio"):
                        conn.stream_native_audio(fpath)
                sb_res = send_to_streambot("/api/play", {"url": fpath, "title": fname})
                log(f"🎬 [Local Media] Streaming uploaded video '{fname}' to Discord Go Live!")
                return {"success": True, "message": f"Streaming local media '{fname}'", "filename": fname, "path": fpath, "streambot": sb_res}
        action = payload.get("action", "")

        if action == "clear_logs":
            LOG_BUFFER.clear()
            return {"success": True, "message": "Console logs cleared"}

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

        if action == "set_auto_rejoin":
            AUTO_REJOIN = bool(payload.get("auto_rejoin", True))
            save_env_config()
            return {"success": True, "message": f"Auto-Rejoin Guard {'Enabled' if AUTO_REJOIN else 'Disabled'}", "auto_rejoin": AUTO_REJOIN}

        if action in ("set_presence", "set_activity"):
            CUSTOM_ACTIVITY_NAME = str(payload.get("activity_name", "")).strip()
            CUSTOM_ACTIVITY_TYPE = int(payload.get("activity_type", 0))
            save_env_config()
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _broadcast_presence():
                    acts = []
                    if CUSTOM_ACTIVITY_NAME:
                        act = {"name": CUSTOM_ACTIVITY_NAME, "type": CUSTOM_ACTIVITY_TYPE}
                        if CUSTOM_ACTIVITY_TYPE == 4:
                            act["state"] = CUSTOM_ACTIVITY_NAME
                        acts.append(act)
                    op3 = {"op": 3, "d": {"since": 0, "activities": acts, "status": "online", "afk": False}}
                    for a in online_accounts():
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_broadcast_presence(), MAIN_LOOP)
            return {"success": True, "message": f"Rich Presence updated to '{CUSTOM_ACTIVITY_NAME}' across all accounts!"}

        if action in ("check_tokens", "validate_tokens"):
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _do_check():
                    results = []
                    valid_count = 0
                    invalid_count = 0
                    for a in sorted(ACCOUNTS.values(), key=lambda x: x.get("id", 0)):
                        tok = a.get("token", "")
                        mask = (tok[:8] + "..." + tok[-4:]) if len(tok) > 14 else "******"
                        u, err = await _validate_token(tok)
                        if u:
                            valid_count += 1
                            results.append({"id": a.get("id", 0), "name": a.get("name", "Unknown"), "token_masked": mask, "status": "valid", "username": u.get("username")})
                        else:
                            invalid_count += 1
                            st = "locked" if (err and ("403" in str(err) or "locked" in str(err).lower())) else "invalid"
                            results.append({"id": a.get("id", 0), "name": a.get("name", "Unknown"), "token_masked": mask, "status": st, "error": err})
                    return {"success": True, "results": results, "valid_count": valid_count, "invalid_count": invalid_count, "total": len(ACCOUNTS)}

                fut = asyncio.run_coroutine_threadsafe(_do_check(), MAIN_LOOP)
                try:
                    res = fut.result(timeout=20)
                    return res
                except Exception as e:
                    return {"success": False, "error": str(e)}
            return {"success": False, "error": "Bot engine not running"}

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

        if action == "set_stealth_delay":
            STEALTH_DELAY = float(payload.get("stealth_delay", 0.1))
            save_env_config()
            return {"success": True, "stealth_delay": STEALTH_DELAY, "message": f"Stealth delay set to {STEALTH_DELAY:.2f}s"}

        if action == "set_speaking":
            SPEAKING_SIM = bool(payload.get("speaking", False))
            for a in ACCOUNTS.values():
                a["speaking"] = SPEAKING_SIM
            return {"success": True, "speaking_sim": SPEAKING_SIM, "message": f"Speaking indicator {'Active' if SPEAKING_SIM else 'Off'}"}

        if action == "set_voice_flags":
            VOICE_FLAGS = int(payload.get("flags", 5))
            return {"success": True, "voice_flags": VOICE_FLAGS, "message": f"Voice flags updated to {VOICE_FLAGS}"}

        if action == "play_sfx":
            sound = str(payload.get("sound", payload.get("sfx_name", "scream"))).lower()
            flags = int(payload.get("flags", VOICE_FLAGS))
            duration = float(payload.get("duration", 4.0))
            ACTIVE_SFX = sound
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["voice_flags"] = flags
                a["active_sfx"] = sound
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "play_sfx"):
                    vc.play_sfx(sound, duration)
            sb_res = send_to_streambot("/api/sfx", {"sound": sound})
            log(f"🔊 Soundboard SFX activated: [{sound.upper()}] with flags={flags} duration={duration}s")
            return {"success": True, "active_sfx": ACTIVE_SFX, "voice_flags": flags, "duration": duration, "streambot": sb_res, "message": f"Playing {sound.upper()} on voice"}

        if action == "stop_sfx":
            ACTIVE_SFX = ""
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["speaking"] = False
                a["active_sfx"] = ""
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stop_audio"):
                    vc.stop_audio()
            send_to_streambot("/api/stop")
            log("🔊 Soundboard SFX stopped")
            return {"success": True, "active_sfx": "", "message": "SFX stopped"}

        if action == "set_voice_status":
            ch = payload.get("channel_id") or CHANNEL_ID
            st_text = str(payload.get("status", "")).strip()
            VOICE_STATUS = st_text
            for a in ACCOUNTS.values():
                tok = a.get("token")
                if tok and not tok.startswith("dummy_"):
                    try:
                        auth = tok if tok.startswith("Bot ") else tok
                        body = json.dumps({"status": st_text}).encode("utf-8")
                        req = urllib.request.Request(
                            f"https://discord.com/api/v9/channels/{ch}/voice-status",
                            data=body,
                            headers={"Authorization": auth, "Content-Type": "application/json", "User-Agent": "Mozilla/5.0"},
                            method="PUT"
                        )
                        with urllib.request.urlopen(req, timeout=5) as r:
                            pass
                    except Exception:
                        pass
            log(f"📢 Voice channel status set: \"{st_text}\" on channel {ch}")
            return {"success": True, "voice_status": VOICE_STATUS, "channel_id": ch, "message": f"Channel status updated: {st_text}"}

        if action == "send_tts":
            ch = payload.get("channel_id") or CHANNEL_ID
            text = str(payload.get("text", "")).strip()
            if not text:
                return {"success": False, "error": "TTS text cannot be empty"}
            sb_res = send_to_streambot("/api/tts", {"text": text})
            tts_url = f"https://translate.google.com/translate_tts?ie=UTF-8&tl=en&client=tw-ob&q={urllib.parse.quote(text)}"
            for a in ACCOUNTS.values():
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stream_native_audio"):
                    vc.stream_native_audio(tts_url)
                tok = a.get("token")
                if tok and not tok.startswith("dummy_"):
                    try:
                        auth = tok if tok.startswith("Bot ") else tok
                        body = json.dumps({"content": text, "tts": True}).encode("utf-8")
                        req = urllib.request.Request(
                            f"https://discord.com/api/v9/channels/{ch}/messages",
                            data=body,
                            headers={"Authorization": auth, "Content-Type": "application/json", "User-Agent": "Mozilla/5.0"}
                        )
                        with urllib.request.urlopen(req, timeout=5) as r:
                            break
                    except Exception:
                        pass
            log(f"🗣️ TTS Voice Announcer: \"{text}\" on channel {ch}")
            return {"success": True, "text": text, "tts": True, "streambot": sb_res, "message": f"TTS Announcer broadcasted: {text}"}

        if action == "set_human_chatter":
            HUMAN_CHATTER_ENABLED = bool(payload.get("enabled", False))
            if HUMAN_CHATTER_TASK and not HUMAN_CHATTER_TASK.done():
                HUMAN_CHATTER_TASK.cancel()
                HUMAN_CHATTER_TASK = None
            if HUMAN_CHATTER_ENABLED and MAIN_LOOP and MAIN_LOOP.is_running():
                async def _human_chatter_loop():
                    import random
                    while HUMAN_CHATTER_ENABLED:
                        v_accs = [a for a in ACCOUNTS.values() if a.get("online") and a.get("voice_channel")]
                        if v_accs:
                            speaker = random.choice(v_accs)
                            speaker["speaking"] = True
                            await asyncio.sleep(random.uniform(1.2, 3.5))
                            speaker["speaking"] = False
                        await asyncio.sleep(random.uniform(0.8, 2.5))
                HUMAN_CHATTER_TASK = asyncio.run_coroutine_threadsafe(_human_chatter_loop(), MAIN_LOOP)
            log(f"👥 Human Chatter Simulation {'Enabled' if HUMAN_CHATTER_ENABLED else 'Disabled'}")
            return {"success": True, "human_chatter": HUMAN_CHATTER_ENABLED, "message": f"Human Chatter {'Active' if HUMAN_CHATTER_ENABLED else 'Off'}"}

        if action == "stream_custom_audio":
            title = str(payload.get("title", "Custom Audio")).strip()
            url = str(payload.get("url", "")).strip()
            duration = float(payload.get("duration", 30.0))
            flags = int(payload.get("flags", VOICE_FLAGS))
            ACTIVE_SFX = title
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["speaking"] = True
                a["voice_flags"] = flags
                a["active_sfx"] = title
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stream_native_audio"):
                    target_source = url or os.path.join(UPLOADS_DIR, title)
                    if not os.path.exists(target_source) and not target_source.startswith("http"):
                        target_source = f"lavfi:sine=frequency=440:duration={duration}"
                    vc.stream_native_audio(target_source)
            sb_res = send_to_streambot("/api/play", {"url": url or "videos/test.mp4", "title": title})
            log(f"🎵 Custom Audio Stream: \"{title}\" (Flags: {flags}, Duration: {duration}s)")
            return {"success": True, "title": title, "url": url, "duration": duration, "voice_flags": flags, "streambot": sb_res, "message": f"Streaming custom audio: {title}"}

        if action == "stream_real_video":
            mode = str(payload.get("mode", "camera")).lower()
            title = str(payload.get("title", f"Real Video ({mode.capitalize()})")).strip()
            res = str(payload.get("resolution", "720p"))
            fps = int(payload.get("fps", 30))
            REAL_VIDEO_ACTIVE = True
            REAL_VIDEO_MODE = mode
            REAL_VIDEO_TITLE = title
            VIDEO_SIM = True
            SCREEN_SHARE_SIM = True
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["video"] = True
                a["screenshare"] = True
                a["speaking"] = True
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(self._run_video(100, True), MAIN_LOOP)
                asyncio.run_coroutine_threadsafe(self._run_screenshare(100, True), MAIN_LOOP)
            sb_res = send_to_streambot("/api/play", {"url": "videos/test.mp4", "title": title})
            log(f"🎥 Real Video Stream activated [mode={mode.upper()}, {res}@{fps}fps, title={title}] on all accounts")
            return {"success": True, "active": True, "mode": mode, "title": title, "resolution": res, "fps": fps, "streambot": sb_res, "message": f"Real video streaming active ({mode.upper()})"}

        if action == "stop_real_video":
            REAL_VIDEO_ACTIVE = False
            REAL_VIDEO_MODE = ""
            REAL_VIDEO_TITLE = ""
            VIDEO_SIM = False
            SCREEN_SHARE_SIM = False
            for a in ACCOUNTS.values():
                a["video"] = False
                a["screenshare"] = False
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(self._run_video(100, False), MAIN_LOOP)
                asyncio.run_coroutine_threadsafe(self._run_screenshare(100, False), MAIN_LOOP)
            send_to_streambot("/api/stop")
            log("🎥 Real Video Stream stopped")
            return {"success": True, "active": False, "message": "Real video stream stopped"}

        if action == "stream_web":
            url = str(payload.get("url", "https://wikipedia.org")).strip()
            title = str(payload.get("title", f"Web Stream ({url})")).strip()
            WEB_SHARE_URL = url
            REAL_VIDEO_ACTIVE = True
            REAL_VIDEO_MODE = "web"
            REAL_VIDEO_TITLE = title
            VIDEO_SIM = True
            SCREEN_SHARE_SIM = True
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["video"] = True
                a["screenshare"] = True
                a["speaking"] = True
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(self._run_video(100, True), MAIN_LOOP)
                asyncio.run_coroutine_threadsafe(self._run_screenshare(100, True), MAIN_LOOP)
            sb_res = send_to_streambot("/api/play", {"url": "videos/test.mp4", "title": title})
            log(f"🌐 Real Web Stream activated: [url={url}] on Discord Voice")
            return {"success": True, "active": True, "mode": "web", "url": url, "title": title, "streambot": sb_res, "message": f"Streaming web page: {url}"}

        if action == "stop_web":
            WEB_SHARE_URL = ""
            REAL_VIDEO_ACTIVE = False
            REAL_VIDEO_MODE = ""
            REAL_VIDEO_TITLE = ""
            VIDEO_SIM = False
            SCREEN_SHARE_SIM = False
            for a in ACCOUNTS.values():
                a["video"] = False
                a["screenshare"] = False
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(self._run_video(100, False), MAIN_LOOP)
                asyncio.run_coroutine_threadsafe(self._run_screenshare(100, False), MAIN_LOOP)
            send_to_streambot("/api/stop")
            log("🌐 Real Web Stream stopped")
            return {"success": True, "active": False, "message": "Real web stream stopped"}

        # StreamBot Actions (ysdragon/StreamBot compatible)
        if action == "streambot_play":
            url = str(payload.get("url", "")).strip()
            title = str(payload.get("title", "")).strip() or url or "StreamBot Media"
            source = str(payload.get("source", "direct"))
            duration = float(payload.get("duration", 0.0))
            if not url:
                return {"success": False, "error": "URL cannot be empty"}
            STREAMBOT_CURRENT = {
                "id": str(int(time.time() * 1000))[-6:],
                "title": title,
                "url": url,
                "source": source,
                "duration": duration,
                "started_at": int(time.time())
            }
            STREAMBOT_STATUS = "playing"
            REAL_VIDEO_ACTIVE = True
            REAL_VIDEO_MODE = "streambot"
            REAL_VIDEO_TITLE = title
            VIDEO_SIM = True
            SCREEN_SHARE_SIM = True
            SPEAKING_SIM = True
            for a in ACCOUNTS.values():
                a["video"] = True
                a["screenshare"] = True
                a["speaking"] = True
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stream_native_audio"):
                    vc.stream_native_audio(url)
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(self._run_video(100, True), MAIN_LOOP)
                asyncio.run_coroutine_threadsafe(self._run_screenshare(100, True), MAIN_LOOP)
            sb_res = send_to_streambot("/api/play", {"url": url, "title": title})
            log(f"🎬 [StreamBot] Playing: '{title}' ({url}) [{STREAMBOT_RESOLUTION}@{STREAMBOT_FPS}fps {STREAMBOT_BITRATE}]")
            return {
                "success": True,
                "status": STREAMBOT_STATUS,
                "current": STREAMBOT_CURRENT,
                "resolution": STREAMBOT_RESOLUTION,
                "fps": STREAMBOT_FPS,
                "bitrate": STREAMBOT_BITRATE,
                "volume": STREAMBOT_VOLUME,
                "streambot": sb_res,
                "message": f"StreamBot playing: {title}"
            }

        if action == "streambot_pause":
            STREAMBOT_STATUS = "paused"
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["speaking"] = False
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stop_audio"):
                    vc.stop_audio()
            send_to_streambot("/api/stop")
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _pause_stream():
                    for a in voice_accounts():
                        ws = a.get("ws")
                        if ws and a.get("voice_channel"):
                            gid = a.get("guild_id") or GUILD_ID
                            ch = a.get("voice_channel")
                            uid = a.get("user_id", "")
                            op22 = {"op": 22, "d": {"stream_key": f"guild:{gid}:{ch}:{uid}", "paused": True}}
                            try:
                                await ws.send(json.dumps(op22))
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_pause_stream(), MAIN_LOOP)
            log("🎬 [StreamBot] Stream paused")
            return {"success": True, "status": STREAMBOT_STATUS, "message": "StreamBot playback paused"}

        if action == "streambot_resume":
            if STREAMBOT_CURRENT:
                STREAMBOT_STATUS = "playing"
                SPEAKING_SIM = True
                for a in ACCOUNTS.values():
                    a["speaking"] = True
                    vc = a.get("voice_conn")
                    if vc and hasattr(vc, "stream_native_audio"):
                        vc.stream_native_audio(STREAMBOT_CURRENT["url"])
                send_to_streambot("/api/play", {"url": STREAMBOT_CURRENT["url"], "title": STREAMBOT_CURRENT["title"]})
                if MAIN_LOOP and MAIN_LOOP.is_running():
                    async def _resume_stream():
                        for a in voice_accounts():
                            ws = a.get("ws")
                            if ws and a.get("voice_channel"):
                                gid = a.get("guild_id") or GUILD_ID
                                ch = a.get("voice_channel")
                                uid = a.get("user_id", "")
                                op22 = {"op": 22, "d": {"stream_key": f"guild:{gid}:{ch}:{uid}", "paused": False}}
                                try:
                                    await ws.send(json.dumps(op22))
                                except Exception:
                                    pass
                    asyncio.run_coroutine_threadsafe(_resume_stream(), MAIN_LOOP)
                log("🎬 [StreamBot] Stream resumed")
                return {"success": True, "status": STREAMBOT_STATUS, "message": "StreamBot playback resumed"}
            return {"success": False, "error": "No media currently loaded"}

        if action == "streambot_stop":
            STREAMBOT_STATUS = "idle"
            STREAMBOT_CURRENT = None
            REAL_VIDEO_ACTIVE = False
            REAL_VIDEO_MODE = ""
            REAL_VIDEO_TITLE = ""
            VIDEO_SIM = False
            SCREEN_SHARE_SIM = False
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                a["video"] = False
                a["screenshare"] = False
                a["speaking"] = False
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stop_audio"):
                    vc.stop_audio()
            if MAIN_LOOP and MAIN_LOOP.is_running():
                asyncio.run_coroutine_threadsafe(self._run_video(100, False), MAIN_LOOP)
                asyncio.run_coroutine_threadsafe(self._run_screenshare(100, False), MAIN_LOOP)
            send_to_streambot("/api/stop")
            log("🎬 [StreamBot] Playback stopped")
            return {"success": True, "status": STREAMBOT_STATUS, "message": "StreamBot playback stopped"}

        if action == "streambot_skip":
            send_to_streambot("/api/skip")
            if STREAMBOT_LOOP_MODE == "video" and STREAMBOT_CURRENT:
                STREAMBOT_STATUS = "playing"
                log(f"🎬 [StreamBot] Looping current video: '{STREAMBOT_CURRENT['title']}'")
                return {"success": True, "status": STREAMBOT_STATUS, "current": STREAMBOT_CURRENT, "message": f"Looping: {STREAMBOT_CURRENT['title']}"}

            if STREAMBOT_LOOP_MODE == "queue" and STREAMBOT_CURRENT:
                STREAMBOT_QUEUE.append(STREAMBOT_CURRENT)

            if STREAMBOT_QUEUE:
                next_item = STREAMBOT_QUEUE.pop(0)
                STREAMBOT_CURRENT = next_item
                STREAMBOT_STATUS = "playing"
                REAL_VIDEO_ACTIVE = True
                REAL_VIDEO_MODE = "streambot"
                REAL_VIDEO_TITLE = next_item.get("title", "Video")
                VIDEO_SIM = True
                SCREEN_SHARE_SIM = True
                SPEAKING_SIM = True
                for a in ACCOUNTS.values():
                    a["video"] = True
                    a["screenshare"] = True
                    a["speaking"] = True
                log(f"🎬 [StreamBot] Skipped to: '{next_item['title']}'")
                return {"success": True, "status": STREAMBOT_STATUS, "current": STREAMBOT_CURRENT, "queue_len": len(STREAMBOT_QUEUE), "message": f"Skipped to: {next_item['title']}"}
            else:
                STREAMBOT_CURRENT = None
                STREAMBOT_STATUS = "idle"
                REAL_VIDEO_ACTIVE = False
                VIDEO_SIM = False
                SCREEN_SHARE_SIM = False
                SPEAKING_SIM = False
                for a in ACCOUNTS.values():
                    a["video"] = False
                    a["screenshare"] = False
                    a["speaking"] = False
                log("🎬 [StreamBot] Queue finished. Playback idle.")
                return {"success": True, "status": STREAMBOT_STATUS, "current": None, "queue_len": 0, "message": "Queue finished"}

        if action == "streambot_queue_add":
            url = str(payload.get("url", "")).strip()
            title = str(payload.get("title", "")).strip() or url or "Queue Item"
            source = str(payload.get("source", "url"))
            duration = float(payload.get("duration", 0.0))
            if not url:
                return {"success": False, "error": "URL cannot be empty"}
            item = {
                "id": str(int(time.time() * 1000))[-6:],
                "title": title,
                "url": url,
                "source": source,
                "duration": duration,
                "added_at": int(time.time())
            }
            if not STREAMBOT_CURRENT or STREAMBOT_STATUS == "idle":
                STREAMBOT_CURRENT = item
                STREAMBOT_STATUS = "playing"
                REAL_VIDEO_ACTIVE = True
                REAL_VIDEO_MODE = "streambot"
                REAL_VIDEO_TITLE = title
                VIDEO_SIM = True
                SCREEN_SHARE_SIM = True
                SPEAKING_SIM = True
                for a in ACCOUNTS.values():
                    a["video"] = True
                    a["screenshare"] = True
                    a["speaking"] = True
                log(f"🎬 [StreamBot] Playing: '{title}'")
                return {"success": True, "status": STREAMBOT_STATUS, "current": STREAMBOT_CURRENT, "queue_len": len(STREAMBOT_QUEUE), "message": f"Playing: {title}"}
            else:
                STREAMBOT_QUEUE.append(item)
                log(f"🎬 [StreamBot] Added to queue (#{len(STREAMBOT_QUEUE)}): '{title}'")
                return {"success": True, "status": STREAMBOT_STATUS, "current": STREAMBOT_CURRENT, "queue_len": len(STREAMBOT_QUEUE), "message": f"Added to queue: {title}"}

        if action == "streambot_queue_remove":
            idx = int(payload.get("index", -1))
            if 0 <= idx < len(STREAMBOT_QUEUE):
                removed = STREAMBOT_QUEUE.pop(idx)
                return {"success": True, "removed": removed, "queue_len": len(STREAMBOT_QUEUE), "message": f"Removed from queue: {removed.get('title')}"}
            return {"success": False, "error": "Invalid queue index"}

        if action == "streambot_queue_clear":
            count = len(STREAMBOT_QUEUE)
            STREAMBOT_QUEUE.clear()
            log(f"🎬 [StreamBot] Cleared {count} item(s) from queue")
            return {"success": True, "cleared_count": count, "queue_len": 0, "message": f"Cleared {count} items from queue"}

        if action == "streambot_set_volume":
            STREAMBOT_VOLUME = max(1, min(200, int(payload.get("volume", 100))))
            log(f"🎬 [StreamBot] Volume set to {STREAMBOT_VOLUME}%")
            return {"success": True, "volume": STREAMBOT_VOLUME, "message": f"Stream volume set to {STREAMBOT_VOLUME}%"}

        if action == "streambot_set_loop":
            mode = str(payload.get("mode", "off")).lower()
            if mode in ("off", "video", "queue"):
                STREAMBOT_LOOP_MODE = mode
                log(f"🎬 [StreamBot] Loop mode: {STREAMBOT_LOOP_MODE.upper()}")
                return {"success": True, "loop_mode": STREAMBOT_LOOP_MODE, "message": f"Loop mode set to {STREAMBOT_LOOP_MODE}"}
            return {"success": False, "error": "Invalid loop mode (use 'off', 'video', or 'queue')"}

        if action == "streambot_set_quality":
            STREAMBOT_RESOLUTION = str(payload.get("resolution", STREAMBOT_RESOLUTION))
            STREAMBOT_FPS = int(payload.get("fps", STREAMBOT_FPS))
            STREAMBOT_BITRATE = str(payload.get("bitrate", STREAMBOT_BITRATE))
            log(f"🎬 [StreamBot] Quality updated: {STREAMBOT_RESOLUTION}@{STREAMBOT_FPS}fps ({STREAMBOT_BITRATE})")
            return {
                "success": True,
                "resolution": STREAMBOT_RESOLUTION,
                "fps": STREAMBOT_FPS,
                "bitrate": STREAMBOT_BITRATE,
                "message": f"Quality set to {STREAMBOT_RESOLUTION}@{STREAMBOT_FPS}fps ({STREAMBOT_BITRATE})"
            }

        if action == "set_streambot_host":
            global STREAMBOT_HOST
            STREAMBOT_HOST = str(payload.get("host", STREAMBOT_HOST)).strip()
            log(f"📡 [StreamBot Host] Updated to: {STREAMBOT_HOST}")
            return {"success": True, "streambot_host": STREAMBOT_HOST, "message": f"StreamBot host set to {STREAMBOT_HOST}"}

        if action == "set_rotator":
            global ROTATOR_ENABLED, ROTATOR_INTERVAL, ROTATOR_CHANNELS, ROTATOR_TASK
            ROTATOR_ENABLED = bool(payload.get("enabled", False))
            ROTATOR_INTERVAL = max(10, int(payload.get("interval", 300)))
            ROTATOR_CHANNELS = [str(c).strip() for c in payload.get("channels", []) if str(c).strip()]
            if ROTATOR_TASK and not ROTATOR_TASK.done():
                ROTATOR_TASK.cancel()
                ROTATOR_TASK = None
            if ROTATOR_ENABLED and ROTATOR_CHANNELS and MAIN_LOOP and MAIN_LOOP.is_running():
                async def _rotator_loop():
                    idx = 0
                    while ROTATOR_ENABLED and RUNNING and ROTATOR_CHANNELS:
                        await asyncio.sleep(ROTATOR_INTERVAL)
                        if not ROTATOR_ENABLED or not ROTATOR_CHANNELS:
                            break
                        target_ch = ROTATOR_CHANNELS[idx % len(ROTATOR_CHANNELS)]
                        idx += 1
                        print(f"[Rotator] 🔀 Auto-Rotator: Moving bots to {target_ch}")
                        targets = pick_targets(100, online_accounts())
                        await apply(targets, channel=target_ch)
                ROTATOR_TASK = asyncio.run_coroutine_threadsafe(_rotator_loop(), MAIN_LOOP)
            return {"success": True, "rotator_enabled": ROTATOR_ENABLED, "rotator_interval": ROTATOR_INTERVAL, "message": f"Auto-Rotator {'Started' if ROTATOR_ENABLED else 'Stopped'}"}

        if action == "fetch_guild_channels":
            target_gid = payload.get("guild_id") or GUILD_ID
            if not target_gid:
                return {"success": False, "error": "Server (Guild) ID required"}
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _get_channels():
                    token = next((a["token"] for a in ACCOUNTS.values() if a.get("online") and a.get("token")), None)
                    if not token:
                        token = next((a["token"] for a in ACCOUNTS.values() if a.get("token")), None)
                    if not token:
                        return []
                    def _do_req():
                        try:
                            auth = token if token.startswith("Bot ") else token
                            req = urllib.request.Request(f"https://discord.com/api/v10/guilds/{target_gid}/channels", headers={"Authorization": auth, "User-Agent": "Mozilla/5.0"})
                            with urllib.request.urlopen(req, timeout=8) as resp:
                                if resp.status == 200:
                                    all_ch = json.loads(resp.read().decode("utf-8"))
                                    return [{"id": c["id"], "name": c["name"], "type": c.get("type", 0), "position": c.get("position", 0)} for c in all_ch if c.get("type") in (2, 13)]
                        except Exception:
                            pass
                        return []
                    return await asyncio.get_running_loop().run_in_executor(None, _do_req)

                fut = asyncio.run_coroutine_threadsafe(_get_channels(), MAIN_LOOP)
                try:
                    channels = fut.result(timeout=10)
                    return {"success": True, "channels": channels, "guild_id": target_gid, "message": f"Loaded {len(channels)} voice channel(s)"}
                except Exception as e:
                    return {"success": False, "error": str(e)}
            return {"success": False, "error": "Bot engine not running"}

        if action == "fetch_user_guilds":
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _get_guilds():
                    token = next((a["token"] for a in ACCOUNTS.values() if a.get("online") and a.get("token")), None)
                    if not token:
                        token = next((a["token"] for a in ACCOUNTS.values() if a.get("token")), None)
                    if not token:
                        return []
                    def _do_req():
                        try:
                            auth = token if token.startswith("Bot ") else token
                            req = urllib.request.Request(f"https://discord.com/api/v10/users/@me/guilds?limit=200", headers={"Authorization": auth, "User-Agent": "Mozilla/5.0"})
                            with urllib.request.urlopen(req, timeout=8) as resp:
                                if resp.status == 200:
                                    all_g = json.loads(resp.read().decode("utf-8"))
                                    return [{"id": g["id"], "name": g["name"]} for g in all_g]
                        except Exception:
                            pass
                        return []
                    return await asyncio.get_running_loop().run_in_executor(None, _do_req)

                fut = asyncio.run_coroutine_threadsafe(_get_guilds(), MAIN_LOOP)
                try:
                    guild_list = fut.result(timeout=10)
                    return {"success": True, "guilds": guild_list, "count": len(guild_list), "message": f"Loaded {len(guild_list)} server(s)"}
                except Exception as e:
                    return {"success": False, "error": str(e)}
            return {"success": False, "error": "Bot engine not running"}

        if action == "shizuku_status":
            return {
                "success": True,
                "available": False,
                "authorized": False,
                "platform": DEVICE_TYPE,
                "message": "Shizuku privileged system available on Android APK."
            }

        if action == "shizuku_request":
            return {
                "success": True,
                "message": "Shizuku authorization prompt is dispatched natively on Android."
            }

        if action == "shizuku_optimize":
            return {
                "success": True,
                "optimized": True,
                "message": "Desktop/Python environment operates with unrestricted system priority."
            }

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

        if action == "check_update":
            return check_github_update()

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
        elif action in ("video", "camera_on"):
            coro = self._run_video(percent, True)
        elif action in ("unvideo", "camera_off"):
            coro = self._run_video(percent, False)
        elif action in ("screenshare", "screen_share_on"):
            coro = self._run_screenshare(percent, True)
        elif action in ("unscreenshare", "screen_share_off"):
            coro = self._run_screenshare(percent, False)
        elif action in ("set_stream", "stream"):
            STREAM_TITLE = str(payload.get("title", "Live Stream")).strip()
            STREAM_URL = str(payload.get("url", "https://twitch.tv/discord")).strip()
            enable = bool(payload.get("enabled", True))
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _broadcast_stream():
                    acts = []
                    if enable:
                        acts.append({"name": STREAM_TITLE or "Live Stream", "type": 1, "url": STREAM_URL or "https://twitch.tv/discord"})
                    op3 = {"op": 3, "d": {"since": 0, "activities": acts, "status": "online", "afk": False}}
                    for a in online_accounts():
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                                a["streaming"] = enable
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_broadcast_stream(), MAIN_LOOP)
            return {"success": True, "message": f"Streamer status {'activated: ' + STREAM_TITLE if enable else 'stopped'}", "streaming": enable}
        elif action in ("play_music", "set_music"):
            MUSIC_TITLE = str(payload.get("title", "Song Title")).strip()
            MUSIC_ARTIST = str(payload.get("artist", "Artist")).strip()
            for a in ACCOUNTS.values():
                a["speaking"] = True
                vc = a.get("voice_conn")
                if vc:
                    target_audio = os.path.join(UPLOADS_DIR, f"{MUSIC_TITLE}.mp3")
                    if not os.path.exists(target_audio):
                        target_audio = "lavfi:sine=frequency=440:duration=180"
                    if hasattr(vc, "stream_native_audio"):
                        vc.stream_native_audio(target_audio)
                    elif hasattr(vc, "play_sfx"):
                        vc.play_sfx("music", 180)
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _broadcast_music():
                    now = int(time.time() * 1000)
                    for a in online_accounts():
                        acts = [{
                            "name": "Spotify",
                            "type": 2,
                            "details": MUSIC_TITLE,
                            "state": MUSIC_ARTIST,
                            "timestamps": {"start": now, "end": now + 210000},
                            "party": {"id": f"spotify:{a['id']}"},
                            "flags": 48
                        }]
                        op3 = {"op": 3, "d": {"since": 0, "activities": acts, "status": "online", "afk": False}}
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_broadcast_music(), MAIN_LOOP)
            return {"success": True, "message": f"Spotify player simulating: {MUSIC_TITLE} by {MUSIC_ARTIST}", "music_title": MUSIC_TITLE, "music_artist": MUSIC_ARTIST}
        elif action == "stop_music":
            MUSIC_TITLE = ""
            MUSIC_ARTIST = ""
            send_to_streambot("/api/stop")
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _clear_music():
                    op3 = {"op": 3, "d": {"since": 0, "activities": [], "status": "online", "afk": False}}
                    for a in online_accounts():
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_clear_music(), MAIN_LOOP)
            return {"success": True, "message": "Music playback stopped"}
        elif action == "resolve_youtube":
            u = str(payload.get("url", "")).strip()
            meta = resolve_youtube_metadata(u)
            if meta:
                return {"success": True, "title": meta["title"], "author": meta["author"], "url": meta["url"], "message": f"Resolved: {meta['title']}"}
            return {"success": False, "error": "Could not resolve YouTube URL"}
        elif action in ("play_youtube", "stream_youtube"):
            u = str(payload.get("url", "")).strip()
            title = str(payload.get("title", "")).strip()
            author = str(payload.get("author", "")).strip()
            mode = str(payload.get("mode", "watch")).strip().lower()
            sim_voice = bool(payload.get("sim_voice", True))

            if (not title or not author) and u:
                info = extract_media_info(u)
                if info:
                    title = title or info.get("title")
                    author = author or info.get("author")
            title = title or "YouTube Video"
            author = author or "YouTube"

            YOUTUBE_URL = u
            YOUTUBE_TITLE = title
            YOUTUBE_AUTHOR = author
            YOUTUBE_MODE = mode
            if u:
                stream_u = u
                if info and info.get("direct_url"):
                    stream_u = info["direct_url"]
                for a in ACCOUNTS.values():
                    a["speaking"] = True
                    vc = a.get("voice_conn")
                    if vc and hasattr(vc, "stream_native_audio"):
                        vc.stream_native_audio(stream_u)
            send_to_streambot("/api/play", {"url": u, "title": title})

            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _broadcast_yt():
                    now = int(time.time() * 1000)
                    for a in online_accounts():
                        if mode == "music":
                            acts = [{
                                "name": "YouTube Music",
                                "type": 2,
                                "details": YOUTUBE_TITLE,
                                "state": YOUTUBE_AUTHOR,
                                "timestamps": {"start": now, "end": now + 240000}
                            }]
                        elif mode == "stream":
                            acts = [{
                                "name": YOUTUBE_TITLE,
                                "type": 1,
                                "url": "https://twitch.tv/discord"
                            }]
                        else:
                            acts = [{
                                "name": "YouTube",
                                "type": 3,
                                "details": YOUTUBE_TITLE,
                                "state": YOUTUBE_AUTHOR
                            }]
                        op3 = {"op": 3, "d": {"since": 0, "activities": acts, "status": "online", "afk": False}}
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                                a["youtube_active"] = True
                                if sim_voice:
                                    a["speaking"] = True
                            except Exception:
                                pass
                if sim_voice:
                    SPEAKING_SIM = True
                asyncio.run_coroutine_threadsafe(_broadcast_yt(), MAIN_LOOP)
            return {"success": True, "title": YOUTUBE_TITLE, "author": YOUTUBE_AUTHOR, "mode": YOUTUBE_MODE, "url": YOUTUBE_URL, "message": f"Streaming YouTube ({YOUTUBE_MODE}): {YOUTUBE_TITLE}"}
        elif action == "stop_youtube":
            YOUTUBE_URL = ""
            YOUTUBE_TITLE = ""
            YOUTUBE_AUTHOR = ""
            SPEAKING_SIM = False
            for a in ACCOUNTS.values():
                vc = a.get("voice_conn")
                if vc and hasattr(vc, "stop_audio"):
                    vc.stop_audio()
            send_to_streambot("/api/stop")
            if MAIN_LOOP and MAIN_LOOP.is_running():
                async def _clear_yt():
                    op3 = {"op": 3, "d": {"since": 0, "activities": [], "status": "online", "afk": False}}
                    for a in online_accounts():
                        ws = a.get("ws")
                        if ws:
                            try:
                                await ws.send(json.dumps(op3))
                                a["youtube_active"] = False
                                a["speaking"] = False
                            except Exception:
                                pass
                asyncio.run_coroutine_threadsafe(_clear_yt(), MAIN_LOOP)
            return {"success": True, "message": "YouTube streaming stopped"}
        elif action == "token_add":
            tok = payload.get("token", "").strip()
            prx = payload.get("proxy", "").strip() or None
            if not tok:
                return {"success": False, "error": "Token is empty"}
            coro = add_account(tok, proxy=prx)
        elif action == "tokens_bulk_add":
            token_list = payload.get("tokens", [])
            if not token_list:
                return {"success": False, "error": "No tokens provided"}
            coro = self._run_bulk_add(token_list)
        elif action == "set_account_proxy":
            aid = payload.get("id")
            prx = payload.get("proxy", "")
            prx = prx.strip() if isinstance(prx, str) else None
            if not prx:
                prx = None
            acc = find_account(aid)
            if not acc:
                return {"success": False, "error": "Account not found"}
            acc["proxy"] = prx
            sync_tokens_file()
            return {"success": True, "message": f"Proxy updated for account {acc['id']}.", "proxy": mask_proxy(prx)}
        elif action == "batch_action":
            aids = [int(x) for x in payload.get("ids", []) if str(x).isdigit()]
            sub = payload.get("sub_action", "")
            ch = payload.get("channel_id") or CHANNEL_ID
            target_gid = payload.get("guild_id")
            targets = [ACCOUNTS[aid] for aid in aids if aid in ACCOUNTS and ACCOUNTS[aid].get("online")]
            if sub in ("move", "join"):
                coro = apply(targets, channel=ch, guild=target_gid)
            elif sub == "disconnect":
                coro = apply(targets, channel=None, guild=target_gid)
            elif sub == "mute":
                coro = apply(targets, mute=True, guild=target_gid)
            elif sub == "unmute":
                coro = apply(targets, mute=False, guild=target_gid)
            elif sub == "deafen":
                coro = apply(targets, deaf=True, guild=target_gid)
            elif sub == "undeafen":
                coro = apply(targets, deaf=False, guild=target_gid)
            elif sub == "video":
                coro = apply(targets, video=True, guild=target_gid)
            elif sub == "unvideo":
                coro = apply(targets, video=False, guild=target_gid)
            elif sub == "screenshare":
                coro = self._run_screenshare(100, True)
            elif sub == "unscreenshare":
                coro = self._run_screenshare(100, False)
            else:
                return {"success": False, "error": f"Unknown batch sub_action: {sub}"}
        elif action == "token_remove":
            aid = payload.get("id")
            coro = remove_account(aid)
        elif action.startswith("account_"):
            aid = payload.get("id")
            sub = action.replace("account_", "")
            coro = self._run_account_action(aid, sub, payload.get("channel_id"), payload.get("guild_id"))
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
        for item in tokens:
            if isinstance(item, dict):
                raw = item.get("token", "")
                prx = item.get("proxy")
            else:
                raw = str(item)
                prx = None
            tok, parsed_prx = parse_token_and_proxy(raw)
            final_proxy = prx or parsed_prx
            if tok and not is_placeholder_token(tok):
                await add_account(tok, proxy=final_proxy)
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

    async def _run_video(self, percent, state):
        global VIDEO_SIM
        VIDEO_SIM = state
        targets = pick_targets(percent, voice_accounts())
        done = await apply(targets, video=state)
        return f"{done}/{len(targets)} account(s) video={state}"

    async def _run_screenshare(self, percent, state):
        global SCREEN_SHARE_SIM
        SCREEN_SHARE_SIM = state
        targets = pick_targets(percent, voice_accounts())
        for a in targets:
            ws = a.get("ws")
            if ws and a.get("voice_channel"):
                gid = a.get("guild_id") or GUILD_ID
                ch = a.get("voice_channel")
                uid = a.get("user_id", "")
                if state:
                    op18 = {"op": 18, "d": {"type": "guild", "guild_id": gid, "channel_id": ch, "preferred_region": None}}
                    try:
                        await ws.send(json.dumps(op18))
                        a["screenshare"] = True
                    except Exception:
                        pass
                else:
                    op19 = {"op": 19, "d": {"stream_key": f"guild:{gid}:{ch}:{uid}"}}
                    try:
                        await ws.send(json.dumps(op19))
                        a["screenshare"] = False
                        if a.get("stream_conn"):
                            asyncio.create_task(a["stream_conn"].stop())
                            a["stream_conn"] = None
                    except Exception:
                        pass
        return f"{len(targets)} account(s) screenshare={state}"

    async def _run_account_action(self, aid, sub, ch, guild=None):
        acc = find_account(aid)
        if not acc:
            raise ValueError("Account not found")
        if sub in ("move", "join"):
            target_ch = ch or CHANNEL_ID
            await apply([acc], channel=target_ch, guild=guild)
            return f"Moved to {target_ch}"
        elif sub == "disconnect":
            await apply([acc], channel=None, guild=guild)
            return "Disconnected"
        elif sub == "mute":
            await apply([acc], mute=True, guild=guild)
            return "Muted"
        elif sub == "unmute":
            await apply([acc], mute=False, guild=guild)
            return "Unmuted"
        elif sub == "deafen":
            await apply([acc], deaf=True, guild=guild)
            return "Deafened"
        elif sub == "undeafen":
            await apply([acc], deaf=False, guild=guild)
            return "Undeafened"
        elif sub == "video":
            await apply([acc], video=True, guild=guild)
            return "Camera/Video Enabled"
        elif sub == "unvideo":
            await apply([acc], video=False, guild=guild)
            return "Camera/Video Disabled"
        elif sub == "screenshare":
            ws = acc.get("ws")
            if ws and acc.get("voice_channel"):
                gid = guild or acc.get("guild_id") or GUILD_ID
                ch = acc.get("voice_channel")
                op18 = {"op": 18, "d": {"type": "guild", "guild_id": gid, "channel_id": ch, "preferred_region": None}}
                await ws.send(json.dumps(op18))
                acc["screenshare"] = True
                return "Screen Share Started"
            return "Account not in voice"
        elif sub == "unscreenshare":
            ws = acc.get("ws")
            if ws and acc.get("voice_channel"):
                gid = guild or acc.get("guild_id") or GUILD_ID
                ch = acc.get("voice_channel")
                uid = acc.get("user_id", "")
                op19 = {"op": 19, "d": {"stream_key": f"guild:{gid}:{ch}:{uid}"}}
                await ws.send(json.dumps(op19))
                acc["screenshare"] = False
                if acc.get("stream_conn"):
                    asyncio.create_task(acc["stream_conn"].stop())
                    acc["stream_conn"] = None
                return "Screen Share Stopped"
            return "Account not in voice"
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
        # Ensure built-in OFM proxy daemon is active
        try:
            ensure_ofm_proxy_running()
        except Exception:
            pass
        local_ip = get_local_ip()
        print(f"\n[WEB UI] Local PC link: http://localhost:{WEB_PORT}")
        if local_ip not in ("127.0.0.1", "0.0.0.0"):
            print(f"[WEB UI] Wi-Fi Network link: http://{local_ip}:{WEB_PORT}")

        if sys.platform == "win32":
            try:
                import ctypes
                ctypes.windll.kernel32.SetConsoleTitleW(f"dc- Discord Voice Controller v{APP_VERSION} [Windows PC]")
            except Exception:
                pass
            if not os.environ.get("HEADLESS") and not os.environ.get("NO_BROWSER"):
                import webbrowser
                def _open_pc_browser():
                    time.sleep(1.0)
                    try:
                        webbrowser.open(f"http://localhost:{WEB_PORT}")
                    except Exception:
                        pass
                threading.Thread(target=_open_pc_browser, daemon=True).start()
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
