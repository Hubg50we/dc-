import asyncio
import json
import os
import sys
import threading
import time
import urllib.request
import urllib.error

# Point to local test server
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import main

TEST_TOKEN = os.environ.get("TEST_TOKEN", "dummy_token_1234567890abcdef")
TEST_GUILD = "1273592244473172001"
TEST_VC = "1535504524121473145"

BASE_URL = ""

def http_get(path):
    url = f"{BASE_URL}{path}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=5) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def http_post(path, data):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def run_tests():
    global BASE_URL
    port = main.WEB_PORT
    BASE_URL = f"http://127.0.0.1:{port}"
    print(f"\n========================================================")
    print(f"       DC- FULL SYSTEM FUNCTIONALITY AUDIT")
    print(f"       Target: {BASE_URL} (Version: {main.APP_VERSION})")
    print(f"========================================================\n")

    results = []

    def check(name, condition, detail=""):
        status = "PASSED" if condition else "FAILED"
        results.append((name, condition, detail))
        icon = "✅" if condition else "❌"
        print(f"{icon} [{status}] {name} {('- ' + detail) if detail else ''}")

    # 1. Test Static UI Serve
    try:
        req = urllib.request.Request(f"{BASE_URL}/", headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=5) as r:
            html = r.read().decode("utf-8", errors="ignore")
            check("HTTP GET / (Static Web UI)", r.status == 200 and "dc-" in html and "brand-badge" in html)
    except Exception as e:
        check("HTTP GET / (Static Web UI)", False, str(e))

    # 2. Test GET /api/status
    try:
        st, data = http_get("/api/status")
        check("GET /api/status Schema", st == 200 and "total_accounts" in data and "version" in data and "stream_profile" in data and "spotify_song" in data and "voice_hud" in data and "uploaded_media" in data)
    except Exception as e:
        check("GET /api/status Schema", False, str(e))

    # 3. Test GET /api/logs
    try:
        st, data = http_get("/api/logs")
        check("GET /api/logs", st == 200 and "logs" in data and isinstance(data["logs"], list))
    except Exception as e:
        check("GET /api/logs", False, str(e))

    # 4. Test POST clear_logs
    try:
        st, data = http_post("/api/action", {"action": "clear_logs"})
        check("POST action: clear_logs", st == 200 and data.get("success") is True)
    except Exception as e:
        check("POST action: clear_logs", False, str(e))

    # 5. Test POST set_device_type (Desktop & Mobile)
    try:
        st, data1 = http_post("/api/action", {"action": "set_device_type", "device_type": "desktop"})
        st2, data2 = http_post("/api/action", {"action": "set_device_type", "device_type": "mobile"})
        check("POST action: set_device_type (mobile/desktop)", data1.get("success") and data2.get("success") and main.DEVICE_TYPE == "mobile")
    except Exception as e:
        check("POST action: set_device_type", False, str(e))

    # 6. Test POST set_auto_rejoin
    try:
        st, data = http_post("/api/action", {"action": "set_auto_rejoin", "auto_rejoin": True})
        check("POST action: set_auto_rejoin", data.get("success") and main.AUTO_REJOIN is True)
    except Exception as e:
        check("POST action: set_auto_rejoin", False, str(e))

    # 7. Test POST set_presence / set_activity
    try:
        st, data = http_post("/api/action", {"action": "set_presence", "activity_name": "Testing DC Pro", "activity_type": 0})
        check("POST action: set_presence", data.get("success") and main.CUSTOM_ACTIVITY_NAME == "Testing DC Pro")
    except Exception as e:
        check("POST action: set_presence", False, str(e))

    # 8. Test POST set_stealth_delay
    try:
        st, data = http_post("/api/action", {"action": "set_stealth_delay", "stealth_delay": 0.5})
        check("POST action: set_stealth_delay", data.get("success") and abs(main.STEALTH_DELAY - 0.5) < 0.01)
    except Exception as e:
        check("POST action: set_stealth_delay", False, str(e))

    # 9. Test POST set_speaking
    try:
        st, data = http_post("/api/action", {"action": "set_speaking", "speaking": True})
        check("POST action: set_speaking", data.get("success") and main.SPEAKING_SIM is True)
    except Exception as e:
        check("POST action: set_speaking", False, str(e))

    # 10. Test POST set_rotator
    try:
        st, data = http_post("/api/action", {"action": "set_rotator", "enabled": True, "interval": 60, "channels": ["111", "222"]})
        st_off, data_off = http_post("/api/action", {"action": "set_rotator", "enabled": False})
        check("POST action: set_rotator", data.get("success") and data_off.get("success") and main.ROTATOR_ENABLED is False)
    except Exception as e:
        check("POST action: set_rotator", False, str(e))

    # 11. Test POST save_preset & delete_preset
    try:
        st, data_save = http_post("/api/action", {"action": "save_preset", "name": "AuditPreset", "channel_id": "12345", "guild_id": "67890"})
        st2, data_del = http_post("/api/action", {"action": "delete_preset", "name": "AuditPreset"})
        check("POST action: presets (save & delete)", data_save.get("success") and data_del.get("success"))
    except Exception as e:
        check("POST action: presets", False, str(e))

    # 12. Test POST set_config (server & channel)
    try:
        st, data = http_post("/api/action", {"action": "set_config", "channel_id": TEST_VC, "guild_id": TEST_GUILD})
        check("POST action: set_config", data.get("success") and main.CHANNEL_ID == TEST_VC and main.GUILD_ID == TEST_GUILD)
    except Exception as e:
        check("POST action: set_config", False, str(e))

    # 13. Test POST exec_command
    try:
        st, data = http_post("/api/action", {"action": "exec_command", "command": "status"})
        check("POST action: exec_command", data.get("success") and "status" in data.get("message", "").lower())
    except Exception as e:
        check("POST action: exec_command", False, str(e))

    # 14. Test Token Add (Single & Bulk)
    try:
        st, data_single = http_post("/api/action", {"action": "token_add", "token": TEST_TOKEN})
        check("POST action: token_add", data_single.get("success") and len(main.ACCOUNTS) > 0, f"Account count: {len(main.ACCOUNTS)}")
    except Exception as e:
        check("POST action: token_add", False, str(e))

    # 15. Test Token Health Validation
    try:
        st, data_val = http_post("/api/action", {"action": "check_tokens"})
        check("POST action: check_tokens (Validation API)", data_val.get("success") and "valid_count" in data_val, f"Valid: {data_val.get('valid_count')}, Invalid: {data_val.get('invalid_count')}")
    except Exception as e:
        check("POST action: check_tokens", False, str(e))

    # 16. Test Fetch Guild Channels (Visual Channel Picker API)
    try:
        st, data_ch = http_post("/api/action", {"action": "fetch_guild_channels", "guild_id": TEST_GUILD})
        check("POST action: fetch_guild_channels", data_ch.get("success") and "channels" in data_ch, f"Found {len(data_ch.get('channels', []))} channel(s)")
    except Exception as e:
        check("POST action: fetch_guild_channels", False, str(e))

    # 17. Test Batch Actions (Move, Mute, Deafen, Video)
    try:
        st, data_move = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "join", "channel_id": TEST_VC})
        st_mute, data_mute = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "mute"})
        st_unmute, data_unmute = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "unmute"})
        st_vid, data_vid = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "video"})
        check("POST action: batch_action (join, mute, unmute, video)", data_move.get("success") and data_mute.get("success") and data_unmute.get("success") and data_vid.get("success"))
    except Exception as e:
        check("POST action: batch_action", False, str(e))

    # 18. Test Video / Camera Streaming Simulation
    try:
        st_v1, data_v1 = http_post("/api/action", {"action": "video", "percent": 100})
        st_v2, data_v2 = http_post("/api/action", {"action": "unvideo", "percent": 100})
        check("POST action: video / unvideo (Camera Streaming)", data_v1.get("success") and data_v2.get("success") and main.VIDEO_SIM is False)
    except Exception as e:
        check("POST action: video / unvideo", False, str(e))

    # 19. Test Go Live / Streamer Status
    try:
        st_s1, data_s1 = http_post("/api/action", {"action": "set_stream", "enabled": True, "title": "Audit Streamer"})
        st_s2, data_s2 = http_post("/api/action", {"action": "set_stream", "enabled": False})
        check("POST action: set_stream (Go Live Streamer)", data_s1.get("success") and data_s2.get("success"))
    except Exception as e:
        check("POST action: set_stream", False, str(e))

    # 20. Test Spotify Music Player Simulation
    try:
        st_m1, data_m1 = http_post("/api/action", {"action": "play_music", "title": "Starboy", "artist": "The Weeknd"})
        st_m2, data_m2 = http_post("/api/action", {"action": "stop_music"})
        check("POST action: play_music / stop_music (Spotify Simulation)", data_m1.get("success") and data_m2.get("success") and main.MUSIC_TITLE == "")
    except Exception as e:
        check("POST action: play_music / stop_music", False, str(e))

    # 21. Test YouTube oEmbed Metadata Resolver
    try:
        st_yt, data_yt = http_post("/api/action", {"action": "resolve_youtube", "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"})
        check("POST action: resolve_youtube (oEmbed Title & Channel)", data_yt.get("success") and "Never Gonna Give You Up" in data_yt.get("title", ""), f"Title: {data_yt.get('title')}")
    except Exception as e:
        check("POST action: resolve_youtube", False, str(e))

    # 22. Test YouTube Video & Music Streamer
    try:
        st_y1, data_y1 = http_post("/api/action", {"action": "play_youtube", "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "mode": "music", "sim_voice": True})
        st_y2, data_y2 = http_post("/api/action", {"action": "stop_youtube"})
        check("POST action: play_youtube / stop_youtube (Stream via URL)", data_y1.get("success") and data_y2.get("success") and main.YOUTUBE_URL == "")
    except Exception as e:
        check("POST action: play_youtube / stop_youtube", False, str(e))

    # 23. Test Voice Channel Screen Sharing (Opcode 18 Stream Create)
    try:
        st_ss1, data_ss1 = http_post("/api/action", {"action": "screenshare", "percent": 100})
        st_ss2, data_ss2 = http_post("/api/action", {"action": "unscreenshare", "percent": 100})
        check("POST action: screenshare / unscreenshare (Voice Screen Share)", data_ss1.get("success") and data_ss2.get("success") and main.SCREEN_SHARE_SIM is False)
    except Exception as e:
        check("POST action: screenshare / unscreenshare", False, str(e))

    # 24. Test Voice Channel Soundboard / Scream / Audio FX & Voice Flags
    try:
        st_vf, data_vf = http_post("/api/action", {"action": "set_voice_flags", "flags": 5})
        st_sfx, data_sfx = http_post("/api/action", {"action": "play_sfx", "sound": "scream", "flags": 5, "duration": 4.0})
        st_stp, data_stp = http_post("/api/action", {"action": "stop_sfx"})
        check("POST action: play_sfx / stop_sfx / set_voice_flags (Voice Soundboard & Scream FX)", data_vf.get("success") and data_sfx.get("success") and data_stp.get("success") and data_sfx.get("active_sfx") == "scream" and main.ACTIVE_SFX == "")
    except Exception as e:
        check("POST action: play_sfx / stop_sfx / set_voice_flags", False, str(e))

    # 25. Test Voice Channel Status Banner (Official 2026 API)
    try:
        st_vst, data_vst = http_post("/api/action", {"action": "set_voice_status", "status": "🔴 LIVE: 24/7 Music Radio", "channel_id": TEST_VC})
        check("POST action: set_voice_status (Channel Status Banner)", data_vst.get("success") and data_vst.get("voice_status") == "🔴 LIVE: 24/7 Music Radio")
    except Exception as e:
        check("POST action: set_voice_status", False, str(e))

    # 26. Test TTS Voice Announcer
    try:
        st_tts, data_tts = http_post("/api/action", {"action": "send_tts", "text": "Testing TTS Announcer Voice", "channel_id": TEST_VC})
        check("POST action: send_tts (Text-to-Speech Channel Announcer)", data_tts.get("success") and data_tts.get("text") == "Testing TTS Announcer Voice")
    except Exception as e:
        check("POST action: send_tts", False, str(e))

    # 27. Test Human Chatter Simulation
    try:
        st_hc1, data_hc1 = http_post("/api/action", {"action": "set_human_chatter", "enabled": True})
        st_hc2, data_hc2 = http_post("/api/action", {"action": "set_human_chatter", "enabled": False})
        check("POST action: set_human_chatter (Conversational Speaking Turns)", data_hc1.get("success") and data_hc2.get("success") and main.HUMAN_CHATTER_ENABLED is False)
    except Exception as e:
        check("POST action: set_human_chatter", False, str(e))

    # 28. Test Custom Audio Streamer
    try:
        st_ca, data_ca = http_post("/api/action", {"action": "stream_custom_audio", "title": "MyCustomAudio.mp3", "duration": 45.0, "flags": 5})
        check("POST action: stream_custom_audio (Direct Audio & MP3 Streamer)", data_ca.get("success") and data_ca.get("title") == "MyCustomAudio.mp3")
    except Exception as e:
        check("POST action: stream_custom_audio", False, str(e))

    # 29. Test Real Video & Camera Streaming Studio
    try:
        st_rv1, data_rv1 = http_post("/api/action", {"action": "stream_real_video", "mode": "camera", "title": "Live Camera Feed", "resolution": "720p", "fps": 30})
        st_rv2, data_rv2 = http_post("/api/action", {"action": "stop_real_video"})
        check("POST action: stream_real_video / stop_real_video (Real Camera & Video Stream)", data_rv1.get("success") and data_rv2.get("success") and data_rv1.get("mode") == "camera" and main.REAL_VIDEO_ACTIVE is False)
    except Exception as e:
        check("POST action: stream_real_video / stop_real_video", False, str(e))

    # 30. Test Real Web Sharing & Browser Streamer
    try:
        st_ws1, data_ws1 = http_post("/api/action", {"action": "stream_web", "url": "https://en.wikipedia.org"})
        st_ws2, data_ws2 = http_post("/api/action", {"action": "stop_web"})
        check("POST action: stream_web / stop_web (Real Web Sharing & Live Co-Browsing)", data_ws1.get("success") and data_ws2.get("success") and data_ws1.get("url") == "https://en.wikipedia.org" and main.WEB_SHARE_URL == "")
    except Exception as e:
        check("POST action: stream_web / stop_web", False, str(e))

    # 31. Test StreamBot (ysdragon/StreamBot) Video & Queue Engine
    try:
        # Play direct / YT video
        st_sb1, d_sb1 = http_post("/api/action", {"action": "streambot_play", "url": "https://www.youtube.com/watch?v=jfKfPfyJRdk", "title": "Lofi Beats"})
        # Add item to queue
        st_sb2, d_sb2 = http_post("/api/action", {"action": "streambot_queue_add", "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", "title": "Big Buck Bunny"})
        # Pause & Resume
        st_sb3, d_sb3 = http_post("/api/action", {"action": "streambot_pause"})
        st_sb4, d_sb4 = http_post("/api/action", {"action": "streambot_resume"})
        # Volume, Loop, Quality
        st_sb5, d_sb5 = http_post("/api/action", {"action": "streambot_set_volume", "volume": 125})
        st_sb6, d_sb6 = http_post("/api/action", {"action": "streambot_set_loop", "mode": "video"})
        st_sb7, d_sb7 = http_post("/api/action", {"action": "streambot_set_quality", "resolution": "1080p", "fps": 60, "bitrate": "4500k"})
        # Skip to queued track
        st_sb8, d_sb8 = http_post("/api/action", {"action": "streambot_skip"})
        # Stop
        st_sb9, d_sb9 = http_post("/api/action", {"action": "streambot_stop"})
        
        sb_ok = (
            d_sb1.get("success") is True and
            d_sb2.get("success") is True and
            d_sb3.get("success") is True and
            d_sb4.get("success") is True and
            d_sb5.get("volume") == 125 and
            d_sb6.get("loop_mode") == "video" and
            d_sb7.get("fps") == 60 and
            d_sb8.get("success") is True and
            d_sb9.get("success") is True and
            main.STREAMBOT_STATUS == "idle"
        )
        check("POST action: streambot_play / pause / resume / queue / skip / stop (StreamBot Engine)", sb_ok)
    except Exception as e:
        check("POST action: streambot_play / pause / resume / queue / skip / stop", False, str(e))

    # 32. Test User Guilds Auto-Discovery
    try:
        st, data_guilds = http_post("/api/action", {"action": "fetch_user_guilds"})
        check("POST action: fetch_user_guilds (Server Auto-Discovery)", data_guilds.get("success") is True and isinstance(data_guilds.get("guilds"), list))
    except Exception as e:
        check("POST action: fetch_user_guilds", False, str(e))

    # 33. Test Shizuku Privileged System (Status & Optimizer)
    try:
        st_sh, d_sh = http_post("/api/action", {"action": "shizuku_status"})
        st_opt, d_opt = http_post("/api/action", {"action": "shizuku_optimize"})
        check("POST action: shizuku_status / shizuku_optimize (Privileged 24/7 Background)", d_sh.get("success") is True and d_opt.get("success") is True)
    except Exception as e:
        check("POST action: shizuku_status / shizuku_optimize", False, str(e))

    # 34. Test Unrestricted Media Profiles (4K / 1440p 60fps)
    try:
        st_q, d_q = http_post("/api/action", {"action": "streambot_set_quality", "resolution": "4k", "fps": 60, "bitrate": "18000k"})
        check("POST action: streambot_set_quality (Unrestricted 4K 60fps Pipeline)", d_q.get("success") is True and d_q.get("resolution") == "4k" and d_q.get("fps") == 60)
    except Exception as e:
        check("POST action: streambot_set_quality (4K)", False, str(e))

    # 35. Test Stream Engine Host configuration (for PC & Mobile Wi-Fi/Termux)
    try:
        st_h, d_h = http_post("/api/action", {"action": "set_streambot_host", "host": "http://127.0.0.1:8080"})
        check("POST action: set_streambot_host (PC & Mobile Wi-Fi / Termux Relay)", d_h.get("success") is True and "streambot_host" in d_h)
    except Exception as e:
        check("POST action: set_streambot_host", False, str(e))

    # 36. Test Account Remove
    try:
        st, data_rem = http_post("/api/action", {"action": "token_remove", "id": 1})
        check("POST action: token_remove", data_rem.get("success") is True)
    except Exception as e:
        check("POST action: token_remove", False, str(e))

    # 37. Test Dynamic Stream Quality & Eco Battery Profiles
    try:
        st_p1, d_p1 = http_post("/api/action", {"action": "set_stream_profile", "profile": "eco"})
        st_p2, d_p2 = http_post("/api/action", {"action": "set_stream_profile", "profile": "ultra"})
        st_p3, d_p3 = http_post("/api/action", {"action": "set_stream_profile", "profile": "balanced"})
        check("POST action: set_stream_profile (Eco/Ultra/Balanced Quality Scaling)",
              d_p1.get("profile") == "eco" and d_p2.get("profile") == "ultra" and d_p3.get("profile") == "balanced")
    except Exception as e:
        check("POST action: set_stream_profile", False, str(e))

    # 38. Test Spotify & Rich Presence Simulator
    try:
        st_sp, d_sp = http_post("/api/action", {
            "action": "set_spotify_presence",
            "song": "Blinding Lights",
            "artist": "The Weeknd",
            "album": "After Hours",
            "duration": 200
        })
        check("POST action: set_spotify_presence (Listening to Spotify & Progress)",
              d_sp.get("success") is True and d_sp.get("song") == "Blinding Lights" and d_sp.get("duration") == 200)
    except Exception as e:
        check("POST action: set_spotify_presence", False, str(e))

    # 39. Test Direct Media Upload (/api/upload_media)
    test_fname = "test_upload_sample.mp3"
    import base64
    sample_b64 = base64.b64encode(b"ID3\x03\x00\x00\x00\x00\x00\x00FAKE_AUDIO_SAMPLE_DATA").decode("ascii")
    try:
        st_up, d_up = http_post("/api/upload_media", {
            "filename": test_fname,
            "data": sample_b64
        })
        check("POST /api/upload_media (Direct File Upload & Base64 Decoder)",
              d_up.get("success") is True and d_up.get("filename") == test_fname and d_up.get("size") > 0)
    except Exception as e:
        check("POST /api/upload_media", False, str(e))

    # 40. Test Get & Stream Uploaded Media
    try:
        st_get, d_get = http_post("/api/action", {"action": "get_uploaded_media"})
        files = d_get.get("files", [])
        has_file = any(f.get("filename") == test_fname for f in files)
        st_play, d_play = http_post("/api/action", {"action": "stream_uploaded_file", "filename": test_fname})
        check("POST action: get_uploaded_media / stream_uploaded_file (Local Media Player)",
              st_get == 200 and has_file and d_play.get("success") is True)
    except Exception as e:
        check("POST action: get_uploaded_media / stream_uploaded_file", False, str(e))

    # 41. Test Delete Uploaded Media
    try:
        st_del, d_del = http_post("/api/action", {"action": "delete_uploaded_media", "filename": test_fname})
        check("POST action: delete_uploaded_media",
              d_del.get("success") is True)
    except Exception as e:
        check("POST action: delete_uploaded_media", False, str(e))

    # 42. Test Live Spectrum Visualizer Mode (spectrum, bars, testsrc, smpte)
    try:
        st_v1, d_v1 = http_post("/api/action", {"action": "set_visualizer_mode", "mode": "neon_bars"})
        st_v2, d_v2 = http_post("/api/action", {"action": "set_visualizer_mode", "mode": "spectrum"})
        check("POST action: set_visualizer_mode (Live Audio Reactive Visualizer Engine)",
              st_v1 == 200 and d_v1.get("success") is True and d_v2.get("mode") == "spectrum")
    except Exception as e:
        check("POST action: set_visualizer_mode", False, str(e))

    # 43. Test Go Live Stream Overlay & Dynamic Watermark
    try:
        st_ov, d_ov = http_post("/api/action", {
            "action": "set_stream_overlay",
            "enabled": True,
            "title": "🔴 LIVE 24/7 BROADCAST",
            "ticker": "Now Playing: High Fidelity Audio Stream"
        })
        check("POST action: set_stream_overlay (Dynamic Stream Watermark & Ticker)",
              st_ov == 200 and d_ov.get("enabled") is True and d_ov.get("title") == "🔴 LIVE 24/7 BROADCAST")
    except Exception as e:
        check("POST action: set_stream_overlay", False, str(e))

    # 44. Test Smart Text Chat Auto-Responder
    try:
        st_cr1, d_cr1 = http_post("/api/action", {"action": "set_chat_auto_responder", "enabled": True})
        st_cr2, d_cr2 = http_post("/api/action", {
            "action": "add_chat_rule",
            "trigger": "!ping",
            "response": "Pong! Discord 24/7 Bot v1.4.3 is fully active 🚀",
            "exact": True
        })
        st_cr3, d_cr3 = http_post("/api/action", {"action": "delete_chat_rule", "trigger": "!ping"})
        check("POST action: chat_auto_responder / add_chat_rule / delete_chat_rule (Conversational Engine)",
              d_cr1.get("enabled") is True and d_cr2.get("success") is True and d_cr3.get("success") is True)
    except Exception as e:
        check("POST action: chat_auto_responder", False, str(e))

    # 45. Test 12-Pad Soundboard Activation
    try:
        st_pad, d_pad = http_post("/api/action", {"action": "play_soundboard_pad", "id": "airhorn"})
        check("POST action: play_soundboard_pad (Multi-Pad Instant SFX Broadcaster)",
              st_pad == 200 and d_pad.get("success") is True and d_pad.get("pad") == "airhorn")
    except Exception as e:
        check("POST action: play_soundboard_pad", False, str(e))

    # 46. Test Microphone Voice Clip Broadcast
    try:
        dummy_clip_b64 = base64.b64encode(b"RIFF\x24\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00\x44\xac\x00\x00data\x00\x00\x00\x00").decode("ascii")
        st_vc, d_vc = http_post("/api/action", {
            "action": "broadcast_voice_clip",
            "name": "Live Phone Mic",
            "duration": 4.5,
            "data": f"data:audio/wav;base64,{dummy_clip_b64}"
        })
        check("POST action: broadcast_voice_clip (Direct Phone Mic / Audio Memo Broadcaster)",
              st_vc == 200 and d_vc.get("success") is True and d_vc.get("name") == "Live Phone Mic")
    except Exception as e:
        check("POST action: broadcast_voice_clip", False, str(e))

    # Summary
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n========================================================")
    print(f"AUDIT SUMMARY: {passed}/{total} FUNCTIONS VERIFIED WORKING")
    print(f"========================================================")
    return passed == total

async def async_main():
    main.START_TIME = time.time()
    main.MAIN_LOOP = asyncio.get_running_loop()
    main.start_web_server()
    await asyncio.sleep(0.5)
    
    # Run tests in thread
    loop = asyncio.get_running_loop()
    all_ok = await loop.run_in_executor(None, run_tests)
    if not all_ok:
        sys.exit(1)
    else:
        print("\nAll functions operational and verified with live evidence!")
        sys.exit(0)

if __name__ == "__main__":
    asyncio.run(async_main())
