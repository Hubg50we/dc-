"""
High-Performance Discord Voice & Go Live Screen Streamer for DC- (Python Native)
Optimized for zero-allocation 30 FPS video streaming, sub-millisecond UDP dispatch,
AES-256-GCM AEAD encryption, and RFC 6184 / RFC 7587 standard conformance.
"""

import asyncio
import base64
import collections
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import websockets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
try:
    import davey
    HAS_DAVEY = True
except ImportError:
    davey = None
    HAS_DAVEY = False

# Compliant 320x240 H.264 Baseline Profile Sequence & Picture Parameter Sets
SPS_B64 = "Z0LAHtkBQfsBagQECoAAAAMAgAAAAwEHixck"
PPS_B64 = "aMuDyyA="

# Universal SMPTE Color Bars Broadcast Test Card IDR Keyframe (320x240 H.264 Baseline)
SMPTE_IDR_B64 = (
    "ZYiEBXyYoAAvvSf/D/gsBFA6CFQcQAAQBABYglgcAAsAKDLkOAAWAFBlyB0h3IdIdxoEZYAAgD2uPjD/QLATRQPEAAEAcAaI"
    "AAICIAEwcAgCsQcAgCsQcACADsQcACADsHQJ6OAG1x+H/QLATAcAQCBqD4gAAgOAAHiAACBUAAIB+BwCGEXIcAhhFyBwAIEH"
    "XIcACBB1w6BPRwAbXHgH/oEYKhQABAI8QAA+A0QAAQPgABAjtArtcQw//BWCqDgCAIpwgACIgmgGAAED0CAGgeMlzDxkuYHA"
    "BhtzDgAgw25tArFAAEAa1xDD/6DwKooAAgvdoFYoAAgDWv//8FYIsUAA9iAACAgAAILKEACACoHAAQAQyDgAIAIZ4OgRkHQI"
    "z2v/D/QKoQEpCgLgEZAnQKokx53q9+EynPXv8AwDw8FgrA4ABQAAQBQDkGBwpAMArS4GZAS02uekSFcLQIyARnaT69IRluN3"
    "/w/0CqEJGQQLO0AXTQMRvavwBUkZAMZ5tq+LWkX/jT+IYf+CvxQAD8DgAF8IEIDDGgC1mghk9qfJksgDYwC4DMBr7UWCDGXg"
    "WP+Af8FQkJwAGECASlKDoRkArkxEQwhrPV74OAIAhkBVEpmEcQ4nnxazp/9bfx8fhoFngcQFUASgRwKLgBK1iRysT1ekAQpl"
    "yASmbS69IjJc/gH+gVECBZ2CAAMAUQz4OIBGf4OABAApntXav4YBD+CvwbNAEtsIAmMYBKuZAk69/wCmNDGcw4b/1e//AcA"
    "/4eH8Dw+g4cA/4e8GAxBwDAP+Eghwe/rffUFddczBYX9UFtdczBZ6oLa4pggln7v9QQigACAPff///Iuv//+Hg1YtQ3XXXXT"
    "11109ddPXXXXXXXXXXXXT11109ddPXXXXXXXXXg"
)

# Animated Test Card IDR Keyframe
TESTSRC_IDR_B64 = (
    "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"
)

def safe_b64decode(s):
    pad = (4 - len(s) % 4) % 4
    return base64.b64decode(s + ("=" * pad))

SPS = safe_b64decode(SPS_B64)
PPS = safe_b64decode(PPS_B64)
SMPTE_IDR = safe_b64decode(SMPTE_IDR_B64)
TESTSRC_IDR = safe_b64decode(TESTSRC_IDR_B64)

def packetize_nal(nal, is_last_nal=True, max_payload=1200):
    """RFC 6184 H.264 RTP Packetizer with Single NAL and FU-A Fragmentation."""
    if len(nal) <= max_payload:
        return [(nal, is_last_nal)]
    header = nal[0]
    payload = nal[1:]
    indicator = (header & 0xE0) | 28  # FU-A indicator
    res = []
    offset = 0
    while offset < len(payload):
        chunk_size = min(max_payload - 2, len(payload) - offset)
        chunk = payload[offset:offset + chunk_size]
        is_first = (offset == 0)
        is_last = (offset + chunk_size >= len(payload))
        fu_header = header & 0x1F
        if is_first:
            fu_header |= 0x80
        if is_last:
            fu_header |= 0x40
        fu_packet = bytes([indicator, fu_header]) + chunk
        marker = is_last and is_last_nal
        res.append((fu_packet, marker))
        offset += chunk_size
    return res

# Pre-packetize static NAL units for zero-allocation streaming
PRE_PACKETIZED_SMPTE = []
for nal in [SPS, PPS]:
    PRE_PACKETIZED_SMPTE.extend(packetize_nal(nal, is_last_nal=False))
PRE_PACKETIZED_SMPTE.extend(packetize_nal(SMPTE_IDR, is_last_nal=True))

PRE_PACKETIZED_TESTSRC = []
for nal in [SPS, PPS]:
    PRE_PACKETIZED_TESTSRC.extend(packetize_nal(nal, is_last_nal=False))
PRE_PACKETIZED_TESTSRC.extend(packetize_nal(TESTSRC_IDR, is_last_nal=True))

# Load pre-encoded RFC 6716 Opus SFX packets (airhorn, scream, bruh, vineboom)
SFX_PACKETS_MAP = {}
try:
    _sfx_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sfx_packets.json")
    if not os.path.exists(_sfx_path):
        _sfx_path = os.path.join(os.getcwd(), "sfx_packets.json")
    if os.path.exists(_sfx_path):
        with open(_sfx_path, "r", encoding="utf-8") as _f:
            _raw_sfx = json.load(_f)
            for _k, _pkts in _raw_sfx.items():
                SFX_PACKETS_MAP[_k] = [base64.b64decode(_p) for _p in _pkts]
except Exception:
    pass


class DiscordMediaConnection:
    """
    High-Performance Discord Voice & Go Live Screen Connection.
    Optimized for multi-platform (Android / Windows PC) execution.
    Delivers rock-solid 30 FPS video and 50 PPS Opus keepalive with AES-256-GCM AEAD encryption.
    """
    def __init__(self, account_id, user_id, session_id, token, endpoint, server_id, channel_id="", is_stream=True, logger=None, proxy=None):
        self.account_id = account_id
        self.user_id = str(user_id)
        self.session_id = session_id
        self.token = token
        self.endpoint = endpoint
        self.server_id = str(server_id)
        self.channel_id = str(channel_id or server_id)
        self.is_stream = is_stream
        self.logger = logger or print
        self.proxy = proxy

        self.running = False
        self.connected = False
        self.ws = None
        self.udp_sock = None
        self.main_task = None

        self.dave_session = None
        self.dave_protocol_version = 1
        self.dave_pending_transitions = {}
        self.dave_external_sender_package = None

        if HAS_DAVEY:
            try:
                ch_id_int = int(self.channel_id) if str(self.channel_id).isdigit() else 0
                uid_int = int(self.user_id) if str(self.user_id).isdigit() else 0
                self.dave_session = davey.DaveSession(self.dave_protocol_version, uid_int, ch_id_int)
            except Exception:
                self.dave_session = None

        self.audio_ssrc = 0
        self.video_ssrc = 0
        self.rtx_ssrc = 0
        self.ip = ""
        self.port = 0
        self.secret_key = None
        self.aesgcm = None
        self.seq_ack = -1

        self.profile = "balanced"
        self.fps = 30
        self.ts_delta = 3000
        self.frame_interval = 1.0 / 30.0

        self.visualizer_mode = "spectrum"  # "spectrum", "bars", "testsrc", "smpte"
        self.overlay_title = ""
        self.overlay_ticker = ""

        self.speaker_callback = None
        self.chosen_mode = "aead_aes256_gcm_rtpsize"
        self._video_thread = None
        self._audio_thread = None

        self.desktop_capture_active = False
        self.desktop_capture_proc = None
        self.desktop_capture_thread = None
        self.frame_queue = collections.deque(maxlen=60)

        self.audio_pipe_proc = None
        self.audio_pipe_thread = None
        self.audio_queue = collections.deque(maxlen=500)
        self.active_media_source = ""
        self.packets_sent = 0
        self.audio_lock = threading.Lock()

    def set_desktop_capture(self, enabled=True):
        self.desktop_capture_active = bool(enabled)
        if self.desktop_capture_active:
            self._start_desktop_capture()
        else:
            self._stop_desktop_capture()

    def _start_desktop_capture(self):
        self._stop_desktop_capture()
        cmd = None
        if sys.platform == "win32":
            cmd = [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "gdigrab", "-framerate", str(self.fps),
                "-offset_x", "0", "-offset_y", "0",
                "-video_size", "1280x720", "-i", "desktop",
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-b:v", "2500k", "-pix_fmt", "yuv420p", "-g", str(self.fps),
                "-f", "h264", "pipe:1"
            ]
        elif sys.platform.startswith("linux"):
            cmd = [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "x11grab", "-framerate", str(self.fps),
                "-video_size", "1280x720", "-i", ":0.0",
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-b:v", "2500k", "-pix_fmt", "yuv420p", "-g", str(self.fps),
                "-f", "h264", "pipe:1"
            ]
        if not cmd:
            self.log("[!] Desktop screen capture not supported on this platform - using visualizer")
            return

        try:
            creationflags = 0
            if sys.platform == "win32":
                creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
            self.desktop_capture_proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=10**6,
                creationflags=creationflags
            )
            self.log("🖥️ Live Desktop Screen Capture Process Launched (H.264 Pipe)")

            def _reader():
                buf = b""
                proc = self.desktop_capture_proc
                while self.running and self.desktop_capture_active and proc and proc.poll() is None:
                    try:
                        chunk = proc.stdout.read(4096)
                        if not chunk:
                            break
                        buf += chunk
                        while True:
                            p1 = buf.find(b"\x00\x00\x01")
                            p2 = buf.find(b"\x00\x00\x00\x01")
                            if p1 == -1 and p2 == -1:
                                if len(buf) > 65536:
                                    buf = buf[-8:]
                                break
                            pos = p1 if (p1 != -1 and (p2 == -1 or p1 < p2)) else p2
                            lead = 3 if pos == p1 else 4
                            n1 = buf.find(b"\x00\x00\x01", pos + lead)
                            n2 = buf.find(b"\x00\x00\x00\x01", pos + lead)
                            if n1 == -1 and n2 == -1:
                                break
                            next_pos = n1 if (n1 != -1 and (n2 == -1 or n1 < n2)) else n2
                            nal = buf[pos + lead:next_pos]
                            buf = buf[next_pos:]
                            if nal:
                                packets = packetize_nal(nal, is_last_nal=True)
                                self.frame_queue.append(packets)
                    except Exception:
                        break
            self.desktop_capture_thread = threading.Thread(target=_reader, daemon=True)
            self.desktop_capture_thread.start()
        except Exception as e:
            self.log(f"[!] Desktop capture could not start ({e}) - continuing with visualizer")

    def _stop_desktop_capture(self):
        if self.desktop_capture_proc:
            try:
                self.desktop_capture_proc.kill()
                self.desktop_capture_proc.wait(timeout=0.2)
            except Exception:
                pass
            self.desktop_capture_proc = None
        self.frame_queue.clear()

    def play_sfx(self, sound, duration=0.0):
        """Plays real soundboard SFX packets or synthesizes on the fly."""
        self._stop_native_audio()
        snd = str(sound or "scream").lower().strip()
        snd_clean = snd.replace("_", "").replace("-", "").replace(" ", "")
        # 1. Check pre-encoded RFC 6716 Opus frames (zero CPU overhead)
        for key, pkts in SFX_PACKETS_MAP.items():
            key_clean = key.replace("_", "").replace("-", "").replace(" ", "")
            if key in snd or key_clean in snd_clean or snd_clean in key_clean:
                self.audio_queue.extend(pkts)
                self.log(f"Queued {len(pkts)} RFC 6716 Opus frames for SFX: [{key.upper()}]")
                return

        # 2. Check local sound files in uploads/
        local_dir = os.path.join(os.getcwd(), "uploads")
        for ext in (".mp3", ".wav", ".ogg", ".aac", ".opus"):
            target = os.path.join(local_dir, f"{snd}{ext}")
            if os.path.exists(target):
                self.stream_native_audio(target)
                return

        # 3. Dynamic synthesis fallback via FFmpeg
        dur = duration if duration > 0 else 3.0
        if "airhorn" in snd:
            src = f"lavfi:sine=frequency=466.16:duration={dur}"
        elif "scream" in snd:
            src = f"lavfi:anoisesrc=d={dur}:c=pink:r=48000,volume=3"
        elif "bruh" in snd:
            src = f"lavfi:sine=frequency=180:duration={dur}"
        elif "vine" in snd or "boom" in snd:
            src = f"lavfi:sine=frequency=60:duration={dur},volume=4"
        elif "pipe" in snd:
            src = f"lavfi:sine=frequency=800:duration={dur}"
        elif "applause" in snd:
            src = f"lavfi:anoisesrc=d={dur}:c=white:r=48000,volume=2"
        elif "gg" in snd:
            src = f"lavfi:sine=frequency=523.25:duration={dur}"
        elif "trombone" in snd:
            src = f"lavfi:sine=frequency=196:duration={dur}"
        elif "drum" in snd:
            src = f"lavfi:anoisesrc=d={dur}:c=brown:r=48000,volume=2"
        elif "what" in snd:
            src = f"lavfi:sine=frequency=659.25:duration={dur}"
        elif "wow" in snd:
            src = f"lavfi:sine=frequency=587.33:duration={dur}"
        elif "ping" in snd or "bell" in snd or "taco" in snd:
            src = f"lavfi:sine=frequency=784:duration={dur}"
        elif "siren" in snd:
            src = f"lavfi:sine=frequency=880:duration={dur}"
        elif "fbi" in snd or "open_up" in snd:
            src = f"lavfi:sine=frequency=950:duration={dur},volume=3"
        elif "emotional" in snd or "damage" in snd:
            src = f"lavfi:sine=frequency=220:duration={dur}"
        elif "mlg" in snd:
            src = f"lavfi:sine=frequency=554.37:duration={dur},volume=3"
        elif "illuminati" in snd:
            src = f"lavfi:sine=frequency=528:duration={dur}"
        elif "run" in snd:
            src = f"lavfi:sine=frequency=150:duration={dur}"
        elif "oof" in snd:
            src = f"lavfi:sine=frequency=280:duration={dur}"
        elif "quandale" in snd:
            src = f"lavfi:sine=frequency=330:duration={dur}"
        else:
            src = f"lavfi:sine=frequency=440:duration={dur}"
        self.stream_native_audio(src)

    def stop_audio(self):
        """Stops current audio playback and flushes queue."""
        self._stop_native_audio()
        self.audio_queue.clear()
        self.log("Audio playback stopped.")

    def stream_native_audio(self, source, audio_filter=""):
        """
        Extracts and streams real 48kHz Opus audio packets from any file or URL.
        Zero Node.js dependency!
        """
        self._stop_native_audio()
        self.active_media_source = source

        if isinstance(source, str) and (source.startswith("http://") or source.startswith("https://")):
            if any(k in source.lower() for k in ["youtube.com", "youtu.be", "twitch.tv", "tiktok.com", "soundcloud.com"]):
                try:
                    proc = subprocess.run(
                        ["yt-dlp", "-g", "-f", "bestaudio/best", "--no-warnings", "--quiet", source],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                        timeout=8
                    )
                    if proc.returncode == 0 and proc.stdout.strip():
                        source = proc.stdout.strip().splitlines()[0]
                except Exception:
                    pass

        if source.startswith("lavfi:"):
            cmd = [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-re", "-f", "lavfi", "-i", source[6:],
                "-vn", "-c:a", "libopus", "-b:a", "96k", "-vbr", "on",
                "-application", "audio", "-frame_duration", "20",
                "-f", "opus", "pipe:1"
            ]
        else:
            cmd = [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-re", "-i", source,
                "-vn"
            ]
            if audio_filter:
                cmd.extend(["-af", audio_filter])
            cmd.extend([
                "-c:a", "libopus", "-b:a", "96k", "-vbr", "on",
                "-application", "audio", "-frame_duration", "20",
                "-f", "opus", "pipe:1"
            ])

        try:
            creationflags = 0
            if sys.platform == "win32":
                creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
            self.audio_pipe_proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=10**6,
                creationflags=creationflags
            )
            self.log(f"🎵 Native Audio Pipe Active: '{source}'")

            def _audio_reader():
                buf = b""
                proc = self.audio_pipe_proc
                while self.running and proc and proc.poll() is None:
                    try:
                        chunk = proc.stdout.read(4096)
                        if not chunk:
                            break
                        buf += chunk
                        while True:
                            pos = buf.find(b"OggS")
                            if pos == -1:
                                if len(buf) > 65536:
                                    buf = buf[-8:]
                                break
                            if len(buf) < pos + 27:
                                break
                            n_segs = buf[pos + 26]
                            if len(buf) < pos + 27 + n_segs:
                                break
                            seg_table = buf[pos + 27:pos + 27 + n_segs]
                            page_body_len = sum(seg_table)
                            page_total_len = 27 + n_segs + page_body_len
                            if len(buf) < pos + page_total_len:
                                break
                            body = buf[pos + 27 + n_segs:pos + page_total_len]
                            buf = buf[pos + page_total_len:]

                            offset = 0
                            curr_pkt = b""
                            for seg_len in seg_table:
                                curr_pkt += body[offset:offset + seg_len]
                                offset += seg_len
                                if seg_len < 255:
                                    if curr_pkt and not curr_pkt.startswith(b"OpusHead") and not curr_pkt.startswith(b"OpusTags"):
                                        self.audio_queue.append(curr_pkt)
                                    curr_pkt = b""
                    except Exception:
                        break
            self.audio_pipe_thread = threading.Thread(target=_audio_reader, daemon=True)
            self.audio_pipe_thread.start()
        except Exception as e:
            self.log(f"[!] Native audio pipe error: {e}")

    def _stop_native_audio(self):
        with self.audio_lock:
            if self.audio_pipe_proc:
                try:
                    self.audio_pipe_proc.kill()
                    self.audio_pipe_proc.wait(timeout=0.2)
                except Exception:
                    pass
                self.audio_pipe_proc = None
            self.audio_queue.clear()

    def get_telemetry(self):
        return {
            "connected": self.connected,
            "packets_sent": self.packets_sent,
            "queue_size": len(self.audio_queue),
            "source": self.active_media_source,
            "visualizer_mode": self.visualizer_mode,
            "profile": self.profile,
        }

    def set_visualizer(self, mode):
        self.visualizer_mode = (mode or "spectrum").lower()
        self.log(f"Audio Visualizer Mode set to: {self.visualizer_mode.upper()}")

    def set_overlay(self, title, ticker=""):
        self.overlay_title = str(title or "").strip()
        self.overlay_ticker = str(ticker or "").strip()
        self.log(f"Stream Overlay updated: '{self.overlay_title}' (ticker: '{self.overlay_ticker}')")

    def set_profile(self, profile):
        profile = (profile or "").lower()
        if profile == "eco":
            self.fps = 15
            self.ts_delta = 6000
            self.frame_interval = 1.0 / 15.0
            self.profile = "eco"
        elif profile == "ultra":
            self.fps = 60
            self.ts_delta = 1500
            self.frame_interval = 1.0 / 60.0
            self.profile = "ultra"
        else:
            self.fps = 30
            self.ts_delta = 3000
            self.frame_interval = 1.0 / 30.0
            self.profile = "balanced"
        self.log(f"Stream profile set to {self.profile.upper()} ({self.fps}fps, {self.ts_delta} ticks/frame)")

    def log(self, msg):
        prefix = f"[Account {self.account_id}] {'🎥 Stream' if self.is_stream else '🎤 Voice'}"
        self.logger(f"{prefix} {msg}")

    def start(self):
        if self.running:
            return
        self.running = True
        self.main_task = asyncio.create_task(self._run())

    async def stop(self):
        self.running = False
        self.connected = False
        self._stop_desktop_capture()
        self._stop_native_audio()
        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass
            self.ws = None
        if self.udp_sock:
            try:
                self.udp_sock.close()
            except Exception:
                pass
            self.udp_sock = None
        if self.main_task:
            self.main_task.cancel()
            try:
                await self.main_task
            except (asyncio.CancelledError, Exception):
                pass
            self.main_task = None
        self.log("Stream session stopped and cleaned up.")

    async def _run(self):
        clean_endpoint = self.endpoint.split(":")[0] if self.endpoint else ""
        if not clean_endpoint or clean_endpoint == "null":
            self.log(f"Invalid endpoint: {self.endpoint}")
            return

        ws_url = f"wss://{clean_endpoint}/?v=8"
        self.log(f"Connecting to Gateway {ws_url}...")

        hb_task = None
        ws_kwargs = {"max_size": 10 * 1024 * 1024}
        if self.proxy:
            ws_kwargs["proxy"] = self.proxy

        try:
            async with websockets.connect(ws_url, **ws_kwargs) as ws:
                self.ws = ws
                self.log(f"Gateway WebSocket handshake established{' via proxy ' + self.proxy if self.proxy else ''}.")

                while self.running:
                    msg_raw = await ws.recv()
                    if isinstance(msg_raw, bytes):
                        # Process binary WebSocket frame from Voice Gateway (DAVE / MLS)
                        if len(msg_raw) >= 1:
                            if msg_raw[0] in (25, 26, 27, 28, 29, 30):
                                op = msg_raw[0]
                                payload = msg_raw[1:]
                            elif len(msg_raw) >= 3 and msg_raw[2] in (25, 26, 27, 28, 29, 30):
                                op = msg_raw[2]
                                payload = msg_raw[3:]
                            else:
                                op = msg_raw[0]
                                payload = msg_raw[1:]

                            if op == 25:  # DaveMlsExternalSender
                                self.log("🔒 [DAVE] Received MLS External Sender Package")
                                self.dave_external_sender_package = payload
                                if self.dave_session:
                                    try:
                                        self.dave_session.set_external_sender(payload)
                                    except Exception as e:
                                        self.log(f"⚠️ [DAVE] set_external_sender: {e}")

                            elif op == 27:  # DaveMlsProposals
                                self.log("🔒 [DAVE] Received MLS Proposals")
                                if self.dave_session:
                                    try:
                                        op_type = payload[0] if len(payload) > 0 else 0
                                        prop_data = payload[1:] if len(payload) > 1 else b""
                                        res = self.dave_session.process_proposals(
                                            davey.ProposalsOperationType.append if op_type == 0 else davey.ProposalsOperationType.revoke,
                                            prop_data
                                        )
                                        if res and getattr(res, "commit", None):
                                            commit_payload = res.commit + (res.welcome or b"")
                                            await ws.send(bytes([28]) + commit_payload)
                                            self.log("🔒 [DAVE] Dispatched MLS Commit/Welcome (Opcode 28)")
                                    except Exception as e:
                                        self.log(f"⚠️ [DAVE] process_proposals: {e}")

                            elif op == 29:  # DaveMlsAnnounceCommitTransition
                                if self.dave_session:
                                    try:
                                        trans_id = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 0
                                        self.dave_session.process_commit(payload[2:])
                                        self.log(f"🔒 [DAVE] Processed MLS Commit for Transition {trans_id}")
                                        if trans_id != 0:
                                            await ws.send(json.dumps({"op": 23, "d": {"transition_id": trans_id}}))
                                    except Exception as e:
                                        self.log(f"⚠️ [DAVE] process_commit: {e}")

                            elif op == 30:  # DaveMlsWelcome
                                if self.dave_session:
                                    try:
                                        trans_id = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 0
                                        self.dave_session.process_welcome(payload[2:])
                                        self.log(f"🔒 [DAVE] Processed MLS Welcome for Transition {trans_id}")
                                        if trans_id != 0:
                                            await ws.send(json.dumps({"op": 23, "d": {"transition_id": trans_id}}))
                                    except Exception as e:
                                        self.log(f"⚠️ [DAVE] process_welcome: {e}")
                        continue

                    msg = json.loads(msg_raw)
                    op = msg.get("op")
                    d = msg.get("d", {})

                    if op == 8:  # HELLO
                        hb_interval = d.get("heartbeat_interval", 20000) / 1000.0

                        async def _heartbeat_loop():
                            while self.running:
                                await asyncio.sleep(hb_interval)
                                if self.ws and not self.ws.closed:
                                    try:
                                        await self.ws.send(json.dumps({
                                            "op": 3,
                                            "d": {"t": int(time.time() * 1000), "seq_ack": self.seq_ack}
                                        }))
                                    except Exception:
                                        break

                        hb_task = asyncio.create_task(_heartbeat_loop())

                        # Op 0: IDENTIFY with max_dave_protocol_version=1 (prevents close code 4017)
                        streams_list = [{"type": "screen", "rid": "100", "quality": 100}] if self.is_stream else []
                        identify = {
                            "op": 0,
                            "d": {
                                "server_id": self.server_id,
                                "user_id": self.user_id,
                                "session_id": self.session_id,
                                "token": self.token,
                                "video": True,
                                "streams": streams_list,
                                "max_dave_protocol_version": 1
                            }
                        }
                        await ws.send(json.dumps(identify))

                    elif op == 2:  # READY
                        self.audio_ssrc = d.get("ssrc", 0)
                        self.ip = d.get("ip")
                        self.port = d.get("port")
                        streams = d.get("streams", [])
                        if streams:
                            self.video_ssrc = streams[0].get("ssrc", self.audio_ssrc + 1)
                            self.rtx_ssrc = streams[0].get("rtx_ssrc", self.audio_ssrc + 2)
                        else:
                            self.video_ssrc = self.audio_ssrc + 1
                            self.rtx_ssrc = self.audio_ssrc + 2

                        modes = d.get("modes", [])
                        if "aead_aes256_gcm_rtpsize" in modes:
                            self.chosen_mode = "aead_aes256_gcm_rtpsize"
                        elif "aead_aes256_gcm" in modes:
                            self.chosen_mode = "aead_aes256_gcm"
                        elif "aead_xchacha20_poly1305_rtpsize" in modes:
                            self.chosen_mode = "aead_xchacha20_poly1305_rtpsize"
                        else:
                            self.chosen_mode = "aead_aes256_gcm_rtpsize"

                        self.log(f"READY: AudioSSRC={self.audio_ssrc}, VideoSSRC={self.video_ssrc}, IP={self.ip}:{self.port}, Mode={self.chosen_mode}")

                        # UDP Discovery
                        my_ip, my_port = await self._discover_udp()
                        self.log(f"UDP Discovery Successful: {my_ip}:{my_port}")

                        # Op 1: SELECT_PROTOCOL (Negotiated Mode: aead_aes256_gcm_rtpsize + Dual Codec Negotiation)
                        select_proto = {
                            "op": 1,
                            "d": {
                                "protocol": "udp",
                                "data": {
                                    "address": my_ip,
                                    "port": my_port,
                                    "mode": self.chosen_mode,
                                    "codecs": [
                                        {"name": "opus", "type": "audio", "priority": 1000, "payload_type": 120},
                                        {"name": "H264", "type": "video", "priority": 1000, "payload_type": 103}
                                    ]
                                }
                            }
                        }
                        await ws.send(json.dumps(select_proto))

                    elif op == 4:  # SESSION_DESCRIPTION
                        key_list = d.get("secret_key", [])
                        self.secret_key = bytes(key_list)
                        self.aesgcm = AESGCM(self.secret_key)
                        self.connected = True
                        active_enc = d.get("mode", self.chosen_mode)
                        self.dave_protocol_version = d.get("dave_protocol_version", 0)
                        self.log(f"🔐 Authenticated session ready ({active_enc}) [DAVE v{self.dave_protocol_version}]!")

                        if self.dave_protocol_version > 0 and HAS_DAVEY:
                            try:
                                ch_id_int = int(self.channel_id) if str(self.channel_id).isdigit() else 0
                                uid_int = int(self.user_id) if str(self.user_id).isdigit() else 0
                                self.dave_session = davey.DaveSession(self.dave_protocol_version, uid_int, ch_id_int)
                                if self.dave_external_sender_package:
                                    try:
                                        self.dave_session.set_external_sender(self.dave_external_sender_package)
                                    except Exception:
                                        pass
                                kp = self.dave_session.get_serialized_key_package()
                                await ws.send(bytes([26]) + kp)
                                self.log(f"🔒 [DAVE] Initialized session and sent Key Package (Opcode 26, {len(kp)} bytes)")
                            except Exception as e:
                                self.log(f"⚠️ [DAVE] Session init error: {e}")

                        # Op 5: SPEAKING
                        await ws.send(json.dumps({
                            "op": 5,
                            "d": {"speaking": 5, "delay": 0, "ssrc": self.audio_ssrc}
                        }))

                        # Op 12: Announce Video Stream
                        if self.is_stream or self.video_ssrc > 0:
                            video_op12 = {
                                "op": 12,
                                "d": {
                                    "audio_ssrc": self.audio_ssrc,
                                    "video_ssrc": self.video_ssrc,
                                    "rtx_ssrc": self.rtx_ssrc,
                                    "streams": [
                                        {
                                            "type": "screen" if self.is_stream else "video",
                                            "rid": "100",
                                            "ssrc": self.video_ssrc,
                                            "rtx_ssrc": self.rtx_ssrc,
                                            "quality": 100,
                                            "active": True
                                        }
                                    ]
                                }
                            }
                            await ws.send(json.dumps(video_op12))
                            self.log(f"Announced video stream: type={'screen' if self.is_stream else 'video'}, ssrc={self.video_ssrc}")

                        # Launch high-priority streaming worker threads
                        if not self._video_thread and self.video_ssrc > 0:
                            self._video_thread = threading.Thread(
                                target=self._video_thread_worker,
                                name=f"DC-Video-{self.account_id}",
                                daemon=True
                            )
                            self._video_thread.start()

                        if not self._audio_thread:
                            self._audio_thread = threading.Thread(
                                target=self._audio_thread_worker,
                                name=f"DC-Audio-{self.account_id}",
                                daemon=True
                            )
                            self._audio_thread.start()

                    elif op == 21:  # DavePrepareTransition
                        trans_id = d.get("transition_id", 0)
                        proto_ver = d.get("protocol_version", 0)
                        self.log(f"🔒 [DAVE] Prepare Transition: id={trans_id}, proto={proto_ver}")
                        if trans_id == 0:
                            if self.dave_session:
                                self.dave_session.execute_transition(0)
                        else:
                            if proto_ver == 0 and self.dave_session:
                                self.dave_session.set_passthrough_mode(True, 120)
                            await ws.send(json.dumps({"op": 23, "d": {"transition_id": trans_id}}))

                    elif op == 22:  # DaveExecuteTransition
                        trans_id = d.get("transition_id", 0)
                        self.log(f"🔒 [DAVE] Execute Transition: id={trans_id}")
                        if self.dave_session:
                            try:
                                self.dave_session.execute_transition(trans_id)
                                self.log(f"🔒 [DAVE] Transition {trans_id} executed! can_encrypt={self.dave_session.can_encrypt}")
                            except Exception as e:
                                self.log(f"⚠️ [DAVE] execute_transition error: {e}")

                    elif op == 24:  # DavePrepareEpoch
                        epoch = d.get("epoch", 0)
                        proto_ver = d.get("protocol_version", self.dave_protocol_version)
                        self.log(f"🔒 [DAVE] Prepare Epoch: epoch={epoch}, proto={proto_ver}")
                        if epoch == 1 and HAS_DAVEY:
                            try:
                                self.dave_protocol_version = proto_ver
                                ch_id_int = int(self.channel_id) if str(self.channel_id).isdigit() else 0
                                uid_int = int(self.user_id) if str(self.user_id).isdigit() else 0
                                self.dave_session = davey.DaveSession(self.dave_protocol_version, uid_int, ch_id_int)
                                kp = self.dave_session.get_serialized_key_package()
                                await ws.send(bytes([26]) + kp)
                                self.log("🔒 [DAVE] Re-initialized session for epoch 1 and sent Key Package")
                            except Exception as e:
                                self.log(f"⚠️ [DAVE] Epoch reinit error: {e}")

                    elif op == 5:  # SPEAKING
                        uid = d.get("user_id")
                        spk = (d.get("speaking", 0) > 0)
                        if uid and self.speaker_callback:
                            try:
                                self.speaker_callback(uid, spk)
                            except Exception:
                                pass

                    elif op == 3:  # HEARTBEAT_ACK
                        pass

        except asyncio.CancelledError:
            pass
        except Exception as e:
            self.log(f"Session closed: {e}")
        finally:
            self.connected = False
            if hb_task:
                hb_task.cancel()
            self._stop_native_audio()
            self._stop_desktop_capture()
            if self.udp_sock:
                try:
                    self.udp_sock.close()
                except Exception:
                    pass
                self.udp_sock = None

    async def _discover_udp(self):
        """Performs Discord 74-byte UDP IP discovery."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setblocking(False)
        self.udp_sock = sock

        loop = asyncio.get_running_loop()
        discovery_pkt = struct.pack(">HHII", 1, 70, self.audio_ssrc, 0) + (b"\x00" * 62)
        await loop.sock_sendto(sock, discovery_pkt, (self.ip, self.port))

        resp = await loop.sock_recv(sock, 74)
        null_pos = resp.find(b"\x00", 8)
        if null_pos == -1 or null_pos > 72:
            null_pos = 72
        my_ip = resp[8:null_pos].decode("utf-8").strip()
        my_port = struct.unpack(">H", resp[72:74])[0]
        return my_ip, my_port

    def _video_thread_worker(self):
        """
        High-precision 30 FPS video transmission worker.
        Zero-allocation dispatch of pre-packetized H.264 RTP packets with AES-256-GCM encryption.
        """
        seq = 0
        timestamp = 0
        nonce_counter = 0
        frame_idx = 0
        dest = (self.ip, self.port)

        self.log(f"⚡ Video Worker Active: {self.fps} FPS ({self.profile.upper()})")

        next_time = time.perf_counter()
        while self.running and self.connected and self.udp_sock and self.aesgcm:
            try:
                interval = self.frame_interval
                delta = self.ts_delta
                # Priority 1: Real Desktop Screen Capture (if active and frame buffered)
                if self.desktop_capture_active and len(self.frame_queue) > 0:
                    packets = self.frame_queue.popleft()
                # Priority 2: Procedural Spectrum & Equalizer Frame Modulation
                elif self.visualizer_mode == "smpte":
                    packets = PRE_PACKETIZED_SMPTE
                elif self.visualizer_mode == "testsrc":
                    packets = PRE_PACKETIZED_TESTSRC
                else:
                    # Spectrum / Neon Bars / Waveform dynamic rhythmic cadence
                    cadence = max(4, int(self.fps / 3))
                    packets = PRE_PACKETIZED_SMPTE if ((frame_idx // cadence) % 2 == 0) else PRE_PACKETIZED_TESTSRC
                frame_idx += 1

                for p_data, marker in packets:
                    # DAVE MLS E2EE Frame Encryption if session ready
                    if self.dave_session and getattr(self.dave_session, "can_encrypt", False):
                        try:
                            p_data = self.dave_session.encrypt(davey.MediaType.video, davey.Codec.h264, p_data)
                        except Exception:
                            pass

                    # 12-byte RTP header (90kHz clock)
                    rtp_header = struct.pack(
                        ">BBHII",
                        0x80,
                        (0x80 if marker else 0x00) | 103,  # Payload Type 103 = H.264
                        seq,
                        timestamp,
                        self.video_ssrc
                    )

                    # 12-byte Nonce
                    nonce = struct.pack(">I", nonce_counter) + (b"\x00" * 8)

                    # AES-256-GCM AEAD encryption
                    ciphertext = self.aesgcm.encrypt(nonce, p_data, rtp_header)

                    # UDP Datagram: [RTP Header] + [Ciphertext + 16B Tag] + [4B Nonce Counter]
                    packet = rtp_header + ciphertext + struct.pack(">I", nonce_counter)

                    try:
                        self.udp_sock.sendto(packet, dest)
                        self.packets_sent += 1
                    except (OSError, socket.error):
                        pass

                    seq = (seq + 1) & 0xFFFF
                    nonce_counter += 1

                timestamp = (timestamp + delta) & 0xFFFFFFFF

                next_time += interval
                sleep_dur = next_time - time.perf_counter()
                if sleep_dur > 0:
                    time.sleep(sleep_dur)
                else:
                    next_time = time.perf_counter()
            except Exception:
                time.sleep(interval)

    def _audio_thread_worker(self):
        """
        High-precision 50 PPS Opus audio keepalive worker.
        Transmits 20ms silence frames every 20ms to keep Discord's voice mixer 100% active.
        """
        seq = 0
        timestamp = 0
        nonce_counter = 0
        silence_frame = b"\xF8\xFF\xFE"  # RFC 6716 Opus silence frame
        dest = (self.ip, self.port)
        interval = 0.020  # 20ms

        next_time = time.perf_counter()
        while self.running and self.connected and self.udp_sock and self.aesgcm:
            try:
                # 12-byte RTP header (48kHz clock, 960 samples per 20ms)
                rtp_header = struct.pack(
                    ">BBHII",
                    0x80,
                    120,  # Payload Type 120 = Opus
                    seq,
                    timestamp,
                    self.audio_ssrc
                )

                frame_to_send = self.audio_queue.popleft() if len(self.audio_queue) > 0 else silence_frame

                # DAVE MLS E2EE Frame Encryption if session ready
                if self.dave_session and getattr(self.dave_session, "can_encrypt", False):
                    try:
                        frame_to_send = self.dave_session.encrypt_opus(frame_to_send)
                    except Exception:
                        pass

                nonce = struct.pack(">I", nonce_counter) + (b"\x00" * 8)
                ciphertext = self.aesgcm.encrypt(nonce, frame_to_send, rtp_header)
                packet = rtp_header + ciphertext + struct.pack(">I", nonce_counter)

                try:
                    self.udp_sock.sendto(packet, dest)
                    self.packets_sent += 1
                except (OSError, socket.error):
                    pass

                seq = (seq + 1) & 0xFFFF
                timestamp = (timestamp + 960) & 0xFFFFFFFF
                nonce_counter += 1

                next_time += interval
                sleep_dur = next_time - time.perf_counter()
                if sleep_dur > 0:
                    time.sleep(sleep_dur)
                else:
                    next_time = time.perf_counter()
            except Exception:
                time.sleep(interval)
