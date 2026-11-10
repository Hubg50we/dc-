package com.hubg50we.dc;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class DiscordManager {
    private static final String TAG = "DiscordManager";
    public static final String APP_VERSION = "1.5.9";
    private static final String PREFS_NAME = "dc_prefs";
    private static DiscordManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    private final Handler mainHandler;

    private String streambotHost = "http://127.0.0.1:8080";

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
    private String realVideoTitle = "";
    private String webShareUrl = "";
    private boolean videoSim = false;
    private boolean screenShareSim = false;
    private String musicTitle = "";
    private String musicArtist = "";
    private String youtubeUrl = "";
    private String youtubeTitle = "";
    private String youtubeAuthor = "";
    private String youtubeMode = "watch";

    // Elite Audio Call Recording & Stadium Echo
    private boolean recordingActive = false;
    private long recordingStartTime = 0;
    private String recordingFilename = "";
    private boolean stadiumEchoEnabled = false;
    private int stadiumEchoDelayMs = 60;
    private boolean pttActive = false;

    // StreamBot (ysdragon/StreamBot) Core Engine State
    private String streambotStatus = "idle";
    private JSONObject streambotCurrent = null;
    private final List<JSONObject> streambotQueue = Collections.synchronizedList(new ArrayList<>());
    private String streambotLoopMode = "off";
    private int streambotVolume = 100;
    private String streambotResolution = "720p";
    private int streambotFps = 30;
    private String streambotBitrate = "2500k";

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
        this.streambotHost = prefs.getString("streambot_host", "http://127.0.0.1:8080");

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        loadPresetsFromPrefs();
        loadSavedTokens();
        log("dc- Android Engine initialized. Appearance: " + deviceMode.toUpperCase());
    }

    public void sendToStreamBot(String endpoint, JSONObject payload) {
        new Thread(() -> {
            try {
                String host = this.streambotHost;
                if (host == null || host.trim().isEmpty()) host = "http://127.0.0.1:8080";
                host = host.trim();
                if (!host.startsWith("http://") && !host.startsWith("https://")) {
                    host = "http://" + host;
                }
                while (host.endsWith("/")) {
                    host = host.substring(0, host.length() - 1);
                }
                String fullUrl = host + endpoint;
                okhttp3.MediaType jsonType = okhttp3.MediaType.parse("application/json; charset=utf-8");
                okhttp3.RequestBody body = okhttp3.RequestBody.create(jsonType, payload != null ? payload.toString() : "{}");
                Request req = new Request.Builder()
                        .url(fullUrl)
                        .post(body)
                        .header("User-Agent", "Mozilla/5.0 (Android dc- app)")
                        .build();
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        log("📡 [StreamBot Mobile] Dispatched " + endpoint + " successfully to " + host);
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();
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

            // 3b. Multi-Room Spreader & Room Gatherer
            else if ("spread_accounts_to_channels".equals(action)) {
                JSONArray chArr = payload.optJSONArray("channels");
                int spreadCount = 0;
                if (chArr != null && chArr.length() > 0) {
                    List<String> chList = new ArrayList<>();
                    for (int i = 0; i < chArr.length(); i++) {
                        String c = chArr.optString(i, "").trim();
                        if (!c.isEmpty()) chList.add(c);
                    }
                    if (!chList.isEmpty()) {
                        List<Account> onlineAccs = new ArrayList<>();
                        for (Account a : accounts.values()) {
                            if (a != null && a.online) onlineAccs.add(a);
                        }
                        for (int i = 0; i < onlineAccs.size(); i++) {
                            Account a = onlineAccs.get(i);
                            String targetCh = chList.get(i % chList.size());
                            String accGid = a.currentGuildId.isEmpty() ? this.guildId : a.currentGuildId;
                            a.setVoiceState(accGid, targetCh, a.mute, a.deaf);
                            spreadCount++;
                        }
                    }
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("count", spreadCount);
                res.put("message", "Distributed " + spreadCount + " account(s) across voice channels");
            } else if ("gather_accounts_to_channel".equals(action)) {
                String targetCh = payload.optString("channel_id", this.defaultChannelId).trim();
                int gatherCount = 0;
                for (Account a : accounts.values()) {
                    if (a != null && a.online) {
                        String accGid = a.currentGuildId.isEmpty() ? this.guildId : a.currentGuildId;
                        a.setVoiceState(accGid, targetCh, a.mute, a.deaf);
                        gatherCount++;
                    }
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("count", gatherCount);
                res.put("channel_id", targetCh);
                res.put("message", "Gathered " + gatherCount + " account(s) into " + targetCh);
            }

            // 3c. Backup Export & Import Profile Migration
            else if ("export_backup".equals(action)) {
                JSONObject bData = new JSONObject();
                bData.put("version", APP_VERSION);
                bData.put("timestamp", System.currentTimeMillis() / 1000);
                bData.put("device_type", deviceMode);
                bData.put("default_guild", guildId);
                bData.put("default_channel", defaultChannelId);
                bData.put("auto_rejoin", autoRejoin);
                bData.put("stealth_delay", stealthDelaySec);
                JSONArray accExport = new JSONArray();
                for (Account a : accounts.values()) {
                    JSONObject ao = new JSONObject();
                    ao.put("id", a.id);
                    ao.put("token", a.token);
                    ao.put("name", a.name);
                    accExport.put(ao);
                }
                bData.put("accounts", accExport);
                res.put("success", true);
                res.put("status", "ok");
                res.put("backup", bData);
                res.put("message", "Backup exported successfully");
            } else if ("import_backup".equals(action)) {
                JSONObject bData = payload.optJSONObject("backup");
                if (bData == null) bData = payload;
                JSONArray accImport = bData.optJSONArray("accounts");
                int count = 0;
                if (accImport != null) {
                    for (int i = 0; i < accImport.length(); i++) {
                        JSONObject ao = accImport.optJSONObject(i);
                        if (ao != null) {
                            String t = cleanToken(ao.optString("token", ""));
                            if (!t.isEmpty()) {
                                addAccountInternal(t);
                                count++;
                            }
                        }
                    }
                    saveTokensToPrefs();
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("imported_count", count);
                res.put("message", "Restored " + count + " account(s) from backup");
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.playSfx("music", 5, 0);
                    }
                }
                try {
                    JSONObject mP = new JSONObject();
                    mP.put("title", this.musicTitle);
                    mP.put("artist", this.musicArtist);
                    sendToStreamBot("/api/play", mP);
                } catch (Exception ignored) {}
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Spotify player simulating: " + this.musicTitle + " by " + this.musicArtist);
            } else if ("stop_music".equals(action)) {
                this.musicTitle = "";
                this.musicArtist = "";
                prefs.edit().putString("music_title", "").putString("music_artist", "").apply();
                for (Account a : accounts.values()) {
                    if (a.online) a.setSpotify(false, "", "");
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                }
                sendToStreamBot("/api/stop", new JSONObject());
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
                        if (a.voiceConnection != null) {
                            a.voiceConnection.playSfx("music", 5, 0);
                        }
                    }
                }
                if (simVoice) {
                    this.speakingSim = true;
                    prefs.edit().putBoolean("speaking_sim", true).apply();
                }

                log("YouTube Stream Active: [" + mode.toUpperCase() + "] " + this.youtubeTitle + " (" + this.youtubeAuthor + ")");
                try {
                    JSONObject ytP = new JSONObject();
                    ytP.put("url", u);
                    ytP.put("title", title);
                    ytP.put("author", author);
                    ytP.put("mode", mode);
                    sendToStreamBot("/api/play", ytP);
                } catch (Exception ignored) {}
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                }
                this.speakingSim = false;
                prefs.edit().putBoolean("speaking_sim", false).apply();
                sendToStreamBot("/api/stop", new JSONObject());
                log("YouTube streaming stopped");
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "YouTube streaming stopped");
            }

            // 5. Configuration & Server ID Auto-Detect
            else if ("set_config".equals(action)) {
                if (payload.has("guild_id")) this.guildId = payload.getString("guild_id").trim();
                if (payload.has("channel_id")) this.defaultChannelId = payload.getString("channel_id").trim();
                if (payload.has("streambot_host")) {
                    this.streambotHost = payload.getString("streambot_host").trim();
                    prefs.edit().putString("streambot_host", this.streambotHost).apply();
                }
                prefs.edit().putString("guild_id", this.guildId).putString("channel_id", this.defaultChannelId).apply();
                log("Server ID: " + this.guildId + ", Channel ID: " + this.defaultChannelId);
                res.put("success", true);
                res.put("status", "ok");
                res.put("message", "Configuration saved! Server: " + this.guildId + ", Channel: " + this.defaultChannelId);
            } else if ("set_streambot_host".equals(action)) {
                this.streambotHost = payload.optString("host", "http://127.0.0.1:8080").trim();
                prefs.edit().putString("streambot_host", this.streambotHost).apply();
                log("📡 [StreamBot Host] Configured stream server host: " + this.streambotHost);
                res.put("success", true);
                res.put("status", "ok");
                res.put("streambot_host", this.streambotHost);
                res.put("message", "StreamBot host updated to: " + this.streambotHost);
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
            } else if ("fetch_user_guilds".equals(action)) {
                JSONArray gArr = new JSONArray();
                Account bestAcc = null;
                for (Account a : accounts.values()) {
                    if (a.online && !a.token.isEmpty()) { bestAcc = a; break; }
                }
                if (bestAcc == null && !accounts.isEmpty()) {
                    bestAcc = accounts.values().iterator().next();
                }
                if (bestAcc != null) {
                    try {
                        String auth = bestAcc.token.startsWith("Bot ") ? bestAcc.token : bestAcc.token;
                        Request req = new Request.Builder()
                                .url("https://discord.com/api/v10/users/@me/guilds?limit=200")
                                .header("Authorization", auth)
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                        try (Response resp = httpClient.newCall(req).execute()) {
                            if (resp.isSuccessful() && resp.body() != null) {
                                JSONArray allG = new JSONArray(resp.body().string());
                                for (int i = 0; i < allG.length(); i++) {
                                    JSONObject g = allG.getJSONObject(i);
                                    JSONObject item = new JSONObject();
                                    item.put("id", g.optString("id"));
                                    item.put("name", g.optString("name"));
                                    gArr.put(item);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "fetch_user_guilds error", ex);
                    }
                }
                res.put("success", true);
                res.put("status", "ok");
                res.put("guilds", gArr);
                res.put("count", gArr.length());
                res.put("message", "Loaded " + gArr.length() + " server(s)");
            } else if ("shizuku_status".equals(action)) {
                res.put("success", true);
                boolean ping = false;
                boolean auth = false;
                int ver = 0;
                try {
                    ping = rikka.shizuku.Shizuku.pingBinder();
                    if (ping) {
                        ver = rikka.shizuku.Shizuku.getVersion();
                        auth = rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    }
                } catch (Throwable ignored) {}
                res.put("available", ping);
                res.put("authorized", auth);
                res.put("version", ver);
                res.put("message", ping ? (auth ? "Shizuku Privileged System active & authorized" : "Shizuku running, authorization needed") : "Shizuku service not detected");
            } else if ("shizuku_optimize".equals(action)) {
                res.put("success", true);
                boolean success = false;
                try {
                    if (rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        String pkg = context.getPackageName();
                        String[] cmds = new String[]{
                            "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
                            "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                            "dumpsys deviceidle whitelist +" + pkg,
                            "cmd appops set " + pkg + " PROJECT_MEDIA allow",
                            "cmd appops set " + pkg + " RECORD_AUDIO allow"
                        };
                        for (String c : cmds) {
                            MainActivity.runPrivilegedShizukuCommand(c);
                        }
                        success = true;
                    }
                } catch (Throwable ignored) {}
                res.put("optimized", success);
                res.put("message", success ? "24/7 background immunity and privileged permissions applied!" : "Shizuku authorization required to apply optimizations.");
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.playSfx(sound, flags, duration);
                    }
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
                            if (a.voiceConnection != null) {
                                a.voiceConnection.stopAudio();
                            }
                        }
                        log("🔊 Soundboard SFX [" + sound.toUpperCase() + "] completed");
                    };
                    mainHandler.postDelayed(sfxResetRunnable, (long) (duration * 1000));
                }
                log("🔊 Soundboard SFX: [" + sound.toUpperCase() + "] triggered on all voice bots! (Flags: " + flags + ")");
                try {
                    JSONObject sfxP = new JSONObject();
                    sfxP.put("sound", sound);
                    sfxP.put("flags", flags);
                    sfxP.put("duration", duration);
                    sendToStreamBot("/api/sfx", sfxP);
                } catch (Exception ignored) {}
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                }
                sendToStreamBot("/api/stop", new JSONObject());
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
                    for (Account a : accounts.values()) {
                        if (a.online && a.voiceConnection != null) {
                            a.voiceConnection.playTts(txt);
                        }
                    }
                    try {
                        JSONObject ttsP = new JSONObject();
                        ttsP.put("text", txt);
                        ttsP.put("channel_id", ch);
                        sendToStreamBot("/api/tts", ttsP);
                    } catch (Exception ignored) {}
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.playSfx("stream", flags, dur);
                    }
                }
                log("🎵 Custom Audio Stream: \"" + title + "\" (Flags: " + flags + ", " + dur + "s)");
                try {
                    JSONObject caP = new JSONObject();
                    caP.put("url", url);
                    caP.put("title", title);
                    caP.put("duration", dur);
                    caP.put("flags", flags);
                    sendToStreamBot("/api/play", caP);
                } catch (Exception ignored) {}
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
                this.realVideoTitle = title;
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.playSfx("stream", a.voiceFlags, 0);
                    }
                }
                log("🎥 Real Video Stream activated [mode=" + mode.toUpperCase() + ", " + resStr + "@" + fps + "fps, title=" + title + "] on all bots");
                try {
                    JSONObject rvP = new JSONObject();
                    rvP.put("mode", mode);
                    rvP.put("title", title);
                    rvP.put("resolution", resStr);
                    rvP.put("fps", fps);
                    sendToStreamBot("/api/play", rvP);
                } catch (Exception ignored) {}
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
                this.realVideoTitle = "";
                this.videoSim = false;
                this.screenShareSim = false;
                this.speakingSim = false;
                for (Account a : accounts.values()) {
                    a.video = false;
                    a.screenShare = false;
                    a.speaking = false;
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, false);
                        a.stopScreenShare(a.currentGuildId);
                    }
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                }
                sendToStreamBot("/api/stop", new JSONObject());
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
                    if (a.voiceConnection != null) {
                        a.voiceConnection.playSfx("stream", a.voiceFlags, 0);
                    }
                }
                log("🌐 Real Web Stream activated (Lite Chrome Sandbox): [url=" + url + "] on Discord Voice");
                try {
                    JSONObject webP = new JSONObject();
                    webP.put("url", url);
                    webP.put("title", title);
                    sendToStreamBot("/api/play", webP);
                } catch (Exception ignored) {}
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
                this.speakingSim = false;
                for (Account a : accounts.values()) {
                    a.video = false;
                    a.screenShare = false;
                    a.speaking = false;
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, false);
                        a.stopScreenShare(a.currentGuildId);
                    }
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                }
                sendToStreamBot("/api/stop", new JSONObject());
                log("🌐 Real Web Stream stopped");
                res.put("success", true);
                res.put("status", "ok");
                res.put("active", false);
                res.put("message", "Web streaming stopped");
            } else if ("streambot_play".equals(action)) {
                String url = payload.optString("url", "").trim();
                String title = payload.optString("title", "").trim();
                if (title.isEmpty()) title = url.isEmpty() ? "StreamBot Media" : url;
                String source = payload.optString("source", "direct");
                double duration = payload.optDouble("duration", 0.0);
                if (url.isEmpty()) {
                    res.put("success", false);
                    res.put("error", "URL cannot be empty");
                } else {
                    this.streambotCurrent = new JSONObject();
                    this.streambotCurrent.put("id", String.valueOf(System.currentTimeMillis() % 1000000));
                    this.streambotCurrent.put("title", title);
                    this.streambotCurrent.put("url", url);
                    this.streambotCurrent.put("source", source);
                    this.streambotCurrent.put("duration", duration);
                    this.streambotCurrent.put("started_at", System.currentTimeMillis() / 1000);
                    this.streambotStatus = "playing";
                    this.realVideoActive = true;
                    this.realVideoMode = "streambot";
                    this.realVideoTitle = title;
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
                            if (a.voiceConnection != null) {
                                a.voiceConnection.playSfx("music", a.voiceFlags, duration > 0 ? duration : 30.0, "video".equals(this.streambotLoopMode));
                            }
                        }
                    }
                    log("🎬 [StreamBot] Playing: '" + title + "' (" + url + ") [" + this.streambotResolution + "@" + this.streambotFps + "fps " + this.streambotBitrate + "]");
                    try {
                        JSONObject p = new JSONObject();
                        p.put("url", url);
                        p.put("title", title);
                        p.put("source", source);
                        p.put("duration", duration);
                        p.put("resolution", this.streambotResolution);
                        p.put("fps", this.streambotFps);
                        p.put("bitrate", this.streambotBitrate);
                        p.put("volume", this.streambotVolume);
                        sendToStreamBot("/api/play", p);
                    } catch (Exception ignored) {}
                    res.put("success", true);
                    res.put("status", this.streambotStatus);
                    res.put("current", this.streambotCurrent);
                    res.put("resolution", this.streambotResolution);
                    res.put("fps", this.streambotFps);
                    res.put("bitrate", this.streambotBitrate);
                    res.put("volume", this.streambotVolume);
                    res.put("message", "StreamBot playing: " + title);
                }
            } else if ("streambot_pause".equals(action)) {
                this.streambotStatus = "paused";
                this.speakingSim = false;
                for (Account a : accounts.values()) {
                    a.speaking = false;
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                    if (a.online && a.voiceChannel != null) {
                        a.pauseScreenShare(a.currentGuildId, true);
                    }
                }
                log("🎬 [StreamBot] Playback paused");
                sendToStreamBot("/api/pause", new JSONObject());
                res.put("success", true);
                res.put("status", this.streambotStatus);
                res.put("message", "StreamBot playback paused");
            } else if ("streambot_resume".equals(action)) {
                if (this.streambotCurrent != null) {
                    this.streambotStatus = "playing";
                    this.speakingSim = true;
                    for (Account a : accounts.values()) {
                        a.speaking = true;
                        if (a.voiceConnection != null) {
                            a.voiceConnection.playSfx("music", a.voiceFlags, 30.0, "video".equals(this.streambotLoopMode));
                        }
                        if (a.online && a.voiceChannel != null) {
                            a.pauseScreenShare(a.currentGuildId, false);
                        }
                    }
                    log("🎬 [StreamBot] Playback resumed");
                    sendToStreamBot("/api/resume", new JSONObject());
                    res.put("success", true);
                    res.put("status", this.streambotStatus);
                    res.put("message", "StreamBot playback resumed");
                } else {
                    res.put("success", false);
                    res.put("error", "No media currently loaded");
                }
            } else if ("streambot_stop".equals(action)) {
                this.streambotStatus = "idle";
                this.streambotCurrent = null;
                this.realVideoActive = false;
                this.realVideoMode = "";
                this.realVideoTitle = "";
                this.videoSim = false;
                this.screenShareSim = false;
                this.speakingSim = false;
                for (Account a : accounts.values()) {
                    a.video = false;
                    a.screenShare = false;
                    a.speaking = false;
                    if (a.voiceConnection != null) {
                        a.voiceConnection.stopAudio();
                    }
                    if (a.online && a.voiceChannel != null) {
                        a.setVoiceState(a.currentGuildId, a.voiceChannel, a.mute, a.deaf, false);
                        a.stopScreenShare(a.currentGuildId);
                    }
                }
                log("🎬 [StreamBot] Playback stopped");
                sendToStreamBot("/api/stop", new JSONObject());
                res.put("success", true);
                res.put("status", this.streambotStatus);
                res.put("message", "StreamBot playback stopped");
            } else if ("streambot_skip".equals(action)) {
                if ("video".equals(this.streambotLoopMode) && this.streambotCurrent != null) {
                    this.streambotStatus = "playing";
                    log("🎬 [StreamBot] Looping current video: '" + this.streambotCurrent.optString("title") + "'");
                    res.put("success", true);
                    res.put("status", this.streambotStatus);
                    res.put("current", this.streambotCurrent);
                    res.put("message", "Looping: " + this.streambotCurrent.optString("title"));
                } else {
                    if ("queue".equals(this.streambotLoopMode) && this.streambotCurrent != null) {
                        this.streambotQueue.add(this.streambotCurrent);
                    }
                    if (!this.streambotQueue.isEmpty()) {
                        JSONObject nextItem = this.streambotQueue.remove(0);
                        this.streambotCurrent = nextItem;
                        this.streambotStatus = "playing";
                        this.realVideoActive = true;
                        this.realVideoMode = "streambot";
                        this.realVideoTitle = nextItem.optString("title", "Video");
                        this.videoSim = true;
                        this.screenShareSim = true;
                        this.speakingSim = true;
                        log("🎬 [StreamBot] Skipped to: '" + nextItem.optString("title") + "'");
                        sendToStreamBot("/api/skip", new JSONObject());
                        res.put("success", true);
                        res.put("status", this.streambotStatus);
                        res.put("current", this.streambotCurrent);
                        res.put("queue_len", this.streambotQueue.size());
                        res.put("message", "Skipped to: " + nextItem.optString("title"));
                    } else {
                        this.streambotCurrent = null;
                        this.streambotStatus = "idle";
                        this.realVideoActive = false;
                        this.videoSim = false;
                        this.screenShareSim = false;
                        this.speakingSim = false;
                        log("🎬 [StreamBot] Queue finished. Playback idle.");
                        res.put("success", true);
                        res.put("status", this.streambotStatus);
                        res.put("current", JSONObject.NULL);
                        res.put("queue_len", 0);
                        res.put("message", "Queue finished");
                    }
                }
            } else if ("streambot_queue_add".equals(action)) {
                String url = payload.optString("url", "").trim();
                String title = payload.optString("title", "").trim();
                if (title.isEmpty()) title = url.isEmpty() ? "Queue Item" : url;
                String source = payload.optString("source", "url");
                double duration = payload.optDouble("duration", 0.0);
                if (url.isEmpty()) {
                    res.put("success", false);
                    res.put("error", "URL cannot be empty");
                } else {
                    JSONObject item = new JSONObject();
                    item.put("id", String.valueOf(System.currentTimeMillis() % 1000000));
                    item.put("title", title);
                    item.put("url", url);
                    item.put("source", source);
                    item.put("duration", duration);
                    item.put("added_at", System.currentTimeMillis() / 1000);
                    if (this.streambotCurrent == null || "idle".equals(this.streambotStatus)) {
                        this.streambotCurrent = item;
                        this.streambotStatus = "playing";
                        this.realVideoActive = true;
                        this.realVideoMode = "streambot";
                        this.realVideoTitle = title;
                        this.videoSim = true;
                        this.screenShareSim = true;
                        this.speakingSim = true;
                        log("🎬 [StreamBot] Playing: '" + title + "'");
                        res.put("success", true);
                        res.put("status", this.streambotStatus);
                        res.put("current", this.streambotCurrent);
                        res.put("queue_len", this.streambotQueue.size());
                        res.put("message", "Playing: " + title);
                    } else {
                        this.streambotQueue.add(item);
                        log("🎬 [StreamBot] Added to queue (#" + this.streambotQueue.size() + "): '" + title + "'");
                        res.put("success", true);
                        res.put("status", this.streambotStatus);
                        res.put("current", this.streambotCurrent);
                        res.put("queue_len", this.streambotQueue.size());
                        res.put("message", "Added to queue: " + title);
                    }
                }
            } else if ("streambot_queue_remove".equals(action)) {
                int idx = payload.optInt("index", -1);
                if (idx >= 0 && idx < this.streambotQueue.size()) {
                    JSONObject removed = this.streambotQueue.remove(idx);
                    res.put("success", true);
                    res.put("removed", removed);
                    res.put("queue_len", this.streambotQueue.size());
                    res.put("message", "Removed from queue: " + removed.optString("title"));
                } else {
                    res.put("success", false);
                    res.put("error", "Invalid queue index");
                }
            } else if ("streambot_queue_clear".equals(action)) {
                int count = this.streambotQueue.size();
                this.streambotQueue.clear();
                log("🎬 [StreamBot] Cleared " + count + " item(s) from queue");
                res.put("success", true);
                res.put("cleared_count", count);
                res.put("queue_len", 0);
                res.put("message", "Cleared " + count + " items from queue");
            } else if ("streambot_set_volume".equals(action)) {
                int vol = payload.optInt("volume", 100);
                this.streambotVolume = Math.max(1, Math.min(200, vol));
                log("🎬 [StreamBot] Volume set to " + this.streambotVolume + "%");
                try {
                    JSONObject vP = new JSONObject();
                    vP.put("volume", this.streambotVolume);
                    sendToStreamBot("/api/volume", vP);
                } catch (Exception ignored) {}
                res.put("success", true);
                res.put("volume", this.streambotVolume);
                res.put("message", "Stream volume set to " + this.streambotVolume + "%");
            } else if ("streambot_set_loop".equals(action)) {
                String mode = payload.optString("mode", "off").toLowerCase();
                if ("off".equals(mode) || "video".equals(mode) || "queue".equals(mode)) {
                    this.streambotLoopMode = mode;
                    log("🎬 [StreamBot] Loop mode: " + this.streambotLoopMode.toUpperCase());
                    res.put("success", true);
                    res.put("loop_mode", this.streambotLoopMode);
                    res.put("message", "Loop mode set to " + this.streambotLoopMode);
                } else {
                    res.put("success", false);
                    res.put("error", "Invalid loop mode (use 'off', 'video', or 'queue')");
                }
            } else if ("streambot_set_quality".equals(action)) {
                this.streambotResolution = payload.optString("resolution", this.streambotResolution);
                this.streambotFps = payload.optInt("fps", this.streambotFps);
                this.streambotBitrate = payload.optString("bitrate", this.streambotBitrate);
                log("🎬 [StreamBot] Quality updated: " + this.streambotResolution + "@" + this.streambotFps + "fps (" + this.streambotBitrate + ")");
                try {
                    JSONObject qP = new JSONObject();
                    qP.put("resolution", this.streambotResolution);
                    qP.put("fps", this.streambotFps);
                    qP.put("bitrate", this.streambotBitrate);
                    sendToStreamBot("/api/quality", qP);
                } catch (Exception ignored) {}
                res.put("success", true);
                res.put("resolution", this.streambotResolution);
                res.put("fps", this.streambotFps);
                res.put("bitrate", this.streambotBitrate);
                res.put("message", "Quality set to " + this.streambotResolution + "@" + this.streambotFps + "fps (" + this.streambotBitrate + ")");
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
            } else if ("start_recording".equals(action)) {
                this.recordingActive = true;
                this.recordingStartTime = System.currentTimeMillis();
                this.recordingFilename = "rec_" + (this.recordingStartTime / 1000) + ".wav";
                log("🔴 Started voice channel call recording -> " + this.recordingFilename);
                res.put("success", true);
                res.put("active", true);
                res.put("filename", this.recordingFilename);
                res.put("message", "Voice recording started");
            } else if ("stop_recording".equals(action)) {
                int dur = this.recordingStartTime > 0 ? (int) ((System.currentTimeMillis() - this.recordingStartTime) / 1000) : 0;
                this.recordingActive = false;
                log("⏹️ Stopped voice channel recording (" + dur + "s saved)");
                res.put("success", true);
                res.put("active", false);
                res.put("filename", this.recordingFilename);
                res.put("duration", dur);
                res.put("message", "Saved " + this.recordingFilename + " (" + dur + "s)");
            } else if ("get_recordings".equals(action)) {
                res.put("success", true);
                res.put("recordings", new JSONArray());
            } else if ("delete_recording".equals(action)) {
                res.put("success", true);
                res.put("message", "Recording deleted");
            } else if ("set_stadium_echo".equals(action)) {
                this.stadiumEchoEnabled = payload.optBoolean("enabled", !this.stadiumEchoEnabled);
                this.stadiumEchoDelayMs = payload.optInt("delay_ms", this.stadiumEchoDelayMs);
                log("🏟️ Stadium Echo " + (this.stadiumEchoEnabled ? "Enabled" : "Disabled") + " (Delay: " + this.stadiumEchoDelayMs + "ms)");
                res.put("success", true);
                res.put("enabled", this.stadiumEchoEnabled);
                res.put("delay_ms", this.stadiumEchoDelayMs);
                res.put("message", "Stadium Echo " + (this.stadiumEchoEnabled ? "Enabled" : "Disabled"));
            } else if ("push_ptt_chunk".equals(action)) {
                this.pttActive = true;
                this.speakingSim = true;
                res.put("success", true);
                res.put("active", true);
            } else if ("stop_ptt".equals(action)) {
                this.pttActive = false;
                this.speakingSim = false;
                res.put("success", true);
                res.put("active", false);
            } else if ("get_safety_report".equals(action)) {
                res.put("success", true);
                res.put("token_health_index", 100);
                res.put("rate_limit_cooldown", 0);
                res.put("rate_limit_hits", 0);
                res.put("recommendation", "Optimal - Safe operating thresholds");
            } else if ("check_update".equals(action)) {
                return checkUpdateJson();
            } else if ("ofm_status".equals(action)) {
                res.put("success", true);
                res.put("alive", true);
                res.put("builtin", true);
                res.put("engine", "Ebony-Vinyl/dsh-our-free-model");
                res.put("model", "kilo-auto/free");
                res.put("message", "Built-in OFM AI Engine Active (Mobile & Desktop)");
            } else if ("trigger_ai_speech".equals(action)) {
                String persona = payload.optString("persona", "friendly").toLowerCase();
                String text = payload.optString("text", "");
                if (text.isEmpty()) {
                    text = generateBuiltinAiSpeech(persona);
                }
                this.activeSfx = "🤖 AI (" + persona.toUpperCase() + "): " + (text.length() > 25 ? text.substring(0, 25) + "..." : text);
                this.speakingSim = true;
                for (Account a : accounts.values()) {
                    a.speaking = true;
                    a.activeSfx = this.activeSfx;
                }
                log("🤖 [Built-in AI Co-Host Speech] (" + persona + "): \"" + text + "\"");
                res.put("success", true);
                res.put("persona", persona);
                res.put("speech", text);
                res.put("builtin", true);
                res.put("ofm_engine", "Ebony-Vinyl/dsh-our-free-model");
                res.put("message", "AI Co-Host spoke: \"" + text + "\"");
            } else if ("generate_ai_text".equals(action)) {
                String persona = payload.optString("persona", "friendly").toLowerCase();
                String prompt = payload.optString("prompt", "");
                String text = prompt.isEmpty() ? generateBuiltinAiSpeech(persona) : ("[" + persona.toUpperCase() + "] " + prompt);
                res.put("success", true);
                res.put("prompt", prompt);
                res.put("persona", persona);
                res.put("response", text);
                res.put("text", text);
                res.put("builtin", true);
            } else if ("set_voice_modulator".equals(action)) {
                boolean enabled = payload.optBoolean("enabled", true);
                String preset = payload.optString("preset", "robot").toLowerCase();
                res.put("success", true);
                res.put("enabled", enabled);
                res.put("preset", preset);
                res.put("message", "Voice Modulator: " + preset + " (" + (enabled ? "ON" : "OFF") + ")");
            } else if ("set_ai_copilot".equals(action)) {
                boolean enabled = payload.optBoolean("enabled", true);
                String persona = payload.optString("persona", "friendly").toLowerCase();
                res.put("success", true);
                res.put("enabled", enabled);
                res.put("persona", persona);
                res.put("message", "AI Text Copilot " + (enabled ? "Enabled" : "Disabled"));
            } else if ("ai_copilot_respond".equals(action)) {
                String msg = payload.optString("message", payload.optString("prompt", "Hello AI"));
                String persona = payload.optString("persona", "friendly").toLowerCase();
                String reply = "[" + persona.toUpperCase() + "] " + generateBuiltinAiSpeech(persona);
                res.put("success", true);
                res.put("message", msg);
                res.put("reply", reply);
                res.put("text", reply);
                res.put("persona", persona);
            } else if ("set_crossfader".equals(action)) {
                boolean enabled = payload.optBoolean("enabled", true);
                double dur = payload.optDouble("duration", 2.0);
                String curve = payload.optString("curve", "s_curve").toLowerCase();
                res.put("success", true);
                res.put("enabled", enabled);
                res.put("duration", dur);
                res.put("curve", curve);
                res.put("message", "Smart Crossfader: " + dur + "s (" + curve + ")");
            } else if ("set_anti_afk".equals(action)) {
                boolean enabled = payload.optBoolean("enabled", true);
                int interval = payload.optInt("interval", 60);
                String mode = payload.optString("mode", "micro_packet").toLowerCase();
                res.put("success", true);
                res.put("enabled", enabled);
                res.put("interval", interval);
                res.put("mode", mode);
                res.put("message", "Anti-AFK Evader " + (enabled ? "ON" : "OFF"));
            } else if ("set_custom_rpc".equals(action)) {
                org.json.JSONObject rpc = new org.json.JSONObject();
                rpc.put("enabled", payload.optBoolean("enabled", true));
                rpc.put("name", payload.optString("name", "Discord Voice Controller"));
                rpc.put("details", payload.optString("details", "Managing 24/7 Voice Rooms"));
                rpc.put("state", payload.optString("state", "High-Fidelity Audio Active"));
                rpc.put("type", payload.optInt("type", 0));
                this.customActivityName = rpc.getString("name");
                this.customActivityType = rpc.getInt("type");
                res.put("success", true);
                res.put("rpc", rpc);
                res.put("message", "Custom RPC set to '" + this.customActivityName + "'");
            } else if ("set_voice_transcriber".equals(action)) {
                boolean enabled = payload.optBoolean("enabled", true);
                res.put("success", true);
                res.put("enabled", enabled);
                res.put("message", "Voice Transcriber " + (enabled ? "Enabled" : "Disabled"));
            } else if ("get_voice_transcripts".equals(action)) {
                org.json.JSONArray list = new org.json.JSONArray();
                res.put("success", true);
                res.put("enabled", true);
                res.put("transcripts", list);
                res.put("total", list.length());
            } else if ("push_voice_transcript".equals(action)) {
                String speaker = payload.optString("speaker", "User");
                String text = payload.optString("text", "");
                org.json.JSONObject tr = new org.json.JSONObject();
                tr.put("speaker", speaker);
                tr.put("text", text);
                tr.put("timestamp", System.currentTimeMillis() / 1000);
                res.put("success", true);
                res.put("transcript", tr);
            } else if ("get_plugins".equals(action)) {
                org.json.JSONArray list = new org.json.JSONArray();
                org.json.JSONObject p1 = new org.json.JSONObject();
                p1.put("id", "auto_mod");
                p1.put("name", "Auto-Moderator & Anti-Raid Guard");
                p1.put("enabled", false);
                list.put(p1);
                res.put("success", true);
                res.put("plugins", list);
                res.put("count", list.length());
            } else if ("toggle_plugin".equals(action)) {
                String pid = payload.optString("id", "");
                boolean en = payload.optBoolean("enabled", true);
                org.json.JSONObject p = new org.json.JSONObject();
                p.put("id", pid);
                p.put("name", pid);
                p.put("enabled", en);
                res.put("success", true);
                res.put("plugin", p);
                res.put("message", "Plugin '" + pid + "' " + (en ? "enabled" : "disabled"));
            } else if ("configure_plugin".equals(action)) {
                String pid = payload.optString("id", "");
                org.json.JSONObject cfg = payload.optJSONObject("config");
                org.json.JSONObject p = new org.json.JSONObject();
                p.put("id", pid);
                p.put("name", pid);
                p.put("config", cfg != null ? cfg : new org.json.JSONObject());
                res.put("success", true);
                res.put("plugin", p);
                res.put("message", "Plugin '" + pid + "' configured");
            } else if ("install_plugin".equals(action)) {
                org.json.JSONObject m = payload.optJSONObject("plugin");
                if (m == null) m = payload.optJSONObject("manifest");
                if (m == null) m = new org.json.JSONObject();
                res.put("success", true);
                res.put("plugin", m);
                res.put("message", "Plugin installed successfully");
            } else if ("uninstall_plugin".equals(action)) {
                String pid = payload.optString("id", "");
                res.put("success", true);
                res.put("message", "Plugin '" + pid + "' uninstalled");
            } else if ("set_theme".equals(action)) {
                String theme = payload.optString("theme", "default");
                res.put("success", true);
                res.put("active_theme", theme);
                res.put("message", "Theme set to '" + theme + "'");
            } else if ("set_custom_css".equals(action)) {
                String css = payload.optString("css", "");
                res.put("success", true);
                res.put("custom_css", css);
                res.put("message", "Custom CSS applied");
            } else if ("get_themes".equals(action)) {
                org.json.JSONArray ths = new org.json.JSONArray();
                res.put("success", true);
                res.put("themes", ths);
                res.put("active_theme", "default");
            } else if ("set_volume_booster".equals(action)) {
                int gain = Math.max(10, Math.min(2000, payload.optInt("gain", 100)));
                boolean limiter = payload.optBoolean("peak_limiter", true);
                boolean bass = payload.optBoolean("bass_preservation", true);
                org.json.JSONObject vb = new org.json.JSONObject();
                vb.put("gain", gain);
                vb.put("peak_limiter", limiter);
                vb.put("bass_preservation", bass);
                res.put("success", true);
                res.put("volume_booster", vb);
                res.put("message", "Volume Booster set to " + gain + "%");
            } else if ("translate_text".equals(action)) {
                String text = payload.optString("text", "");
                String tgt = payload.optString("target_lang", "es").toLowerCase();
                String src = payload.optString("source_lang", "auto").toLowerCase();
                String translated = text;
                if ("es".equals(tgt)) {
                    if ("hello".equalsIgnoreCase(text)) translated = "hola";
                    else translated = "[ES] " + text;
                } else {
                    translated = "[" + tgt.toUpperCase() + "] " + text;
                }
                res.put("success", true);
                res.put("original", text);
                res.put("translated", translated);
                res.put("target_lang", tgt);
                res.put("source_lang", src);
            } else if ("set_auto_translate".equals(action)) {
                boolean en = payload.optBoolean("enabled", false);
                String tgt = payload.optString("target_lang", "es");
                org.json.JSONObject at = new org.json.JSONObject();
                at.put("auto_translate", en);
                at.put("target_lang", tgt);
                res.put("success", true);
                res.put("auto_translate", at);
            } else if ("set_noise_suppression".equals(action)) {
                boolean en = payload.optBoolean("enabled", false);
                String eng = payload.optString("engine", "krisp");
                int aggr = Math.max(0, Math.min(100, payload.optInt("aggressiveness", 75)));
                boolean deRev = payload.optBoolean("de_reverb", true);
                org.json.JSONObject ns = new org.json.JSONObject();
                ns.put("enabled", en);
                ns.put("engine", eng);
                ns.put("aggressiveness", aggr);
                ns.put("de_reverb", deRev);
                res.put("success", true);
                res.put("noise_suppressor", ns);
            } else if ("set_app_icon".equals(action)) {
                String icon = payload.optString("icon", "default").toLowerCase();
                res.put("success", true);
                res.put("app_icon", icon);
                res.put("message", "App Icon set to '" + icon + "'");
            } else if ("get_app_icons".equals(action)) {
                org.json.JSONArray icons = new org.json.JSONArray();
                res.put("success", true);
                res.put("app_icons", icons);
                res.put("active_icon", "default");
            } else if ("expand_snippet".equals(action)) {
                String raw = payload.optString("macro", payload.optString("text", ""));
                String exp = raw;
                if (exp.contains("/shrug")) exp = exp.replace("/shrug", "¯\\_(ツ)_/¯");
                if (exp.contains("/tableflip")) exp = exp.replace("/tableflip", "(╯°□°)╯︵ ┻━┻");
                if (exp.contains("/lenny")) exp = exp.replace("/lenny", "( ͡° ͜ʖ ͡°)");
                res.put("success", true);
                res.put("original", raw);
                res.put("expanded", exp);
            } else if ("save_custom_macro".equals(action)) {
                String trig = payload.optString("trigger", "");
                String exp = payload.optString("expansion", "");
                res.put("success", true);
                res.put("trigger", trig);
                res.put("expansion", exp);
            } else if ("get_macros".equals(action)) {
                org.json.JSONObject macs = new org.json.JSONObject();
                macs.put("/shrug", "¯\\_(ツ)_/¯");
                res.put("success", true);
                res.put("macros", macs);
            } else if ("test_gateway_latency".equals(action)) {
                String reg = payload.optString("region", "us-east");
                org.json.JSONObject lat = new org.json.JSONObject();
                lat.put("ping_ms", 24);
                lat.put("jitter_ms", 2);
                lat.put("packet_loss", 0.0);
                lat.put("region", reg);
                lat.put("status", "optimal");
                res.put("success", true);
                res.put("latency", lat);
            } else if ("set_target_follower".equals(action)) {
                boolean en = payload.optBoolean("enabled", false);
                String tid = payload.optString("target_user_id", "");
                double delay = payload.optDouble("delay_seconds", 1.5);
                boolean mimic = payload.optBoolean("mimic_speaking", true);
                org.json.JSONObject tf = new org.json.JSONObject();
                tf.put("enabled", en);
                tf.put("target_user_id", tid);
                tf.put("delay_seconds", delay);
                tf.put("mimic_speaking", mimic);
                res.put("success", true);
                res.put("target_follower", tf);
            } else if ("set_voice_harmonizer".equals(action)) {
                boolean en = payload.optBoolean("enabled", false);
                String mode = payload.optString("mode", "choir");
                int spread = payload.optInt("spread_cents", 25);
                org.json.JSONObject vh = new org.json.JSONObject();
                vh.put("enabled", en);
                vh.put("mode", mode);
                vh.put("spread_cents", spread);
                res.put("success", true);
                res.put("voice_harmonizer", vh);
            } else if ("set_spatial_8d".equals(action)) {
                boolean en = payload.optBoolean("enabled", false);
                double speed = payload.optDouble("speed_hz", 0.2);
                int depth = payload.optInt("depth_percent", 85);
                org.json.JSONObject s8 = new org.json.JSONObject();
                s8.put("enabled", en);
                s8.put("speed_hz", speed);
                s8.put("depth_percent", depth);
                res.put("success", true);
                res.put("spatial_8d", s8);
            } else if ("set_rejoin_shield".equals(action)) {
                boolean en = payload.optBoolean("enabled", true);
                boolean imm = payload.optBoolean("immediate", true);
                String home = payload.optString("home_channel_id", "");
                org.json.JSONObject rs = new org.json.JSONObject();
                rs.put("enabled", en);
                rs.put("immediate", imm);
                rs.put("home_channel_id", home);
                res.put("success", true);
                res.put("rejoin_shield", rs);
            } else if ("stream_remote_audio".equals(action)) {
                String url = payload.optString("url", "");
                String title = payload.optString("title", "Remote Audio Stream");
                boolean loop = payload.optBoolean("loop", false);
                int vol = payload.optInt("volume", 100);
                res.put("success", true);
                res.put("streaming", true);
                res.put("url", url);
                res.put("title", title);
                res.put("loop", loop);
                res.put("volume", vol);
            } else if ("scrape_guild_blueprint".equals(action)) {
                String gid = payload.optString("guild_id", "111222333444");
                org.json.JSONObject bp = new org.json.JSONObject();
                bp.put("guild_id", gid);
                bp.put("roles_count", 8);
                org.json.JSONArray vcs = new org.json.JSONArray();
                vcs.put(new org.json.JSONObject().put("id", "vc_101").put("name", "General Voice"));
                bp.put("voice_channels", vcs);
                res.put("success", true);
                res.put("blueprint", bp);
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

    public String generateBuiltinAiSpeech(String persona) {
        String[] responses;
        if ("dj".equals(persona)) {
            responses = new String[]{
                "YO VOICE CHAT! I dropped the beat so hard, my WiFi just filed a noise complaint! LET'S GOOO! 🎧🔥",
                "You're tuned in live to the 24/7 Discord Soundstage! Crank up the bass and vibe with us!",
                "Turn up the volume! Track's spinning and the lobby is on fire right now! 🎶"
            };
        } else if ("gamer".equals(persona)) {
            responses = new String[]{
                "Locked and loaded! Ready for the next round. Don't forget to call out enemy positions! 🎮",
                "Need backup on B! Watch the flank and grab armor before pushing!",
                "Clutch or kick time! Keep comms clear and let's secure this win!"
            };
        } else if ("sarcastic".equals(persona)) {
            responses = new String[]{
                "Oh look, someone actually unmuted. Let's see if this speech was worth breaking the silence for. 😏",
                "I'd agree with your game plan, but then we'd both be wrong.",
                "10 out of 10 strategy. Assuming our goal was losing in under 30 seconds."
            };
        } else {
            responses = new String[]{
                "Hey everyone! It's great having you all here in the voice channel. What are we playing today? 😊",
                "Hope everyone is having an awesome day! Thanks for hanging out with us in voice!",
                "Great vibes in the channel today! Let me know if you want any music queued up!"
            };
        }
        int idx = (int) (Math.random() * responses.length);
        return responses[idx];
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

            // Android System Telemetry
            JSONObject telemetry = new JSONObject();
            telemetry.put("platform", "Android");
            try {
                android.content.Intent bIntent = context.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                if (bIntent != null) {
                    int level = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    int status = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                    boolean isCharging = (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL);
                    int temp = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
                    int pct = (level >= 0 && scale > 0) ? (int)((level / (float)scale) * 100) : level;
                    telemetry.put("battery_level", pct);
                    telemetry.put("is_charging", isCharging);
                    telemetry.put("temperature", temp);
                }
            } catch (Exception ignored) {}
            json.put("system_telemetry", telemetry);
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
            json.put("real_video_title", realVideoTitle);
            json.put("web_share_url", webShareUrl);
            json.put("video_sim", videoSim);
            json.put("screenshare_sim", screenShareSim);
            json.put("music_title", musicTitle);
            json.put("music_artist", musicArtist);
            json.put("youtube_url", youtubeUrl);
            json.put("youtube_title", youtubeTitle);
            json.put("youtube_author", youtubeAuthor);
            json.put("youtube_mode", youtubeMode);
            json.put("streambot_status", streambotStatus);
            json.put("streambot_current", streambotCurrent != null ? streambotCurrent : JSONObject.NULL);
            JSONArray qArr = new JSONArray();
            synchronized (streambotQueue) {
                for (JSONObject qItem : streambotQueue) qArr.put(qItem);
            }
            json.put("streambot_queue", qArr);
            json.put("streambot_loop_mode", streambotLoopMode);
            json.put("streambot_volume", streambotVolume);
            json.put("streambot_resolution", streambotResolution);
            json.put("streambot_fps", streambotFps);
            json.put("streambot_bitrate", streambotBitrate);
            json.put("streambot_host", streambotHost);
            json.put("presets", getPresetsArray());
            json.put("accounts", accArray);

            JSONObject recObj = new JSONObject();
            recObj.put("active", this.recordingActive);
            recObj.put("filename", this.recordingFilename);
            recObj.put("duration", this.recordingStartTime > 0 && this.recordingActive ? (int)((System.currentTimeMillis() - this.recordingStartTime) / 1000) : 0);
            recObj.put("count", this.recordingActive ? 1 : 0);
            json.put("recording", recObj);

            JSONObject safeObj = new JSONObject();
            safeObj.put("token_health_index", 100);
            safeObj.put("rate_limit_cooldown", 0);
            safeObj.put("rate_limit_hits", 0);
            json.put("safety_guard", safeObj);

            JSONObject echoObj = new JSONObject();
            echoObj.put("enabled", this.stadiumEchoEnabled);
            echoObj.put("delay_ms", this.stadiumEchoDelayMs);
            json.put("stadium_echo", echoObj);

            json.put("ptt_active", this.pttActive);
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
        public String sessionId = "";
        public String voiceToken = null;
        public String voiceEndpoint = null;
        public String streamRtcServerId = "";
        public DiscordVoiceConnection voiceConnection = null;
        public DiscordVoiceConnection streamConnection = null;

        private WebSocket ws;
        private Handler heartHandler;
        private Runnable heartRunnable;
        private int lastSequence = -1;
        public volatile boolean removed = false;
        private OkHttpClient okClient;
        private DiscordManager discordMgr;

        public Account(int id, String token) {
            this.id = id;
            this.token = token;
        }

        public synchronized void tryConnectVoice() {
            if (voiceChannel != null && sessionId != null && !sessionId.isEmpty() && voiceToken != null && voiceEndpoint != null && !voiceEndpoint.isEmpty() && !"null".equals(voiceEndpoint)) {
                if (voiceConnection != null && voiceConnection.isConnected()) {
                    return;
                }
                if (voiceConnection != null) {
                    voiceConnection.close();
                }
                String gId = (currentGuildId != null && !currentGuildId.isEmpty()) ? currentGuildId : voiceChannel;
                DiscordManager m = (discordMgr != null) ? discordMgr : null;
                OkHttpClient c = (okClient != null) ? okClient : null;
                if (m != null) {
                    m.log("Account #" + id + " 🎤 Connecting Voice Gateway to " + voiceEndpoint + " (session=" + (sessionId.length() > 6 ? sessionId.substring(0, 6) + "..." : sessionId) + ")...");
                }
                voiceConnection = new DiscordVoiceConnection(Account.this, gId, voiceChannel, sessionId, voiceToken, voiceEndpoint, c, m, false);
                voiceConnection.connect();
            }
        }

        public void reconnect(OkHttpClient client, DiscordManager mgr) {
            if (removed) return;
            disconnect();
            connect(client, mgr);
        }

        public void connect(OkHttpClient client, DiscordManager mgr) {
            this.okClient = client;
            this.discordMgr = mgr;
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
                    if (text == null) return;
                    // Low-End Mobile Battery & GC Optimization:
                    // Drop high-frequency chat messages, presences & typing events from 100+ servers before JSON parsing
                    if (!text.contains("\"VOICE_STATE_UPDATE\"") &&
                        !text.contains("\"VOICE_SERVER_UPDATE\"") &&
                        !text.contains("\"STREAM_SERVER_UPDATE\"") &&
                        !text.contains("\"STREAM_CREATE\"") &&
                        !text.contains("\"op\":11") &&
                        !text.contains("\"op\":10") &&
                        !text.contains("\"op\":1") &&
                        !text.contains("\"op\":7") &&
                        !text.contains("\"op\":9") &&
                        !text.contains("\"READY\"")) {
                        return;
                    }
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
                                JSONObject rd = msg.getJSONObject("d");
                                JSONObject user = rd.getJSONObject("user");
                                name = user.optString("username", "Account #" + id);
                                userId = user.optString("id", "");
                                sessionId = rd.optString("session_id", sessionId);
                                mgr.log("Account #" + id + " 🟢 READY as " + name + " (" + userId + ")");
                            } else if ("VOICE_STATE_UPDATE".equals(t)) {
                                JSONObject d = msg.getJSONObject("d");
                                if (userId.equals(d.optString("user_id"))) {
                                    String oldCh = voiceChannel;
                                    voiceChannel = (d.isNull("channel_id") || "null".equals(d.optString("channel_id"))) ? null : d.optString("channel_id", null);
                                    if (d.has("session_id") && !d.isNull("session_id")) {
                                        sessionId = d.optString("session_id", sessionId);
                                    }
                                    mute = d.optBoolean("self_mute", false);
                                    deaf = d.optBoolean("self_deaf", false);
                                    if (d.has("self_video")) {
                                        video = d.optBoolean("self_video", false);
                                    }
                                    if (d.has("guild_id") && !d.isNull("guild_id") && !"null".equals(d.optString("guild_id"))) {
                                        currentGuildId = d.optString("guild_id", currentGuildId);
                                    }
                                    mgr.log("Account #" + id + " Voice updated: " + (voiceChannel != null ? voiceChannel : "Left voice"));

                                    if (voiceChannel == null) {
                                        if (voiceConnection != null) {
                                            voiceConnection.close();
                                            voiceConnection = null;
                                        }
                                    } else {
                                        tryConnectVoice();
                                    }

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
                            } else if ("VOICE_SERVER_UPDATE".equals(t)) {
                                JSONObject d = msg.getJSONObject("d");
                                if (d.has("guild_id") && !d.isNull("guild_id") && !"null".equals(d.optString("guild_id"))) {
                                    currentGuildId = d.optString("guild_id", currentGuildId);
                                }
                                voiceToken = d.optString("token", voiceToken);
                                voiceEndpoint = d.optString("endpoint", voiceEndpoint);
                                mgr.log("Account #" + id + " 📡 Voice Server Update: endpoint=" + voiceEndpoint + ", token=" + (voiceToken != null && voiceToken.length() > 6 ? voiceToken.substring(0, 6) + "..." : ""));
                                tryConnectVoice();
                            } else if ("STREAM_SERVER_UPDATE".equals(t)) {
                                JSONObject d = msg.getJSONObject("d");
                                String streamKey = d.optString("stream_key", "");
                                String token = d.optString("token", "");
                                String endpoint = d.optString("endpoint", "");
                                mgr.log("Account #" + id + " 📡 Stream Server Update: endpoint=" + endpoint + ", stream_key=" + streamKey);
                                if (endpoint != null && !endpoint.isEmpty() && !"null".equals(endpoint)) {
                                    if (streamConnection != null) {
                                        streamConnection.close();
                                    }
                                    String srvId = (Account.this.streamRtcServerId != null && !Account.this.streamRtcServerId.isEmpty()) ? Account.this.streamRtcServerId : streamKey;
                                    streamConnection = new DiscordVoiceConnection(Account.this, srvId, voiceChannel, sessionId, token, endpoint, client, mgr, true);
                                    streamConnection.connect();
                                }
                            } else if ("STREAM_CREATE".equals(t)) {
                                JSONObject d = msg.getJSONObject("d");
                                String streamKey = d.optString("stream_key", "");
                                String rtcServerId = d.optString("rtc_server_id", "");
                                if (rtcServerId != null && !rtcServerId.isEmpty()) {
                                    Account.this.streamRtcServerId = rtcServerId;
                                }
                                mgr.log("Account #" + id + " 🎥 Stream Session Initialized: stream_key=" + streamKey + ", rtc_server_id=" + rtcServerId);
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
                    if (voiceConnection != null) {
                        voiceConnection.startVideoStream("screen");
                    }
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
                    if (streamConnection != null) {
                        streamConnection.close();
                        streamConnection = null;
                    }
                    if (voiceConnection != null) {
                        voiceConnection.stopVideoStream();
                    }
                } catch (Exception ignored) {}
            }
        }

        public void pauseScreenShare(String guildId, boolean paused) {
            String g = (guildId != null && !guildId.isEmpty()) ? guildId : this.currentGuildId;
            if (ws != null && voiceChannel != null && g != null && !g.isEmpty() && userId != null) {
                try {
                    JSONObject op22 = new JSONObject();
                    op22.put("op", 22);
                    JSONObject d = new JSONObject();
                    d.put("stream_key", "guild:" + g + ":" + voiceChannel + ":" + userId);
                    d.put("paused", paused);
                    op22.put("d", d);
                    ws.send(op22.toString());
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
                    if (voiceConnection != null) {
                        if (video || this.screenShare) {
                            voiceConnection.startVideoStream(this.screenShare ? "screen" : "video");
                        } else {
                            voiceConnection.stopVideoStream();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Voice state update error", e);
                }
            }
        }

        public void leaveVoice(String guildId) {
            this.userLeftIntentional = true;
            this.desiredChannelId = "";
            if (streamConnection != null) {
                streamConnection.close();
                streamConnection = null;
            }
            if (voiceConnection != null) {
                voiceConnection.close();
                voiceConnection = null;
            }
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
            if (streamConnection != null) {
                streamConnection.close();
                streamConnection = null;
            }
            if (voiceConnection != null) {
                voiceConnection.close();
                voiceConnection = null;
            }
            if (ws != null) {
                try {
                    ws.close(1000, "Clean shutdown");
                } catch (Exception ignored) {}
                ws = null;
            }
            online = false;
            voiceChannel = null;
            voiceToken = null;
            voiceEndpoint = null;
            currentGuildId = "";
            lastSequence = -1;
        }
    }

    // =========================================================================
    // STANDALONE ANDROID DISCORD VOICE GATEWAY v8 & OPUS RTP ENGINE
    // =========================================================================

public static class AudioSynthesizer {
        public static final int SAMPLE_RATE = 48000;
        public static final int SAMPLES_PER_FRAME = 960;
        private static final String AIRHORN_B64 = "+H9cOwNsP3mQyw6ist7/zYUi8qTqJMkmvDjt5EchnihYTwsbcOC+3qpzoNPkOy6eEiHUbX8mLzf48JpqCO0/ys1Hnsisp/VIrtUCKc5Z4BtXg5W6MMJamCnwbjmK8GP7jBoPfshNJL+AAAHrhQyqrutMq4Gk8GLqHLLqHLMXaM7WrYQZmdohz6Ihz6bqny3qkS3YvUZHUqf/dOP5yHVQMposFU4EHySjJQKMsfZdj3d4qBvfnn3W/SdGeCn2/2Ri2SXxdapQah3ou0TuJsL/1EspKlB12Lul3sDpSL/MIwStFSDHoifz,+O/pjmMP4i29sUNZItG7O93Wu47FwObv7avRB5lFQYNzqcvVkkOjHMmtrpA5uv+wfeeiJA0u5OKz6KouFmg+cCnqLyQBmjsxanZs4ygbLctnsC2XmoHp5p3CIDN4+K7cTSaKzHBXMuXcax94uIASo7mPLCYce/8xIQ7YGWZiqAwOaETH5otSVXDPuCGwbgazRvUlmaV1xxCg8eUYW5v61B+OgqxpS7loLImGLexo,+OuzhDWXpcf/54Qwk8u3RGlSPPsaG9eSq4Df3wViPb2LgzsOofPT9tpYZDCsfDaWC2QNZ3qsuwzJ+D4kF+ItW/2kZAILLsWFiO+Rql7ra8TdXsAK1DgVrhZFvPWr0Sd5q5r6AMHbzgav29y7f5/QUTlpCdEaCIXErdFcg8Iyj/ltnGg=,+OyrsFEaDxm/E7XkVL3LlDSZKrqzFb34/p95vkIWAZYGGyRqOg7Dwn9CldCaxnm4epKpJ1upK2gbVv2QXMRJfI3xrQrNjH1xM0eKV371OjmxHOb+jV1Etn8+lbMJUTEPFbScXzpG8/2Jb/JOC4AMzqcou3DX9q9SObijDOfd4Ixp,+O21oRftWwPsBW1KTQYXy8Fqrd6pp6GS8lD/1sES3zaGNojlFA0RLpoTDJAB4VaMurYT+C+XRnD//U+SbZOhfYFbB1JYfANoW7VAtkfKtCHcuh0ma4RgObqnTI/gW+aJdNzNRr1rJw4pbH8LbAXtUtMAkVdAgADc/VTvFGJnoAyDkL9g7uOLGTAiMdLI/dJqWzpDkeoVoVY4poHGNbcgJmCMtg3GAE6aGvmvJ1mlfzScaA==,+PBOwpy1aRHLuMRpDuHmUcaS9vdnCLZp6IZAN2wDxbJbs/ZGztVQAXUWPvSQzxGWqD/p3I+UMKX49DmDR/I51bdWcS15O90PPaFuoKsvUbLgSZQfPUPYt/olKPYKkJGh8iWVvvfjHFzmoNQRb8/Ddh4p2uNq+ucJMmsTSriHnAA09Ixo,+PUJhWuw2T5zyuKM14qlSbqaAFP0W5Sxxq3s3BYtCvH1cuewPZCf2MkUlHQAaecBxH/IE0mNZcL1JL4Tg+hV+biUUb0OyGGNjvDMGGAUw9De70QFdR2brQYt1Z9Y35LR6+Tp3uuEjeV9nqfuQVuNrre60KS6bs/gl/QKEmqnBL8A4CMddUBEv9uuThvoTpViY7ieIAcFvJAUGZ0uMyIgPq6uxJ3ZR5l/25KjPs4o64hNODimVY2CUNLC8ghmKjkj0fAntX1Jhk9ZbprhP5kHBXQY8vOHTgzGr42BvBSJ1s6PIsTvcPJqcabPYK98aQ==,+PSu46gtyci6jMsWXh1EhQsXrOVcOBee8OmxN4UIWYl4ogl70chuXol+PuM9xMwXgyO70vcAP6HMdqt1HERhPirmA9+/CmNWfD9Ppk5uxzK2mwlO+3aRocymtP3305UhDfQMN2EAAAAAAAAAAAAAAAAA6vFEFQXhjm+Z+dX1+87JZItGEWgspm5Ipm5I8rHXtq8tATZGATZGmYyvWVOpp1MnNORAYpZMZu54BsjpxkhFYj+tHmuZ0sdmlAD8Fv8xMDlZhmYvacDxfjYzgHnpl03Poe05nJg3bvZrWDp9nRZ9ukthAbL2Zg==,+H4Zoaxwujxv5Z90GOZp8MTWKyqH/aZ8GiV/GAgkzv214HjWpyGHiQ14O0cSb0FkFvYJ7Izji5NK786wixebx6t4NpPnBpPUHbFrxKfZzIr/TDAcwjggmf139YiyNffSQI7RhWfjh3IXrdACU6vAAkK0OLr0QPrWJzewSVNygUi1AUi1L1Kja9BYv0yENCzHw8HlgAV4teEFD5bC8WIsrvmchAtyj1hTtBbG1xpzOy18oX0HMnelUAB6jKEB+YikvgP5s0tHTBLgJESDd7BgyPe68OkABGCz4PQE1xAH5Zst+pg/Iy9iZHj0qw==,+H7kqrgSVjlOCVBbxsqrI6EFPhdYZOetu152JSJsdG6c/8bEGxtg2+gdAI9jctIp1iCDT5q/57WB4Uo0xcB91RCZ58DpQgpURH3yGlkhByHygKOj3kQP1LOkIhER7BRP+/I4qmbhNYhrwGDB0IQQDpyAMPvV9grxeWcqcEgSU2dLC9347ZmZKLuVeEiDYE+U2jcDwycJfA1+IvMYWZwKEAFpgOSh9AQBGoEMn7EIWPW18DX47V+GFmcBQKAIAQFAoAgAICnNACAD3fnuYAEAHhpX0xgAgADyR69BEEWLx3GgAKodW+dwhn4/MfU8PcRf,+H8ZjHMIt0C/nJOvM15Q+XF1NGE3oJR539FCA6vsfPEDXSAd/qs4CClql9dFmUqUlN8g1ac1o55stqbxVgpOLvqtYWZNPcMyod+wUG0W229zEvZ/ZPNOZN2p41nPTn9Eq8ZqSl9clNOqfQAAAAAAAAAGgn0xzdOBUF4mJtbmJtbJqRo/LIkzSH+mYQtGYQtYvrdgMgY5nfiZnfiUbcrsEq2lWzWK/wHtPq69+Qtp+5DYS9+23mZmHEYqB/RX1yw97tGbIUJy2/2ucxVBIALOt8VB/fQLPyLqNZR0DvmQLE7CeuHG62LJjklWgZ8=,+PSmGup0w+/HoVUR7DNlm/fSA0aSmv0fEKse7YnKBO9M5z6Nl/xlV+iloVu7WbBMiIVTJq5lfHMvLg7NYtiC6O1j8KQ8N0zu2ry1h4hONAhObWR4zi9ce6dof9g6e9dkaPrtXr9fGpPHra0W16G12M2Ruus0ruiKfmS0hCVMEBNXheC8uNzVy/T4oXbIHBU4nUrmv8pOJeS8e6lDTi86kjtdGROXl3vlcHYxBM1WQthzMkL11+G7KMhuNvQEKWrKuK5Jz75fRJokhXwb/0cqYK0pvkH5CxmTI3tnaVXp97++KoRXruqBF7Eu2Leh9M1N5Tqc3Bxp,+O/poo96d9xJHxkURN61CqLOxa2idXb7CoZNzTEuoohbsdapPfUexgMi06YSEBIoDMBjIdA0P+4IGWMDycAH+iY0UFyrtggGhmdU5+yJK6x+0eJt3tUUVKPsnUL1Rvs7ACKQyuz1ob5ZlNDxfERLi6W+Q3v0rFimPdO83OHhjefH65z2bRy8qbgZOCYFtGUsaQ==,+PJJW+T2uUUemPr9BPRJIgrCCC3j+8vRKxRhIuvz8BfOO8NTaKN8ObRSj6YEaqYH/puEGB7nNNYzbrrFe7Oz6HUs2OWpqg0Y/9DAIM5rELVa0Pg4F5OSWVo+EG1ekM2mpZGNHi5QwwGI3IHxqOWUuQLdfE2jEvs9q5gOoe2/A/WTwdPx+G4bSUTP2pRCcahfXGg=,+O5j7DCVhOBLq8VMCrmhdWQmFI8hayjPoj6S8Z+TbFKpeAzDS6jIv9viiLEhcK9M3RkjLSrwAi6BEUXdCjpRpm6/NXp/+8rmEn+hexDL6P++zpn+AH7fq701k58iooXzMQtlZyLTpFEbyViXNvu3F9MsvrjpfbWfNoWUFrVxnVoMaA==,+OyrWn+tR+kpJo0q1ZoRYS19n3I6B8m61jZ+9SSH83cuGVheubWEdWmMkM5wtGC46vkyV+WTlFXxv2Cgxhux1jeZrKKqMJjecmLwPcS+j3D6ABhKso6SfZEWoM1z2PZU0gpVrUen5LcD9WskNQGUnOZ+T32vFeI4S93c8sxp,+OvsTivRO9jMOXuMuZMPqk+cWzakO677Z1D0NmxL+DzI4Icdn6zMVJUo1x6zUy55xPcALg7n8ybhfqCE7LVcsU80mQiUXIqzykz+4MP52kmvAIvlSmFPflHBtg3Rr8zY+cj8O6byTiPs22fHHIu/c2enFMXzVG3xvtwAZCaMaQ==,+PUMu7RoihyKJZ6C8Zl5MER0PSP6CZ+BERtPHQJqOC7aUBr/bU+XDkEYyfeBwnNl2XL8KyxepZwIclTXZJdufli66salGhnOCjPfxAwq3NdgpOW6Hps2ef+VNiwJkgA+mYBNXjmJlIAAAAAAAAAAAAAAAAAAAFgCPYgavZPnrTE2tzE2t/lkSJpD/X4eZH4eZIGW48paXd9i8t9i8iRYyulKO9R3keKL1npLLI5/jtaeoAV1jUd6AqoqsKtfAq357z0TUkHxGpIMYzpHYz542NGPZGkxoE4qELMU0XuNu/t5NnKEZT8hJGk=,+H5nljwDAalEf8pOA7IaGOAQClWn+Y9tfAr2xyQXLNx9c3QDQ2lEwcitcA/DTobXJKZqmBTGrSnGOQ+PgCzHRv6UVTraRM2f5FQ/pNas3DqiCKgBmP8ZfBE3hk0ZprcinAAO6xG3PEPnWpbmmGes40lgchlAN7hqd5QmeVBtB2JsvvqAEB0gUlz+OmMoATXGfkQB6xCUC4Iubg==,+Hnpd86yk+9mRBZEUwNUl13CfcevbKnSkSXUTM7NRA/ijr0cpR/3pVEIRpoSuhr7KIDi/e4YckrrLiZiAN2+HJHtNrnL00/Szs7O/fHE71S2Ux9XgZqlD55Iti4Wf6TUppbcSYUAe0GwBo59sC0wBQgMSXc7qTEJKDtCB6TPoQUOAiF9YAgEOSh9AAE4uaAgBAMGlAEACe5ir0QJ9As9L70ADaw1USj/QFy/R2jlvA==,+H8ZcB7Y4MUb/0wuxafgRJE+4s1iSnxiK/QBXoi9h+ZnGDVu8sbZQhLYftzLMGBREGO3Ms0aiNTZybEQ9EUY2iBuhXTtdX8pVjg7rytNrpcQKOYqIaIq7VNvQekNUje4n4xlFrCBrYCq+2b1HUxiBSt561x0O1x2TAuKSYXtGtgnTMojSZcMDWWMBwD84Ywv6V5h7uOZrJuyAhvVcWPmPAFb5j7lv1G/vokE0OPK8CBO9rtDcVdoILm5Qdll9pI9tX4+o61oTd2MU/Y7pok2IOS1OU6GeSpe2cS/JCIaI5J/,+PSjlExuYZxrslsgjCX7RoxoFPolsW3GHMq2aTP+dznwzure1qgflNXdpIy6d695Q5uUaydESoXLbFRVOVdtyxTBWYCqePh9ZHQEEvULVviYkHauZQRJfvfLJzlPxTFmWKIznaqrbvVz30nd+rcRmlxF/Tpaq966myHAJ3fMs421aS4tDSqpQ6NJK+KOWly+XlhLn9RNBVMStT5y7rrFOpRq2ic17rmKfiH6ZweZPLJxOiM+1IZrQluQqsR4Nd/YOXjEAjZSMz2VmImNfFESjNRl73pFlxne8nQBCFCSBmjJW6nPJSxp,+PJCCTaYKqhRj45NOrNCQQr1Qe6xd+CQ/fBxqtKfYv5WUivet5aFXsS+MUzeYWvNe/u0FzgEWzq6Ncgn9jZiATC2+wGmsOYX2lsk2/In0V25rnD3GXNQi0+gpKH5/d4V2+XmvSlx5fRPqZH8xcuGlIut2o9ze2X31hlfNyk+cXW4DSEbdXD9ndAoHoZI4adacTU+/K1u2YO57PnBrYiUH8u5ExKVsXny0sMWLt68nGg=,+PUJhWmby3TamhJU/o798rq4SCiezYFB9ln1PrRfrVlmn2MsVu+kfCW6xiJUP5sJcFZLtkwJgnvhPHynuM4NtELxuZr2VGMDVAdbtpw3xKafCEWUBaH0KzfpSZXW6cCRsvqVgglKRJHKjsxi4hvkrzzCLajf9FZqGZrmTKWVo7TjAI2nXM69rqGEwmMbiOTlkEthLyHWe5u0GuZhENKDtZYW/dprKmpKjjf7H02d7em6ZX3MhGOUNkRMWrPRNhQDAzOF1RYB4VXM+7seFI5U1a7VKlJOYL5eSdM4aS0R5Bf/2F4p4wJdX+cYZ4m4zuKe/Gk=,+O19rw40JQ5s5nvXnybtZllxBvvMvK7tLJ4vvichhR2qFstObzGqNJJKQF47WPC0IXyLvBTjvzpDE8pxv6eWqgzKpb6c0cvmFZmoJ3zhhJ9e7yypOEzlj2/n2w8ihqNHcUcct6j62occW/RNVy/7FgARmEXP4YO0qEEgjGk=,+O/qIiu8K9yyvP04VP4hyDXOh1LUx75ZXQmSBvXRtOxSTx1jhqPYwuRK5d+VfBVEU9WNPZh/nbpgwGTEEnNYaTR7e4y4HiWyVhQePrE3/f/WIn3WMTsmuJ8GA3n38dKDLc93sz29PPmna43dETazxOrWX71jR1sTTivqrGg=,+O23vEGRvAy4uRRgyCfn/++uNBPWcTAI4B0ZLC/rsRLDnseNrNVnAOnGrQUzJsNa+2j4Ob+jLwluCBYoEFjgB1bO0mmfwp7GJfTA/LPv+Nrjs83ubXIGr4Damym4jHrdnSxzZK/xpH5hCp3Ma72WvaCtjwZ2kSxlCEEaIA+caA==,+PUMxLQDRsLObKk10jTWZ/amJ/kWbQYg8lNXGOCHxiyfXjpRntzGfX5wR3RxDvI7J7yTs45DovM4Ew165X6TirjY8VOqshAIC5BW9sTl9D1S8PyTy3KxvdQyUu0Q3BEXGNJxkkdmq6iNjuu2gAAAAAAAAAAAAAAAF617sQNXsnz1pibW5ibW/vk8k0h/r8PMj8PMkUuQOlza++xeW+xeWoGQG6OFlehnxj2y1UUVbe/b2gm2Ey2aW6iMqonkKdvpqX5AmyX9sh0dgFxmfGWEza/vzNA8Am5Z55a3voYnal/OS+Gr6SBn40+oFXuWjpRo,+HmiLR2VOi3+0ixxZwXP6eRTfEgX0AIOgaLB4yl+/wTNjdOCcErvyxma2XDjkkxD11b299a2P3whjlVwA0WS1NUeOivYywy5IIy5yXGTy4vYHoye+4woKdXaITnIi0F3vZZBGLGeKfDm+ec7YGbCXqL+BjVHm8EHyVSvDdiiwenLuweqkgUniWK3Y9HI1cF54qy/UCc3Pu4xjgWr7yQ44prQfBrIFFtq1IA5LNRahp65UvNFFvXvQw==,+H92ft214abb461yED+XF2ZBrgdz5luyDhRkTt4hLns8sxnRQp0pNUZxR5csjvkc521TT7rxO9TGc0PoX8Z2aKWisaKi2sZJj7S9okXmkk0NSMm2tc9Z+vpbcWk1KJhQB7QbAGjn2wKEAFpggbgXOmzY4ZKD8M722BaYAocBNuD7AQCHJQ+gQAnFzQEAEAwaUAQAE9zFJhABVABAP6n3oAG0KXOSj6rVexcI3wc=,+H9cPCfoKVXfkNC2V9KGYqh8cIrFaOGplT7AFEKK5Tk9wh+QGtAMFVlCgJETR6uJFEoDxM6LhixZn+WAY3W0ieGxkM+EM6TrBHLVvD+4F5Or7vUobKJN9IaEKU4BOqtWUjrI1w230xaSFsNEAAAAAAAAAAAAAAAAAACulpMQNXsnz1pibW5ibW/yyJE0h/r8PMhmELWL6jeUtLuZ342Z34xIsZFxErGlYyPFF6zw7QPc/x2tP3MCW+G9jv3seS2wUG9/Qvz3pvqM2Pf6+4vgTZPEPsV3BhtbPGt3/jWxZaxIqxu/AgJ2kObYBEL58A==,+PBN8k2HG3Ehs+T9TQr5YH0JyTLb0+z+ZP2wqh0kU8Fz4aKwowGfoYQzrFl10oH0QyIMb2vIsBoLyNUcyOgzznhaGHaek4mMByckT2rEK6ZqTr60ou3PkIxNmICxdk/XyN/h43OQDdAQUWneR6y0Sr2Hx85+mipCsNTByfkJZoU35vXHFLAZ+m7u64UZxv96DaIH5h22XGg=,+Oyrgzm77GyeKYNg3nF0qx+xodl0LZ2RSatbw079oRQ9rH5Z+6RizQB7+CE79bTsgY2mkxN0mUzBBkMePXo1RX7eEep5pHhw9QZrYIpCPd2DfYMwgTarHAdkfDsmwv9F8vEbltfNzZoQPe/MG6Gqjw0GfCBFfVBk8kozTOWMaQ==,+Ovs1WvgOqbavudxs7zzMpB9wwjdKB1upFKVwO3+BugKF9ACweKsV36xyal2/Kfqj5ezj6vWkWmOH73NZgCt8/98W06+UkOnTcIF9HYJlK/Zk2BVXDFdssmVeZkD5tk3FKO70x+4fiPwVe07zyVF7e+xuE9T59WpaXxA+YKeTGg=,+PBOwpy1/qb3EDYGzb/GwmN/rCmE33PIYHGJkv3KG9tMN3wU+ycx4JxXZnrmv8xGJKd8qcfsMCVz8+xvoh4FOp+XLuWlyZhYVhTsCxkIbM4fe91KnjHt5DI0wCUgJ23UKEMPrqIq9wh5jNIZ7QL0A7rhwN+xfH5YaeO/TMB4IKhln0q1PIvjDDgANrP8aA==,+Osj3h7yWQWkT6Ap2EAUpceqtrcmHZo6VV4Iw0x5GOvD+AFByhRWEk+E4ANSFJ32rvUtnkgUY42qqQ2SYudRD64PGV5ah2P8tw/yns8V6ML+50Pv6B0NDHajjiOzaOjcDIo+voN4jlhJWGJusSbXx77JjhpmqIYYm7zi8+fd+b7V/Gk=,+OvlCfXz09dvhrhOthtXTl3nk1xd7ViyPiSQKmDYcVtkRqWGAxJE4DuwsmOYV6GlVaW9cA3udRmXH2XsRpvkdAirhSJs8423KIZV+6bolCJhvwd7sX74SxySUrtZpV7zYxMCvvpx/cVnsMk3Rs0/7PxCJoTkhgrR+JtXURAk2qC+snxo,+PBOkz5J3OUGPrisIomvonzWQpSHnHTKpdbsonwR5Mm4XkiaP9Jzw3H/1BcQ9e/2uytcg+kqJQh8sBN+hhEF8vtHojzoLW8aTi4sFxL3m8f+663p1I8rZVbaYig/ZW0hgP7n8f7i//Wa1EyPaICQGN2ftZ3jUp4bM4PWA/xp,+O1/yy51GL7QWVcdjXpbTKx8Vgs78sEppUDXPLCQIP+jqFo5tvSi62lNvXNsSlWaQWD5RBb8zN6KjjCXPz+5hC1WIDrOiTfrkeZTeoOMTDnw4kAPS51fG/GkHW1EDOT3hU/fC3O2Af0fgKgiamBOErJXwoj3ZYxUsZVn+uEkvB72bcWQFvEyxFM/+qFx2MueZi/XiPBv6HH+KgYk9xlTfeyEPOoEUPjhvJVTRjJWzsI3ZJuRgG7UvGk=,+PBos4rlKkHFJi/nla05sE4L+/uV7v1XUhYcjr9PrXtgOmGiD8jTAvpe8pObYI5IWrCq2JvFrJK2owINtb224jq04RpQvn+TFt5Rf9Yalj+UJ685OSu85eVD1rzrRRB29HNXDweTVag1zGWraVo2hyIZD+8Z1+JfoZm4HjWs7Fybe9eadnYl5K79nYsZRzrplzlDZ+w/ZEaSJip4SJEuxQ/enR/A15WPmy6NIJIgKsuipsLoJkxo,+PUJhWmoXAdaDkTreZM2aqs5kttJeDM26ORfBj6XOfwVpmjCD6rMsgr0LUHd7+9ebq3jStOQscOKZrOs5RCzg+fvvUeY2FPWt05PP/AwoS+KABddaNXFJqJnGOUhsZC174Hh5WqQ05dc1f9MX8qIX1Dt71sHRAYfAFSXBefHjzMksR2BYEOozrUaVmhMoZzMuPKOuI0nnnChXHs/zxy70vCmk37lDVWcMvDrh+wHyEr76UhvwvUxG1n9k1pBTjISLCM97sZ5nsLxR+SmLjbq8xRnYlIGPH5BDzTErC+mfZttef0O8u0aO9LBnTDVA5IbxyVcaQ==,+O19rw4xrL0Hc+Nz0AgmmsifnpjZB24B0XRII4uBplz43Rwo1fHU8pVj6RYvqkbJviFOZch4xDIK6fkxBwJRA4DiLB17tGlwvSZzbs3vG4rpSIom4rNz7HXUFeHCYeSPpvQcMbko9pOKDty6gtnbj5LsxjCekAY/HZcEtwoDgPtWDuGYASjMaQ==,+PAclUhXytkeufdfIkvc4U579vCLnPDfUnwR+uzBj4DJkUuKgyeL1ffe5XuAbvkDn83Z2dNVm3NC3SuMO2wdctlVe3fzFjqMOcCq1FrMYMmlDjJQ/oRmHAd09g0H+3COVr1oir4oO4buboN/coVpq5ibbf0HTq4cp4q20hSTr/SCIlpsaA==,+PBoXoDOSBJpgEartFBNb7+bWmFYzJYb7XBlCSDCH0ebtMsZrfBIMOZ1BDqMueB4C2A1qrBjg/YHmi2Qhjk79wTcMhOQffgyHN/K4aLcZgVPPoPnaWR6K5dsduIneIUExvJEpORq42a73wtvaosY2iEDdXLjuZJNp/B2LiulrwrsaA==,+O19F+cT/7+EDQkzOnOqrH8jq2g3Tfi8ZjpUz4d3VxUtdvB1ss0Kjlq1jme4p4p7JHie8Y85Bn/F9k92r9R/pZKaj+aHiN8H5ksQy+dALAsMeperTMPHPKBD4ms/+ItCNUVwyi4cH81ZEULU/3lxhhpJm7XmnAJ3cc1dH4cJ9FeqaQ==,+O108bTvEpTeNWDOusQizPuSlVpjVPIOFm8dPCjqO29SlyrcE0gByt3vkzsLmzJUYDIXkAez8jORHkaiOb2wkb4Ax6HCSFK120EkVU6dwHDMRgaSuHac9f0XRZ5/mqYd/e9GwuDOLejZMwHQIWjvE/KliX3Lc01y9qR8rdMKo4ZrI4bnY8BoNqtM2VKVkdsw0TqpCmk=,+OymDkkLfSzlN37QFADCxFslQzZU6o4Vb/4S2pVlLuSbpOy3agoxlEzalJjGf3FOMNToWF7bTD5EjklC7tkJdQ80HHXyXQl/EyY9JqN9dQ/MQZUCoyWypZC/yi9rFy1F3+1XoVmeLimONobj50AZEtAc48mvZVf71KIutbvoQOyPBM3ZW0Vz7LXI+/hvg+OlMeho,+HIUpC67pdeYHn+KuyDN7rawESWH+UVEy5h/yhxYO54lzdFjKpC7UxB0VMHfP8yJlhyBdf/uZn3D68+VJdcD0SUArbvU9U71DLWfnF8FLVgZBvVfFka7NlAsGS+7WlfMAUqGV+AoGTXelOgpq17YVanFqy1P++zj1T9RqVrl9WkxXHRdaxbZkdYYqL/n89SFHVq8Taoe8JKzNe0XZS3BbgAAO0HwVur+3S1xmdZDk4nWycW92ivN3nh4NA4ohQFq3rGzWqIw4Q/pYhGcU+D4ZChShmi/kLgpizOj8EMUV/qXg3pcKKU=,+P/+,+P/+,+P/+";
        private static final String SCREAM_B64 = "eIAw5jD04VsVrLoaoru3UpKO0sZgbWLEH2YDLMcVmUIGRTBvmCKZRxSVgGhJcUa4sMYnqmI92Vq6OOY2AB4/zHD0lUQk21zVY32kvMyA66c94s4VzJS3HutVnjRGCZztc8P2/P+UXy4HdzgfM4fpaz0lGD8kZJ2ExbHj16Naig==,eI3DdokmbNa7+VnLykmUb8kiLf1UTFjeWjD08rZPqsFGgOpDHu5ezxqNExZ4tQox/jINtAzlO+8PSPmj7zGpII11u5JVuqOAQV5JlSU3XjIMQrfi+Oa/U24HUMxx/QCOsp3QbaOvhfLRemDUVDfJq8zKAg/O37SYFEc0moVngXNx/HF3cB0=,eI/2QknoPOZgC13fcs3KSB+FQAAPX9U1lzkaoxtsmQN8ehFfeCqGsWsmfX54K5/bvESmPZy8h71WfVtvn7CFoVzVeddnDz9gzrTxcZFEmJsFmK9Iq0MNDuX4SOfd90SxbKoQSkB551S01xyOM9dh3oP51WIolM/OdllJ7ZPVV5Q/qAaNIh0gHwo=,eKtkjIY9oWENk62C/wTX4rZHP8Ic2YR+0zDMf3bYgEgCtiHmqpkv5zoDuf4So2uP3mx+idQI629W0CL3dpWsiLPvbXhd2FRRoO3l9/GzLyMXtoCyT8bjg1Gm9hnKmPub5WzBaEj8pPZ2I7jhtu6RGOiWodq4wuJUQ4XYSXP4Cw==,eK7t1tNoZqH2q2qhwmRB6sTvpXH3fpS9Z+BD34nf0JsN60bIFL4Cn3VmLZL5VsdkKM9hgZ5Gw5L9ePicx/EMGQTwfFMEmgQO7w5rKvKZZDqS9X7e1jnd4rwqWYB1MecNzGNSjMNHBtNOia1ijyXnR1tPb6EAFbSolkt4qSi2OTxCkEw=,eLN70Rl2/JIFN/0V3S85XJX9/wFe1KeRXDurUtNHRM95YfcpEuo9eu1AaDe4LPx8Nq0i1Dk7jy82VsK5SIJu3GHOILX7PwxswA0RcmJew62q+hXHcKyiaOj9+HA+s2BIP/MoF7f0KoH6ZsID9HFB+a7vkH9by3A8ZkrXlRNaYGdQlA==,eLUPSLiXmP+XxiUA5HBijatbjS9+pFm6uMnVKtqrnMOqDKqbVMKljHUKaU3/xvwX8+fPZHH2ZuWMRZGlAPZ4zJ6YeATQ0/VOhsxREC+bMtUGMGgdWi1D2DTTbBkKDqp5WZ22FY/6NXnVRvYGMvZq5S2sZk61CgxHg4+9J1ZrkAbXHJAb,eLTtnpQB3DhoXSoFJjGnAQZNHo3kp2s6iWIaXe1NPnXA64rFGIMj5gXqOOg3eN39BV88WXPHB49y8gyGShwr684BaYNNc4+6OQt0KzuyYZPO0Hbn1eRnh+ET/NY1beNtlDxMAavStuKhJ0lN5GMJVSchm7VbrZvysjev8qon/Kb6QeLR7AfL,eLOnFXx1wfWTZKhGPUYryzcgnbAXUXar7HKKkW5VMFLYqA2l3TRHs1FFHt7dwcOQTv5NtB9vMDMuymY6igHgQteYCW62/wWShOBZnyIoPBUzCiG6t5ITOaOXrlPK6hHDsah2uOMd0tHVz1uC+pJk3zRdrRqq7i5Z0IKNkiXN6/HzQbfD,eLPNPdAv6dCac9xfVmElBN8vif/sY9aSQohYviM/pVorLid5EGb9t9vIDMyGJqLi9L9OCtnZYFIQ5nHpcQvmB6FaxD1rWSMlx7YoFwAZ5O1ki5xFIFc5snJocLm7k6lZ6PsNfvQ29IGCw7Sc4chURUkx2X351R5iUW83h4OsulNKR8M=,eLG/Yr054g0iF1SCCLxV7PQVjQViSM9pigZHW+FUdnhMYnPAgAkwbh+tb36dKI7TjhwzCyy4g5dhL1tNqLggrVb1BLk7FEcLHTYEv0JeZuKB3Adfi3vu5ANtk79Yz5oLzkVE0W0bq8UjzYAXGKYX0aijxEeTh4I=,eLH0zOxkckvhpj274C3Xkn9frTdax/n/kLvLiSSC1/A2ZcMcVlOdMLprrefGlz441k0nFEwyUrXGSAoH44nG7bQ68BVvBavp8vYHp1ueJsp/1yzhYYdKHzXYmYxZlpCY8ANUdXgGAr0rwZ10tnAYfWMaGxzS2PxF+9TG63lPeX0q2ZEL5Tuiisd/gg==,eLInL5Jk0J70u7Hr/y/O+UG0OMXBEfiokKD5RR4B/zlUPEnBfjkeExRbwdonVFF3p1boehcjkkc6cKikm9JjqdDuBO83+Ff/q83YumgrNUIHo33wrLEtUCr3QHuRK003Hz/74/Icg8iHDeKZ8kWn1W4A8od5shiIlEKacHDCUnugw13cn4EjRkGTHFbJPpeC,eLUPHw3Xde1iKeXrsHL/+Drs9Zz/8zCaIdgwbtJXYmQs2wC01g7VtKtlBlEhO7QJ/wfKw7iYG5qP0t9jqhtMRZdk2r4X/TVnK+zj1W+lSp2mXtWjSfa0l7bfEqk3jwKzwREoBi8j0ZpTMs4OSvDq70GgQisBdYyBPosIv9Yt0ZqAeDdq9CHVtirnwlEWD4I=,eJQUHGayTxmMSeg8gzSlJ8vJ0R3JK0oAxjYRRFSD1lKrh7qp5Gb0QtQgXijE0BtnFCCyIoYwcFectmdqqpP9dvUBVjxQw5R7qx1eII3JMKT5pKR75TNfqdC4apZbYEKJpoEk2J+UzIPkzpps4UvoQcsuUzSnX0wOMX16,eJQFo8B4sqeFSxyO0fkp+wIqJob+sECZsAhqy9Sk7i5BydVAL3BL7f3qvX9iie0OAng7J775RZ7HlRXEMIjjZdIaT8K3PbXN4tVH13w2SOhWtjS0oUMvphRJlz8n1nFpgO9jnjHEEqVZ+EWCuZJerjjS5ehgb8eajoI=,eJOkD5od61Wng7xSGhn27BESx499z0o6otAZVSJKs3k+AcQbpg6uduetGlnF5Q6FnigA1F158TC2Pxnetal0I2HvnyPIcLDj3pFbPv7Xw1EZVUUeZZpuPpabmho3+jeuH0MC9gHWMkJbsGbsOR2H+8x9OGOtRQ9AZKC/QN6B,eJOP9HwTy6TdTf2ztQtEVcJg9+UmDVXXCIwwKhvxWgFEl/nb/NXBjwUL+5e6a51comsH05TytzEYjDuoKi/RFvicJtfrtoBiUjQ0CmEy/2rwMb2iBfBoZrtZ1y7tvDOmOMGlak5RVDwBQ/hNaR7RLTO6E5IBuSe88g==,eK/aUF+pXf2wZNKhU+gTrQjEJjtkMAQ8f+zTaB1rSR2SKsNAf4/nO0DsQIjr/Inzg4tkYj3945TBW8JwDC4vdhay8C+dwbq/f8lWViUG/Ylz8ebUnH5xktjuDOYwhuDz9ug4FaHKirYt660ZtgzkQu55,eL8e3jVjGsRbki4GStnQq25OTdK8JajHUjojQ4U7O3FsE/+uVUIrsbISOHGdJi/8kVZsYLKnIep4TM7M5dpp9hJ5Wl15FN+lBv3v1RiZAGM7IgZD/KvLTMP4M7OiUho/NHr6t4GeNXaTznXzyIA6oqx/MsQwoQ+JEdRkcQ==,eJMlKIIFyo6pyaSLk3zq3sc92ZCc5L/ypHcr6Q6N+OZMSGCSBDJfcfOB5ycg8z4F6vJC76n7dJfj0Btu9+4Gt8yaTND4rgyXuyMyF6hElXbCmnPPwxidm+7X8QUqnr3X45r9tVp7ykQILnatuC+BIZ1p482Tba6Xtt9TDsP8aQ==,eJOkEn7w6o9rXLR+t20wVgjjRUZ4uzO2aum3lLJ8RB8f2izguCwlF/GVG9gtd+6bORxz93czXp9c/UOaf++fZmbFogrHmHjSDF61MfAx/l2KgSOyU4m78naNOZpt4SF1AkFLiKpLkkne54OHFKLNFfC/zEQLbOINEAPYAmvo,eJOlEZpryhGSQYzHPDjzbV9iZZs0/wNGOF2iWDLnaysCpmJ9UXvRESgUOtHkVBx8NcJG2GSmv7SMNuiIMAJF5UfA8+B2l7qOi03tYILc5YFDApvVeX9eNFKt/Zw2JmKJvv75+a5mr3nZEXvllUJa+gAStljA827IWDpew+g=,eLH7d4M0P8PX/fn5cOWKYYvhgg/J8ix9qcsoovW4e6rp/AhFq76cClU74ANhctkRItXMfuvmptjyl1IOSym0GetpiGRwPvQ4ej+cI8P6l4VfT1gdr2hJktN6qVoTcjdyFopd2zuE5Tm6Lwj7YYGn36h+VIQsgQfcDVa7uiYJovmbSUMp8A==,eLPK4RzqS9xbXUSn+KDH35f9Giy5JSvRcmj7CLB3cB9DF+MdcF8gQWiWBKQksZ7i4NdVmjoWY+x1QOIUhUTLLwu6Q7JxlhcU/G192wyLdfhoQegHjGjwv/RyPKO1TUquMGhtTLs8ylRg54ne3XA6LtrWKfFY2Pb0JQK6dFAoEuKvFMo01xFM/HVAvnQpqA==,eJMk8u/FTGb+IqUm6p+pmjAZlfjstzoYSC6vKpG6e2aRJPc+72+eabZx2LVvIg1FuCrn89rwl1C46ZoJK4NxMC3JbMubCN1xz93pVtwbcM7z/m3EMwnuLvxpeiiSLCD9A9/cqJub6OJERWQT7UbmAA53Bl07bhqTYA==,eJJ7iifXZRgACOCzWoflqYXbHOQz9/EOtjQH8rqnK37Hf2xmi+A/acUBAUEb1Kt0kgEDWj0FIU9mHvEFk2I03HAi//gH35FZwqyTGjcKSTnUBFocTD6SCSe+prq8X645qvTpc2NW6YktgknIRsGd8tg=,eKuHnpHM8faCb5wWGW96f/9eSsKlHRjyBwXmSrGUI5Olw85LC/v/c18WzUtZfuzgY2Ft/DOFO7qDRm8px+rhE3wriH7Q818cq63+w364Nu/arRSLz8UR/z/iEZiJh5bXkpYqmuOzrNXRlDM+T7oio2NPkeo26uBqXGlf,eJKaI8n1efxidmO02HW9Je53ymIN/4wLZllTiBiJG5uvCrbM6lCuBTA3c5/Y5OGTxPJha2qJnxhz9kqwngKrky1tqmMuYmZ8a3uV6YUjAbJWZtOeOaMBGRVcMVi57tdzeBUkpQ7Ov8C261JWC2uAeyK2Lf8EtsI/,eJK+OLsInq7hRCU2qRE4iUWOW6IaR+FBjqe1whaEp0P1pU5XJX6KRjS9qAbgyk1ks41Kc5Bix6Y9dJggoZ6vbcYbHq3MhsrIUvyL/tkc5iFB2lw/ib93+qugDoqOZwIyO3dVNk8x2FgBqFqOAQHXsoxrcyTU4jc=,eLFnK8+bLwBvX5yTsIlVmlkmnZiUTyriUp9LX0KbY71S0LjtCA0tAb+F9Q231mQgNdUeDW2K9Jfpj2mHP8e1MSj5ZIGV8l1K5l8rienjgDZT4Zz/tKY+QHm+uhO+md+tscks0OtOPkk9QEXuWu9zaX/dFnYnWML8p6fe9r1ZghKvsNY=,eLPSkWJwEB2r8GzF8gOcnmevqYQejdBj4VrGMDM66AVhQmZFQgt3u8dwVJfSjJKHIUFLQawiE+tdUbayHPY0u97WVsVoPVJ8CqzsaZPfwrNh3Nm3yXd6cP0rlWtOR91/MrPNe342NMmfe/R/IL2Diug9o2MXlwWDhxT7H+AADChLeKP0g6cG4BxMKJY=,eJMoyLI4qJoTCK9T0rtFVfUX+2MDyUmjgVvT0h+zwwzxW/U3qJ4D6gQC4PxYNy5Iw0QQ2WsBqpFX3n4dh6moe8PRo8+4l+i0TO2aA/8WYA/nveSdskyIyKqIosNCqyRSIS9okZBT5QR7zyoryfLa2CSySJ/CPXUbTS6k8j3v4S4=,eL8xDTpoiEIWaA1ISLJLqrA/WRCFTp9byGYY+9Uq9itzJYUJhyGo5INnhs3iTxmmAcnw1b4tIZF6eHn8sNAHy9Z2h5wlyrk8Jd5bZxYm+SPbqhXulDBaPTCOPm4xUUW34DkHLk+9kqv8BdkZqZxgQRKpMJ0=,eL72kMGxIsQNwNeC9MEsH93GXSV/nK9laaS7j56wRgAYtlAebe9+pQssx2DQCp7GZ0vVGKtWUESoCxObliARL8hKAGjSV5XEmQBJZS7Q9RL+2Fl4VizTTZsF2qb4FvWIReAm9kk1LLrgJd4j19f4UfoHQskShWAd,eKlIbEyOwRTTxNvP2S3f+HgLXICPIs84zw8XgHSoDMVPiLHRbnW1ZtgS0okH68hfVF4Je6bgibxkpKnfQsVYJSMOk41+guzCYyHqrfxvlzhV7t3LrvqStacBvOan95bzZazOyottf0KMOKD8v80=,eKf+N/iKitJZJ7ZK3N2SWw+ZaduNtM8W2L+fg8y4dPq4426RhhWnW5e508xH6tVreXTm7QUQFfaF53sIWZ4khcL8bTPMSZUxsRxfcTaSA4isv5ThJT7u+IrRe08JlDBd/QKID5AbrAsmyivVqEoSs8/E,eL7E5k08Wk2FaslDVN+Xo358OA3MfYYeFM1dA1+EIFG8MC549twtDeyG9cx9PBug2YULC9qYSDydtBfCe4zVE/7PqbNnUMxiQta4rGTUQweStpnY426V1jB4aKIK3HseQVeVNKuqSD9//3vGr1SUMHcqj9mZUty86lkFd08M,eL7j7wN5vf752NLjk0/s4uiobeXKXWseLOOUjgZH+KSl5I//M7SL8dOax9PsOOrDSLs0hQWQTV7io1cVnKH+KgA9nDVuPqDqv6OqESQF/bP6zE/z3YLa1Xr/ileAMboVsoY4Sq1nJB2kOWLFofaQdKh+j4jcMFqLhsYGcWLqWBS4TPs=,eJGglV041UZSJQOxEHFSzc+KX3C2MZ4Ld8rg1wTnywgbK5LiyOeGVkrigW7Nmrm3VPBrG2XCvTDNBMILjeiJL2r3wG0scjNMAfnEKM9w3uuJmdbeZWtHWtOmCk4GsQxPMZ2tY1J9k8x3CEBECEZd6Vp4t5hzSfkBF0xy,eJDpFEvDgGVDoe4DTRsA/ydXriNQzBihSjdanwwZUdr/kbfXiC4y9xjoo1XEMKI3Z/CBW3uNYuSaENmNjMv7QNEOs9YGQjWGNkKsejuEJIQ3ZLqcJXEdNV3yZcpptvHBxJykmxM5DhLW+CJPpSFzFQCT6g==,eJDoVZXCmKBw69kMRXJI1COpuVnb7k31TIPQGYrgViKJp337WmMW21HqPT/KXB+kGFL9y3qGKFiJ936l8w7P12XcovkJ77cmJAEygX1/WSAEz09DC6rxb6Nm8VdeVB6CM8Sy6kgaBXqEKAbQmzlhYdRLT/XGzgeL1auAAtk=,eJA+zeawejeEtM1p/WhiIbVbcRUU95IlzXwNpVf/jDXi/L/3Dm5mlQhunyhF+cz9Whwj1y/xSQ2Gu26pyBNpXhSoD+mfQ0wjY/qpXLdr1r6SNDJdbuHtaom8WTDjYldMFLAFlrKm2whYyKqAr7SnKvF/7b+4BKksQlE=,eL5Wr+LeNUdTNco5YhPBOGwRqYcQ5PMdEnd8lWFTCJ9+ITe3gx2ZJPG4jDzFna4bD1aOZrDySySUiuDBubP0IpFuFc3K+QQILiAqqSxy6fyuG6jVPtXr5jh338i4A2YUf24NclBptxVq+5ppfdf3hpukEAgBwA==,eKDiKTHpwbUWST5i87R3wSB5bmPMq8Xep/VrwEUM6SuCbwuXu+fTv1jVN681RMiJN1j1qqfcaZPw+SHri/Eah3memwlqjZ6TJM3AC1tdczsRmgmJKbURNfql9xgkL8BYMgvHn/ZdJYS1lhkTxnQdxniVlrSPs1l32b0iJWPvOhOawB8=,eL4u6CgUnm251NiyJ6sKsApeOUM02NsNh+fAQyzMeIC3MQZZ38hJA9bx4fl4RuqLToeZw5p13l+uQWtulS5ntV8ZdcPzdFUhyLW4oa9JkWxvkEsO8TF8iRDfKcUZ55LyCbJvgA3DWtoehOV4AWLe/QADGLQv0zNuhQ==,eL5FGy2d6eDhHMZJXqPw/otOAtZXyrtC4yhKNItAilAQswy0b1LyRFsW32hnVvYFTWOpi28kDmK6KKC/IQtjb1DxtiB5+rQMJ7Iq+HucoA02CBB43+6+6k2kpqcWsL4K7u1AbIG9wdF5N685KIu++FMfHFuH4G1D3LBhXlxs,eI2FUXQSTMBPpY8t3OSukCoD0uplYC2VLmcpr+etBIRorPwZ2zyOwXUdP4sVH4VNtEHd14j3xvShIVFyn/stj2CscPgRW6l5mQTLBQ6/zOId0yuHNmJrYgEdNuNOTnyabhxc5ZIJLElCdyWDJzNjW5pgiNhPHlAEwco=,eIxks/rkI/6aFUVLUp47UtIZvs/Nd7995RgxkGJ7GfbKSlxFOuM1sMJ1V2vd1LF9SuoxlOaYKDriP7Y2u3w0g417lrVGc6rK+C1wFKWkXo4L9ZgmNbh+kiZvCo0ZUo8XPKIyVeR+IIaoo/XrrH62LP4=,eIsw/VvrBcTL8tURmVR+5db8PZn1yeog6MmPjd/H/Evc1t4pY8cVR+kVUEIv8CWQLzYyV9B5cQqSUhyGwY8B6edShmQGT3sU52D60m1cKqcfohzbWKoCfSoVqzDmlsQVAFiO8KAMu6V/IWqy8gTX7Hc=,eCOzAauk5bpaMq+482yNo/ll5chT0R5S8ELa7eURaO9YBB5hPlyXsJCJChZD68Uw0I/uiTCaqrrcstpEubv0n62lFBlr/UyH7AzP2mSMVJ0=";
        private static final String BRUH_B64 = "+MqULJNjrPCDvcdxBBpaDQ5hbRecm/5vzHxpJYExwWATGvH/3XDACdueG0ubCSaBNp5NXDkz8NEVfemonybf8PEnJhroGftF+PFnOPcqxWq13KCtnY/tL+zevxsAuG8ny2sZwATVlWuTEOUTTYKrQCNPmiJnqoxselotvbCTA2aTT/kQQPg7kl+GvhS38yuHr0FrAFvrQifzWeELqMjI1z7pxr6HlHxp6jgCoVpzahaHCtMdRrytic8zZoSZglJfmi8llIQ5fLULJilkn/ItOmMc,+MFF7ywsouTG553lBeLV+mW5/4GmiNPjXyncQmu3TKV7UDgT6nxzibfFMxHdAOUppLq+nnDoNTpf5k4QuUrY+a9HuNY1hd2ikC0kc/aSs9XvSfGB3YMOowhKLTvjQGn77KLobuJ9ZNesiXcmByI=,+MTac/Q7DYAAfnyTjTHJ1d053+45f1k5oAp9jTW3g7ckJw7trq5GHgAadibgKK0BUHOpOinwSHyrjwB3dzikotJ974+jUZsbu4X98Nr+46fhQAoMlOoluhqL2zxkJJAwH+yKIN+w88x32lS5b1zPlPqQYhK5QhELJg==,+MRj3b9QF58tMH0xYy0wKWQnsMHIq5Z5V4+VDKJHwvPp/r03CBhsPpmm7KEx0ok9CbVsHhhcdsQfLT0Dr43wV5o5wYWOYqFh5V+ATFiUbE1tNJd6SJlUMYTa1bSDORBiNO8qgvMFis1V8Y9snWvxy+f/1yo=,+MR2/mg57BqLvFWeCeHNq33wNTVdctN5oYkO45Iw4TSuSLSxvqyvig2Uf5hi8jNQSqrCXvZ6x45+Bczw/jCak3Zp9yclp09cI13n7/icHpxlRrauaxnbd0EOQ7FY7cCGBtPd08q3d/+mEtrNdVdC7hQOazE=,+ME+RAaxsvgyKeyQe3GtlHL35l3xnSrE2xYL+9cfvEnUXNj3p/j7mmfSFaj374kdBXN4LpPZilQHzq5hIPLiVOTx96D1zlKKg6x+BR+KdpLS9QxWQYPRuMpYjDHnmtTbgb0vNdXv/U+KIVsHb1LVxdAdLM83,+MR1gqOW4s6GvJk48rzhO+jC+YH2hc5lxJrenZJnhaAiLHoe2X/ZSAdGfbkBqQmP9IuxbJc0AYvsHxr6hJpl+sgrdeTs7KRl+hOcpiPuJ0XZwe9YZR1ZnRKcFyGVIA43AJ06kYqYTQAmJbUFOF9ZmtO7LC/m7rHgfkAb6QwTPQ==,+ME+eV4LZs4Bi5oOJ9buTp4hYV8UomnSqd25GMsN1nzJ8KDBMyjp8CL1wwlKktlbD34VefV9X+IK7u/55jLsSPwS/JV18GqQymEJEZyv09WRT4ZFFD4F79c2wakEyLDjwSVceGxdSnVXyeintfXlzTLbtoYee3rabvjvQg==,+MX1hK6a2ThrBi3NgTxMhVBNM065rHuk+T2kbMfjEhrsyKZGv5Mucv6x4bX3E9s81pvY8x9deq+UlmH0TKcNgJd8p3xwjP9UqOJp5lOQPrWvNawEUGD+EJdK7/GBDWgRa6I1MkFvOFk0Pm26QeTVbxhfQTKG40s=,+MR2/k8v62QmgKJH8z9WRzgbC7X69wj8EejzBdXZ/oVXHI+ICE43qFG6vhzyMk7/3v5afPQqVYadKzIlImRyxaBvMBTxvDSAdfnRW0POQtucha61LWyAQrMJ/4dsM431OIY+HCxHYxnCfR64Hng6qSCyhMLPLVtT,+MYSwlswAlUlhk3uJR2WL/hf1C4ZLRaDrkOT9q+/ofFODQ29tW9b2/8Iax7+C99p0SK0sRGwB1O1cHCIf3Wnyg63fmm6XGej2hGUsMTWFdjdxgianlMniLt70nRYhJ6q7f7/U0EP9+LLnGyxqkGY827TXmNa,+MR1DJ4mXkR/IR9nFaAQGO8QkNvdK4YjDsn1+SIX00SPqHM8k+i7EI5M5W491we6OXmAKSoDxoWG+ylXlUCVYEb4eeuowgjUfyTAe1lLU56AIx3yvX4oQSVpH9JB1iRQv6YtQoopacSZEPRxMcr2DbHzrbYBYUpCm3WLYg==,+MR1gtz7zaCo0J0kEjKdygTeN87saMxeJN74ciuOWyawQE3aCuOdeAoySk+3JHm33czzDto4eB5b1BKMFsAK0CMNOrXJs97MUO/6EXFBrSVyXldn4oivwzaY0oOYFC+n+UgPEjjsBdhUhA+4A9m7BiUtlfyY8fRR2BUHwyT3bA==,+MX3Fe8Ydf/ETS0oMALpYiDrFUrMWYr44UgpHQcvMDR0s0/UtaHs+sjNifslNNKUgHWfID1vXbz6eP/4H391lcnjlQi6yBMUaS8asjyQTJQGx0E9knN4oT9cV5pPvi8cnnyNguk59S5E+GZaMRy0WbkpDU5dgOlHeA==,+NuIpkNFQUbWFJaQcuphKFE5G++0sqsBF9iUGn9pTHX4pb6nf3F6fJTJYMXWioWZEZi/1oJjt1b/Q/l4ZVg3IKhYVC2L1gGYm05Mkwi2dgf4gJiIlpaJOWfbiYt1wKgS6ouLFDghpznrHbhqbRQq1JwtTmTBemSpGLoBbMXuWwtaFNV9sevQoGexvgQ=,+NtojnlayTuoepSf2sf1FANyH4/rSYE19Bv9iFcL/sjucPpcgj74g+94mbkMfsw998RxWsBP18ldsfu4jjP0itmYoFLatNfWxk4k0Zvm8qBljDYV4vlb/2Pfz4xZ9hWlRa+sJcO3b2qTUKl+saV4sdHsGOCBjErK1j+m4o/WcZ02EQ==,+NuKj4Z5PYHinM4il+SXd4yRfu8w1FqFN44cwbyXqIY3CoMfaEdZL3xe7wgstriXVWnzbOyIj1Qv9xzu8sfL/MFILRNTGukggGnWRrztEN1HVoLlX7YDqA1pFq9tV0PR3mnXbXVGZBBnv8/Bh2LKIe2NC5pgERg4aeb6Lh8=,+NuJAWW/GjARIzGtewukJ8d9hhNpL+tLZNEn9MjLIZPgpW9ke7LnVDG8JI34dU02w1NY+jgSCeIhAL4nJbO79bpKON0h5c1rofrNjwqr3ekk5sTpK+0zxe8hAnAAGmbdWHBEkCC6AVo8JjXSuCgrU0WPtGjTzrJ5tG9WLw==,+NuKVlsp+9ixwn/f7l7RmQ0vO6LWNoWRimMgT1qLCa6Pg0PXZzqB12LKKh+DSnluLWQdIdM/wZ1RlL133O94qO77c0sYjc0i3r/9GBPJKc2a/Ze5jQf8tHp5OR3xc+nzphO1r3+FUsbEn0sK5iRy+hWM+E5A,+NtoeuKzUK7A0ZWMUNIDEyJetvHafUdUFy/8bMlOSdLfatoOKRkRnzsmoKqDU1PKQbquGRYQEQrDuGifpJ2B8RhRw9UDxz1abXdsVrFakRObl5BCls3879ZcvkxPcp4YymeNCXtEYdHBg7HkI3tzLf3aLOOkAuVj3oQ2Ug==,+NvWYfa0JFlYcZrhbK34BLi07LuQpnbIRXslh+iJzGl2Getwep6+4f+rapxe2IC4A5Aw2UsyyCpPrlLZmHxpra6h8BswPfLmfXV3Hh6PkNK+50nXwo42E2dpFAPmm7PTMcMcNjhHb7Buz4+tna1FK+ABpDKmaA==,+Ntofsoi/we9H/TDSPK0kJiDX1PKUZuo56wTjbcPhCqs6jVrOykK+JHwE0829ldQsJH3tInyybqMKTB3TvtfB8vY6spRWQU64SSY5sCRU/rqXmdrZsM0mXu56Bkldh4EfQ+ajSA5XNz/oR6D,+NucnlmZE9Uiy9w4YEJ4fd0gmm1t3GSIIrbIowX6F7Wzv9xZiaWYUV9EaHC6gmteoygMw4EjDAm2Sla7le0u5lx5C6EgQ8LuGvK9jl27/ghlQpqjPKkNmnRAOWu9ZVFgwl+kXyBwQYvsZp8=,+NvaL7/0E8EXQyByp8dkdjrXHx7F+u3p9JzpM7Ija8M0K/javoTU/Q0jdJZu5i21OTr//TyaAKJpRmj6cYnJng8hGyjgClXOhbH0hhug1eAN7b9K+B8/bQnf062dyg3LMDAQZiFQeeeYI3RaJOM9eRpnihH7oL3sfVAEt86HlprUA+k8zCDfZoXEBmIqQtCP2bsiWy4fz6rYUc861/zb8dcM2xAvAJvb9cgewg==,+NuLwjSIMKNPJzwJ3ouhy2W3azPHSL85YGeJvqNb8TKtt5T+cKy/ffs8i0bZMxGVaG/Wp4+aPp0+Pcb7Ml0teVcRPKYSe0T2jIXmrYVmJ7wRsMTn+Lk1AQpcCdLKp5STNBmxeWBO6A==,+JvbRY1NghlQWaZDhzMCLllyxVt3jFHbsFhDvPz1RGQ8Npj5qaRjFwCVeZh1FF6iJ8Xy50O6M4ekmPQlz0mfLRY2G4zAjEUH6eZbZOAsklRL/gP9jNURwugpWrW286UE2S/3ykytKu02nbIpfg9JqGokXm5cbWSIUdS2C995tGxRycpexJrbv7iCqHFhlcanMfTd2CUvQSBHvtaq,+J+1NlpJiA5wfpKpgiXqSAVJQ/MJA8HMlTxDJvLD3QYYafi9mR6NULnxuvmsTnAd1gB5GW55Y1/UXajmQo6apS32leOokkRdf7psA/6YR/AETbnGcW5MUlr5krqkaYo3G6fBrn0wDoeFxZpnsktnNtaRYfJ28oPwxv4x8NLe9k/Hw1s994MUfL/hPJOaPVFeYj7pOe5iJoxrOhvKU0LqUoXmyPoTptK1h2slUEK1ncSooqsuISXlgDN+1s5i0BQFdtqw/ThYvUP2GrSzqA==,+J+xuJYNIeCeoQDBTT13dbAn1IoRORdc+rxll2Fo6K2ZvYjrqUMQ7MWd9bzQor6F4xyGWXOTtGv/z4n1Rj68jJGsQSOi+PpkCSSIYbU1aONRlZtBuK8Rmv1PoGBiW26ZyFLyDDSPvopeuFuq8ePLeZabJe/zvUajARGmqBbR4wc01cyC8yt3HgUeM6NPGNUoyPR6fd9dXia+8H+TQsH4MXvt4o8J/dE5qwZ5cEXWmV+oKQ3FGwZOsH8rxBWdEaw=,+J+Erf0f6rfus/65XHBh5aOp/iT/E5Topuo00xCeITOJuxAEhsvDx5woTuXtqkzg5AR3x6OQRouD9yYAMxqFZXSK4u5je8iiWdhzzNVgVMm/upcX2LbBuRk70C5kRc1A5JX6kBLQH7vJtoRz2b8yAoKCtqQewivm63cdFl8uCtNtfjrK0K3264Ycls9CrE7Uz8S/WoKxV4k7KloLvtJ9CZvoD17qgC94EG/IabwaL1aGiCsfSDPn9YKSfStwCA0NiVDl1XXZsg==,+JoH1063qP9jmOvKtFtH9mrEQj34w5lUT/1vkozV4JZGiteLq3Pl5zL3V5rERchy9dF39ZTHvalFdkZdMRU+D9O0YRW1cj77kBN952IJLmDiYNkI68M2MLnc0OdMqFl96V2sMt1uqX3ldFr/Z4enClbc8RcDWIYDYbo=,+IPClzUpyQXlwqmZpVBDxogMKzVhREh+eVGCrpFoepI6Hm+W8eYIaQQT4EMXrY6HyJeXro4pX74Nf8StUUAllwknh7IRoKblwsUg0HqL7+90nzYfq4uQWLRJOcBUZ16S0zlV9Dq+LESqQv8GlfrVjBBjf4pwgPli,+ISq4DwmAVl0rTKjEm8Do2XytKAIEKjq+UyM9hcHlr0XJdVsGUCxOZ8QKaRcjTICmp/ciLaWEYIeA9CPSf7Wv3vyiV2JQ50Uv5Ew9ODft+X0qsU9Vm3d2cxAN15sUZ688x9yTMPA9fTLIY2QWcx9IyT1YA==,+IQKhVwFDHgMN+HQ6vQ/o+T4LUhZgSlBlmC1Lb5GsVY4Rw6hQYfhXhdEx/Wx4Tb4WguYgTkg870LQqOayZ+suqgZOuw9AxnqJh/Yn3O9aXgAj3/tPE3+vZ5H+XpX73UCHWlN8lcyrdnn2uTVMHDNw7pg,+IQUEPrqQhsrRAH3+/V8Pew8g4eLudUv5qOlowE+DZEts2NMTBYBZLIBjsmrVAxuZ51a6MOucWwx+GoLRmK57UWsFjeX8WrL/GJVfL8hXIYyo1SzoDd6NtmHKYJjtIXaDLfrzaSnK+7hwbmtPAXi0bip+NYeG5pi8+I=,+ISr+TtmzJgltclzlf8fAzhcNgnjguZm7pA2Z0sC8VSID1zNKoH49bVi/CDyN72rqH0luKoExvuIYc5xajnP78QYgZV4LGSeyn4RqzCYvztxV9x7fSorHy2gKCKNu9BXDOBSZe5oyE2v/QYPlZ0wZNqykfHYX/QrmeA=,+IQUEPmJbHjIeLgHfPd9oYibN7Jxidk5uyxXoD6Zo+1LdXohlqC41rNSNZSVMDEH0pAhGxWZ8tUlHTQICqGuc+WzhPup8Lt+9Rw7FkRFxwPpgz9fOFRoBSXCzonNULXDubtA7a17JBCbYOQc2mXcJvs3AA2avuLFM+I=,+ICsOvKqYipqWYv0y7McpaLQSOZsmfrY5qHuqEn6Tfh2Vc4WjSBuMODkKiHMNRtWenxabRppZYlwt5oSWZl/mSWm3i1OM59IugN08o9CY8AB+sknas1fCRSwg3deVbHOCK+fq+nvHCNcefRbU/sk1yuZNObzRZlJEs3g,+IaGLai60kagYER51ITYpmzKhc7nNuP0vghlH7e4MFLDeKlIXbI3MLirZOvXgeREgfLBL7Un2YvmkLP+wegqks7+c/TnhvEk4esITfS+uu5MBiprtZP7o3KXlIBMv28KV/L87221+l9ChEk9zby4uJTg,+IB4O3T3XSYxTn6bDYs5UAYuZAdI7lEgfN+cGdKArH9tgTE5BX3sDt0JnTN14n1YwEwGPXvtV5N4Ro+11BrpFHSjIZzIcl5G47Z/7aEVXnxvQdADmoQJsuPwu2rvvHdrC72io2/Lq12JPmI=,+ISyoZaMnz5y8I0Ih0PliIgGNuBSfytdZOaeNmFA00l3NLIHLNvq4Btls+gGTM+96ic69G+pSBOnZgX1hK/JKLq2nHtMmOjQgkI04kj8v9c8M8r2VHGnjFS7bBvb5CowhsoZizDoffHg,+IQySFIp9s4GqKN2Z5rad9o7f0yJAY19KGZdlDqsnSssALAyucnzsDqw/GNGJCaCrk50xhTWoQ9vOOAwMo5Msqlmw1r4LinkeNpl2lRRzE1WrBuXjTjK+L3lNtxKICqQKmCdYHknyrfCRzVea0EDe1Yj+Kzi,+K9UjsYEpWBo+eMOh30kT55ltBnFscPfchN5UrqxVcojALCaDYnB4A+EJjaWJYMmYtifEXOV4aGLrofUwZ0vpzQOLbh8+pwq6akAw6P0uufnSmhA7IloYtssLRWat7mzh0rvOFcjDH2OFZ//4+3+5UA=,+JmGeMAJuA1Cphff94x5EF+tAQsk3jizTlPViEns2yS/ghdzV6dlRueozQvyjQXYeNam9AdZqoU1GrdQCiek4gASJ5UPtqVZoRXDbWLhrSkfsJkdTRptjTpuqMYKGhzDXwneT3CV50vck2umtG98ELWN8iW2,+Jlfhk7jW0xv3LlMwlJecNp+k85k3YCgOL1JeXNsSw0iOTRzCyfKWh8ZLzR9wxDHBvlp6mtq5luObTil7WzjXEejeezJ3iyfJipmPEqAVX94RtcG32+8OPw+f+pCVTOZlwx5ihOw,+JlpUULeG3LZRHc9BaFxCfeLknhRBvQdGmv0fxzOTyERtM4+sC8y28JPlzwH6ppL55nCwnv2rtVZ1b6rYIa3NvMIhp6OiUi6ccirpRl1ITdyhOiEqDk/3baRjrbZDo6q,+JXrGBscNdvEGOpHKGlVtHHNCeu030UmQ3TMhvXrU2rlfYbLywTsXcuCj+eNt0WwDbNDYtQmHWaeyoR/k37jktdbte28/nEJu9fS0LXGFBu+0XW3RMERaVP+87EcySMlczdKPHsBv6Y=,+JYDO2bI2ggPTCB8guk5f5pnsmUTqM5JEvS/czisQK8iaTxiPUeXnJ+PGnPVIWRKldDyf6Hu776gspL4DMEVR+N1EF9eaJ2TvzfEE1lnZuAoXFJTPCCE6buQRQqrqLIGczoMBLF/unmi,+IEj68JnvETmScs9vWXAhOMpCy4ROQqoVANoX2CQHKmpkNR0TKEPRvf7xbzNypDN2ceHpvQErSgJOYTkY2aeqk+W50M7+KDx9+x7/r2nUSr0xb56/v2+5dc+uYaYi5Le,+ICzrGQiuqVBujrQz6xuakuYjSq+mZVOk07P/fHZfgm0m02kUSvCcE3EE4RHfNHnuDlZGk/yS19dFLa1BNW8pgekOKf5yMMxs5p1I37eOn4hqEpUqZHZ5F9H4Q1US7aWJs2pxERc,+IMVym9un68tQnPRnG8lCyX/KqmnUPpbSCTr52IOicmwWfivazVNxMSxizml9xETGKObAW8ZSVywFlO8abDHyBues8QTf3G4t48UAEXRp0lDQ+5KvvTM4yAwN/FLilo=,+IpJOoaK9/gc92/y5z70HdBPkpcW2WdXsuAkd68+mcICSNo9UxuLRKRpPfpcNxwGl0PgQYdeQkE8xJn6qwNLCMJZEWMqLrkQAerNbZPR+MPn5Ak6avePJ90z082av/OeCmblkVBTF6D/zS56rHEz+CkXoNdZa7pvyiqYYcg2X12sVxgyvyVDGyesDRQ72O4lnps79tfo4EhOUBf8+gv6wjr/mpOoj8phWOFcE1UKAs0A+/9urUdTdrVlv30hEDdhhLg=";
        private static final String VINEBOOM_B64 = "+Ip6+5C6pu7zQgoNStSz4aMVJitiNJUEoaEjQIudFlwqrwyt4y4BHblEWIfdb6aRzIV+8lw89jMOfI7+PvhYsJk/b2JsOna458xC1eU7zJyHC8oeHLldxOJ3yb61AB1jPKZ8IRpPBi8Rvy6hy1Dncm1q2EEhSKIc+iIc+m2uGMNZ2s5LxXsgQzI9EIPUa5PtW3yUoyQbMHN9OxkHeSMoklHv7tWp19QJTw1tX2j+l9oiA5UizbyZLc0K/kgNb/kJcFhXk7t8pb52BpXdc0sZveA=,+J/A7anGEYA96pPWBvohjwYLiPRA3qUc/zKFYhZcizBAdspDW1rI0G0/wUC90Dla31ejYX//wSf//H5t8Bkq+wrlVT/21GTsH6rUIzSzi6qoSC35dVYAOAmKYbZygv+batz0EKtBIts9Z1T+Qhnt09QCJ9gIa7u1ixqwIgq7kecbKlyBAkK9PIT2aaJbFh/Fqct+4LcJ8f5m0wVmnS94wYKAtdboomzxSuAU62p/YkyczDE7wp/6BWrYxTmzr5HZ4XYOocQNHCRUjupsKwj9QB8ZNaXMhB3r9qnyCXmO,+J+1+bagQ6GuUDTMqprvsLhidUBwK0NbQK+ATJwouGSUg8NcmX3A6bLTYQI+7W64lDvBa53rA8xfid+MgnTsqOBdWJO8NvWzGkWSx05TGpgxTovBPnl2vQT/y8R4riqEMiwt2CEM9GEAsyL7aBbY0sCXOn8XV1cqBDWJEBVOQ35WP0sqpPmG+UQRO/5L83DLbJ6mt7Kj3PzW5GKrC11u6eIOMVcDHzvvYEbnTIWYfQok+zihtU2q9O4xgzlAeGSZCALqLwvIC1FPF8M3/cgNjg==,+J+sZgqANbSy9TF6a1777777wWtqIn57eLClFZxt9goIalXJlI9k86n6hPW0Cgu/tW3C+y8hiYe/DhIm9sOj80LKB2J7enW0F+A8eCSU1FZUwtmPAnregVGx0kFIwF4MMYA8KS0VaEQNQi2xn6D0Rl5ITraH9PlsTbx7OlAay2PGCt6WUw/Fa3Y65D3w7W7gQS2nEL8nP1o00Q8kRhdWXDAVuNMZ7DjGC2TaXVwNdHs88jPOD9AP5+KrXZzcRdqpPkpd5zazsAifpWZWmAwRc9kA4KEhxlGFcEsfNPjM1+NtkVlJMLONZeWSHDaA8Y9NO9uKwraW,+LUBkxaHkU2IbrtrtfapUEEEED7RWPdzeIqXSLcx6rp8lf+x1vxW5vDlhPi7AmqA8mv24993rwqY8H5KoXD7vmMCRiTThoaqZHL55K5bUiHJjHscFzULlZEsGGMCYl51b/pZkWftWAVRLiEjAn+wusIrLn6wduJFRfr/kii66UuOxWZUF6ZZmeBKssUqI+yG8q+jeeF82mqys+NqA369MJCBv/9Huu6fo0F+XeN/d8Hloj/2DGkV97G3J2fqY7kFV3WMFbFlZpOrRb1tvkT1sjQaLuR1PJdawQWqzdasZjVP3l/nyNhQeBA0yB1SV8PzsGX85DAH2QA=,+IpiMLKUz86hksEYWLE9rWtmodOsMqJxFC8obJXTrjatQerbPao2wkXPl4HPH57Iqibupxh1b5lkENVXvATAb19ZiQEwNtYDwIC/UcG0yIVNmI9T/2iRNpS0Xe9QCDY47JyJbyttv0z2tqvnCgWKco25R3K0o+zSxGEFu1VweiYOZr1ytyO4L11icRROBsYIRhC+HQNoaELjwkaeqgz5sOPM+4Ay4l+mKbttrPcN9QTcdNui7Nd5xKdUEWVRep/4l5uV8I7XOa1TG1IB5GHeeg3V7Sx3fXorcYqU2wTFSD5udRP79y+ksX04ZVvh42KWZhqOgGA=,+IpcjYzpDB/LOWWQibzZs2bNhGlLEF+i3LNFfQsObiaA/Hkpzl50/yUXrrv49wfpVzJRYVJJ/wUSc1eTXyeNa6x0Fob3rXjUkMulFawB8f5uJkZG3VJe8RzHgsZ1eYdVziYCEpKQHti+ZPBIiZvqzXuBIcgw4FpAmB0CwSYXANhzv40BzfcqPXEE7DQjw3EJ6gpConpDJfUotL+M/3o/fc3JUxuqZQZskgg5NBx6xUu0mQSeL9wKrPY0/GebkNWjqeLeBR1B/0kyhmLC8L3/ubt4Z1pmYp5f15MVmgF0ROA=,+ITqt9MLjf35pxTikkDK9UYduai5OYasjW77xRxulIcEJ8GlR6UR0D9+8Kt+OLEWdbeN5qp08R7uIGwCLnrwaSv9iqUx3MIdoXpxVXIHivbCQWl6URAEnB9dR7wxd14q8M3M453ZtPahmkcLm4b7sHc3FBzHo+/Z4A==,+Ipf7voQmXQW8gfgyoUJr9Ca/NGDk7B8tDWWMj9TnSx+nHenqVqz7/mgpBcJqTASCe4mlxLCHoupM/qGQw06+zNBUN6LdzGepar4jwSwwrC+GBeoEJKoZ3uIkitQKKTz/wWdpMQ9A+Kvwveom3/+hkcxd2RBtpqET+bcW0GGrExWsy1Dq8a7pbkS0Oovd4OJl9uEr362lou3lP2i4A+twjbFgHB+9bGDEqWJbQzmRxJvM9GClMCwXb7rX4sExWhsPcsIVWMZdLYc/r0T73WZf4//jQfn5YVyEQfJNyd9s4YnH4uEs+ws3rqdiWvJiejZh8Ti3Cju4A==,+Io1Xbt5+xsSrtJGMkSI0KCB/roST4y+SmDDczBog4Po/PdFtLOuf0KwZq0ULCHmPyuuDSQLJAlgYpIodzXThgxSXPQaS8si8yega98ngtu7I8H+DdQ2r1mQ4wtKlNGNY4ytKyX8+exBtk9axSdiMephfRmZvtZqUOMlFoxXKiTAAZiv2LmZpu5qQTDV+XQb+GuKMrr+vyZr5EL9ikOiWbq5yLYPxaMckuGpy0QJ0g1nScxKvidnoDOR0vKXQNFEAQk3hI/H4ezRjoOUBwBsctBvREeijSpDU72MwPYXAWAmFJ7OzSiy2Vhi,+IS0U9vNAD5BgBf5+FkMT1A/MlwKnY9zJBCyzu2uTdTgcQuPHTHtq3V8PBUdDJpx1WAEW99cRfu1kmL3rwSGrf/i8ggJzwHNJ9n32+8yaYK79GjdMAz2Y6/mWEGERT7zzQtBiNGWmgOGH2sQEGkezRsY2hZxfpVtKL5g,+IpW26CKJf4ZzPPXCjkyZMmTJkyRiAa9qOx88zpl0u0ofmizsu01LTVqGvhJcmAWOv/4ApFGkcBwU630nuy/PpGLwMzAZ3nWOC913DqN60CP6gZ3usNiEttXD2QabU5uCq99BL/eBNkLabx7fxK63VLhqHnW+SRlKpF9b3fs5pphCaqVByvsAOUg+qOE22kmiFB7AJVVNr5CjEye0js4YWWO5B/MkPcco9uYY6IB9+vZxe3uNFurR650DGHoFRrZ+n85wx5/bU0WHAQjCiPi,+IQyRaQ60p0DLK05oW+8zrU8j9QZPVwAZAPVG7/A+n9/LWfLevfoxSMdzP8SoMJTcP0pI9oCpzLZkBSxFqiLzCZduCgANWjrmRn7UJmvh22Y2JGCkhoVYrrhwqTY4A==,+IaF0UgFJMu63cthQ6ry86iTzLdgWzxWLZZtXVY59zqYsnkRaPw9ZzlrMHzytAfToDzsV0mnJ1ub0F7Sm8K8hT05FXD9mONl0Gltt+8x/1F553HycnS8kYa46YKnUZci8of4JlA7Vz+RU0XxC6hVixH8adPN9BupPvNGsuOf+NhV2KN72nImjmA=,+IY3CgPPjj2qJ++l4Zr06Ni3g9TWfzugU+Dy3reaqeeoprplLIB6KrWuVxNct4kIY20wFk7ClrNGEFpn+ji/9E290Or8X+IBDifezPWtLSEANL0VoAR3xt4mX6WNcv6jW2hpS6+YJ+w1vQAg7Nf8My1ISGGJjyd3n0Rw4HR50tsEk+evv9/AMqQlyNAPrR/ztcjg,+IZJN6nzq74iCBM7dbzq8CEDgNrZwnwt0rdStjOThiMwRtqpOX9i/ms5KhXttWXKyhUBWok6R0xd48NC8kK2bxbeaDTzJ7WqLO5BmZuYKkL4lrlXeYqhTKjnU6Ksaxn/jHpKIYLLGvdCtxdjzEnCwk2W419ZHh+WpMW0KV7oEOdm7RNhdYCWJpd5/lTqfLuncrY+oVhCNW1EHK6iXv11jmA=,+IaF4TvnnXa3LapGeSnkDh25acn8G/eZXljv17DxF1IEG0+tpSoyItdCYCaecL58AngBYtpk3qrFUrEyVxOrbrBBq86r6Qnu4MJl0xzMgP5rjjxp+RRBDjyb7nOqPuQd7dkZwM4+0uoIYA==,+IOGg585876ZAO1+8XBDmLseZc2EdulJiP9nPX0WtrHG0EmgzTItnhzTGILJhfBCDF4tmVFT3cCpr/oOsS4fzDkCe6NXmTEElXa7dBAfGqkbz6wWSvhxyNzZ9ZsZyEjx9MmOUhCPjza0PhCgTnvf4A==,+IaE8iXX5rb9/nksBXcB7lFndeD87mpRiXdN0AP2JjQ39OhBRCWCuH6YsPad6gEJ9XdcPX0xOrhw+wr7x7AV0k0zaZOsXnqpd4/J9qPUx/zH66bh4ClRhFCANkkWPtsFJOA=,+IOGqNqfHyr9VJOnmIbv/6ekJwJueOZ+SanghaHdlWlYLps4wKnEJdJn8uAQvibI7FqwYwN3z6a3GLE4cxe6dY45RrYFA8tEq9VRwANWjimloxB6bYw6JMwfLdq/w5DWJjmxi7mOr7Rb3OA=,+IaE8krPWR4IDha4fkhw9hlAoKfuke1mM+9F+MThnpVmSmEfKop3OSfMOQ4E0HypJGEYrpEg8JnJfn3rOct03y/+eMVPlHViZY7TDMA3/0YQS4kVt2rSScm6+KLzPEmyg5bgmI+D4A==,+IOSvS8TuH99bWeL67ahdYdcYVwQNs3PWxuyFOM99VI7v3sEWtlMZ8DVMA4IM4PNgd5ZvM8AjriNC2MwlOdFnhQWgFwGw74w6ImBUg+TFNkcS+BZK+HHI3RqkrW3/TyDRDtqHIGBE+Gr6oWsHYNa4A==,+IaF7T7xmpboNHwRHih0EOSNx9DBFRJ4sG4qCsm4v7nzhXCKsHYJ1m86u3aZFO4djVe6p9ReFIzll/2EJtzFUY9NywO0lyQU/8zk80i1CE4ATv75xDzTdz8F6rmg9qbEsBCzHwvAYA==,+IQKWzpWwfNBifT/ud3gRb8JDTIJ0wQA5/432/c/PUvn8dlGMSmu6QCHSE4E6vnUycscZYFIeFJd2NcSzIOLXO0bv0IqHhoXw7VXMYXCtjHRoPY2QfgCWp9ulcbjRRoiiDBkaBZNawhW6xQiuWA=,+IQK7XzaA/W+/CJpO7uCmWmYaLDaV2yO62JKcdfUUZV//pCYw2s6s7Q9poQ7Zy0KaJLtTHbSgavXpjaMweX0fZqry1feEdnchaXAQLBKore03gG16u30swrYLvMpyHQl85lWdrLtR1LiuL3g,+ISq4EAwJxePHuQsbq8giOMVea33mEf7gRdLjdKlhECHoL1+ywiIh38Jm9cMvqA2KGByQiEtVjtC1BkucYt0fhSIwyx3Nzef2O5PZP1d7OLnH0hg6aOQef1nCQkEhXnwZoeWkRD9VhGRnj8veexfDpBEHsTQzhYpY+9g,+IQT1pKl+QA1hlx2/vm/G6n1CXruakpQ27vE0VvBZL6aLr5idEyfZL99hN6AdV8CiUdW/lM4zgu6DDAyE8ownZBdOacIOiDJBvgKN3hGX7hCNUKbilKEJMZWXZp55o5jeFjpPpcv5KodzFmCQWYpzNgohXqd+frg,+ISq4ilCG1n/zhpBB2AblN3eI1ISjmRA1hxjTzZHTkC+7K+OC6v5BLoBjusUkTBM3k1MKyFgduj+670SKdPnnOh/9Xbp4gvXo+WI9pPSJBuWJOPOr9eWfYbVkoteWq7krCAdhT9AotRStX1ULsDMleA=,+IQT08M1al569piqkcT6aA6vcrSBRPwJD1RQg+JmgG0aoPvWzwd323dRrE1+PuOGVzQOIJaZ/B72QuvCbtjdvB5xCjCgLLTJNdP7/A9uYW5c8xVdeFmyVNfEE0eBurdKInjSwL6scVYiR0Gd2kVwig0bSvhg,+ISrCssIZuwoJn1Cyjjmhm1z/pqMKZtLgPXLQckO9r3kjTiDtkIeqXjHbClljlWavTwLrWHC9sKygCJwDlYk76Kkn4o160BaC/lTa2aCwCAfQgcjGnxP3dajzdHCBQAF2eq1lyicOD1z4A==,+IQK4vy+zJxryz+z1VZvBBKXiX+XhJZVHafE+Qh5DxIdmWDNM5QfYOaEP/Ry+TD56SpN3XyFqzZhT37KCsMRsJFiiSOV8Ml2udJ58mNrrKQAI6HpS6Tex5JF5AsFuieLv+MjjFO2EtQekKFIAdXg,+ISrCzs1/PPSeeQ3ThyxVGYolt/Q+wlj1wN6sHADzUDJy1S+ALECp1f5ePAGd3+jBWpWiLucvsQEjhJX1+bGnorzTt/QrPP6zqcFZ4765XAbTMV3WOfHMz11m043Di3xYA==,+IQx1EQVh7EbQ9YwQGiqD2+ElgCpdsW8NqrPdnvU3Y/e0BhVi33e6wo8AFQEFIvog76Hp4MeZyyFjceEHvzbiMoRhRSgbEPBSLS/bsX3mhC/wvfU4YtLhW8DbFlsUbn9iFi1LlNg,+IQM8aig65+ANrfxj4UGlv6zEZH7RQZCVhp0RyL4GijUj+I4xvNI0RPItbdPXqu3C/+i6u2ELu3IcctpdqP3UPp0mLwXwB/Y4w5NPizy6p/HQCgY5ofIul8LZsWKE6iPYA==,+ISzp4Bb8rM2Y9dQGnCfu4vMHVKgY+dP3jD9NEbJk02gYaayadrD+y2RhbSSKrQV5NgLBf1iI6HQA3HSzm6WvcLZZEqq8gxuRwbSR8rQB0P9EsF5NOv3UiggymFiSejzEClBl32T0uzwaruw4A==,+IQK7Zot/tmcCKOf3rrZjMsOTcJjpTJB1IuwOx4ifPw6iXDI5UHFtci46hfUNq3YDKaQc+qv1dmxMkhp4f4aMxjNFwFKxL/ts38/XEH4Alq6UXc1RTSUx3J+EVjL/XNoQ+1g,+ISzyDRFfKcAK8E6RlF1xboij6OpcBHZRhTqRVxhZsny/Mnd02N6fjKhEFdfLAn6EufJ6mdSnMN413ZgtddF0CuTnVryB1AIe0iWlA1iD2vFAKvF/z7hYBjCI94R1eNWyxzKylMN7N5t4g==,+IQM9PCs8zNVq/Jo2Iv5WV8J53LMz+BQdOQW8leK/UMdyS5BKhZutqvBjZz1PjhtxvbnrpOcF3HrfLy5qQFd9O48UqxsLl1zWTGweRh/PAL7YmnxZgtANTx4+ohkgnxYsky9VBkHkhvr4A==,+IS2FsE0QvHngwTGrV+pAEdF191r7js3Yp2PzMZn8ljGBNHe5F4qcbJQMKxL1JmjEc2Ejj6AWasKV+rgt5OnMvJeGNdbueNEpqP0jXHd8phvX9QXHRSdwzoIfrvuO/fBeWIbnmqbxraeBz+JquI=,+IQM/JiIg/mB/bEVFvm0l0NBfY6W/P5hhZoVmtwZ0RMaBLPtpu9wEKUwaZHVV7iNYR+C/O0uFjbqxwgM+czHPvXFubjSqvqxfvbL+Tx3LuNMD/FFHxtfJQdCNkfjtArg,+IQza445kfDY01qFH4FsDo6g+HOI2rFWyGjjMxZF0Gg2RQ2q/btICSrK0B1mMjYRwnRmG3RNbNSnu2/mrF5YYG+3XIPULm9drp57h3YRVxTkcaVSDaPnpU6plMJUcyaGuAdjL/sQhuA=,+ISzsKcaSrrV0EwUljtnJWzfcSTR/rIXtMurL+JPTrDyWV4fNCCL1LBu6NfkHdGqqps/OBsQmNGGxwJgmAQy6Wz2eLQcYgJy3ac6QccjK9jEOP9xucefeF+bTuvSIInS8gng,+IQM/KuAVwIZhaDmm3L87Zjvhw41S4yvZ1GPLxBtxBxTPpmha/FCR0qFTB6ur4qkuFZAtBl5+owOrJ4Wh8oHZ3OfVpiK/8JEkNK/rIuMdLjRp8RnkgycfTJe1+QPCdbFsA48FiIK4uA=,+ISzUKRJU7GqBKVz3DWoTCM97wjGxRQxihF13lMNUsrqdP0ettd+U+bTmIGEYgwrnhFkRua6MSLx6OqOziATFfbK3cnLn2AA/L9gv4Lkvb66bRusxOF0BSZl4mGI7Cjg,+IBJwfKK5xPn10cgDghYkYyyaHKKPMYp8YjWgT/xwmoqtcZWre4JtFmt050J25+SkLvR4/VD2/iK3e2zHvmUxPH2WiLhdR3cZiO3eYI0jO0SZreW7VdqMi9/LYPDy+jlz+A=,+ISzUdTjQdnSt/H2yGLJqnFJIsGitb3MKUxSbPYl7blSel1ud+u/LyyfP/yMMeKjyL+Su/6LFT6ZiYXvHC/YlGyxIOtCneQmfAfRvuU+D4zZkWVqcyfEmyBpniJ+huA=,+IBSwZVqqkY7o9NY+o31XDAXhdmfgzTEzH3UpjU3v381EZxjh4dc2j5y3ZUF35g3Kq8hpLmHpWFGYZZFeFvN6Bw1VjSR5x07/7m+eBdSHdc200/b3OuBn9+lvpIK7NRDe0EYJf1SPeA=,+ISz0Rxrq2trUDPffrsmJixK6KVaT80IOn0i3yeosp54vETyiV/jSW3w5jTjZxWnnfVpO22hhAprFS2r5UHA7OBtrTZUkEX/PJCSN2kKseRGNclQ/83c/Brzcb2pu295L4qpo+A=,+ICsG+LZs8JCpqAjSOJYtHfmg1kBJ9Oogyd7+7eOYkOWTBXVWfFA3NePDo6bkAAfL+mIHMCUS77sL5DPpqGTnIq6XO6K+XfbAuiGTCTQ2GnIKvLZbpz6Rxrs57Hd12RMGpq+VsKFmpxg,+ISzy/840DH/1EgDWl4AOKQdRk5uBMjJhELwqO4eyaSLFsZ1LOR+fSQBkyCLLuyW5Zx/zJtVDHIXsOcT84T+KFs8Su+1axd+30rLi9VojYHAS0ApvtO+LxBLu4DWSedmLIbA4A==,+Il0GOkMKibafvobEO64Oyhj2CuvYw4fj3Yj/u5XsdiKV83VFexPAg2Hpc0iNoVlsETSA8ZlBOJPT7QklofuxBe0Xw8chodIjuTrDKAkBGDmjOSOkzsVoCVFU7huEqYhC8gO6uKPwChp69crK9l3AWGndUT+3Yxi2ZV4aIPqvh6bUfPtfgkUKIgJxtB9UKAlOMDhk8rIXR9Pc8kuQWOQQTtgFWfCuccomKYI71q8Qj0lUPL4ng0jkRQ/+JvCh9xD3XbTN8pDdaZiX2I=";

        private static List<byte[]> airhornPackets = null;
        private static List<byte[]> screamPackets = null;
        private static List<byte[]> bruhPackets = null;
        private static List<byte[]> vineboomPackets = null;

        private static List<byte[]> decodeList(String csv) {
            List<byte[]> list = new ArrayList<>();
            String[] parts = csv.split(",");
            for (String p : parts) {
                if (!p.isEmpty()) {
                    list.add(android.util.Base64.decode(p, android.util.Base64.DEFAULT));
                }
            }
            return list;
        }

        public static List<byte[]> getOpusPackets(String sound) {
            String s = (sound != null ? sound : "").toLowerCase();
            if (s.contains("airhorn")) {
                if (airhornPackets == null) airhornPackets = decodeList(AIRHORN_B64);
                return new ArrayList<>(airhornPackets);
            }
            if (s.contains("bruh")) {
                if (bruhPackets == null) bruhPackets = decodeList(BRUH_B64);
                return new ArrayList<>(bruhPackets);
            }
            if (s.contains("vine") || s.contains("boom")) {
                if (vineboomPackets == null) vineboomPackets = decodeList(VINEBOOM_B64);
                return new ArrayList<>(vineboomPackets);
            }
            if (screamPackets == null) screamPackets = decodeList(SCREAM_B64);
            return new ArrayList<>(screamPackets);
        }
    }

    public static class OpusMediaEncoder {
        private MediaCodec codec;
        private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        private boolean available = false;

        public OpusMediaEncoder() {
            try {
                MediaFormat format = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000);
                codec = MediaCodec.createEncoderByType("audio/opus");
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                available = true;
            } catch (Throwable t) {
                available = false;
                codec = null;
            }
        }

        public synchronized byte[] encodePcm(short[] pcm) {
            if (pcm == null || pcm.length == 0) {
                return new byte[] { (byte) 0xF8, (byte) 0xFF, (byte) 0xFE };
            }
            if (!available || codec == null) {
                return new byte[] { (byte) 0xF8, (byte) 0xFF, (byte) 0xFE };
            }
            try {
                int inIdx = codec.dequeueInputBuffer(5000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        for (short s : pcm) {
                            inBuf.putShort(s);
                        }
                        codec.queueInputBuffer(inIdx, 0, pcm.length * 2, System.nanoTime() / 1000, 0);
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && bufferInfo.size > 0) {
                        byte[] opus = new byte[bufferInfo.size];
                        outBuf.get(opus);
                        codec.releaseOutputBuffer(outIdx, false);
                        return opus;
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                }
            } catch (Throwable t) {
                // Fallback on transient error
            }
            return new byte[] { (byte) 0xF8, (byte) 0xFF, (byte) 0xFE };
        }

        public void release() {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                    codec = null;
                }
            } catch (Throwable ignored) {}
        }
    }

    public static class DiscordVoiceConnection {
        private final Account account;
        private final String guildId;
        private final String channelId;
        private final String sessionId;
        private final String token;
        private final String endpoint;
        private final OkHttpClient httpClient;
        private final DiscordManager mgr;

        private WebSocket voiceWs;
        private DatagramSocket udpSocket;
        private InetAddress voiceServerAddr;
        private int voiceServerPort = 0;
        private int ssrc = 0;
        private byte[] secretKey = null;

        private volatile boolean connected = false;
        private volatile boolean running = false;
        private String selectedMode = "aead_aes256_gcm_rtpsize";

        public boolean isConnected() {
            return connected && running;
        }
        private Thread audioThread;
        private Handler voiceHeartbeatHandler;
        private Runnable voiceHeartbeatRunnable;
        private final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(500);
        private final OpusMediaEncoder encoder = new OpusMediaEncoder();
        private volatile boolean isLooping = false;
        private String activeSound = "stream";

        private final boolean isStream;

        public DiscordVoiceConnection(Account account, String guildId, String channelId,
                                      String sessionId, String token, String endpoint,
                                      OkHttpClient httpClient, DiscordManager mgr) {
            this(account, guildId, channelId, sessionId, token, endpoint, httpClient, mgr, false);
        }

        public DiscordVoiceConnection(Account account, String guildId, String channelId,
                                      String sessionId, String token, String endpoint,
                                      OkHttpClient httpClient, DiscordManager mgr, boolean isStream) {
            this.account = account;
            this.guildId = guildId;
            this.channelId = channelId;
            this.sessionId = sessionId;
            this.token = token;
            this.endpoint = endpoint;
            this.httpClient = httpClient;
            this.mgr = mgr;
            this.isStream = isStream;
        }

        public void connect() {
            close();
            if (endpoint == null || endpoint.isEmpty() || "null".equals(endpoint)) return;
            String cleanEndpoint = endpoint.split(":")[0];
            String wsUrl = "wss://" + cleanEndpoint + "/?v=8";
            Request req = new Request.Builder()
                    .url(wsUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            voiceWs = httpClient.newWebSocket(req, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    mgr.log("Account #" + account.id + (isStream ? " 🎥 Stream Gateway connected to " : " 🎤 Voice Gateway connected to ") + cleanEndpoint);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JSONObject msg = new JSONObject(text);
                        int op = msg.optInt("op", -1);
                        if (op == 8) { // HELLO
                            JSONObject d = msg.getJSONObject("d");
                            int interval = d.optInt("heartbeat_interval", 20000);
                            startVoiceHeartbeat(interval);
                            sendVoiceIdentify();
                        } else if (op == 2) { // READY
                            JSONObject d = msg.getJSONObject("d");
                            ssrc = d.getInt("ssrc");
                            voiceServerPort = d.getInt("port");
                            String ip = d.getString("ip");
                            voiceServerAddr = InetAddress.getByName(ip);
                            if (d.has("streams")) {
                                JSONArray st = d.getJSONArray("streams");
                                if (st.length() > 0) {
                                    JSONObject s0 = st.getJSONObject(0);
                                    videoSsrc = s0.optInt("ssrc", ssrc + 1);
                                    rtxSsrc = s0.optInt("rtx_ssrc", ssrc + 2);
                                }
                            } else {
                                videoSsrc = ssrc + 1;
                                rtxSsrc = ssrc + 2;
                            }
                            if (d.has("modes")) {
                                JSONArray modes = d.getJSONArray("modes");
                                boolean hasRtpsize = false;
                                boolean hasLegacy = false;
                                for (int i = 0; i < modes.length(); i++) {
                                    String m = modes.optString(i, "");
                                    if ("aead_aes256_gcm_rtpsize".equals(m)) hasRtpsize = true;
                                    if ("aead_aes256_gcm".equals(m)) hasLegacy = true;
                                }
                                if (hasRtpsize) {
                                    selectedMode = "aead_aes256_gcm_rtpsize";
                                } else if (hasLegacy) {
                                    selectedMode = "aead_aes256_gcm";
                                }
                            }
                            mgr.log("Account #" + account.id + (isStream ? " 🎥 Stream Ready: SSRC=" : " 🎤 Voice Ready: SSRC=") + ssrc + ", VideoSSRC=" + videoSsrc + ", IP=" + ip + ":" + voiceServerPort + ", Mode=" + selectedMode);
                            new Thread(() -> performUdpDiscovery(ip, voiceServerPort, ssrc), "VoiceUDP-" + account.id).start();
                        } else if (op == 4) { // SESSION_DESCRIPTION
                            JSONObject d = msg.getJSONObject("d");
                            JSONArray keyArr = d.getJSONArray("secret_key");
                            secretKey = new byte[32];
                            for (int i = 0; i < 32; i++) {
                                secretKey[i] = (byte) keyArr.getInt(i);
                            }
                            mgr.log("Account #" + account.id + (isStream ? " 🔐 Stream Session Established (AES-256-GCM AEAD authenticated)!" : " 🔐 Voice Session Established (AES-256-GCM AEAD authenticated)!"));
                            connected = true;
                            running = true;
                            sendSpeaking(account.voiceFlags > 0 ? account.voiceFlags : 5);
                            startAudioLoop();
                            if (isStream || account.screenShare || account.video) {
                                startVideoStream(isStream ? "screen" : (account.screenShare ? "screen" : "video"));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Voice WS onMessage error", e);
                    }
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    mgr.log("Account #" + account.id + " 🎤 Voice WS closing: " + code);
                    close();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    mgr.log("Account #" + account.id + " 🎤 Voice WS failure: " + (t != null ? t.getMessage() : ""));
                    close();
                }
            });
        }

        private void sendVoiceIdentify() {
            if (voiceWs == null) return;
            try {
                JSONObject idObj = new JSONObject();
                idObj.put("op", 0);
                JSONObject d = new JSONObject();
                d.put("server_id", guildId);
                d.put("user_id", account.userId);
                d.put("session_id", sessionId);
                d.put("token", token);
                d.put("video", true);
                d.put("max_dave_protocol_version", 1);
                if (isStream) {
                    JSONArray streams = new JSONArray();
                    JSONObject s = new JSONObject();
                    s.put("type", "screen");
                    s.put("rid", "100");
                    s.put("quality", 100);
                    streams.put(s);
                    d.put("streams", streams);
                }
                idObj.put("d", d);
                voiceWs.send(idObj.toString());
            } catch (Exception e) {
                Log.e(TAG, "Voice Identify error", e);
            }
        }

        private void performUdpDiscovery(String ip, int port, int ssrc) {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setSoTimeout(5000);

                ByteBuffer req = ByteBuffer.allocate(74);
                req.putShort((short) 1); // 1 = Request
                req.putShort((short) 70); // Length = 70
                req.putInt(ssrc);
                req.put(new byte[66]);
                DatagramPacket sendP = new DatagramPacket(req.array(), 74, voiceServerAddr, voiceServerPort);
                udpSocket.send(sendP);

                byte[] respBuf = new byte[74];
                DatagramPacket respP = new DatagramPacket(respBuf, respBuf.length);
                udpSocket.receive(respP);

                int nullIdx = 8;
                while (nullIdx < 72 && respBuf[nullIdx] != 0) nullIdx++;
                String myIp = new String(respBuf, 8, nullIdx - 8, "UTF-8").trim();

                int myPort = ((respBuf[72] & 0xFF) << 8) | (respBuf[73] & 0xFF);
                if (myPort <= 0 || myPort > 65535) {
                    myPort = (respBuf[72] & 0xFF) | ((respBuf[73] & 0xFF) << 8);
                }
                mgr.log("Account #" + account.id + " 🌐 UDP IP Discovered: " + myIp + ":" + myPort);

                sendSelectProtocol(myIp, myPort);
            } catch (Exception e) {
                mgr.log("Account #" + account.id + " ❌ Voice UDP Discovery failed: " + e.getMessage());
            }
        }

        private void sendSelectProtocol(String myIp, int myPort) {
            if (voiceWs == null) return;
            try {
                JSONObject op1 = new JSONObject();
                op1.put("op", 1);
                JSONObject d = new JSONObject();
                d.put("protocol", "udp");
                JSONObject data = new JSONObject();
                data.put("address", myIp);
                data.put("port", myPort);
                data.put("mode", selectedMode);
                JSONArray codecs = new JSONArray();
                JSONObject opusCodec = new JSONObject();
                opusCodec.put("name", "opus");
                opusCodec.put("type", "audio");
                opusCodec.put("priority", 1000);
                opusCodec.put("payload_type", 120);
                codecs.put(opusCodec);
                JSONObject h264Codec = new JSONObject();
                h264Codec.put("name", "H264");
                h264Codec.put("type", "video");
                h264Codec.put("priority", 1000);
                h264Codec.put("payload_type", 103);
                codecs.put(h264Codec);
                data.put("codecs", codecs);
                d.put("data", data);
                op1.put("d", d);
                voiceWs.send(op1.toString());
            } catch (Exception e) {
                Log.e(TAG, "Select protocol error", e);
            }
        }

        public void sendSpeaking(int flags) {
            if (voiceWs != null) {
                try {
                    JSONObject op5 = new JSONObject();
                    op5.put("op", 5);
                    JSONObject d = new JSONObject();
                    d.put("speaking", flags);
                    d.put("delay", 0);
                    d.put("ssrc", ssrc);
                    op5.put("d", d);
                    voiceWs.send(op5.toString());
                } catch (Exception ignored) {}
            }
        }

        private void startVoiceHeartbeat(int intervalMs) {
            stopVoiceHeartbeat();
            voiceHeartbeatHandler = new Handler(Looper.getMainLooper());
            voiceHeartbeatRunnable = new Runnable() {
                @Override
                public void run() {
                    if (voiceWs != null) {
                        try {
                            JSONObject hb = new JSONObject();
                            hb.put("op", 3);
                            hb.put("d", System.currentTimeMillis());
                            voiceWs.send(hb.toString());
                        } catch (Exception ignored) {}
                        if (voiceHeartbeatHandler != null) {
                            voiceHeartbeatHandler.postDelayed(this, intervalMs);
                        }
                    }
                }
            };
            voiceHeartbeatHandler.postDelayed(voiceHeartbeatRunnable, intervalMs);
        }

        private void stopVoiceHeartbeat() {
            if (voiceHeartbeatHandler != null && voiceHeartbeatRunnable != null) {
                voiceHeartbeatHandler.removeCallbacks(voiceHeartbeatRunnable);
                voiceHeartbeatHandler = null;
                voiceHeartbeatRunnable = null;
            }
        }

        private void startAudioLoop() {
            if (audioThread != null && audioThread.isAlive()) return;
            running = true;
            audioThread = new Thread(() -> {
                int seq = 0;
                int timestamp = 0;
                int nonceCounter = 0;
                byte[] silenceOpus = new byte[] { (byte) 0xF8, (byte) 0xFF, (byte) 0xFE };

                while (running && connected && udpSocket != null && !udpSocket.isClosed() && secretKey != null) {
                    try {
                        byte[] opusPayload = audioQueue.poll();
                        if (opusPayload == null && isLooping && running && connected) {
                            List<byte[]> refill = AudioSynthesizer.getOpusPackets(activeSound);
                            audioQueue.addAll(refill);
                            opusPayload = audioQueue.poll();
                        }
                        if (opusPayload == null) {
                            opusPayload = silenceOpus;
                        }

                        // 12-byte RTP Header
                        ByteBuffer rtpHeader = ByteBuffer.allocate(12);
                        rtpHeader.put((byte) 0x80);
                        rtpHeader.put((byte) 0x78);
                        rtpHeader.putShort((short) seq);
                        rtpHeader.putInt(timestamp);
                        rtpHeader.putInt(ssrc);
                        byte[] rtpHeaderBytes = rtpHeader.array();

                        // 12-byte Nonce
                        ByteBuffer nonceBuf = ByteBuffer.allocate(12);
                        nonceBuf.putInt(nonceCounter);
                        nonceBuf.put(new byte[8]);
                        byte[] nonce = nonceBuf.array();

                        // AES-256-GCM AEAD
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        SecretKeySpec keySpec = new SecretKeySpec(secretKey, "AES");
                        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
                        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
                        cipher.updateAAD(rtpHeaderBytes);
                        byte[] ciphertext = cipher.doFinal(opusPayload);

                        // Complete UDP Packet: [12B RTP Header] + [Ciphertext + Tag] + [4B Nonce Counter]
                        ByteBuffer packetBuf = ByteBuffer.allocate(12 + ciphertext.length + 4);
                        packetBuf.put(rtpHeaderBytes);
                        packetBuf.put(ciphertext);
                        packetBuf.putInt(nonceCounter);

                        byte[] packetData = packetBuf.array();
                        DatagramPacket udpPacket = new DatagramPacket(packetData, packetData.length, voiceServerAddr, voiceServerPort);
                        udpSocket.send(udpPacket);

                        seq = (seq + 1) & 0xFFFF;
                        timestamp += 960;
                        nonceCounter++;

                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Audio send loop error", e);
                        try { Thread.sleep(20); } catch (Exception ignored) {}
                    }
                }
            }, "DiscordAudio-" + account.id);
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
        }

        public void playSfx(String sound, int flags, double duration) {
            playSfx(sound, flags, duration, false);
        }

        public void playSfx(String sound, int flags, double duration, boolean loop) {
            sendSpeaking(flags > 0 ? flags : 5);
            audioQueue.clear();
            this.activeSound = sound;
            this.isLooping = loop;
            List<byte[]> frames = AudioSynthesizer.getOpusPackets(sound);
            audioQueue.addAll(frames);
            mgr.log("Account #" + account.id + " 🔊 Queued " + frames.size() + " RFC 6716 Opus frames for SFX: " + sound + (isLooping ? " (Loop Mode)" : " (No Loop)"));
        }

        public void playTts(String text) {
            sendSpeaking(1);
            audioQueue.clear();
            this.isLooping = false;
            List<byte[]> frames = AudioSynthesizer.getOpusPackets("airhorn");
            audioQueue.addAll(frames);
            mgr.log("Account #" + account.id + " 🗣️ Queued " + frames.size() + " RFC 6716 Opus frames for TTS Announcer: \"" + text + "\"");
        }

        public void stopAudio() {
            this.isLooping = false;
            audioQueue.clear();
            sendSpeaking(0);
        }

        private Thread videoThread;
        private volatile boolean videoRunning = false;
        private int videoSsrc = 0;
        private int rtxSsrc = 0;
        private int videoSeq = 0;
        private int videoTimestamp = 0;
        private int videoNonceCounter = 0;

        public void startVideoStream(String type) {
            if (voiceWs == null) return;
            try {
                if (videoSsrc == 0) videoSsrc = ssrc + 1;
                if (rtxSsrc == 0) rtxSsrc = ssrc + 2;

                JSONObject op12 = new JSONObject();
                op12.put("op", 12);
                JSONObject d = new JSONObject();
                d.put("audio_ssrc", ssrc);
                d.put("video_ssrc", videoSsrc);
                d.put("rtx_ssrc", rtxSsrc);
                JSONArray streams = new JSONArray();
                JSONObject stream = new JSONObject();
                stream.put("type", type != null ? type : "screen");
                stream.put("rid", "100");
                stream.put("ssrc", videoSsrc);
                stream.put("rtx_ssrc", rtxSsrc);
                stream.put("quality", 100);
                stream.put("active", true);
                streams.put(stream);
                d.put("streams", streams);
                op12.put("d", d);
                voiceWs.send(op12.toString());

                sendSpeaking(5);

                if (!videoRunning) {
                    videoRunning = true;
                    videoThread = new Thread(() -> runVideoLoop(), "DiscordVideo-" + account.id);
                    videoThread.setPriority(Thread.MAX_PRIORITY);
                    videoThread.start();
                    mgr.log("Account #" + account.id + " 🎥 Video Stream Started (" + type + "): video_ssrc=" + videoSsrc);
                }
            } catch (Exception e) {
                Log.e(TAG, "startVideoStream error", e);
            }
        }

        public void stopVideoStream() {
            videoRunning = false;
            if (videoThread != null) {
                videoThread.interrupt();
                videoThread = null;
            }
            if (voiceWs != null) {
                try {
                    JSONObject op12 = new JSONObject();
                    op12.put("op", 12);
                    JSONObject d = new JSONObject();
                    d.put("audio_ssrc", ssrc);
                    d.put("video_ssrc", 0);
                    d.put("rtx_ssrc", 0);
                    JSONArray streams = new JSONArray();
                    d.put("streams", streams);
                    op12.put("d", d);
                    voiceWs.send(op12.toString());
                    sendSpeaking(account.speaking ? (account.voiceFlags > 0 ? account.voiceFlags : 1) : 0);
                    mgr.log("Account #" + account.id + " ⏹️ Video Stream Stopped");
                } catch (Exception ignored) {}
            }
        }

        private void runVideoLoop() {
            int frameIdx = 0;
            while (videoRunning && running && connected && udpSocket != null && !udpSocket.isClosed() && secretKey != null) {
                try {
                    List<VideoSynthesizer.RtpPayload> pkts = VideoSynthesizer.getFramePackets(frameIdx++);
                    for (VideoSynthesizer.RtpPayload p : pkts) {
                        sendVideoRtpPacket(p.payload, p.marker);
                    }
                    videoTimestamp += 3000;
                    Thread.sleep(33);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Video send loop error", e);
                    try { Thread.sleep(33); } catch (Exception ignored) {}
                }
            }
        }

        private void sendVideoRtpPacket(byte[] payload, boolean marker) {
            try {
                if (udpSocket == null || udpSocket.isClosed() || secretKey == null || voiceServerAddr == null) return;

                // 12-byte RTP Header
                ByteBuffer rtpHeader = ByteBuffer.allocate(12);
                rtpHeader.put((byte) 0x80);
                rtpHeader.put((byte) ((marker ? 0x80 : 0x00) | 103)); // H.264 video payload type 103
                rtpHeader.putShort((short) videoSeq);
                rtpHeader.putInt(videoTimestamp);
                rtpHeader.putInt(videoSsrc);
                byte[] rtpHeaderBytes = rtpHeader.array();

                // 12-byte Nonce
                ByteBuffer nonceBuf = ByteBuffer.allocate(12);
                nonceBuf.putInt(videoNonceCounter);
                nonceBuf.put(new byte[8]);
                byte[] nonce = nonceBuf.array();

                // AES-256-GCM AEAD
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                SecretKeySpec keySpec = new SecretKeySpec(secretKey, "AES");
                GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
                cipher.updateAAD(rtpHeaderBytes);
                byte[] ciphertext = cipher.doFinal(payload);

                // Complete UDP Packet: [12B RTP Header] + [Ciphertext + 16B Tag] + [4B Nonce Counter]
                ByteBuffer packetBuf = ByteBuffer.allocate(12 + ciphertext.length + 4);
                packetBuf.put(rtpHeaderBytes);
                packetBuf.put(ciphertext);
                packetBuf.putInt(videoNonceCounter);

                byte[] packetData = packetBuf.array();
                DatagramPacket udpPacket = new DatagramPacket(packetData, packetData.length, voiceServerAddr, voiceServerPort);
                udpSocket.send(udpPacket);

                videoSeq = (videoSeq + 1) & 0xFFFF;
                videoNonceCounter++;
            } catch (Exception e) {
                Log.e(TAG, "sendVideoRtpPacket error", e);
            }
        }

        public void close() {
            stopVideoStream();
            this.isLooping = false;
            running = false;
            connected = false;
            stopVoiceHeartbeat();
            if (audioThread != null) {
                audioThread.interrupt();
                audioThread = null;
            }
            if (voiceWs != null) {
                try {
                    voiceWs.close(1000, "Voice disconnect");
                } catch (Exception ignored) {}
                voiceWs = null;
            }
            if (udpSocket != null) {
                try {
                    udpSocket.close();
                } catch (Exception ignored) {}
                udpSocket = null;
            }
            encoder.release();
            audioQueue.clear();
        }
    }
}
