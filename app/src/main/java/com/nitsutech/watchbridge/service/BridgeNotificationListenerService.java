package com.nitsutech.watchbridge.service;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.AppRuleEntity;
import com.nitsutech.watchbridge.data.ChatMessageEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.data.SenderRuleEntity;
import com.nitsutech.watchbridge.engine.ActionCache;
import com.nitsutech.watchbridge.engine.NotificationParser;
import com.nitsutech.watchbridge.engine.ParsedMessage;
import com.nitsutech.watchbridge.engine.RuleEvaluator;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;
import com.nitsutech.watchbridge.wear.WearProtocol;
import com.nitsutech.watchbridge.widget.BridgeWidgetUpdater;

import java.util.List;

/**
 * Core Notification Proxy intercepting Teams, WhatsApp, and configured apps,
 * managing Anti-Delete Vault, Burst Cooling Time, and bridging to Galaxy Watch 8 Classic.
 */
public class BridgeNotificationListenerService extends NotificationListenerService {

    private static volatile BridgeNotificationListenerService activeInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        activeInstance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    public interface ReplayCallback {
        void onComplete(int sentCount);
    }

    public static void replayActiveNotificationsNow(android.content.Context context, ReplayCallback callback) {
        BridgeNotificationListenerService inst = activeInstance;
        if (inst == null) {
            if (callback != null) callback.onComplete(-1);
            return;
        }
        StatusBarNotification[] active;
        RankingMap ranking;
        try {
            active = inst.getActiveNotifications();
            ranking = inst.getCurrentRanking();
        } catch (Exception e) {
            if (callback != null) callback.onComplete(-1);
            return;
        }
        if (active == null || active.length == 0) {
            if (callback != null) callback.onComplete(0);
            return;
        }
        PrefsManager prefs = new PrefsManager(inst);
        AppDatabase db = AppDatabase.getInstance(inst);
        db.executor().execute(() -> {
            db.ensureDefaultsSync();
            int count = 0;
            for (StatusBarNotification sbn : active) {
                ParsedMessage parsed = NotificationParser.parse(inst, sbn, ranking, prefs);
                if (parsed == null || parsed.isDeletedNotice) continue;

                AppRuleEntity appRule = db.appRuleDao().getByPackageSync(parsed.packageName);
                if (appRule == null || !appRule.enabled) continue;

                ActionCache.put(parsed);
                ChatMessageEntity existingLatest = db.chatMessageDao().getLatestIncomingSync(parsed.conversationKey);
                boolean alreadyRecorded = existingLatest != null
                        && !existingLatest.isDismissed
                        && existingLatest.timestamp == parsed.timestamp
                        && TextUtils.equals(existingLatest.messageText, parsed.messageText);
                if (!alreadyRecorded) {
                    ChatMessageEntity entity = new ChatMessageEntity();
                    entity.conversationKey = parsed.conversationKey;
                    entity.packageName = parsed.packageName;
                    entity.appLabel = parsed.appLabel;
                    entity.conversationTitle = parsed.conversationTitle;
                    entity.senderName = parsed.senderName;
                    entity.messageText = parsed.messageText;
                    entity.timestamp = parsed.timestamp;
                    entity.isGroup = parsed.isGroup;
                    entity.isOutgoing = false;
                    entity.notificationKind = parsed.notificationKind;
                    entity.canReply = parsed.canReply;
                    entity.canMarkRead = parsed.canMarkRead;
                    entity.mediaPath = parsed.mediaPath;
                    entity.mediaBase64 = parsed.mediaBase64;
                    entity.hasVoiceNote = parsed.hasVoiceNote;
                    entity.voiceNoteUri = parsed.voiceNoteUri;
                    db.chatMessageDao().insert(entity);
                }

                List<SenderRuleEntity> senderRules = db.senderRuleDao().getAllSync();
                RuleEvaluator.Decision eval = RuleEvaluator.evaluate(inst, parsed, appRule, senderRules, prefs, parsed.timestamp);
                WearProtocol.AlertTickPayload tick = buildAlertTick(
                        parsed,
                        parsed.isGroup ? 2 : appRule.hapticPattern,
                        false,
                        eval.silentOnWatch,
                        prefs
                );
                WearDataLayerManager.syncAndSendAlertBlocking(inst, tick);
                count++;
            }
            if (callback != null) callback.onComplete(count);
        });
    }

    private static WearProtocol.AlertTickPayload buildAlertTick(
            ParsedMessage parsed,
            int hapticPattern,
            boolean shouldRepeat,
            boolean silentOnWatch,
            PrefsManager prefs
    ) {
        WearProtocol.AlertTickPayload tick = new WearProtocol.AlertTickPayload();
        tick.conversationKey = parsed.conversationKey;
        tick.packageName = parsed.packageName;
        tick.appLabel = parsed.appLabel;
        tick.conversationTitle = parsed.conversationTitle;
        tick.senderName = parsed.senderName;
        tick.messageText = parsed.messageText;
        tick.attemptCount = 1;
        tick.hapticPattern = hapticPattern;
        tick.escalationLevel = (parsed.isIncomingCall && !silentOnWatch) ? 3 : 1;
        tick.notificationKind = parsed.notificationKind;
        tick.canReply = parsed.canReply;
        tick.canMarkRead = parsed.canMarkRead;
        tick.isRepeating = shouldRepeat && !silentOnWatch;
        tick.silentOnWatch = silentOnWatch;
        tick.isGroup = parsed.isGroup;
        tick.isCall = parsed.isIncomingCall;
        tick.canAnswerCall = parsed.answerCallIntent != null;
        tick.canDeclineCall = parsed.declineCallIntent != null;
        tick.autoTts = !silentOnWatch && prefs.isRepeatMode() && prefs.isAutoTtsInRepeatEnabled();
        tick.hasVoiceNote = parsed.hasVoiceNote;
        tick.mediaBase64 = parsed.mediaBase64;
        tick.avatarBase64 = parsed.avatarBase64;
        tick.smallIconBase64 = parsed.smallIconBase64;
        tick.appColor = parsed.appColor;
        tick.extraActionTitles = new java.util.ArrayList<>(parsed.extraActionTitles);
        if (parsed.canReply) {
            tick.quickReplies = java.util.Arrays.asList(prefs.getQuickRepliesForPackage(parsed.packageName));
        }
        return tick;
    }

    public static void cancelOriginalForConversation(String conversationKey) {
        BridgeNotificationListenerService inst = activeInstance;
        List<String> keys = ActionCache.consumeSbnKeysForConversation(conversationKey);
        if (inst == null || keys.isEmpty()) return;
        for (String key : keys) {
            try {
                inst.cancelNotification(key);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        handlePosted(sbn, rankingMap);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handlePosted(sbn, getCurrentRanking());
    }

    private void handlePosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) return;
        PrefsManager prefs = new PrefsManager(this);
        ParsedMessage parsed = NotificationParser.parse(this, sbn, rankingMap, prefs);
        if (parsed == null) return;

        if (parsed.isIncomingCall) {
            Boolean prevHadAnswer = ActionCache.getActiveCallHadAnswer(parsed.notificationKey);
            if (parsed.isChronometerCall) {
                if (prevHadAnswer != null) {
                    ActionCache.clearActiveCallForSbnKey(parsed.notificationKey);
                    PersistentRepeatForegroundService.stopChatSession(this, parsed.conversationKey);
                    WearDataLayerManager.sendCallEndedAsync(this, parsed.conversationKey);
                }
                return;
            }
            if (prevHadAnswer != null) {
                if (prevHadAnswer && parsed.answerCallIntent == null) {
                    ActionCache.clearActiveCallForSbnKey(parsed.notificationKey);
                    PersistentRepeatForegroundService.stopChatSession(this, parsed.conversationKey);
                    WearDataLayerManager.sendCallEndedAsync(this, parsed.conversationKey);
                    return;
                }
                ActionCache.put(parsed);
                return;
            }
            ActionCache.markCallActive(parsed.notificationKey, parsed.answerCallIntent != null);
        }

        if (ActionCache.isDuplicateRecent(parsed.conversationKey, parsed.senderName, parsed.messageText, parsed.timestamp)) {
            ActionCache.put(parsed);
            return;
        }

        AppDatabase db = AppDatabase.getInstance(this);
        db.executor().execute(() -> processParsedMessage(db, prefs, parsed));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();

        // 1. If WatchBridge's own proxy notification was dismissed
        if (getPackageName().equals(pkg)) {
            String convKey = ProxyNotificationHelper.getConversationKeyForNotifId(sbn.getId());
            if (!TextUtils.isEmpty(convKey)) {
                PersistentRepeatForegroundService.stopChatSession(this, convKey);
                AppDatabase db = AppDatabase.getInstance(this);
                db.executor().execute(() -> {
                    db.chatMessageDao().dismissConversation(convKey);
                    WearDataLayerManager.syncStateBlocking(this);
                });
            }
            return;
        }

        // 2. If original app notification was read/cleared on phone
        boolean wasActiveCall = ActionCache.clearActiveCallForSbnKey(sbn.getKey());
        PrefsManager prefs = new PrefsManager(this);
        if (!wasActiveCall && !prefs.isSyncDismissOnPhoneClearEnabled()) {
            return;
        }
        String origConvKey = ActionCache.getConversationKeyForSbnKey(sbn.getKey());
        if (!TextUtils.isEmpty(origConvKey)) {
            PersistentRepeatForegroundService.stopChatSession(this, origConvKey);
            ProxyNotificationHelper.cancelNotificationForChat(this, origConvKey);
            if (wasActiveCall) {
                AlertAudioHelper.clearCallAudioRouting(this);
                WearDataLayerManager.sendCallEndedAsync(this, origConvKey);
            }
            AppDatabase db = AppDatabase.getInstance(this);
            db.executor().execute(() -> {
                db.chatMessageDao().markConversationRead(origConvKey);
                WearDataLayerManager.syncStateBlocking(this);
            });
        }
    }

    private void processParsedMessage(AppDatabase db, PrefsManager prefs, ParsedMessage parsed) {
        db.ensureDefaultsSync();
        ActionCache.put(parsed);

        // Idea #4: Anti-Delete Shield
        if (parsed.isDeletedNotice) {
            ChatMessageEntity lastIncoming = db.chatMessageDao().getLatestIncomingSync(parsed.conversationKey);
            if (lastIncoming != null) {
                lastIncoming.isDeletedBySender = true;
                db.chatMessageDao().update(lastIncoming);
                WearDataLayerManager.syncStateBlocking(this);
                sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST).setPackage(getPackageName()));
            }
            return;
        }

        AppRuleEntity appRule = db.appRuleDao().getByPackageSync(parsed.packageName);
        if (appRule == null) {
            // Unknown app not configured yet -> ignore unless user added it
            return;
        }
        List<SenderRuleEntity> senderRules = db.senderRuleDao().getAllSync();
        long now = System.currentTimeMillis();
        RuleEvaluator.Decision decision = RuleEvaluator.evaluate(this, parsed, appRule, senderRules, prefs, now);

        boolean saveSilently = decision.suppressedAfterHours
                || (decision.suppressedPhoneInUse && prefs.isSavePhoneInUseToVaultEnabled());
        if (!decision.shouldBridge && !decision.coalescedByCooldown && !saveSilently) {
            return;
        }

        if ((AppDatabase.PKG_WHATSAPP.equals(parsed.packageName)
                || AppDatabase.PKG_WHATSAPP_BUSINESS.equals(parsed.packageName))
                && TextUtils.isEmpty(parsed.mediaBase64)
                && NotificationParser.parseExpectedPhotoCount(parsed.messageText) > 0) {
            try {
                Thread.sleep(550L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            NotificationParser.tryAttachRecentWhatsAppMedia(this, parsed);
        }

        ChatMessageEntity entity = new ChatMessageEntity();
        entity.conversationKey = parsed.conversationKey;
        entity.packageName = parsed.packageName;
        entity.appLabel = parsed.appLabel;
        entity.conversationTitle = parsed.conversationTitle;
        entity.senderName = parsed.senderName;
        entity.messageText = parsed.messageText;
        entity.timestamp = parsed.timestamp;
        entity.isGroup = parsed.isGroup;
        entity.isOutgoing = false;
        entity.notificationKind = parsed.notificationKind;
        entity.canReply = parsed.canReply;
        entity.canMarkRead = parsed.canMarkRead;
        entity.isDeletedBySender = false;
        entity.isRead = false;
        entity.isDismissed = saveSilently;
        entity.wasCoalesced = decision.coalescedByCooldown;
        entity.isAfterHoursMuted = decision.suppressedAfterHours;
        entity.mediaPath = parsed.mediaPath;
        entity.mediaBase64 = parsed.mediaBase64;
        entity.hasVoiceNote = parsed.hasVoiceNote;
        entity.voiceNoteUri = parsed.voiceNoteUri;
        db.chatMessageDao().insert(entity);

        if (saveSilently) {
            sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST).setPackage(getPackageName()));
            return;
        }

        if (prefs.isRepeatMode()) {
            if (decision.coalescedByCooldown) {
                prefs.incrementRepeatBurstsAbsorbed();
            } else {
                prefs.incrementRepeatNotifs();
            }
        }

        List<ChatMessageEntity> thread = db.chatMessageDao().getActiveThreadSync(parsed.conversationKey);

        if (decision.coalescedByCooldown) {
            // Burst Cooling Time: silently deliver message to watch & thread without vibration/sound
            if (prefs.isPhoneBridgeNotifEnabled()) {
                ProxyNotificationHelper.postOrRepingProxyNotification(
                        this,
                        parsed.conversationKey,
                        parsed.packageName,
                        parsed.appLabel,
                        parsed.conversationTitle,
                        parsed.isGroup,
                        false,
                        true,
                        false,
                        true,
                        thread,
                        prefs
                );
            }
            WearProtocol.AlertTickPayload tick = buildAlertTick(
                    parsed, decision.hapticPattern, false, true, prefs);
            tick.isRepeating = PersistentRepeatForegroundService.isConversationRepeating(parsed.conversationKey);
            WearDataLayerManager.syncAndSendAlertBlocking(this, tick);
            sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST).setPackage(getPackageName()));
            return;
        }

        if (decision.sourceSilent) {
            // Source chat has no sound & no vibration: forward quietly, no repeat, no watch vibration
            if (prefs.isPhoneBridgeNotifEnabled()) {
                ProxyNotificationHelper.postOrRepingProxyNotification(
                        this,
                        parsed.conversationKey,
                        parsed.packageName,
                        parsed.appLabel,
                        parsed.conversationTitle,
                        parsed.isGroup,
                        false,
                        true,
                        false,
                        true,
                        thread,
                        prefs
                );
            }
            WearDataLayerManager.syncStateBlocking(this);
            sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST).setPackage(getPackageName()));
            return;
        }

        // Only post bridged notification in Phone shade when legacy Galaxy Wearable mode is explicitly enabled
        if (prefs.isPhoneBridgeNotifEnabled()) {
            ProxyNotificationHelper.postOrRepingProxyNotification(
                    this,
                    parsed.conversationKey,
                    parsed.packageName,
                    parsed.appLabel,
                    parsed.conversationTitle,
                    parsed.isGroup,
                    false,
                    decision.silentOnWatch,
                    !decision.silentOnWatch && (decision.isUrgentKeyword || parsed.isIncomingCall),
                    decision.silentOnWatch,
                    thread,
                    prefs
            );
        }

        // Phone sound/vibrate if configured (skipped when silentOnWatch)
        if (!decision.silentOnWatch) {
            AlertAudioHelper.playPhoneAlert(this, decision.phoneSoundMode, parsed.isIncomingCall);
        }

        // Direct instant Wearable Data Layer alert + full sync
        WearProtocol.AlertTickPayload tick = buildAlertTick(
                parsed, decision.hapticPattern, decision.shouldRepeat, decision.silentOnWatch, prefs);

        WearDataLayerManager.syncAndSendAlertBlocking(this, tick);

        if (decision.shouldRepeat && !decision.silentOnWatch) {
            PersistentRepeatForegroundService.enqueueRepeat(
                    this,
                    parsed.conversationKey,
                    parsed.packageName,
                    parsed.appLabel,
                    parsed.conversationTitle,
                    parsed.senderName,
                    parsed.messageText,
                    parsed.isGroup,
                    parsed.isIncomingCall,
                    decision.repeatIntervalSec,
                    decision.phoneSoundMode,
                    decision.hapticPattern,
                    parsed.hasVoiceNote,
                    parsed.mediaBase64
            );
        }

        BridgeWidgetUpdater.updateAllWidgets(this);
        sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST).setPackage(getPackageName()));
    }
}
