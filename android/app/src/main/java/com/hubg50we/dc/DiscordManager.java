package com.hubg50we.dc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class DiscordManager {
    private static final String TAG = "DiscordManager";
    private static final String PREFS_NAME = "dc_prefs";
    private static DiscordManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    private final Handler mainHandler;

    private String guildId = "";
    private String defaultChannelId = "";
    private String deviceMode = "mobile"; // "mobile" or "desktop"
    private final long startTime;

    private final Map<Integer, Account> accounts = new ConcurrentHashMap<>();
    private int nextAccountId = 1;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final List<JSONObject> presets = Collections.synchronizedList(new ArrayList<>());

    public static synchronized DiscordManager getInstance(Context context) {
        if (instance == null) {
            instance = new DiscordManager(context.getApplicationContext());
        }
        return instance;
    }

    private DiscordManager(Context context) {
        this.context = context;
        this.startTime = System.currentTimeMillis();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        this.guildId = prefs.getString("guild_id", "");
        this.defaultChannelId = prefs.getString("channel_id", "");
        this.deviceMode = prefs.getString("device_mode", "mobile");

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();

        loadPresetsFromPrefs();
        loadSavedTokens();
        log("dc- Engine initialized. Device mode: " + deviceMode.toUpperCase());
    }

    public void log(String msg) {
        Log.i(TAG, msg);
        String timestamped = String.format("[%tT] %s", System.currentTimeMillis(), msg);
        logs.add(timestamped);
        if (logs.size() > 300) {
            logs.remove(0);
        }
    }

    private void loadPresetsFromPrefs() {
        try {
            String pStr = prefs.getString("presets", "");
            if (!pStr.isEmpty()) {
                JSONArray arr = new JSONArray(pStr);
                for (int i = 0; i < arr.length(); i++) {
                    presets.add(arr.getJSONObject(i));
                }
            } else {
                // Add sensible defaults
                presets.add(new JSONObject().put("name", "Lounge").put("channel_id", defaultChannelId).put("guild_id", guildId));
                presets.add(new JSONObject().put("name", "Gaming").put("channel_id", "").put("guild_id", guildId));
                presets.add(new JSONObject().put("name", "Music").put("channel_id", "").put("guild_id", guildId));
                presets.add(new JSONObject().put("name", "AFK").put("channel_id", "").put("guild_id", guildId));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading presets", e);
        }
    }

    private synchronized void savePresetsToPrefs() {
        JSONArray arr = new JSONArray();
        for (JSONObject p : presets) {
            arr.put(p);
        }
        prefs.edit().putString("presets", arr.toString()).apply();
    }

    private void loadSavedTokens() {
        String saved = prefs.getString("tokens", "");
        if (!saved.isEmpty()) {
            for (String tok : saved.split("\n")) {
                String clean = tok.trim();
                if (!clean.isEmpty() && !clean.startsWith("#")) {
                    addAccountInternal(clean);
                }
            }
        }
    }

    private synchronized void saveTokensToPrefs() {
        StringBuilder sb = new StringBuilder();
        for (Account a : accounts.values()) {
            if (a.token != null && !a.token.isEmpty()) {
                sb.append(a.token).append("\n");
            }
        }
        prefs.edit().putString("tokens", sb.toString()).apply();
    }

    private synchronized Account addAccountInternal(String token) {
        for (Account a : accounts.values()) {
            if (a.token.equals(token)) {
                return a;
            }
        }
        Account a = new Account(nextAccountId++, token);
        accounts.put(a.id, a);
        connectAccount(a);
        return a;
    }

    public synchronized Account addAccount(String token) {
        Account a = addAccountInternal(token);
        saveTokensToPrefs();
        return a;
    }

    public synchronized void removeAccount(int id) {
        Account a = accounts.remove(id);
        if (a != null) {
            a.disconnect();
            saveTokensToPrefs();
            log("Removed account #" + id);
        }
    }

    public void connectAccount(Account a) {
        a.connect(httpClient, this);
    }

    public void moveAll(String channelId, int percent) {
        List<Account> online = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online) online.add(a);
        }
        int count = Math.max(1, (int) Math.round(online.size() * (percent / 100.0)));
        for (int i = 0; i < Math.min(count, online.size()); i++) {
            online.get(i).setVoiceState(this.guildId, channelId, false, false);
        }
        log("Moved " + Math.min(count, online.size()) + "/" + online.size() + " accounts to " + channelId);
    }

    public void muteAll(boolean mute, int percent) {
        List<Account> inVoice = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online && a.voiceChannel != null) inVoice.add(a);
        }
        int count = Math.max(1, (int) Math.round(inVoice.size() * (percent / 100.0)));
        for (int i = 0; i < Math.min(count, inVoice.size()); i++) {
            Account a = inVoice.get(i);
            a.setVoiceState(this.guildId, a.voiceChannel, mute, a.deaf);
        }
        log((mute ? "Muted " : "Unmuted ") + Math.min(count, inVoice.size()) + " accounts");
    }

    public void deafenAll(boolean deaf, int percent) {
        List<Account> inVoice = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online && a.voiceChannel != null) inVoice.add(a);
        }
        int count = Math.max(1, (int) Math.round(inVoice.size() * (percent / 100.0)));
        for (int i = 0; i < Math.min(count, inVoice.size()); i++) {
            Account a = inVoice.get(i);
            a.setVoiceState(this.guildId, a.voiceChannel, a.mute, deaf);
        }
        log((deaf ? "Deafened " : "Undeafened ") + Math.min(count, inVoice.size()) + " accounts");
    }

    public void disconnectAll(int percent) {
        List<Account> inVoice = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online && a.voiceChannel != null) inVoice.add(a);
        }
        int count = Math.max(1, (int) Math.round(inVoice.size() * (percent / 100.0)));
        for (int i = 0; i < Math.min(count, inVoice.size()); i++) {
            inVoice.get(i).leaveVoice(this.guildId);
        }
        log("Disconnected " + Math.min(count, inVoice.size()) + " accounts");
    }

    public void resolveGuildId(Callback callback) {
        String token = null;
        for (Account a : accounts.values()) {
            if (a.token != null && !a.token.isEmpty()) {
                token = a.token;
                break;
            }
        }
        if (token == null || defaultChannelId.isEmpty()) {
            return;
        }

        Request req = new Request.Builder()
                .url("https://discord.com/api/v9/channels/" + defaultChannelId)
                .header("Authorization", token)
                .header("User-Agent", "Mozilla/5.0")
                .build();

        httpClient.newCall(req).enqueue(callback);
    }

    public JSONObject handleAction(JSONObject payload) {
        JSONObject res = new JSONObject();
        try {
            String action = payload.optString("action", "");
            int percent = payload.optInt("percent", 100);

            if ("move_default".equals(action)) {
                moveAll(this.defaultChannelId, percent);
                res.put("status", "ok");
                res.put("message", "Moving accounts to default channel");
            } else if ("move".equals(action)) {
                String ch = payload.optString("channel_id", this.defaultChannelId);
                moveAll(ch, percent);
                res.put("status", "ok");
                res.put("message", "Moved accounts to " + ch);
            } else if ("mute".equals(action)) {
                muteAll(true, percent);
                res.put("status", "ok");
                res.put("message", "Muted accounts");
            } else if ("unmute".equals(action)) {
                muteAll(false, percent);
                res.put("status", "ok");
                res.put("message", "Unmuted accounts");
            } else if ("deafen".equals(action)) {
                deafenAll(true, percent);
                res.put("status", "ok");
                res.put("message", "Deafened accounts");
            } else if ("undeafen".equals(action)) {
                deafenAll(false, percent);
                res.put("status", "ok");
                res.put("message", "Undeafened accounts");
            } else if ("disconnect".equals(action)) {
                disconnectAll(percent);
                res.put("status", "ok");
                res.put("message", "Disconnected accounts");
            } else if ("set_config".equals(action)) {
                if (payload.has("guild_id")) this.guildId = payload.getString("guild_id").trim();
                if (payload.has("channel_id")) this.defaultChannelId = payload.getString("channel_id").trim();
                prefs.edit().putString("guild_id", this.guildId).putString("channel_id", this.defaultChannelId).apply();
                log("Server ID: " + this.guildId + ", Channel ID: " + this.defaultChannelId);
                res.put("status", "ok");
                res.put("message", "Configuration saved");
            } else if ("set_guild_id".equals(action)) {
                this.guildId = payload.optString("guild_id", this.guildId).trim();
                prefs.edit().putString("guild_id", this.guildId).apply();
                res.put("status", "ok");
                res.put("message", "Server ID set to " + this.guildId);
            } else if ("set_default_channel".equals(action)) {
                this.defaultChannelId = payload.optString("channel_id", this.defaultChannelId).trim();
                prefs.edit().putString("channel_id", this.defaultChannelId).apply();
                res.put("status", "ok");
                res.put("message", "Default channel set to " + this.defaultChannelId);
            } else if ("set_device_mode".equals(action) || "set_device_type".equals(action)) {
                this.deviceMode = payload.optString("device_mode", payload.optString("device_type", "mobile"));
                prefs.edit().putString("device_mode", this.deviceMode).apply();
                for (Account a : accounts.values()) {
                    if (a.online) a.reconnect(httpClient, this);
                }
                log("Switched appearance to " + (deviceMode.equals("mobile") ? "📱 Android Mobile" : "💻 Desktop"));
                res.put("status", "ok");
                res.put("message", "Device appearance updated to " + this.deviceMode);
            } else if ("resolve_guild".equals(action)) {
                resolveGuildId(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        log("Auto-resolve failed: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (response.isSuccessful() && response.body() != null) {
                            try {
                                JSONObject chInfo = new JSONObject(response.body().string());
                                String gid = chInfo.optString("guild_id", "");
                                if (!gid.isEmpty()) {
                                    guildId = gid;
                                    prefs.edit().putString("guild_id", guildId).apply();
                                    log("Auto-detected Server ID: " + guildId);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                });
                res.put("status", "ok");
                res.put("message", "Resolving Server ID via Discord API...");
            } else if ("add_token".equals(action)) {
                String t = payload.optString("token", "").trim();
                if (!t.isEmpty()) {
                    Account a = addAccount(t);
                    res.put("status", "ok");
                    res.put("message", "Added token for account #" + a.id);
                } else {
                    res.put("status", "error");
                    res.put("message", "Empty token");
                }
            } else if ("remove_token".equals(action)) {
                String target = payload.optString("target", "");
                if ("all".equalsIgnoreCase(target)) {
                    for (int id : new ArrayList<>(accounts.keySet())) {
                        removeAccount(id);
                    }
                    res.put("status", "ok");
                    res.put("message", "Removed all accounts");
                } else {
                    try {
                        int id = Integer.parseInt(target);
                        removeAccount(id);
                        res.put("status", "ok");
                        res.put("message", "Removed account #" + id);
                    } catch (Exception e) {
                        res.put("status", "error");
                        res.put("message", "Invalid account ID");
                    }
                }
            } else if ("account_action".equals(action)) {
                int aid = payload.optInt("account_id", -1);
                String sub = payload.optString("subaction", "");
                Account target = accounts.get(aid);
                if (target != null) {
                    if ("move_default".equals(sub)) {
                        target.setVoiceState(this.guildId, this.defaultChannelId, false, false);
                    } else if ("move".equals(sub)) {
                        String ch = payload.optString("channel_id", this.defaultChannelId);
                        target.setVoiceState(this.guildId, ch, false, false);
                    } else if ("mute".equals(sub)) {
                        target.setVoiceState(this.guildId, target.voiceChannel, true, target.deaf);
                    } else if ("unmute".equals(sub)) {
                        target.setVoiceState(this.guildId, target.voiceChannel, false, target.deaf);
                    } else if ("deafen".equals(sub)) {
                        target.setVoiceState(this.guildId, target.voiceChannel, target.mute, true);
                    } else if ("undeafen".equals(sub)) {
                        target.setVoiceState(this.guildId, target.voiceChannel, target.mute, false);
                    } else if ("disconnect".equals(sub)) {
                        target.leaveVoice(this.guildId);
                    }
                    res.put("status", "ok");
                    res.put("message", "Executed " + sub + " on account #" + aid);
                } else {
                    res.put("status", "error");
                    res.put("message", "Account #" + aid + " not found");
                }
            } else if ("restart_all".equals(action)) {
                for (Account a : accounts.values()) {
                    a.reconnect(httpClient, this);
                }
                log("Reconnecting all accounts...");
                res.put("status", "ok");
                res.put("message", "Reconnecting all accounts...");
            } else if ("save_preset".equals(action)) {
                String name = payload.optString("name", "").trim();
                String ch = payload.optString("channel_id", "").trim();
                String gid = payload.optString("guild_id", this.guildId).trim();
                if (!name.isEmpty() && !ch.isEmpty()) {
                    // Remove if existing with same name
                    presets.removeIf(p -> p.optString("name").equalsIgnoreCase(name));
                    presets.add(new JSONObject().put("name", name).put("channel_id", ch).put("guild_id", gid));
                    savePresetsToPrefs();
                    res.put("status", "ok");
                    res.put("message", "Saved preset: " + name);
                }
            } else if ("delete_preset".equals(action)) {
                String name = payload.optString("name", "").trim();
                presets.removeIf(p -> p.optString("name").equalsIgnoreCase(name));
                savePresetsToPrefs();
                res.put("status", "ok");
                res.put("message", "Deleted preset: " + name);
            } else if ("exec_command".equals(action)) {
                String cmd = payload.optString("command", "").trim();
                log("Command executed: " + cmd);
                res.put("status", "ok");
                res.put("message", "Executed command: " + cmd);
            } else {
                res.put("status", "ok");
                res.put("message", "Action processed: " + action);
            }
        } catch (Exception e) {
            try {
                res.put("status", "error");
                res.put("message", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    public JSONObject handleTokensPost(JSONObject payload) {
        JSONObject res = new JSONObject();
        try {
            JSONArray tokensArr = payload.optJSONArray("tokens");
            int count = 0;
            if (tokensArr != null) {
                for (int i = 0; i < tokensArr.length(); i++) {
                    String t = tokensArr.getString(i).trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        addAccountInternal(t);
                        count++;
                    }
                }
                saveTokensToPrefs();
            }
            res.put("status", "ok");
            res.put("message", "Imported " + count + " token(s) successfully");
        } catch (Exception e) {
            try {
                res.put("status", "error");
                res.put("message", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    public JSONObject getStatusJson() {
        JSONObject json = new JSONObject();
        try {
            int onlineCount = 0;
            int voiceCount = 0;
            JSONArray accArray = new JSONArray();

            for (Account a : accounts.values()) {
                if (a.online) onlineCount++;
                if (a.voiceChannel != null) voiceCount++;

                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("user_id", a.userId);
                o.put("online", a.online);
                o.put("voice_channel", a.voiceChannel);
                o.put("mute", a.mute);
                o.put("deaf", a.deaf);
                String masked = a.token.length() > 14 ? a.token.substring(0, 8) + "..." + a.token.substring(a.token.length() - 4) : "******";
                o.put("token_masked", masked);
                accArray.put(o);
            }

            json.put("total_accounts", accounts.size());
            json.put("online_accounts", onlineCount);
            json.put("voice_accounts", voiceCount);
            json.put("uptime", (System.currentTimeMillis() - startTime) / 1000);
            json.put("default_guild", guildId);
            json.put("default_channel", defaultChannelId);
            json.put("device_type", deviceMode);
            json.put("presets", getPresetsArray());
            json.put("accounts", accArray);
        } catch (Exception e) {
            Log.e(TAG, "Status error", e);
        }
        return json;
    }

    public JSONArray getPresetsArray() {
        JSONArray arr = new JSONArray();
        for (JSONObject p : presets) {
            arr.put(p);
        }
        return arr;
    }

    public JSONObject getPresetsJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("presets", getPresetsArray());
        } catch (Exception ignored) {}
        return json;
    }

    public JSONObject getLogsJson() {
        JSONObject json = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            synchronized (logs) {
                for (String l : logs) {
                    arr.put(l);
                }
            }
            json.put("logs", arr);
        } catch (Exception ignored) {}
        return json;
    }

    public JSONObject getTokensJson() {
        JSONObject json = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (Account a : accounts.values()) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                String masked = a.token.length() > 14 ? a.token.substring(0, 8) + "..." + a.token.substring(a.token.length() - 4) : "******";
                o.put("token_masked", masked);
                o.put("online", a.online);
                arr.put(o);
            }
            json.put("tokens", arr);
        } catch (Exception ignored) {}
        return json;
    }

    public static class Account {
        public final int id;
        public final String token;
        public String name = "Connecting...";
        public String userId = "";
        public boolean online = false;
        public String voiceChannel = null;
        public boolean mute = false;
        public boolean deaf = false;

        private WebSocket ws;
        private Handler heartHandler;
        private Runnable heartRunnable;

        public Account(int id, String token) {
            this.id = id;
            this.token = token;
        }

        public void reconnect(OkHttpClient client, DiscordManager mgr) {
            disconnect();
            connect(client, mgr);
        }

        public void connect(OkHttpClient client, DiscordManager mgr) {
            disconnect();
            Request req = new Request.Builder()
                    .url("wss://gateway.discord.gg/?v=9&encoding=json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    mgr.log("Account #" + id + " Gateway connection opened.");
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JSONObject msg = new JSONObject(text);
                        int op = msg.optInt("op", -1);

                        if (op == 10) { // HELLO
                            JSONObject d = msg.getJSONObject("d");
                            int interval = d.getInt("heartbeat_interval");
                            startHeartbeat(interval);
                            sendIdentify(webSocket, mgr.deviceMode);
                        } else if (op == 0) { // DISPATCH
                            String t = msg.optString("t", "");
                            if ("READY".equals(t)) {
                                online = true;
                                JSONObject user = msg.getJSONObject("d").getJSONObject("user");
                                name = user.optString("username", "Account #" + id);
                                userId = user.optString("id", "");
                                mgr.log("Account #" + id + " 🟢 READY as " + name + " (" + userId + ")");
                            } else if ("VOICE_STATE_UPDATE".equals(t)) {
                                JSONObject d = msg.getJSONObject("d");
                                if (userId.equals(d.optString("user_id"))) {
                                    voiceChannel = d.optString("channel_id", null);
                                    mute = d.optBoolean("self_mute", false);
                                    deaf = d.optBoolean("self_deaf", false);
                                    mgr.log("Account #" + id + " Voice updated: " + (voiceChannel != null ? voiceChannel : "Left voice"));
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onMessage error", e);
                    }
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    online = false;
                    stopHeartbeat();
                    mgr.log("Account #" + id + " disconnected (Code: " + code + ")");
                    // If not auth failed, attempt reconnect in 5s
                    if (code != 4004) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> reconnect(client, mgr), 5000);
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    online = false;
                    stopHeartbeat();
                    mgr.log("Account #" + id + " connection failed: " + t.getMessage());
                    new Handler(Looper.getMainLooper()).postDelayed(() -> reconnect(client, mgr), 7000);
                }
            });
        }

        private void sendIdentify(WebSocket ws, String deviceMode) {
            try {
                JSONObject identify = new JSONObject();
                identify.put("op", 2);
                JSONObject d = new JSONObject();
                d.put("token", this.token);
                d.put("intents", 0);

                JSONObject props = new JSONObject();
                if ("desktop".equalsIgnoreCase(deviceMode)) {
                    props.put("$os", "windows");
                    props.put("$browser", "chrome");
                    props.put("$device", "pc");
                } else {
                    props.put("$os", "Android");
                    props.put("$browser", "Discord Android");
                    props.put("$device", "Android");
                }
                d.put("properties", props);
                identify.put("d", d);

                ws.send(identify.toString());
            } catch (Exception e) {
                Log.e(TAG, "Identify error", e);
            }
        }

        private void startHeartbeat(int intervalMs) {
            stopHeartbeat();
            heartHandler = new Handler(Looper.getMainLooper());
            heartRunnable = new Runnable() {
                @Override
                public void run() {
                    if (ws != null) {
                        ws.send("{\"op\":1,\"d\":null}");
                        if (heartHandler != null) {
                            heartHandler.postDelayed(this, intervalMs);
                        }
                    }
                }
            };
            long initialDelay = (long) (intervalMs * Math.random());
            heartHandler.postDelayed(heartRunnable, initialDelay);
        }

        private void stopHeartbeat() {
            if (heartHandler != null && heartRunnable != null) {
                heartHandler.removeCallbacks(heartRunnable);
                heartHandler = null;
                heartRunnable = null;
            }
        }

        public void setVoiceState(String guildId, String channelId, boolean mute, boolean deaf) {
            if (ws != null && guildId != null && !guildId.isEmpty()) {
                try {
                    JSONObject op4 = new JSONObject();
                    op4.put("op", 4);
                    JSONObject d = new JSONObject();
                    d.put("guild_id", guildId);
                    d.put("channel_id", channelId);
                    d.put("self_mute", mute);
                    d.put("self_deaf", deaf);
                    op4.put("d", d);
                    ws.send(op4.toString());
                    this.voiceChannel = channelId;
                    this.mute = mute;
                    this.deaf = deaf;
                } catch (Exception e) {
                    Log.e(TAG, "Voice state update error", e);
                }
            }
        }

        public void leaveVoice(String guildId) {
            if (ws != null && guildId != null) {
                try {
                    JSONObject op4 = new JSONObject();
                    op4.put("op", 4);
                    JSONObject d = new JSONObject();
                    d.put("guild_id", guildId);
                    d.put("channel_id", JSONObject.NULL);
                    d.put("self_mute", false);
                    d.put("self_deaf", false);
                    op4.put("d", d);
                    ws.send(op4.toString());
                    this.voiceChannel = null;
                } catch (Exception ignored) {}
            }
        }

        public void disconnect() {
            stopHeartbeat();
            if (ws != null) {
                try {
                    ws.close(1000, "Clean shutdown");
                } catch (Exception ignored) {}
                ws = null;
            }
            online = false;
            voiceChannel = null;
        }
    }
}
