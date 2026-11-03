package com.hubg50we.dc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

        loadSavedTokens();
    }

    public void log(String msg) {
        Log.i(TAG, msg);
        logs.add(msg);
        if (logs.size() > 200) {
            logs.remove(0);
        }
    }

    private void loadSavedTokens() {
        String saved = prefs.getString("tokens", "");
        if (!saved.isEmpty()) {
            for (String tok : saved.split("\n")) {
                String clean = tok.trim();
                if (!clean.isEmpty()) {
                    addAccount(clean);
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

    public synchronized Account addAccount(String token) {
        for (Account a : accounts.values()) {
            if (a.token.equals(token)) {
                return a;
            }
        }
        Account a = new Account(nextAccountId++, token);
        accounts.put(a.id, a);
        saveTokensToPrefs();
        connectAccount(a);
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
        log("Moved " + Math.min(count, online.size()) + " account(s) to " + channelId);
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
        log((mute ? "Muted " : "Unmuted ") + Math.min(count, inVoice.size()) + " account(s)");
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
        log((deaf ? "Deafened " : "Undeafened ") + Math.min(count, inVoice.size()) + " account(s)");
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
        log("Disconnected " + Math.min(count, inVoice.size()) + " account(s)");
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
                this.guildId = payload.optString("guild_id", this.guildId);
                this.defaultChannelId = payload.optString("channel_id", this.defaultChannelId);
                prefs.edit().putString("guild_id", this.guildId).putString("channel_id", this.defaultChannelId).apply();
                res.put("status", "ok");
                res.put("message", "Configuration saved");
            } else if ("set_device_mode".equals(action)) {
                this.deviceMode = payload.optString("device_mode", "mobile");
                prefs.edit().putString("device_mode", this.deviceMode).apply();
                res.put("status", "ok");
                res.put("message", "Device appearance updated to " + this.deviceMode);
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
            json.put("accounts", accArray);
        } catch (Exception e) {
            Log.e(TAG, "Status error", e);
        }
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

        public void connect(OkHttpClient client, DiscordManager mgr) {
            disconnect();
            Request req = new Request.Builder()
                    .url("wss://gateway.discord.gg/?v=9&encoding=json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    mgr.log("Account #" + id + " Gateway connected.");
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
                                mgr.log("Account #" + id + " logged in as " + name);
                            } else if ("VOICE_STATE_UPDATE".equals(t)) {
                                JSONObject d = msg.getJSONObject("d");
                                if (userId.equals(d.optString("user_id"))) {
                                    voiceChannel = d.optString("channel_id", null);
                                    mute = d.optBoolean("self_mute", false);
                                    deaf = d.optBoolean("self_deaf", false);
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
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    online = false;
                    stopHeartbeat();
                    mgr.log("Account #" + id + " disconnected: " + t.getMessage());
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
            heartHandler.postDelayed(heartRunnable, intervalMs);
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
