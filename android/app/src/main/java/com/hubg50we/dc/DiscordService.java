package com.hubg50we.dc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

public class DiscordService extends Service {
    private static final String CHANNEL_ID = "dc_voice_service";
    private static final int NOTIFICATION_ID = 1001;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dc:VoiceManagerWakeLock");
            wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours
        }

        DiscordManager.getInstance(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            DiscordManager dm = DiscordManager.getInstance(this);
            if ("ACTION_MUTE_ALL".equals(act)) {
                org.json.JSONObject p = new org.json.JSONObject();
                try { p.put("action", "mute"); p.put("percent", 100); } catch (Exception ignored) {}
                dm.handleAction(p);
            } else if ("ACTION_UNMUTE_ALL".equals(act)) {
                org.json.JSONObject p = new org.json.JSONObject();
                try { p.put("action", "unmute"); p.put("percent", 100); } catch (Exception ignored) {}
                dm.handleAction(p);
            } else if ("ACTION_DISCONNECT_ALL".equals(act)) {
                org.json.JSONObject p = new org.json.JSONObject();
                try { p.put("action", "leave"); p.put("percent", 100); } catch (Exception ignored) {}
                dm.handleAction(p);
            } else if ("ACTION_JOIN_VOICE".equals(act) || "ACTION_AUTO_START".equals(act)) {
                org.json.JSONObject p = new org.json.JSONObject();
                try { p.put("action", "move"); p.put("percent", 100); } catch (Exception ignored) {}
                dm.handleAction(p);
            }
        }
        Notification notification = buildNotification("dc- Voice Manager Running in Background");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "dc- Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps Discord voice accounts connected in the background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent muteIntent = new Intent(this, DiscordService.class);
        muteIntent.setAction("ACTION_MUTE_ALL");
        PendingIntent mutePending = PendingIntent.getService(this, 1, muteIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent unmuteIntent = new Intent(this, DiscordService.class);
        unmuteIntent.setAction("ACTION_UNMUTE_ALL");
        PendingIntent unmutePending = PendingIntent.getService(this, 2, unmuteIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent disconnIntent = new Intent(this, DiscordService.class);
        disconnIntent.setAction("ACTION_DISCONNECT_ALL");
        PendingIntent disconnPending = PendingIntent.getService(this, 3, disconnIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("dc- Discord Manager (v1.4.8)")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(R.drawable.ic_launcher, "Mute", mutePending)
                .addAction(R.drawable.ic_launcher, "Unmute", unmutePending)
                .addAction(R.drawable.ic_launcher, "Disconnect", disconnPending)
                .build();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
