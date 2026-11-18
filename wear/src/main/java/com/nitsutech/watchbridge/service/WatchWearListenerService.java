package com.nitsutech.watchbridge.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.wear.tiles.TileService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.gson.Gson;
import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.complication.WatchBridgeComplicationService;
import com.nitsutech.watchbridge.tile.WatchBridgeTileService;
import com.nitsutech.watchbridge.ui.WearMainActivity;
import com.nitsutech.watchbridge.wear.WatchAudioHelper;
import com.nitsutech.watchbridge.wear.WatchHapticHelper;
import com.nitsutech.watchbridge.wear.WatchNotifHelper;
import com.nitsutech.watchbridge.wear.WatchPrefsManager;
import com.nitsutech.watchbridge.wear.WearProtocol;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Listens for Phone -> Galaxy Watch 8 Classic updates (/watchbridge/state, /sync, /alert, /audio_*),
 * handles differentiated Haptics, Progressive Escalation (Idea #8), TTS Readout (Idea #2),
 * and Voice Note playback over the watch speaker.
 */
public class WatchWearListenerService extends WearableListenerService {

    public static final String ACTION_WATCH_STATE_UPDATED = "com.nitsutech.watchbridge.WATCH_STATE_UPDATED";
    public static final String ACTION_WATCH_ALERT_TICK = "com.nitsutech.watchbridge.WATCH_ALERT_TICK";
    public static final String ACTION_WATCH_AUDIO_READY = "com.nitsutech.watchbridge.WATCH_AUDIO_READY";
    public static final String EXTRA_AUDIO_FILE_PATH = "audio_file_path";
    public static final String EXTRA_ALERT_JSON = "alert_json";

    private static final String CHANNEL_WATCH_ESCALATION = "watchbridge_watch_escalation";
    private final Gson gson = new Gson();

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        WatchPrefsManager prefs = new WatchPrefsManager(this);
        boolean updated = false;
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED
                    && WearProtocol.PATH_STATE_DATA.equals(event.getDataItem().getUri().getPath())) {
                String json = DataMapItem.fromDataItem(event.getDataItem()).getDataMap().getString("state_json");
                if (json != null && !json.isEmpty()) {
                    prefs.saveStateJson(json);
                    updated = true;
                }
            }
        }
        if (updated) {
            refreshWatchSurfaces();
            sendBroadcast(new Intent(ACTION_WATCH_STATE_UPDATED).setPackage(getPackageName()));
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        String raw = new String(messageEvent.getData(), StandardCharsets.UTF_8);
        WatchPrefsManager prefs = new WatchPrefsManager(this);

        if (WearProtocol.PATH_SYNC_MSG.equals(path)) {
            prefs.saveStateJson(raw);
            WearProtocol.WatchStatePayload state = prefs.getState();
            if (state.activeAlertCount == 0) {
                WatchNotifHelper.cancelAll(this);
            }
            refreshWatchSurfaces();
            sendBroadcast(new Intent(ACTION_WATCH_STATE_UPDATED).setPackage(getPackageName()));
        } else if (WearProtocol.PATH_ALERT_MSG.equals(path)) {
            try {
                WearProtocol.AlertTickPayload tick = gson.fromJson(raw, WearProtocol.AlertTickPayload.class);
                if (tick != null) {
                    prefs.saveLastAlert(tick);
                    handleIncomingAlertTick(tick, prefs.getState());
                    sendBroadcast(new Intent(ACTION_WATCH_ALERT_TICK)
                            .putExtra(EXTRA_ALERT_JSON, raw)
                            .setPackage(getPackageName()));
                }
            } catch (Exception ignored) {
            }
        } else if (WearProtocol.PATH_AUDIO_MSG.equals(path)) {
            try {
                WearProtocol.AudioTransferHeader header = gson.fromJson(raw, WearProtocol.AudioTransferHeader.class);
                if (header != null) {
                    prefs.setLastFallbackTts(header.fallbackTtsText);
                    if (!header.isAudioAvailable && header.fallbackTtsText != null && !header.fallbackTtsText.isEmpty()) {
                        WatchAudioHelper.speakTts(this, header.fallbackTtsText);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onChannelOpened(@NonNull ChannelClient.Channel channel) {
        if (!WearProtocol.PATH_AUDIO_CHANNEL.equals(channel.getPath())) {
            return;
        }
        try {
            File outFile = new File(getCacheDir(), "watch_voice_" + System.currentTimeMillis() + ".ogg");
            try (InputStream is = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel));
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
            }
            Wearable.getChannelClient(this).close(channel);
            WatchPrefsManager prefs = new WatchPrefsManager(this);
            WatchAudioHelper.playVoiceFile(this, outFile, prefs.getPlaybackSpeed(), null);
            sendBroadcast(new Intent(ACTION_WATCH_AUDIO_READY)
                    .putExtra(EXTRA_AUDIO_FILE_PATH, outFile.getAbsolutePath())
                    .setPackage(getPackageName()));
        } catch (Exception ignored) {
        }
    }

    private void handleIncomingAlertTick(WearProtocol.AlertTickPayload tick, WearProtocol.WatchStatePayload state) {
        WatchHapticHelper.playPattern(this, tick.hapticPattern, tick.escalationLevel);
        WatchNotifHelper.postWatchNotification(this, tick, state);

        if (tick.autoTts && tick.senderName != null && tick.messageText != null) {
            WatchAudioHelper.speakTts(this, tick.appLabel + ", " + tick.senderName + ": " + tick.messageText);
        }

        // Idea #8 Progressive Wake-Up Escalation at Attempt 3+
        if (tick.escalationLevel >= 3) {
            WatchAudioHelper.playEscalationChime(this);
            postEscalatedWatchNotification(tick);
        }
    }

    private void postEscalatedWatchNotification(WearProtocol.AlertTickPayload tick) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_WATCH_ESCALATION,
                    getString(R.string.watch_escalation_channel),
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        Intent openIntent = new Intent(this, WearMainActivity.class);
        openIntent.putExtra(WearMainActivity.EXTRA_OPEN_CHAT_KEY, tick.conversationKey);
        openIntent.putExtra(WearMainActivity.EXTRA_ESCALATED, true);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 8801, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification n = new NotificationCompat.Builder(this, CHANNEL_WATCH_ESCALATION)
                .setSmallIcon(R.drawable.ic_watch_logo)
                .setContentTitle(tick.senderName + " (" + tick.appLabel + ")")
                .setContentText(tick.messageText)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .build();
        nm.notify(8801, n);
    }

    private void refreshWatchSurfaces() {
        try {
            TileService.getUpdater(this).requestUpdate(WatchBridgeTileService.class);
        } catch (Exception ignored) {
        }
        try {
            ComplicationDataSourceUpdateRequester.create(
                    this,
                    new ComponentName(this, WatchBridgeComplicationService.class)
            ).requestUpdateAll();
        } catch (Exception ignored) {
        }
    }
}
