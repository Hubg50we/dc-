package com.hubg50we.dc;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.json.JSONObject;

@RequiresApi(api = Build.VERSION_CODES.N)
public class DcQuickTileService extends TileService {
    private static final String TAG = "DcQuickTileService";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            Tile tile = getQsTile();
            if (tile == null) return;

            DiscordManager dm = DiscordManager.getInstance(this);
            JSONObject status = dm.getStatusJson();
            int voiceAccounts = status.optInt("voice_accounts", 0);

            if (voiceAccounts > 0 || tile.getState() == Tile.STATE_ACTIVE) {
                // Currently connected -> 1-tap disconnect
                JSONObject p = new JSONObject();
                p.put("action", "leave");
                p.put("percent", 100);
                dm.handleAction(p);
                Log.i(TAG, "Quick Settings Tile: Disconnected voice accounts");
            } else {
                // Currently idle -> 1-tap join / reconnect
                Intent s = new Intent(this, DiscordService.class);
                s.setAction("ACTION_JOIN_VOICE");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(s);
                } else {
                    startService(s);
                }
                JSONObject p = new JSONObject();
                p.put("action", "move");
                p.put("percent", 100);
                dm.handleAction(p);
                Log.i(TAG, "Quick Settings Tile: Joining voice accounts");
            }
        } catch (Exception e) {
            Log.e(TAG, "Quick Tile click error", e);
        }
        updateTileState();
    }

    private void updateTileState() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;

            DiscordManager dm = DiscordManager.getInstance(this);
            JSONObject status = dm.getStatusJson();
            int voiceAccounts = status.optInt("voice_accounts", 0);
            int totalAccounts = status.optInt("total_accounts", 0);

            if (voiceAccounts > 0) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("DC- (" + voiceAccounts + " Voice)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.setSubtitle("Tap to Disconnect");
                }
            } else if (totalAccounts > 0) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("DC- Standby");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.setSubtitle("Tap to Join");
                }
            } else {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("DC- 24/7 Voice");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.setSubtitle("No Accounts");
                }
            }
            tile.updateTile();
        } catch (Exception e) {
            Log.e(TAG, "Failed to update Quick Settings Tile state", e);
        }
    }
}
