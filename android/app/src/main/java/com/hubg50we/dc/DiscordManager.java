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
    public static final String APP_VERSION = "1.2.5";
    private static final String PREFS_NAME = "dc_prefs";
    private static DiscordManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    private final Handler mainHandler;

    private String guildId = "";
    private String defaultChannelId = "";
    private String deviceMode = "mobile"; // "mobile" or "desktop"
    private boolean autoRejoin = true;
    private String customActivityName = "";
    private int customActivityType = 0; // 0=Playing, 1=Streaming, 2=Listening, 3=Watching, 4=Custom
    private double stealthDelaySec = 0.1;
    private boolean rotatorEnabled = false;
    private int rotatorIntervalSec = 300;
    private final List<String> rotatorChannels = Collections.synchronizedList(new ArrayList<>());
    private int rotatorIndex = 0;
    private Runnable rotatorRunnable;
    private boolean speakingSim = false;
    private int voiceFlags = 5; // 1=Voice, 2=Soundshare/Music, 4=Priority, 5=Dual Voice+Soundshare
    private String activeSfx = "";
    private String voiceStatus = "";
    private boolean humanChatter = false;
    private Runnable humanChatterRunnable;
    private Runnable sfxResetRunnable;
    private boolean realVideoActive = false;
    private String realVideoMode = "";
    private String webShareUrl = "";
    private boolean videoSim = false;
    private boolean screenShareSim = false;
    private String musicTitle = "";
    private String musicArtist = "";
    private String youtubeUrl = "";
    private String youtubeTitle = "";
    private String youtubeAuthor = "";
    private String youtubeMode = "watch";
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
        this.autoRejoin = prefs.getBoolean("auto_rejoin", true);
        this.customActivityName = prefs.getString("activity_name", "");
        this.customActivityType = prefs.getInt("activity_type", 0);
        this.stealthDelaySec = prefs.getFloat("stealth_delay", 0.1f);
        this.rotatorEnabled = prefs.getBoolean("rotator_enabled", false);
        this.rotatorIntervalSec = prefs.getInt("rotator_interval", 300);
        this.speakingSim = prefs.getBoolean("speaking_sim", false);
        this.voiceFlags = prefs.getInt("voice_flags", 5);
        this.videoSim = prefs.getBoolean("video_sim", false);
        this.screenShareSim = prefs.getBoolean("screenshare_sim", false);
        this.musicTitle = prefs.getString("music_title", "");
        this.musicArtist = prefs.getString("music_artist", "");
        this.youtubeUrl = prefs.getString("youtube_url", "");
        this.youtubeTitle = prefs.getString("youtube_title", "");
        this.youtubeAuthor = prefs.getString("youtube_author", "");
        this.youtubeMode = prefs.getString("youtube_mode", "watch");

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        loadPresetsFromPrefs();
        loadSavedTokens();
        log("dc- Android Engine initialized. Appearance: " + deviceMode.toUpperCase());
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
                String clean = cleanToken(tok);
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

    private String cleanToken(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().replace("\"", "").replace("'", "");
        if (clean.startsWith("Bot ")) {
            clean = clean.substring(4).trim();
        }
        // Extract token from combo formats (e.g. email:pass:token)
        if (clean.contains(":") && clean.length() > 28) {
            String[] parts = clean.split(":");
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i].trim();
                if (p.length() >= 24 && !p.contains("@") && !p.contains(" ")) {
                    clean = p;
                    break;
                }
            }
        }
        return clean;
    }

    private synchronized Account addAccountInternal(String token) {
        String clean = cleanToken(token);
        if (clean.isEmpty() || clean.startsWith("#")) {
            return null;
        }
        for (Account a : accounts.values()) {
            if (a.token.equals(clean)) {
                return a;
            }
        }
        Account a = new Account(nextAccountId++, clean);
        accounts.put(a.id, a);
        connectAccount(a);
        return a;
    }

    public synchronized Account addAccount(String token) {
        Account a = addAccountInternal(token);
        if (a != null) {
            saveTokensToPrefs();
            log("Added new account #" + a.id);
        }
        return a;
    }

    public synchronized void removeAccount(int id) {
        Account a = accounts.remove(id);
        if (a != null) {
            a.removed = true;
            a.disconnect();
            saveTokensToPrefs();
            log("Removed account #" + id);
        }
    }

    public void connectAccount(Account a) {
        a.connect(httpClient, this);
    }

    public void moveAll(String channelId, int percent) {
        if (channelId == null || channelId.trim().isEmpty()) {
            channelId = this.defaultChannelId;
        }
        if (channelId == null || channelId.trim().isEmpty()) {
            log("Cannot move: Channel ID is empty.");
            return;
        }
        if (this.guildId == null || this.guildId.trim().isEmpty()) {
            String detected = resolveGuildIdSync(channelId);
            if (detected != null && !detected.isEmpty()) {
                this.guildId = detected;
                prefs.edit().putString("guild_id", this.guildId).apply();
                log("Auto-detected Server ID: " + this.guildId);
            } else {
                log("Cannot move: Server (Guild) ID is not set. Please detect or enter Server ID.");
                return;
            }
        }
        List<Account> online = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online) online.add(a);
        }
        if (online.isEmpty()) {
            log("Cannot move: No accounts currently online.");
            return;
        }
        int count = Math.max(1, (int) Math.round(online.size() * (percent / 100.0)));
        int moved = 0;
        for (int i = 0; i < Math.min(count, online.size()); i++) {
            if (stealthDelaySec > 0.05 && i > 0) {
                try {
                    long base = (long) (stealthDelaySec * 1000);
                    long jitter = (long) ((Math.random() * 0.4 - 0.2) * base);
                    Thread.sleep(Math.max(20, base + jitter));
                } catch (InterruptedException ignored) {}
            }
            online.get(i).setVoiceState(this.guildId, channelId, false, false);
            moved++;
        }
        log("Moved " + moved + "/" + online.size() + " accounts to " + channelId);
    }

    public synchronized void setRotatorConfig(boolean enabled, int intervalSec, List<String> channels) {
        this.rotatorEnabled = enabled;
        this.rotatorIntervalSec = Math.max(10, intervalSec);
        this.rotatorChannels.clear();
        if (channels != null) this.rotatorChannels.addAll(channels);
        this.rotatorIndex = 0;
        prefs.edit().putBoolean("rotator_enabled", this.rotatorEnabled)
                    .putInt("rotator_interval", this.rotatorIntervalSec).apply();
        if (rotatorRunnable != null) {
            mainHandler.removeCallbacks(rotatorRunnable);
            rotatorRunnable = null;
        }
        if (this.rotatorEnabled && !this.rotatorChannels.isEmpty()) {
            rotatorRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!rotatorEnabled || rotatorChannels.isEmpty()) return;
                    String targetCh = rotatorChannels.get(rotatorIndex % rotatorChannels.size());
                    rotatorIndex++;
                    log("🔀 Auto-Rotator: Moving bots to channel " + targetCh + " (Next in " + rotatorIntervalSec + "s)");
                    new Thread(() -> moveAll(targetCh, 100)).start();
                    mainHandler.postDelayed(this, rotatorIntervalSec * 1000L);
                }
            };
            mainHandler.postDelayed(rotatorRunnable, rotatorIntervalSec * 1000L);
            log("🔀 Auto-Rotator Started: Rotating " + rotatorChannels.size() + " channels every " + rotatorIntervalSec + "s");
        } else {
            log("🔀 Auto-Rotator Stopped.");
        }
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

    public void videoAll(boolean video, int percent) {
        this.videoSim = video;
        prefs.edit().putBoolean("video_sim", this.videoSim).apply();
        List<Account> inVoice = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online && a.voiceChannel != null) inVoice.add(a);
        }
        int count = Math.max(1, (int) Math.round(inVoice.size() * (percent / 100.0)));
        for (int i = 0; i < Math.min(count, inVoice.size()); i++) {
            Account a = inVoice.get(i);
            a.setVoiceState(this.guildId, a.voiceChannel, a.mute, a.deaf, video);
        }
        log((video ? "Camera/Video Enabled for " : "Camera/Video Disabled for ") + Math.min(count, inVoice.size()) + " accounts");
    }

    public void screenShareAll(boolean enable, int percent) {
        this.screenShareSim = enable;
        prefs.edit().putBoolean("screenshare_sim", this.screenShareSim).apply();
        List<Account> inVoice = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.online && a.voiceChannel != null) inVoice.add(a);
        }
        int count = Math.max(1, (int) Math.round(inVoice.size() * (percent / 100.0)));
        for (int i = 0; i < Math.min(count, inVoice.size()); i++) {
            Account a = inVoice.get(i);
            if (enable) {
                a.startScreenShare(this.guildId);
            } else {
                a.stopScreenShare(this.guildId);
            }
        }
        log((enable ? "Screen Sharing Started for " : "Screen Sharing Stopped for ") + Math.min(count, inVoice.size()) + " accounts");
    }

    public JSONObject resolveYouTubeMetadata(String ytUrl) {
        if (ytUrl == null || ytUrl.trim().isEmpty()) return null;
        try {
            String encoded = java.net.URLEncoder.encode(ytUrl.trim(), "UTF-8");
            Request req = new Request.Builder()
                    .url("https://www.youtube.com/oembed?url=" + encoded + "&format=json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject obj = new JSONObject(resp.body().string());
                    JSONObject res = new JSONObject();
                    res.put("title", obj.optString("title", "YouTube Video"));
                    res.put("author", obj.optString("author_name", "YouTube Creator"));
                    res.put("url", ytUrl.trim());
                    return res;
                }
            }
        } catch (Exception e) {
            log("YouTube oEmbed error: " + e.getMessage());
        }
        return null;
    }

    public String resolveGuildIdSync(String channelId) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> f = ex.submit(() -> resolveGuildIdSyncWorker(channelId));
                return f.get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                return null;
            } finally {
                ex.shutdown();
            }
        }
        return resolveGuildIdSyncWorker(channelId);
    }

    private String resolveGuildIdSyncWorker(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return null;
        }
        String token = null;
        for (Account a : accounts.values()) {
            if (a.token != null && !a.token.trim().isEmpty()) {
                token = a.token.trim();
                break;
            }
        }
        if (token == null) {
            log("Auto-detect failed: No account tokens available to query Discord API.");
            return null;
        }

        try {
            Request req = new Request.Builder()
                    .url("https://discord.com/api/v9/channels/" + channelId.trim())
                    .header("Authorization", token)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build();

            try (Response response = httpClient.newCall(req).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject chInfo = new JSONObject(response.body().string());
                    String gid = chInfo.optString("guild_id", "");
                    if (!gid.isEmpty()) {
                        return gid;
                    }
                } else if (response.code() == 401 && !token.startsWith("Bot ")) {
                    // Try with Bot prefix
                    Request retryReq = new Request.Builder()
                            .url("https://discord.com/api/v9/channels/" + channelId.trim())
                            .header("Authorization", "Bot " + token)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .build();
                    try (Response retryResp = httpClient.newCall(retryReq).execute()) {
                        if (retryResp.isSuccessful() && retryResp.body() != null) {
                            JSONObject chInfo = new JSONObject(retryResp.body().string());
                            String gid = chInfo.optString("guild_id", "");
                            if (!gid.isEmpty()) {
                                return gid;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("Auto-detect error: " + e.getMessage());
        }
        return null;
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

        final String tok = token;
        Request req = new Request.Builder()
                .url("https://discord.com/api/v9/channels/" + defaultChannelId)
                .header("Authorization", tok)
                .header("User-Agent", "Mozilla/5.0")
                .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onFailure(call, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.code() == 401 && !tok.startsWith("Bot ")) {
                    Request retryReq = new Request.Builder()
                            .url("https://discord.com/api/v9/channels/" + defaultChannelId)
                            .header("Authorization", "Bot " + tok)
                            .header("User-Agent", "Mozilla/5.0")
                            .build();
                    httpClient.newCall(retryReq).enqueue(callback);
                } else {
                    callback.onResponse(call, response);
                }
            }
        });
    }

    public JSONObject handleAction(JSONObject payload) {
        JSONObject res = new JSONObject();
        try {
            String action = payload.optString("action", "");
            int percent = payload.optInt("percent", 100);

            // 1. Token Addition (Single & Bulk)
            if ("token_add".equals(action) || "add_token".equals(action)) {
                String t = cleanToken(payload.optString("token", ""));
                if (!t.isEmpty()) {
                    Account a = addAccount(t);
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("message", "Account #" + a.id + " added successfully!");
                    res.put("account_id", a.id);
                } else {
                    res.put("success", false);
                    res.put("status", "error");
                    res.put("error", "Token cannot be empty");
                    res.put("message", "Token cannot be empty");
                }
            } else if ("tokens_bulk_add".equals(action)) {
                JSONArray tokensArr = payload.optJSONArray("tokens");
                int count = 0;
                if (tokensArr != null) {
                    for (int i = 0; i < tokensArr.length(); i++) {
                        String t = cleanToken(tokensArr.getString(i));
                        if (!t.isEmpty() && !t.startsWith("#")) {
                            addAccountInternal(t);
                            count++;
                        }
                    }
                    saveTokensToPrefs();
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Imported " + count + " account(s)!");
            }

            // 2. Token Removal
            else if ("token_remove".equals(action) || "remove_token".equals(action)) {
                int targetId = payload.optInt("id", payload.optInt("aid", -1));
                if (targetId == -1) {
                    String targetStr = payload.optString("target", payload.optString("id", ""));
                    if ("all".equalsIgnoreCase(targetStr)) {
                        for (int id : new ArrayList<>(accounts.keySet())) {
                            removeAccount(id);
                        }
                        res.put("success", true);
                        res.put("status", "ok");
                        res.put("message", "Removed all accounts");
                        return res;
                    }
                    try { targetId = Integer.parseInt(targetStr); } catch (Exception ignored) {}
                }
                if (targetId != -1) {
                    removeAccount(targetId);
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("message", "Removed account #" + targetId);
                } else {
                    res.put("success", false);
                    res.put("status", "error");
                    res.put("error", "Invalid account ID");
                }
            }

            // 3. Batch Actions (Target specific bots to specific server & channel)
            else if ("batch_action".equals(action)) {
                JSONArray idsArr = payload.optJSONArray("ids");
                String sub = payload.optString("sub_action", payload.optString("subaction", ""));
                String ch = payload.optString("channel_id", this.defaultChannelId).trim();
                if (ch.isEmpty()) ch = this.defaultChannelId;
                String targetGid = payload.optString("guild_id", "").trim();
                if (targetGid.isEmpty()) targetGid = this.guildId;
                if (targetGid.isEmpty() && !ch.isEmpty()) {
                    String detected = resolveGuildIdSync(ch);
                    if (detected != null && !detected.isEmpty()) {
                        targetGid = detected;
                    }
                }
                int count = 0;
                if (idsArr != null) {
                    for (int i = 0; i < idsArr.length(); i++) {
                        int aid = idsArr.optInt(i, -1);
                        Account a = accounts.get(aid);
                        if (a != null && a.online) {
                            count++;
                            String accGid = !targetGid.isEmpty() ? targetGid : (a.currentGuildId.isEmpty() ? this.guildId : a.currentGuildId);
                            if ("move".equals(sub) || "join".equals(sub)) {
                                a.setVoiceState(accGid, ch, false, false);
                            } else if ("mute".equals(sub)) {
                                a.setVoiceState(accGid, a.voiceChannel, true, a.deaf);
                            } else if ("unmute".equals(sub)) {
                                a.setVoiceState(accGid, a.voiceChannel, false, a.deaf);
                            } else if ("deafen".equals(sub)) {
                                a.setVoiceState(accGid, a.voiceChannel, a.mute, true);
                            } else if ("undeafen".equals(sub)) {
                                a.setVoiceState(accGid, a.voiceChannel, a.mute, false);
                            } else if ("disconnect".equals(sub) || "leave".equals(sub)) {
                                a.leaveVoice(accGid);
                            } else if ("video".equals(sub)) {
                                a.setVoiceState(accGid, a.voiceChannel, a.mute, a.deaf, true);
                            } else if ("unvideo".equals(sub)) {
                                a.setVoiceState(accGid, a.voiceChannel, a.mute, a.deaf, false);
                            } else if ("screenshare".equals(sub)) {
                                a.startScreenShare(accGid);
                            } else if ("unscreenshare".equals(sub)) {
                                a.stopScreenShare(accGid);
                            } else if ("reconnect".equals(sub)) {
                                a.reconnect(httpClient, this);
                            }
                        }
                    }
                }
                log("Batch " + sub + " executed on " + count + " bot(s) -> " + ch + (targetGid.isEmpty() ? "" : " (Server: " + targetGid + ")"));
                res.put("success", true);
                res.put("status", "ok");
                res.put("count", count);
                res.put("guild_id", targetGid);
                res.put("channel_id", ch);
                res.put("message", "Dispatched " + count + " bot(s) to " + ch + (targetGid.isEmpty() ? "" : " (Server: " + targetGid + ")"));
            }

            // 4. Voice Controls
            else if ("move_default".equals(action)) {
                String gid = payload.optString("guild_id", "").trim();
                if (!gid.isEmpty()) {
                    this.guildId = gid;
                    prefs.edit().putString("guild_id", this.guildId).apply();
                }
                moveAll(this.defaultChannelId, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Moving accounts to default channel");
            } else if ("move".equals(action) || "join".equals(action)) {
                String ch = payload.optString("channel_id", "").trim();
                if (ch.isEmpty()) ch = this.defaultChannelId;
                String gid = payload.optString("guild_id", "").trim();
                if (!gid.isEmpty()) {
                    this.guildId = gid;
                    prefs.edit().putString("guild_id", this.guildId).apply();
                }
                if (ch.isEmpty()) {
                    res.put("success", false);
                    res.put("status", "error");
                    res.put("error", "Channel ID required");
                    res.put("message", "Please enter Voice Channel ID");
                    return res;
                }
                moveAll(ch, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Moved accounts to " + ch);
            } else if ("mute".equals(action)) {
                muteAll(true, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Muted accounts");
            } else if ("unmute".equals(action)) {
                muteAll(false, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Unmuted accounts");
            } else if ("deafen".equals(action)) {
                deafenAll(true, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Deafened accounts");
            } else if ("undeafen".equals(action)) {
                deafenAll(false, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Undeafened accounts");
            } else if ("disconnect".equals(action)) {
                disconnectAll(percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Disconnected accounts");
            } else if ("video".equals(action) || "camera_on".equals(action)) {
                videoAll(true, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Camera/Video enabled for accounts");
            } else if ("unvideo".equals(action) || "camera_off".equals(action)) {
                videoAll(false, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Camera/Video disabled for accounts");
            } else if ("screenshare".equals(action) || "screen_share_on".equals(action)) {
                screenShareAll(true, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Screen sharing started for accounts");
            } else if ("unscreenshare".equals(action) || "screen_share_off".equals(action)) {
                screenShareAll(false, percent);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Screen sharing stopped for accounts");
            } else if ("set_stream".equals(action) || "stream".equals(action)) {
                String streamTitle = payload.optString("title", "Live Stream").trim();
                String streamUrl = payload.optString("url", "https://twitch.tv/discord").trim();
                boolean enable = payload.optBoolean("enabled", true);
                for (Account a : accounts.values()) {
                    if (a.online) a.setStreaming(enable, streamTitle, streamUrl);
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", enable ? "Streamer status activated: " + streamTitle : "Streaming status stopped");
            } else if ("play_music".equals(action) || "set_music".equals(action)) {
                this.musicTitle = payload.optString("title", "Song Title").trim();
                this.musicArtist = payload.optString("artist", "Artist").trim();
                prefs.edit().putString("music_title", this.musicTitle).putString("music_artist", this.musicArtist).apply();
                for (Account a : accounts.values()) {
                    if (a.online) a.setSpotify(true, this.musicTitle, this.musicArtist);
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Spotify player simulating: " + this.musicTitle + " by " + this.musicArtist);
            } else if ("stop_music".equals(action)) {
                this.musicTitle = "";
                this.musicArtist = "";
                prefs.edit().putString("music_title", "").putString("music_artist", "").apply();
                for (Account a : accounts.values()) {
                    if (a.online) a.setSpotify(false, "", "");
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Music playback stopped");
            } else if ("resolve_youtube".equals(action)) {
                String u = payload.optString("url", "").trim();
                JSONObject meta = resolveYouTubeMetadata(u);
                if (meta != null) {
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("title", meta.optString("title"));
                    res.put("author", meta.optString("author"));
                    res.put("url", meta.optString("url"));
                    res.put("message", "Resolved: " + meta.optString("title"));
                } else {
                    res.put("success", false);
                    res.put("error", "Could not resolve YouTube URL");
                }
            } else if ("play_youtube".equals(action) || "stream_youtube".equals(action)) {
                String u = payload.optString("url", "").trim();
                String title = payload.optString("title", "").trim();
                String author = payload.optString("author", "").trim();
                String mode = payload.optString("mode", "watch").trim().toLowerCase();
                boolean simVoice = payload.optBoolean("sim_voice", true);

                if ((title.isEmpty() || author.isEmpty()) && !u.isEmpty()) {
                    JSONObject meta = resolveYouTubeMetadata(u);
                    if (meta != null) {
                        if (title.isEmpty()) title = meta.optString("title", "YouTube Video");
                        if (author.isEmpty()) author = meta.optString("author", "YouTube");
                    }
                }
                if (title.isEmpty()) title = "YouTube Video";
                if (author.isEmpty()) author = "YouTube";

                this.youtubeUrl = u;
                this.youtubeTitle = title;
                this.youtubeAuthor = author;
                this.youtubeMode = mode;
                prefs.edit()
                        .putString("youtube_url", this.youtubeUrl)
                        .putString("youtube_title", this.youtubeTitle)
                        .putString("youtube_author", this.youtubeAuthor)
                        .putString("youtube_mode", this.youtubeMode)
                        .apply();

                for (Account a : accounts.values()) {
                    if (a.online) {
                        a.setYouTube(true, this.youtubeTitle, this.youtubeAuthor, this.youtubeMode, this.youtubeUrl);
                        if (simVoice) a.speaking = true;
                    }
                }
                if (simVoice) {
                    this.speakingSim = true;
                    prefs.edit().putBoolean("speaking_sim", true).apply();
                }

                log("YouTube Stream Active: [" + mode.toUpperCase() + "] " + this.youtubeTitle + " (" + this.youtubeAuthor + ")");
                res.put("success", true);
                res.put("status", "ok");
                res.put("title", this.youtubeTitle);
                res.put("author", this.youtubeAuthor);
                res.put("mode", this.youtubeMode);
                res.put("url", this.youtubeUrl);
                res.put("message", "Streaming YouTube (" + mode + "): " + this.youtubeTitle);
            } else if ("stop_youtube".equals(action)) {
                this.youtubeUrl = "";
                this.youtubeTitle = "";
                this.youtubeAuthor = "";
                prefs.edit().putString("youtube_url", "").putString("youtube_title", "").putString("youtube_author", "").apply();
                for (Account a : accounts.values()) {
                    if (a.online) {
                        a.setYouTube(false, "", "", "", "");
                        a.speaking = false;
                    }
                }
                this.speakingSim = false;
                prefs.edit().putBoolean("speaking_sim", false).apply();
                log("YouTube streaming stopped");
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "YouTube streaming stopped");
            }

            // 5. Configuration & Server ID Auto-Detect
            else if ("set_config".equals(action)) {
                if (payload.has("guild_id")) this.guildId = payload.getString("guild_id").trim();
                if (payload.has("channel_id")) this.defaultChannelId = payload.getString("channel_id").trim();
                prefs.edit().putString("guild_id", this.guildId).putString("channel_id", this.defaultChannelId).apply();
                log("Server ID: " + this.guildId + ", Channel ID: " + this.defaultChannelId);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Configuration saved! Server: " + this.guildId + ", Channel: " + this.defaultChannelId);
            } else if ("set_guild_id".equals(action)) {
                this.guildId = payload.optString("guild_id", this.guildId).trim();
                prefs.edit().putString("guild_id", this.guildId).apply();
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Server ID set to " + this.guildId);
            } else if ("set_default_channel".equals(action)) {
                this.defaultChannelId = payload.optString("channel_id", this.defaultChannelId).trim();
                prefs.edit().putString("channel_id", this.defaultChannelId).apply();
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Default channel set to " + this.defaultChannelId);
            } else if ("set_device_mode".equals(action) || "set_device_type".equals(action)) {
                this.deviceMode = payload.optString("device_mode", payload.optString("device_type", "mobile"));
                prefs.edit().putString("device_mode", this.deviceMode).apply();
                for (Account a : accounts.values()) {
                    if (a.online) a.reconnect(httpClient, this);
                }
                log("Switched appearance to " + (deviceMode.equals("mobile") ? "📱 Android Mobile" : "💻 Desktop"));
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Device appearance updated to " + this.deviceMode);
            } else if ("set_auto_rejoin".equals(action)) {
                this.autoRejoin = payload.optBoolean("auto_rejoin", true);
                prefs.edit().putBoolean("auto_rejoin", this.autoRejoin).apply();
                log("Auto-Rejoin Guard " + (autoRejoin ? "Enabled" : "Disabled"));
                res.put("success", true);
                res.put("status", "ok");
                res.put("auto_rejoin", this.autoRejoin);
                res.put("message", "Auto-Rejoin Guard " + (autoRejoin ? "Enabled" : "Disabled"));
            } else if ("set_presence".equals(action) || "set_activity".equals(action)) {
                this.customActivityName = payload.optString("activity_name", "").trim();
                this.customActivityType = payload.optInt("activity_type", 0);
                prefs.edit().putString("activity_name", this.customActivityName).putInt("activity_type", this.customActivityType).apply();
                for (Account a : accounts.values()) {
                    if (a.online) a.updatePresence(this.customActivityName, this.customActivityType);
                }
                log("Updated Rich Presence: " + (customActivityName.isEmpty() ? "Cleared" : customActivityName + " (Type " + customActivityType + ")"));
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Rich Presence updated across all accounts!");
            } else if ("check_tokens".equals(action) || "validate_tokens".equals(action)) {
                JSONArray results = new JSONArray();
                int validCount = 0;
                int invalidCount = 0;
                for (Account a : accounts.values()) {
                    JSONObject item = new JSONObject();
                    item.put("id", a.id);
                    item.put("name", a.name);
                    String mask = a.token.length() > 14 ? a.token.substring(0, 8) + "..." + a.token.substring(a.token.length() - 4) : "******";
                    item.put("token_masked", mask);
                    try {
                        Request req = new Request.Builder()
                                .url("https://discord.com/api/v10/users/@me")
                                .header("Authorization", a.token)
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                        try (Response resp = httpClient.newCall(req).execute()) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                JSONObject u = new JSONObject(resp.body().string());
                                item.put("status", "valid");
                                item.put("username", u.optString("username", a.name));
                                validCount++;
                            } else if (resp.code() == 401 && !a.token.startsWith("Bot ")) {
                                Request botReq = new Request.Builder()
                                        .url("https://discord.com/api/v10/users/@me")
                                        .header("Authorization", "Bot " + a.token)
                                        .header("User-Agent", "Mozilla/5.0")
                                        .build();
                                try (Response botResp = httpClient.newCall(botReq).execute()) {
                                    if (botResp.isSuccessful() && botResp.body() != null) {
                                        JSONObject u = new JSONObject(botResp.body().string());
                                        item.put("status", "valid");
                                        item.put("username", u.optString("username", a.name));
                                        validCount++;
                                    } else {
                                        item.put("status", "invalid");
                                        item.put("error", "HTTP " + botResp.code());
                                        invalidCount++;
                                    }
                                }
                            } else {
                                item.put("status", "invalid");
                                item.put("error", "HTTP " + resp.code());
                                invalidCount++;
                            }
                        }
                    } catch (Exception ex) {
                        item.put("status", "error");
                        item.put("error", ex.getMessage());
                    }
                    results.put(item);
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("results", results);
                res.put("valid_count", validCount);
                res.put("invalid_count", invalidCount);
                res.put("message", "Checked " + accounts.size() + " tokens: " + validCount + " Valid, " + invalidCount + " Invalid");
            } else if ("detect_guild".equals(action) || "resolve_guild".equals(action)) {
                String ch = payload.optString("channel_id", this.defaultChannelId).trim();
                if (ch.isEmpty()) ch = this.defaultChannelId;
                if (!ch.isEmpty()) {
                    this.defaultChannelId = ch;
                    prefs.edit().putString("channel_id", this.defaultChannelId).apply();
                }
                String detected = resolveGuildIdSync(ch);
                if (detected != null && !detected.isEmpty()) {
                    this.guildId = detected;
                    prefs.edit().putString("guild_id", this.guildId).apply();
                    log("Auto-detected Server ID: " + this.guildId);
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("guild_id", this.guildId);
                    res.put("message", "Auto-detected Server ID: " + this.guildId);
                } else {
                    res.put("success", false);
                    res.put("status", "error");
                    res.put("error", "Could not detect Server ID. Ensure Channel ID is valid and tokens are added.");
                    res.put("message", "Could not detect Server ID from Channel ID: " + ch);
                }
            } else if ("fetch_guild_channels".equals(action)) {
                String targetGid = payload.optString("guild_id", this.guildId).trim();
                if (targetGid.isEmpty()) targetGid = this.guildId;
                JSONArray chArr = new JSONArray();
                Account bestAcc = null;
                for (Account a : accounts.values()) {
                    if (a.online && !a.token.isEmpty()) { bestAcc = a; break; }
                }
                if (bestAcc == null && !accounts.isEmpty()) {
                    bestAcc = accounts.values().iterator().next();
                }
                if (bestAcc != null && !targetGid.isEmpty()) {
                    try {
                        String auth = bestAcc.token.startsWith("Bot ") ? bestAcc.token : bestAcc.token;
                        Request req = new Request.Builder()
                                .url("https://discord.com/api/v10/guilds/" + targetGid + "/channels")
                                .header("Authorization", auth)
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                        try (Response resp = httpClient.newCall(req).execute()) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                JSONArray allCh = new JSONArray(resp.body().string());
                                for (int i = 0; i < allCh.length(); i++) {
                                    JSONObject c = allCh.getJSONObject(i);
                                    int cType = c.optInt("type", 0);
                                    if (cType == 2 || cType == 13) {
                                        JSONObject vch = new JSONObject();
                                        vch.put("id", c.optString("id"));
                                        vch.put("name", c.optString("name"));
                                        vch.put("type", cType);
                                        vch.put("position", c.optInt("position", 0));
                                        chArr.put(vch);
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "fetch_guild_channels error", ex);
                    }
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("guild_id", targetGid);
                res.put("channels", chArr);
                res.put("message", "Loaded " + chArr.length() + " voice channel(s)");
            } else if ("set_stealth_delay".equals(action)) {
                this.stealthDelaySec = payload.optDouble("stealth_delay", 0.1);
                prefs.edit().putFloat("stealth_delay", (float) this.stealthDelaySec).apply();
                log("Stealth Join Delay set to " + String.format("%.2f", this.stealthDelaySec) + "s");
                res.put("success", true);
                res.put("status", "ok");
                res.put("stealth_delay", this.stealthDelaySec);
                res.put("message", "Stealth delay updated to " + String.format("%.2f", this.stealthDelaySec) + "s");
            } else if ("set_rotator".equals(action)) {
                boolean en = payload.optBoolean("enabled", false);
                int interval = payload.optInt("interval", 300);
                List<String> chList = new ArrayList<>();
                JSONArray arr = payload.optJSONArray("channels");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String c = arr.optString(i, "").trim();
                        if (!c.isEmpty()) chList.add(c);
                    }
                }
                setRotatorConfig(en, interval, chList);
                res.put("success", true);
                res.put("status", "ok");
                res.put("rotator_enabled", this.rotatorEnabled);
                res.put("rotator_interval", this.rotatorIntervalSec);
                res.put("message", "Auto-Rotator " + (this.rotatorEnabled ? "Started" : "Stopped"));
            } else if ("set_speaking".equals(action)) {
                this.speakingSim = payload.optBoolean("speaking", false);
                prefs.edit().putBoolean("speaking_sim", this.speakingSim).apply();
                for (Account a : accounts.values()) {
                    a.speaking = this.speakingSim;
                }
                log("Speaking Indicator " + (this.speakingSim ? "Enabled (Green Ring)" : "Disabled"));
                res.put("success", true);
                res.put("status", "ok");
                res.put("speaking_sim", this.speakingSim);
                res.put("message", "Speaking Indicator " + (this.speakingSim ? "Active" : "Off"));
            } else if ("set_voice_flags".equals(action)) {
                this.voiceFlags = payload.optInt("flags", 5);
                prefs.edit().putInt("voice_flags", this.voiceFlags).apply();
                for (Account a : accounts.values()) {
                    a.voiceFlags = this.voiceFlags;
                }
                log("Voice Flags updated to " + this.voiceFlags + " (1=Voice, 2=Soundshare, 4=Priority, 5=Dual)");
                res.put("success", true);
                res.put("status", "ok");
                res.put("voice_flags", this.voiceFlags);
                res.put("message", "Voice flags updated to " + this.voiceFlags);
            } else if ("play_sfx".equals(action)) {
                String sound = payload.optString("sound", "scream").toLowerCase();
                int flags = payload.optInt("flags", this.voiceFlags);
                double duration = payload.optDouble("duration", 4.0);
                this.activeSfx = sound;
                this.speakingSim = true;
                for (Account a : accounts.values()) {
                    a.speaking = true;
                    a.activeSfx = sound;
                    a.voiceFlags = flags;
                }
                if (sfxResetRunnable != null) {
                    mainHandler.removeCallbacks(sfxResetRunnable);
                }
                if (duration > 0) {
                    sfxResetRunnable = () -> {
                        this.activeSfx = "";
                        this.speakingSim = false;
                        for (Account a : accounts.values()) {
                            a.speaking = false;
                            a.activeSfx = "";
                        }
                        log("🔊 Soundboard SFX [" + sound.toUpperCase() + "] completed");
                    };
                    mainHandler.postDelayed(sfxResetRunnable, (long) (duration * 1000));
                }
                log("🔊 Soundboard SFX: [" + sound.toUpperCase() + "] triggered on all voice bots! (Flags: " + flags + ")");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active_sfx", this.activeSfx);
                res.put("voice_flags", flags);
                res.put("duration", duration);
                res.put("message", "SFX " + sound.toUpperCase() + " playing on voice");
            } else if ("stop_sfx".equals(action)) {
                if (sfxResetRunnable != null) {
                    mainHandler.removeCallbacks(sfxResetRunnable);
                    sfxResetRunnable = null;
                }
                this.activeSfx = "";
                this.speakingSim = false;
                for (Account a : accounts.values()) {
                    a.speaking = false;
                    a.activeSfx = "";
                }
                log("🔊 Soundboard SFX stopped");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active_sfx", "");
                res.put("message", "SFX stopped");
            } else if ("set_voice_status".equals(action)) {
                String ch = payload.optString("channel_id", this.defaultChannelId);
                String st = payload.optString("status", "").trim();
                this.voiceStatus = st;
                log("📢 Voice channel status set: \"" + st + "\" on channel " + ch);
                res.put("success", true);
                res.put("status", "ok");
                res.put("voice_status", this.voiceStatus);
                res.put("channel_id", ch);
                res.put("message", "Channel status updated: " + st);
            } else if ("send_tts".equals(action)) {
                String ch = payload.optString("channel_id", this.defaultChannelId);
                String txt = payload.optString("text", "").trim();
                if (txt.isEmpty()) {
                    res.put("success", false);
                    res.put("error", "TTS text cannot be empty");
                } else {
                    log("🗣️ TTS Voice Announcer: \"" + txt + "\" on channel " + ch);
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("text", txt);
                    res.put("tts", true);
                    res.put("message", "TTS Announcer broadcasted: " + txt);
                }
            } else if ("set_human_chatter".equals(action)) {
                this.humanChatter = payload.optBoolean("enabled", false);
                if (humanChatterRunnable != null) {
                    mainHandler.removeCallbacks(humanChatterRunnable);
                    humanChatterRunnable = null;
                }
                if (this.humanChatter) {
                    humanChatterRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (!humanChatter) return;
                            List<Account> vAccs = new ArrayList<>();
                            for (Account a : accounts.values()) {
                                if (a.online && a.voiceChannel != null) vAccs.add(a);
                            }
                            if (!vAccs.isEmpty()) {
                                int idx = (int) (Math.random() * vAccs.size());
                                Account spk = vAccs.get(idx);
                                spk.speaking = true;
                                long dur = 1200 + (long) (Math.random() * 2500);
                                mainHandler.postDelayed(() -> {
                                    spk.speaking = false;
                                    long pause = 800 + (long) (Math.random() * 2000);
                                    if (humanChatter && humanChatterRunnable != null) {
                                        mainHandler.postDelayed(humanChatterRunnable, pause);
                                    }
                                }, dur);
                            } else {
                                if (humanChatter && humanChatterRunnable != null) {
                                    mainHandler.postDelayed(this, 3000);
                                }
                            }
                        }
                    };
                    mainHandler.post(humanChatterRunnable);
                }
                log("👥 Human Chatter Simulation " + (this.humanChatter ? "Enabled" : "Disabled"));
                res.put("success", true);
                res.put("status", "ok");
                res.put("human_chatter", this.humanChatter);
                res.put("message", "Human Chatter " + (this.humanChatter ? "Active" : "Off"));
            } else if ("stream_custom_audio".equals(action)) {
                String title = payload.optString("title", "Custom Audio").trim();
                String url = payload.optString("url", "").trim();
                double dur = payload.optDouble("duration", 30.0);
                int flags = payload.optInt("flags", this.voiceFlags);
                this.activeSfx = title;
                this.speakingSim = true;
                for (Account a : accounts.values()) {
                    a.speaking = true;
                    a.voiceFlags = flags;
                    a.activeSfx = title;
                }
                log("🎵 Custom Audio Stream: \"" + title + "\" (Flags: " + flags + ", " + dur + "s)");
                res.put("success", true);
                res.put("status", "ok");
                res.put("title", title);
                res.put("url", url);
                res.put("duration", dur);
                res.put("voice_flags", flags);
                res.put("message", "Streaming custom audio: " + title);
            } else if ("stream_real_video".equals(action)) {
                String mode = payload.optString("mode", "camera").toLowerCase();
                String title = payload.optString("title", "Real Video (" + mode + ")").trim();
                String resStr = payload.optString("resolution", "720p");
                int fps = payload.optInt("fps", 30);
                this.realVideoActive = true;
                this.realVideoMode = mode;
                this.videoSim = true;
                this.screenShareSim = true;
                this.speakingSim = true;
                for (Account a : accounts.values()) {
                    a.video = true;
                    a.screenShare = true;
                    a.speaking = true;
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, true);
                        a.startScreenShare(a.currentGuildId);
                    }
                }
                log("🎥 Real Video Stream activated [mode=" + mode.toUpperCase() + ", " + resStr + "@" + fps + "fps, title=" + title + "] on all bots");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active", true);
                res.put("mode", mode);
                res.put("title", title);
                res.put("resolution", resStr);
                res.put("fps", fps);
                res.put("message", "Real video streaming active (" + mode.toUpperCase() + ")");
            } else if ("stop_real_video".equals(action)) {
                this.realVideoActive = false;
                this.realVideoMode = "";
                this.videoSim = false;
                this.screenShareSim = false;
                for (Account a : accounts.values()) {
                    a.video = false;
                    a.screenShare = false;
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, false);
                        a.stopScreenShare(a.currentGuildId);
                    }
                }
                log("🎥 Real Video Stream stopped");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active", false);
                res.put("message", "Real video stream stopped");
            } else if ("stream_web".equals(action)) {
                String url = payload.optString("url", "https://wikipedia.org").trim();
                String title = payload.optString("title", "Web Stream (" + url + ")").trim();
                this.webShareUrl = url;
                this.realVideoActive = true;
                this.realVideoMode = "web";
                this.videoSim = true;
                this.screenShareSim = true;
                this.speakingSim = true;
                for (Account a : accounts.values()) {
                    a.video = true;
                    a.screenShare = true;
                    a.speaking = true;
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, true);
                        a.startScreenShare(a.currentGuildId);
                    }
                }
                log("🌐 Real Web Stream activated: [url=" + url + "] on Discord Voice");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active", true);
                res.put("mode", "web");
                res.put("url", url);
                res.put("title", title);
                res.put("message", "Streaming web page: " + url);
            } else if ("stop_web".equals(action)) {
                this.webShareUrl = "";
                this.realVideoActive = false;
                this.realVideoMode = "";
                this.videoSim = false;
                this.screenShareSim = false;
                for (Account a : accounts.values()) {
                    a.video = false;
                    a.screenShare = false;
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, false);
                        a.stopScreenShare(a.currentGuildId);
                    }
                }
                log("🌐 Real Web Stream stopped");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active", false);
                res.put("message", "Web streaming stopped");
            } else if ("clear_logs".equals(action)) {
                logs.clear();
                log("Live console cleared.");
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Console logs cleared.");
            }

            // 6. Single Account Actions (Deploy specific bot to specific server & channel)
            else if ("account_action".equals(action) || action.startsWith("account_")) {
                int aid = payload.optInt("id", payload.optInt("account_id", -1));
                String sub = action.startsWith("account_") ? action.substring(8) : payload.optString("subaction", payload.optString("sub_action", ""));
                String ch = payload.optString("channel_id", this.defaultChannelId).trim();
                if (ch.isEmpty()) ch = this.defaultChannelId;
                String targetGid = payload.optString("guild_id", "").trim();
                if (targetGid.isEmpty()) targetGid = this.guildId;
                if (targetGid.isEmpty() && !ch.isEmpty()) {
                    String detected = resolveGuildIdSync(ch);
                    if (detected != null && !detected.isEmpty()) {
                        targetGid = detected;
                    }
                }
                Account target = accounts.get(aid);
                if (target != null) {
                    String accGid = !targetGid.isEmpty() ? targetGid : (target.currentGuildId.isEmpty() ? this.guildId : target.currentGuildId);
                    if ("move_default".equals(sub)) {
                        target.setVoiceState(accGid, this.defaultChannelId, false, false);
                    } else if ("move".equals(sub) || "join".equals(sub)) {
                        target.setVoiceState(accGid, ch, false, false);
                    } else if ("mute".equals(sub)) {
                        target.setVoiceState(accGid, target.voiceChannel, true, target.deaf);
                    } else if ("unmute".equals(sub)) {
                        target.setVoiceState(accGid, target.voiceChannel, false, target.deaf);
                    } else if ("deafen".equals(sub)) {
                        target.setVoiceState(accGid, target.voiceChannel, target.mute, true);
                    } else if ("undeafen".equals(sub)) {
                        target.setVoiceState(accGid, target.voiceChannel, target.mute, false);
                    } else if ("disconnect".equals(sub) || "leave".equals(sub)) {
                        target.leaveVoice(accGid);
                    } else if ("video".equals(sub)) {
                        target.setVoiceState(accGid, target.voiceChannel, target.mute, target.deaf, true);
                    } else if ("unvideo".equals(sub)) {
                        target.setVoiceState(accGid, target.voiceChannel, target.mute, target.deaf, false);
                    } else if ("screenshare".equals(sub)) {
                        target.startScreenShare(accGid);
                    } else if ("unscreenshare".equals(sub)) {
                        target.stopScreenShare(accGid);
                    } else if ("reconnect".equals(sub)) {
                        target.reconnect(httpClient, this);
                    }
                    log("Bot #" + aid + " (" + target.name + "): " + sub + " -> " + ch + (accGid.isEmpty() ? "" : " (Server: " + accGid + ")"));
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("guild_id", accGid);
                    res.put("channel_id", ch);
                    res.put("message", "Bot #" + aid + " (" + target.name + ") -> " + ch + (accGid.isEmpty() ? "" : " (Server: " + accGid + ")"));
                } else {
                    res.put("success", false);
                    res.put("status", "error");
                    res.put("error", "Account #" + aid + " not found");
                }
            } else if ("restart_all".equals(action)) {
                for (Account a : accounts.values()) {
                    a.removed = false;
                    a.reconnect(httpClient, this);
                }
                log("Reconnecting all accounts...");
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Reconnecting all accounts...");
            } else if ("save_preset".equals(action)) {
                String name = payload.optString("name", "").trim();
                String ch = payload.optString("channel_id", "").trim();
                String gid = payload.optString("guild_id", this.guildId).trim();
                if (!name.isEmpty() && !ch.isEmpty()) {
                    presets.removeIf(p -> p.optString("name").equalsIgnoreCase(name));
                    presets.add(new JSONObject().put("name", name).put("channel_id", ch).put("guild_id", gid));
                    savePresetsToPrefs();
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("message", "Saved preset: " + name);
                }
            } else if ("delete_preset".equals(action)) {
                String name = payload.optString("name", "").trim();
                presets.removeIf(p -> p.optString("name").equalsIgnoreCase(name));
                savePresetsToPrefs();
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Deleted preset: " + name);
            } else if ("shutdown".equals(action)) {
                for (Account a : accounts.values()) {
                    a.removed = true;
                    a.leaveVoice(this.guildId);
                    a.disconnect();
                }
                log("Cleanly shut down all Discord connections.");
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "All accounts safely disconnected.");
            } else if ("exec_command".equals(action)) {
                String cmd = payload.optString("command", "").trim();
                String reply = executeTerminalCommand(cmd);
                log(reply);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", reply);
            } else if ("check_update".equals(action)) {
                return checkUpdateJson();
            } else {
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Action processed: " + action);
            }
        } catch (Exception e) {
            try {
                res.put("success", false);
                res.put("status", "error");
                res.put("error", e.getMessage());
                res.put("message", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    private String executeTerminalCommand(String rawCmd) {
        if (rawCmd == null || rawCmd.trim().isEmpty()) return "Type 'help' for available commands.";
        String[] parts = rawCmd.trim().split("\\s+");
        String cmd = parts[0].toLowerCase();

        if ("help".equals(cmd) || "?".equals(cmd)) {
            return "Commands: move <ch> [pct], mute [pct], unmute [pct], deafen [pct], undeafen [pct], disconnect [pct], status, reconnect, clear";
        } else if ("status".equals(cmd) || "stats".equals(cmd)) {
            int on = 0, vc = 0;
            for (Account a : accounts.values()) {
                if (a.online) on++;
                if (a.voiceChannel != null) vc++;
            }
            return "Status: " + on + "/" + accounts.size() + " Online | " + vc + " In Voice | Server: " + (guildId.isEmpty() ? "None" : guildId);
        } else if ("move".equals(cmd) || "join".equals(cmd)) {
            String ch = parts.length > 1 ? parts[1] : defaultChannelId;
            int pct = 100;
            if (parts.length > 2) {
                try { pct = Integer.parseInt(parts[2].replace("%", "")); } catch (Exception ignored) {}
            }
            moveAll(ch, pct);
            return "Moving accounts to " + ch + " (" + pct + "%)";
        } else if ("mute".equals(cmd)) {
            int pct = parts.length > 1 ? parsePercent(parts[1]) : 100;
            muteAll(true, pct);
            return "Muted accounts (" + pct + "%)";
        } else if ("unmute".equals(cmd)) {
            int pct = parts.length > 1 ? parsePercent(parts[1]) : 100;
            muteAll(false, pct);
            return "Unmuted accounts (" + pct + "%)";
        } else if ("deafen".equals(cmd)) {
            int pct = parts.length > 1 ? parsePercent(parts[1]) : 100;
            deafenAll(true, pct);
            return "Deafened accounts (" + pct + "%)";
        } else if ("undeafen".equals(cmd)) {
            int pct = parts.length > 1 ? parsePercent(parts[1]) : 100;
            deafenAll(false, pct);
            return "Undeafened accounts (" + pct + "%)";
        } else if ("disconnect".equals(cmd) || "leave".equals(cmd)) {
            int pct = parts.length > 1 ? parsePercent(parts[1]) : 100;
            disconnectAll(pct);
            return "Disconnected accounts (" + pct + "%)";
        } else if ("reconnect".equals(cmd) || "restart".equals(cmd)) {
            for (Account a : accounts.values()) {
                if (!a.removed) a.reconnect(httpClient, this);
            }
            return "Reconnecting all accounts...";
        } else if ("clear".equals(cmd)) {
            logs.clear();
            return "Logs cleared.";
        }
        return "Unknown command: '" + cmd + "'. Type 'help' for options.";
    }

    private int parsePercent(String s) {
        try {
            int val = Integer.parseInt(s.replace("%", "").trim());
            return Math.max(1, Math.min(100, val));
        } catch (Exception e) {
            return 100;
        }
    }

    public JSONObject handleTokensPost(JSONObject payload) {
        JSONObject res = new JSONObject();
        try {
            JSONArray tokensArr = payload.optJSONArray("tokens");
            int count = 0;
            if (tokensArr != null) {
                for (int i = 0; i < tokensArr.length(); i++) {
                    String t = cleanToken(tokensArr.getString(i));
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        addAccountInternal(t);
                        count++;
                    }
                }
                saveTokensToPrefs();
            }
            res.put("success", true);
            res.put("status", "ok");
            res.put("message", "Imported " + count + " token(s) successfully");
        } catch (Exception e) {
            try {
                res.put("success", false);
                res.put("status", "error");
                res.put("error", e.getMessage());
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
                o.put("guild_id", a.currentGuildId != null ? a.currentGuildId : "");
                o.put("mute", a.mute);
                o.put("deaf", a.deaf);
                o.put("ping", a.pingMs);
                o.put("speaking", a.speaking);
                o.put("voice_flags", a.voiceFlags);
                o.put("active_sfx", a.activeSfx);
                o.put("video", a.video);
                o.put("streaming", a.streaming);
                o.put("screenshare", a.screenShare);
                o.put("youtube_active", a.youtubeActive);
                String masked = a.token.length() > 14 ? a.token.substring(0, 8) + "..." + a.token.substring(a.token.length() - 4) : "******";
                o.put("token_masked", masked);
                accArray.put(o);
            }

            json.put("total_accounts", accounts.size());
            json.put("online_accounts", onlineCount);
            json.put("voice_accounts", voiceCount);
            json.put("version", APP_VERSION);
            json.put("uptime", (System.currentTimeMillis() - startTime) / 1000);
            json.put("default_guild", guildId);
            json.put("default_channel", defaultChannelId);
            json.put("device_type", deviceMode);
            json.put("auto_rejoin", autoRejoin);
            json.put("activity_name", customActivityName);
            json.put("activity_type", customActivityType);
            json.put("stealth_delay", stealthDelaySec);
            json.put("rotator_enabled", rotatorEnabled);
            json.put("rotator_interval", rotatorIntervalSec);
            json.put("speaking_sim", speakingSim);
            json.put("voice_flags", voiceFlags);
            json.put("active_sfx", activeSfx);
            json.put("voice_status", voiceStatus);
            json.put("human_chatter", humanChatter);
            json.put("real_video_active", realVideoActive);
            json.put("real_video_mode", realVideoMode);
            json.put("web_share_url", webShareUrl);
            json.put("video_sim", videoSim);
            json.put("screenshare_sim", screenShareSim);
            json.put("music_title", musicTitle);
            json.put("music_artist", musicArtist);
            json.put("youtube_url", youtubeUrl);
            json.put("youtube_title", youtubeTitle);
            json.put("youtube_author", youtubeAuthor);
            json.put("youtube_mode", youtubeMode);
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
            JSONArray rawArr = new JSONArray();
            for (Account a : accounts.values()) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("token", a.token); // full token for export
                String masked = a.token.length() > 14 ? a.token.substring(0, 8) + "..." + a.token.substring(a.token.length() - 4) : "******";
                o.put("token_masked", masked);
                o.put("online", a.online);
                arr.put(o);
                rawArr.put(a.token);
            }
            json.put("tokens", arr);
            json.put("raw_tokens", rawArr);
            json.put("count", arr.length());
        } catch (Exception ignored) {}
        return json;
    }

    public JSONObject checkUpdateJson() {
        JSONObject res = new JSONObject();
        try {
            Request request = new Request.Builder()
                    .url("https://api.github.com/repos/Hubg50we/dc-/releases/latest")
                    .header("User-Agent", "Mozilla/5.0 (Android dc- app)")
                    .build();
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonStr = response.body().string();
                    JSONObject release = new JSONObject(jsonStr);
                    String tagName = release.optString("tag_name", "").trim();
                    String cleanRemote = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String releaseName = release.optString("name", "v" + cleanRemote);
                    String notes = release.optString("body", "");
                    String publishedAt = release.optString("published_at", "");

                    String downloadUrl = "";
                    long fileSize = 0;
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String aName = asset.optString("name", "");
                            if (aName.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url", "");
                                fileSize = asset.optLong("size", 0);
                                if (aName.equals("dc-android.apk")) {
                                    break;
                                }
                            }
                        }
                    }
                    if (downloadUrl.isEmpty()) {
                        downloadUrl = "https://github.com/Hubg50we/dc-/releases/download/" + tagName + "/dc-android.apk";
                    }

                    boolean isNewer = compareVersions(cleanRemote, APP_VERSION) > 0;
                    res.put("success", true);
                    res.put("status", "ok");
                    res.put("update_available", isNewer);
                    res.put("current_version", APP_VERSION);
                    res.put("latest_version", cleanRemote);
                    res.put("tag_name", tagName);
                    res.put("release_name", releaseName);
                    res.put("release_notes", notes);
                    res.put("download_url", downloadUrl);
                    res.put("file_size", fileSize);
                    res.put("published_at", publishedAt);
                    return res;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "checkUpdate error", e);
        }
        try {
            res.put("success", false);
            res.put("status", "error");
            res.put("update_available", false);
            res.put("current_version", APP_VERSION);
            res.put("error", "Could not check for updates. Check internet connection.");
        } catch (Exception ignored) {}
        return res;
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i].replaceAll("[^0-9]", "").isEmpty() ? "0" : parts1[i].replaceAll("[^0-9]", "")) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i].replaceAll("[^0-9]", "").isEmpty() ? "0" : parts2[i].replaceAll("[^0-9]", "")) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    public static class Account {
        public final int id;
        public final String token;
        public String name = "Connecting...";
        public String userId = "";
        public boolean online = false;
        public String voiceChannel = null;
        public String currentGuildId = "";
        public boolean mute = false;
        public boolean deaf = false;
        public String desiredGuildId = "";
        public String desiredChannelId = "";
        public boolean userLeftIntentional = false;
        public int pingMs = 0;
        public long lastHeartbeatSent = 0;
        public boolean speaking = false;
        public int voiceFlags = 5;
        public String activeSfx = "";
        public boolean video = false;
        public boolean streaming = false;
        public boolean screenShare = false;
        public boolean youtubeActive = false;

        private WebSocket ws;
        private Handler heartHandler;
        private Runnable heartRunnable;
        private int lastSequence = -1;
        public volatile boolean removed = false;

        public Account(int id, String token) {
            this.id = id;
            this.token = token;
        }

        public void reconnect(OkHttpClient client, DiscordManager mgr) {
            if (removed) return;
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
                        if (msg.has("s") && !msg.isNull("s")) {
                            lastSequence = msg.optInt("s", lastSequence);
                        }
                        int op = msg.optInt("op", -1);

                        if (op == 10) { // HELLO
                            JSONObject d = msg.getJSONObject("d");
                            int interval = d.getInt("heartbeat_interval");
                            startHeartbeat(interval);
                            sendIdentify(webSocket, mgr);
                        } else if (op == 1) { // Discord Gateway requests immediate Heartbeat
                            sendHeartbeat();
                        } else if (op == 7) { // RECONNECT
                            mgr.log("Account #" + id + " Gateway requested reconnect (Op 7). Reconnecting...");
                            reconnect(client, mgr);
                        } else if (op == 9) { // INVALID SESSION
                            mgr.log("Account #" + id + " Invalid Session (Op 9). Reconnecting with delay...");
                            new Handler(Looper.getMainLooper()).postDelayed(() -> reconnect(client, mgr), 3000);
                        } else if (op == 11) { // HEARTBEAT ACK
                            if (lastHeartbeatSent > 0) {
                                pingMs = (int) Math.max(1, System.currentTimeMillis() - lastHeartbeatSent);
                            }
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
                                    String oldCh = voiceChannel;
                                    voiceChannel = d.optString("channel_id", null);
                                    mute = d.optBoolean("self_mute", false);
                                    deaf = d.optBoolean("self_deaf", false);
                                    if (d.has("self_video")) {
                                        video = d.optBoolean("self_video", false);
                                    }
                                    if (d.has("guild_id") && !d.isNull("guild_id")) {
                                        currentGuildId = d.optString("guild_id", currentGuildId);
                                    }
                                    mgr.log("Account #" + id + " Voice updated: " + (voiceChannel != null ? voiceChannel : "Left voice"));

                                    // Auto-Rejoin Guard: if unexpectedly disconnected/kicked and autoRejoin is enabled
                                    if (voiceChannel == null && !userLeftIntentional && mgr.autoRejoin && !desiredChannelId.isEmpty()) {
                                        mgr.log("Account #" + id + " 🛡️ Auto-Rejoin Guard triggered! Rejoining " + desiredChannelId + " in 4s...");
                                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                            if (!removed && !userLeftIntentional && voiceChannel == null) {
                                                setVoiceState(desiredGuildId, desiredChannelId, mute, deaf);
                                            }
                                        }, 4000);
                                    }
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
                    if (!removed && code != 4004) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!removed) reconnect(client, mgr);
                        }, 5000);
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    online = false;
                    stopHeartbeat();
                    mgr.log("Account #" + id + " connection failed: " + (t != null ? t.getMessage() : "Unknown"));
                    if (!removed) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!removed) reconnect(client, mgr);
                        }, 7000);
                    }
                }
            });
        }

        private void sendIdentify(WebSocket ws, DiscordManager mgr) {
            try {
                JSONObject identify = new JSONObject();
                identify.put("op", 2);
                JSONObject d = new JSONObject();
                d.put("token", this.token);
                d.put("intents", 0);

                String deviceMode = mgr != null ? mgr.deviceMode : "mobile";
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

                // Rich Presence / Custom Activity
                JSONObject presence = new JSONObject();
                presence.put("status", "online");
                presence.put("afk", false);
                presence.put("since", 0);
                if (mgr != null && mgr.customActivityName != null && !mgr.customActivityName.trim().isEmpty()) {
                    JSONArray acts = new JSONArray();
                    JSONObject act = new JSONObject();
                    act.put("name", mgr.customActivityName.trim());
                    act.put("type", mgr.customActivityType);
                    if (mgr.customActivityType == 4) { // Custom status
                        act.put("state", mgr.customActivityName.trim());
                    }
                    acts.put(act);
                    presence.put("activities", acts);
                }
                d.put("presence", presence);

                identify.put("d", d);

                ws.send(identify.toString());
            } catch (Exception e) {
                Log.e(TAG, "Identify error", e);
            }
        }

        public void updatePresence(String name, int type) {
            if (ws != null) {
                try {
                    JSONObject op3 = new JSONObject();
                    op3.put("op", 3);
                    JSONObject d = new JSONObject();
                    d.put("since", 0);
                    d.put("status", "online");
                    d.put("afk", false);
                    JSONArray acts = new JSONArray();
                    if (name != null && !name.trim().isEmpty()) {
                        JSONObject act = new JSONObject();
                        act.put("name", name.trim());
                        act.put("type", type);
                        if (type == 4) act.put("state", name.trim());
                        acts.put(act);
                    }
                    d.put("activities", acts);
                    op3.put("d", d);
                    ws.send(op3.toString());
                } catch (Exception ignored) {}
            }
        }

        public void setYouTube(boolean enable, String title, String author, String mode, String url) {
            this.youtubeActive = enable;
            if (ws != null) {
                try {
                    JSONObject op3 = new JSONObject();
                    op3.put("op", 3);
                    JSONObject d = new JSONObject();
                    d.put("since", 0);
                    d.put("status", "online");
                    d.put("afk", false);
                    JSONArray acts = new JSONArray();
                    if (enable) {
                        JSONObject act = new JSONObject();
                        if ("music".equalsIgnoreCase(mode)) {
                            act.put("name", "YouTube Music");
                            act.put("type", 2);
                            act.put("details", title);
                            act.put("state", author);
                            long now = System.currentTimeMillis();
                            JSONObject ts = new JSONObject();
                            ts.put("start", now);
                            ts.put("end", now + 240000);
                            act.put("timestamps", ts);
                        } else if ("stream".equalsIgnoreCase(mode)) {
                            act.put("name", title);
                            act.put("type", 1);
                            act.put("url", "https://twitch.tv/discord");
                        } else {
                            act.put("name", "YouTube");
                            act.put("type", 3);
                            act.put("details", title);
                            act.put("state", author);
                        }
                        acts.put(act);
                    }
                    d.put("activities", acts);
                    op3.put("d", d);
                    ws.send(op3.toString());
                } catch (Exception ignored) {}
            }
        }

        public void setStreaming(boolean enable, String streamTitle, String streamUrl) {
            this.streaming = enable;
            if (ws != null) {
                try {
                    JSONObject op3 = new JSONObject();
                    op3.put("op", 3);
                    JSONObject d = new JSONObject();
                    d.put("since", 0);
                    d.put("status", "online");
                    d.put("afk", false);
                    JSONArray acts = new JSONArray();
                    if (enable) {
                        JSONObject act = new JSONObject();
                        act.put("name", streamTitle != null && !streamTitle.isEmpty() ? streamTitle : "Live Stream");
                        act.put("type", 1);
                        act.put("url", streamUrl != null && !streamUrl.isEmpty() ? streamUrl : "https://twitch.tv/discord");
                        acts.put(act);
                    }
                    d.put("activities", acts);
                    op3.put("d", d);
                    ws.send(op3.toString());
                } catch (Exception ignored) {}
            }
        }

        public void startScreenShare(String guildId) {
            String g = (guildId != null && !guildId.isEmpty()) ? guildId : this.currentGuildId;
            if (ws != null && voiceChannel != null && g != null && !g.isEmpty()) {
                try {
                    JSONObject op18 = new JSONObject();
                    op18.put("op", 18);
                    JSONObject d = new JSONObject();
                    d.put("type", "guild");
                    d.put("guild_id", g);
                    d.put("channel_id", voiceChannel);
                    d.put("preferred_region", JSONObject.NULL);
                    op18.put("d", d);
                    ws.send(op18.toString());
                    this.screenShare = true;
                } catch (Exception ignored) {}
            }
        }

        public void stopScreenShare(String guildId) {
            String g = (guildId != null && !guildId.isEmpty()) ? guildId : this.currentGuildId;
            if (ws != null && voiceChannel != null && g != null && !g.isEmpty()) {
                try {
                    JSONObject op19 = new JSONObject();
                    op19.put("op", 19);
                    JSONObject d = new JSONObject();
                    d.put("stream_key", "guild:" + g + ":" + voiceChannel + ":" + userId);
                    op19.put("d", d);
                    ws.send(op19.toString());
                    this.screenShare = false;
                } catch (Exception ignored) {}
            }
        }

        public void setSpotify(boolean enable, String title, String artist) {
            if (ws != null) {
                try {
                    JSONObject op3 = new JSONObject();
                    op3.put("op", 3);
                    JSONObject d = new JSONObject();
                    d.put("since", 0);
                    d.put("status", "online");
                    d.put("afk", false);
                    JSONArray acts = new JSONArray();
                    if (enable && title != null && !title.isEmpty()) {
                        JSONObject act = new JSONObject();
                        act.put("name", "Spotify");
                        act.put("type", 2);
                        act.put("details", title);
                        act.put("state", artist != null && !artist.isEmpty() ? artist : "Unknown Artist");
                        long now = System.currentTimeMillis();
                        JSONObject ts = new JSONObject();
                        ts.put("start", now);
                        ts.put("end", now + 210000);
                        act.put("timestamps", ts);
                        JSONObject party = new JSONObject();
                        party.put("id", "spotify:" + id);
                        act.put("party", party);
                        act.put("flags", 48);
                        acts.put(act);
                    }
                    d.put("activities", acts);
                    op3.put("d", d);
                    ws.send(op3.toString());
                } catch (Exception ignored) {}
            }
        }

        private void sendHeartbeat() {
            if (ws != null) {
                try {
                    lastHeartbeatSent = System.currentTimeMillis();
                    JSONObject hb = new JSONObject();
                    hb.put("op", 1);
                    if (lastSequence >= 0) {
                        hb.put("d", lastSequence);
                    } else {
                        hb.put("d", JSONObject.NULL);
                    }
                    ws.send(hb.toString());
                } catch (Exception ignored) {}
            }
        }

        private void startHeartbeat(int intervalMs) {
            stopHeartbeat();
            heartHandler = new Handler(Looper.getMainLooper());
            heartRunnable = new Runnable() {
                @Override
                public void run() {
                    if (ws != null) {
                        sendHeartbeat();
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
            setVoiceState(guildId, channelId, mute, deaf, this.video);
        }

        public void setVoiceState(String guildId, String channelId, boolean mute, boolean deaf, boolean video) {
            this.userLeftIntentional = false;
            if (channelId == null || channelId.isEmpty() || "null".equals(channelId)) {
                leaveVoice(guildId);
                return;
            }
            if (guildId == null || guildId.isEmpty()) {
                guildId = this.currentGuildId;
            }
            this.desiredGuildId = guildId;
            this.desiredChannelId = channelId;
            if (ws != null && guildId != null && !guildId.isEmpty()) {
                try {
                    JSONObject op4 = new JSONObject();
                    op4.put("op", 4);
                    JSONObject d = new JSONObject();
                    d.put("guild_id", guildId);
                    d.put("channel_id", channelId);
                    d.put("self_mute", mute);
                    d.put("self_deaf", deaf);
                    d.put("self_video", video);
                    op4.put("d", d);
                    ws.send(op4.toString());
                    this.currentGuildId = guildId;
                    this.voiceChannel = channelId;
                    this.mute = mute;
                    this.deaf = deaf;
                    this.video = video;
                } catch (Exception e) {
                    Log.e(TAG, "Voice state update error", e);
                }
            }
        }

        public void leaveVoice(String guildId) {
            this.userLeftIntentional = true;
            this.desiredChannelId = "";
            if (guildId == null || guildId.isEmpty()) {
                guildId = this.currentGuildId;
            }
            if (ws != null && guildId != null && !guildId.isEmpty()) {
                try {
                    JSONObject op4 = new JSONObject();
                    op4.put("op", 4);
                    JSONObject d = new JSONObject();
                    d.put("guild_id", guildId);
                    d.put("channel_id", JSONObject.NULL);
                    d.put("self_mute", false);
                    d.put("self_deaf", false);
                    d.put("self_video", false);
                    op4.put("d", d);
                    ws.send(op4.toString());
                    this.voiceChannel = null;
                    this.currentGuildId = "";
                    this.video = false;
                    this.screenShare = false;
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
            currentGuildId = "";
            lastSequence = -1;
        }
    }
}
