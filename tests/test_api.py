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
        check("GET /api/status Schema", st == 200 and "total_accounts" in data and "version" in data and "stealth_delay" in data and "rotator_enabled" in data)
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

    # 17. Test Batch Actions (Move, Mute, Deafen)
    try:
        st, data_move = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "join", "channel_id": TEST_VC})
        st_mute, data_mute = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "mute"})
        st_unmute, data_unmute = http_post("/api/action", {"action": "batch_action", "ids": [1], "sub_action": "unmute"})
        check("POST action: batch_action (join, mute, unmute)", data_move.get("success") and data_mute.get("success") and data_unmute.get("success"))
    except Exception as e:
        check("POST action: batch_action", False, str(e))

    # 18. Test Account Remove
    try:
        st, data_rem = http_post("/api/action", {"action": "token_remove", "id": 1})
        check("POST action: token_remove", data_rem.get("success") is True)
    except Exception as e:
        check("POST action: token_remove", False, str(e))

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
