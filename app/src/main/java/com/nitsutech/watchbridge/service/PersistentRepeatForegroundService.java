package com.nitsutech.watchbridge.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.WatchBridgeApp;
import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.ChatMessageEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.receiver.ProxyActionReceiver;
import com.nitsutech.watchbridge.ui.MainActivity;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;
import com.nitsutech.watchbridge.wear.WearProtocol;
import com.nitsutech.watchbridge.widget.BridgeWidgetUpdater;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service driving Repeat Mode persistent foreground state, repeating Re-Ping alerts
 * for Galaxy Watch 8 Classic, and Idea #8 Progressive Wake-Up Escalation.
 */
public class PersistentRepeatForegroundService extends Service {

    public static final String ACTION_START_REPEAT = "com.nitsutech.watchbridge.ACTION_START_REPEAT";
    public static final String ACTION_STOP_REPEAT = "com.nitsutech.watchbridge.ACTION_STOP_REPEAT";
    public static final String ACTION_ENQUEUE_REPEAT = "com.nitsutech.watchbridge.ACTION_ENQUEUE_REPEAT";
    public static final String ACTION_STOP_CHAT = "com.nitsutech.watchbridge.ACTION_STOP_CHAT";
    public static final String ACTION_STOP_ALL_ALERTS = "com.nitsutech.watchbridge.ACTION_STOP_ALL_ALERTS";
    public static final String ACTION_STATE_BROADCAST = "com.nitsutech.watchbridge.STATE_UPDATED";

    public static final String EXTRA_CONV_KEY = "conv_key";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_APP_LABEL = "app_label";
    public static final String EXTRA_CONV_TITLE = "conv_title";
    public static final String EXTRA_SENDER = "sender";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_IS_GROUP = "is_group";
    public static final String EXTRA_IS_CALL = "is_call";
    public static final String EXTRA_REPEAT_SEC = "repeat_sec";
    public static final String EXTRA_SOUND_MODE = "sound_mode";
    public static final String EXTRA_HAPTIC_PATTERN = "haptic_pattern";
    public static final String EXTRA_HAS_VOICE = "has_voice";
    public static final String EXTRA_MEDIA_B64 = "media_b64";

    private static final int FOREGROUND_ID = 1001;

    public static class ActiveSession {
        public String conversationKey;
        public String packageName;
        public String appLabel;
        public String conversationTitle;
        public String senderName;
        public String latestText;
        public boolean isGroup;
        public boolean isCall;
        public boolean hasVoiceNote;
        public String mediaBase64;
        public int repeatIntervalSec;
        public int phoneSoundMode;
        public int hapticPattern;
        public long startedAtMs;
        public int attemptCount = 1;
        public ScheduledFuture<?> future;
    }

    private static final ConcurrentHashMap<String, ActiveSession> SESSIONS = new ConcurrentHashMap<>();
    private static volatile PersistentRepeatForegroundService activeInstance;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static int getActiveSessionCount() {
        return SESSIONS.size();
    }

    public static boolean isConversationRepeating(String conversationKey) {
        return conversationKey != null && SESSIONS.containsKey(conversationKey);
    }

    public static void startRepeatForeground(Context context) {
        Intent i = new Intent(context, PersistentRepeatForegroundService.class);
        i.setAction(ACTION_START_REPEAT);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stopRepeatForeground(Context context) {
        for (ActiveSession s : SESSIONS.values()) {
            if (s.future != null) s.future.cancel(false);
        }
        SESSIONS.clear();
        ProxyNotificationHelper.cancelAllNotifications(context);
        PersistentRepeatForegroundService inst = activeInstance;
        if (inst != null) {
            inst.stopForeground(STOP_FOREGROUND_REMOVE);
            inst.stopSelf();
        }
        broadcastAndSync(context);
    }

    public static void enqueueRepeat(Context context, String convKey, String pkg, String appLabel,
                                     String convTitle, String sender, String text,
                                     boolean isGroup, boolean isCall, int repeatSec,
                                     int soundMode, int hapticPattern, boolean hasVoice, String mediaB64) {
        Intent i = new Intent(context, PersistentRepeatForegroundService.class);
        i.setAction(ACTION_ENQUEUE_REPEAT);
        i.putExtra(EXTRA_CONV_KEY, convKey);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_APP_LABEL, appLabel);
        i.putExtra(EXTRA_CONV_TITLE, convTitle);
        i.putExtra(EXTRA_SENDER, sender);
        i.putExtra(EXTRA_TEXT, text);
        i.putExtra(EXTRA_IS_GROUP, isGroup);
        i.putExtra(EXTRA_IS_CALL, isCall);
        i.putExtra(EXTRA_REPEAT_SEC, Math.max(3, repeatSec));
        i.putExtra(EXTRA_SOUND_MODE, soundMode);
        i.putExtra(EXTRA_HAPTIC_PATTERN, hapticPattern);
        i.putExtra(EXTRA_HAS_VOICE, hasVoice);
        i.putExtra(EXTRA_MEDIA_B64, mediaB64 == null ? "" : mediaB64);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stopChatSession(Context context, String convKey) {
        if (convKey != null) {
            ActiveSession s = SESSIONS.remove(convKey);
            if (s != null && s.future != null) {
                s.future.cancel(false);
            }
            ProxyNotificationHelper.cancelNotificationForChat(context, convKey);
        }
        PrefsManager prefs = new PrefsManager(context);
        PersistentRepeatForegroundService inst = activeInstance;
        if (inst != null) {
            if (!prefs.isRepeatMode() && SESSIONS.isEmpty()) {
                inst.stopForeground(STOP_FOREGROUND_REMOVE);
                inst.stopSelf();
            } else {
                inst.updatePersistentNotification();
            }
        }
        broadcastAndSync(context);
    }

    public static void stopAllSessions(Context context) {
        for (ActiveSession s : SESSIONS.values()) {
            if (s.future != null) s.future.cancel(false);
        }
        SESSIONS.clear();
        ProxyNotificationHelper.cancelAllNotifications(context);
        PrefsManager prefs = new PrefsManager(context);
        PersistentRepeatForegroundService inst = activeInstance;
        if (inst != null) {
            if (!prefs.isRepeatMode()) {
                inst.stopForeground(STOP_FOREGROUND_REMOVE);
                inst.stopSelf();
            } else {
                inst.updatePersistentNotification();
            }
        }
        broadcastAndSync(context);
    }

    private static void broadcastAndSync(Context context) {
        BridgeWidgetUpdater.updateAllWidgets(context);
        WearDataLayerManager.syncStateAsync(context);
        context.sendBroadcast(new Intent(ACTION_STATE_BROADCAST).setPackage(context.getPackageName()));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        activeInstance = this;
        startForeground(FOREGROUND_ID, buildPersistentNotification());

        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        PrefsManager prefs = new PrefsManager(this);

        if (ACTION_START_REPEAT.equals(action)) {
            updatePersistentNotification();
            notifyStateChanged();
            return START_STICKY;
        } else if (ACTION_STOP_REPEAT.equals(action)) {
            clearAllSessionFutures();
            notifyStateChanged();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_STOP_ALL_ALERTS.equals(action)) {
            clearAllSessionFutures();
            notifyStateChanged();
            if (!prefs.isRepeatMode()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else {
                updatePersistentNotification();
            }
            return START_STICKY;
        } else if (ACTION_STOP_CHAT.equals(action)) {
            String key = intent.getStringExtra(EXTRA_CONV_KEY);
            if (key != null) {
                ActiveSession s = SESSIONS.remove(key);
                if (s != null && s.future != null) s.future.cancel(false);
            }
            notifyStateChanged();
            if (!prefs.isRepeatMode() && SESSIONS.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else {
                updatePersistentNotification();
            }
            return START_STICKY;
        } else if (ACTION_ENQUEUE_REPEAT.equals(action)) {
            handleEnqueueRepeat(intent);
            updatePersistentNotification();
            notifyStateChanged();
            return START_STICKY;
        }

        return START_STICKY;
    }

    private void handleEnqueueRepeat(Intent intent) {
        String key = intent.getStringExtra(EXTRA_CONV_KEY);
        if (key == null || key.isEmpty()) return;

        ActiveSession existing = SESSIONS.get(key);
        if (existing != null) {
            existing.senderName = intent.getStringExtra(EXTRA_SENDER);
            existing.latestText = intent.getStringExtra(EXTRA_TEXT);
            existing.hasVoiceNote = intent.getBooleanExtra(EXTRA_HAS_VOICE, false);
            existing.mediaBase64 = intent.getStringExtra(EXTRA_MEDIA_B64);
            return;
        }

        ActiveSession session = new ActiveSession();
        session.conversationKey = key;
        session.packageName = intent.getStringExtra(EXTRA_PKG);
        session.appLabel = intent.getStringExtra(EXTRA_APP_LABEL);
        session.conversationTitle = intent.getStringExtra(EXTRA_CONV_TITLE);
        session.senderName = intent.getStringExtra(EXTRA_SENDER);
        session.latestText = intent.getStringExtra(EXTRA_TEXT);
        session.isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false);
        session.isCall = intent.getBooleanExtra(EXTRA_IS_CALL, false);
        session.repeatIntervalSec = Math.max(3, intent.getIntExtra(EXTRA_REPEAT_SEC, 12));
        session.phoneSoundMode = intent.getIntExtra(EXTRA_SOUND_MODE, 1);
        session.hapticPattern = intent.getIntExtra(EXTRA_HAPTIC_PATTERN, 0);
        session.hasVoiceNote = intent.getBooleanExtra(EXTRA_HAS_VOICE, false);
        session.mediaBase64 = intent.getStringExtra(EXTRA_MEDIA_B64);
        session.startedAtMs = System.currentTimeMillis();
        session.attemptCount = 1;

        session.future = scheduler.scheduleWithFixedDelay(
                () -> executeRepeatTick(session),
                session.repeatIntervalSec,
                session.repeatIntervalSec,
                TimeUnit.SECONDS
        );
        SESSIONS.put(key, session);
    }

    private void executeRepeatTick(ActiveSession session) {
        PrefsManager prefs = new PrefsManager(this);

        // Stop if Repeat mode was turned off (unless repeating in all modes is enabled, or call/test)
        boolean afterHoursMutedApp = prefs.getActiveProfile() == PrefsManager.PROFILE_AFTER_HOURS
                && prefs.isPackageMutedInAfterHours(session.packageName);
        boolean repeatAllowed = prefs.isRepeatMode()
                || (prefs.isRepeatInAllModesEnabled() && !afterHoursMutedApp)
                || session.isCall
                || session.conversationKey.startsWith("test|");
        if (!repeatAllowed) {
            stopChatSession(this, session.conversationKey);
            return;
        }

        // Check max repeat cap
        int maxMin = prefs.getMaxRepeatMinutes();
        if (maxMin > 0 && (System.currentTimeMillis() - session.startedAtMs) > (maxMin * 60_000L)) {
            stopChatSession(this, session.conversationKey);
            return;
        }

        // Check if currently snoozed
        Map<String, Long> snoozed = prefs.getSnoozedChats();
        Long snoozeUntil = snoozed.get(session.conversationKey);
        if (snoozeUntil != null && System.currentTimeMillis() < snoozeUntil) {
            return;
        } else if (snoozeUntil != null) {
            prefs.clearSnooze(session.conversationKey);
        }

        session.attemptCount++;
        boolean progressive = prefs.isProgressiveEscalationEnabled();
        int escalateAt = prefs.getEscalationAttemptThreshold();
        int escalationLevel = progressive
                ? (session.attemptCount >= escalateAt ? 3 : session.attemptCount)
                : 1;
        boolean escalated = escalationLevel >= 3;

        AppDatabase db = AppDatabase.getInstance(this);
        List<ChatMessageEntity> thread = db.chatMessageDao().getActiveThreadSync(session.conversationKey);
        if (thread == null || thread.isEmpty()) {
            // Synthesize thread entry if cleared or test alert
            ChatMessageEntity fallback = new ChatMessageEntity();
            fallback.conversationKey = session.conversationKey;
            fallback.packageName = session.packageName;
            fallback.appLabel = session.appLabel;
            fallback.conversationTitle = session.conversationTitle;
            fallback.senderName = session.senderName;
            fallback.messageText = session.latestText;
            fallback.timestamp = System.currentTimeMillis();
            fallback.isGroup = session.isGroup;
            thread = java.util.Collections.singletonList(fallback);
        }

        // 1. Optional legacy Galaxy Wearable notification re-ping on phone shade
        if (prefs.isPhoneBridgeNotifEnabled()) {
            ProxyNotificationHelper.postOrRepingProxyNotification(
                    this,
                    session.conversationKey,
                    session.packageName,
                    session.appLabel,
                    session.conversationTitle,
                    session.isGroup,
                    true,
                    false,
                    escalated,
                    thread,
                    prefs
            );
        }

        // 2. Phone audio/vibration (or forced alarm on Attempt #3+)
        AlertAudioHelper.playPhoneAlert(this, session.phoneSoundMode, escalated);

        // 3. Direct Wearable Data Layer tick to native Watch App (posts/re-pings native Watch notif)
        ChatMessageEntity lastItem = thread.get(thread.size() - 1);
        WearProtocol.AlertTickPayload tick = new WearProtocol.AlertTickPayload();
        tick.conversationKey = session.conversationKey;
        tick.packageName = session.packageName;
        tick.appLabel = session.appLabel;
        tick.conversationTitle = session.conversationTitle;
        tick.senderName = session.senderName;
        tick.messageText = session.latestText;
        tick.attemptCount = session.attemptCount;
        tick.hapticPattern = escalated ? 3 : session.hapticPattern;
        tick.escalationLevel = escalationLevel;
        tick.notificationKind = lastItem.notificationKind;
        tick.canReply = lastItem.canReply;
        tick.canMarkRead = lastItem.canMarkRead;
        tick.isRepeating = true;
        tick.isGroup = session.isGroup;
        tick.isCall = session.isCall;
        tick.autoTts = escalated && prefs.isAutoTtsInRepeatEnabled();
        tick.hasVoiceNote = session.hasVoiceNote;
        tick.mediaBase64 = session.mediaBase64;
        com.nitsutech.watchbridge.engine.ActionCache.CachedActions cached =
                com.nitsutech.watchbridge.engine.ActionCache.get(session.conversationKey);
        if (cached != null) {
            tick.avatarBase64 = cached.avatarBase64;
            tick.smallIconBase64 = cached.smallIconBase64;
            tick.appColor = cached.appColor;
            tick.extraActionTitles = new java.util.ArrayList<>(cached.extraActionTitles);
        }
        if (lastItem.canReply) {
            tick.quickReplies = java.util.Arrays.asList(prefs.getQuickRepliesForPackage(session.packageName));
        }
        WearDataLayerManager.sendAlertTickAsync(this, tick);

        updatePersistentNotification();
        notifyStateChanged();
    }

    private void clearAllSessionFutures() {
        for (ActiveSession s : SESSIONS.values()) {
            if (s.future != null) s.future.cancel(false);
        }
        SESSIONS.clear();
    }

    private void updatePersistentNotification() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(FOREGROUND_ID, buildPersistentNotification());
        }
    }

    private Notification buildPersistentNotification() {
        PrefsManager prefs = new PrefsManager(this);
        String title = prefs.isRepeatMode()
                ? getString(R.string.fg_repeat_active_title)
                : getString(R.string.fg_alert_active_title);
        String body = getString(R.string.fg_status_body, SESSIONS.size());

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 10, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopAll = new Intent(this, ProxyActionReceiver.class);
        stopAll.setAction(prefs.isRepeatMode()
                ? ProxyActionReceiver.ACTION_END_REPEAT_MODE
                : ProxyActionReceiver.ACTION_WIDGET_STOP_ALERTS);
        PendingIntent stopPi = PendingIntent.getBroadcast(
                this, 11, stopAll,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String stopLabel = prefs.isRepeatMode()
                ? getString(R.string.action_end_repeat)
                : getString(R.string.action_stop_alert);

        return new NotificationCompat.Builder(this, WatchBridgeApp.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_watch_bridge)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setContentIntent(contentPi)
                .addAction(R.drawable.ic_stop, stopLabel, stopPi)
                .build();
    }

    private void notifyStateChanged() {
        BridgeWidgetUpdater.updateAllWidgets(this);
        WearDataLayerManager.syncStateAsync(this);
        sendBroadcast(new Intent(ACTION_STATE_BROADCAST).setPackage(getPackageName()));
    }

    @Override
    public void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        clearAllSessionFutures();
        scheduler.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
