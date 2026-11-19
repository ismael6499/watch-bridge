package com.nitsutech.watchbridge.service;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.AppRuleEntity;
import com.nitsutech.watchbridge.data.ChatMessageEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.data.SenderRuleEntity;
import com.nitsutech.watchbridge.engine.ActionCache;
import com.nitsutech.watchbridge.engine.CooldownTracker;
import com.nitsutech.watchbridge.engine.ParsedMessage;
import com.nitsutech.watchbridge.engine.RuleEvaluator;
import com.nitsutech.watchbridge.receiver.ScheduleAndTimerReceiver;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;
import com.nitsutech.watchbridge.widget.BridgeWidgetUpdater;

import java.util.List;

/**
 * Single unified controller for Profile switching, Reply execution, Mark Read, Snooze, Mute, VIP,
 * and Watch <-> Phone command execution.
 */
public class ProfileController {

    public static void setProfile(Context context, int newProfile, int customRepeatMinutes) {
        PrefsManager prefs = new PrefsManager(context);
        int previousProfile = prefs.getActiveProfile();
        prefs.setActiveProfile(newProfile);

        if (newProfile == PrefsManager.PROFILE_REPEAT) {
            int durationMin = customRepeatMinutes >= 0 ? customRepeatMinutes : prefs.getDefaultRepeatDurationMin();
            if (durationMin > 0) {
                long endMs = System.currentTimeMillis() + (durationMin * 60_000L);
                prefs.setRepeatEndTimeMs(endMs);
                ScheduleAndTimerReceiver.scheduleRepeatAutoOff(context, endMs);
            } else {
                prefs.setRepeatEndTimeMs(0L);
                ScheduleAndTimerReceiver.cancelRepeatAutoOff(context);
            }
            prefs.resetRepeatCounters();
        } else {
            prefs.setRepeatEndTimeMs(0L);
            ScheduleAndTimerReceiver.cancelRepeatAutoOff(context);
            if (previousProfile == PrefsManager.PROFILE_REPEAT) {
                PersistentRepeatForegroundService.stopRepeatForeground(context);
            }
        }

        BridgeWidgetUpdater.updateAllWidgets(context);
        WearDataLayerManager.syncStateAsync(context);
        context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                .setPackage(context.getPackageName()));
    }

    public static void toggleRepeatMode(Context context) {
        PrefsManager prefs = new PrefsManager(context);
        if (prefs.isRepeatMode()) {
            setProfile(context, PrefsManager.PROFILE_NORMAL, 0);
        } else {
            setProfile(context, PrefsManager.PROFILE_REPEAT, prefs.getDefaultRepeatDurationMin());
        }
    }

    public static boolean executeReply(Context context, String conversationKey, String replyText) {
        if (TextUtils.isEmpty(conversationKey) || TextUtils.isEmpty(replyText)) {
            return false;
        }
        boolean dispatchedToOriginalApp = false;
        ActionCache.CachedActions cached = ActionCache.get(conversationKey);
        if (cached != null && cached.replyAction != null && cached.replyAction.actionIntent != null) {
            RemoteInput[] remoteInputs = cached.replyAction.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0) {
                try {
                    Intent replyIntent = new Intent();
                    Bundle bundle = new Bundle();
                    for (RemoteInput ri : remoteInputs) {
                        bundle.putCharSequence(ri.getResultKey(), replyText);
                    }
                    RemoteInput.addResultsToIntent(remoteInputs, replyIntent, bundle);
                    cached.replyAction.actionIntent.send(context, 0, replyIntent);
                    dispatchedToOriginalApp = true;
                } catch (PendingIntent.CanceledException ignored) {
                }
            }
        }

        PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);

        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().markConversationRead(conversationKey);
            ChatMessageEntity outgoing = new ChatMessageEntity();
            outgoing.conversationKey = conversationKey;
            outgoing.packageName = cached != null ? cached.packageName : "";
            outgoing.appLabel = cached != null ? cached.appLabel : "";
            outgoing.conversationTitle = cached != null ? cached.conversationTitle : "";
            outgoing.senderName = context.getString(R.string.me_label);
            outgoing.messageText = replyText;
            outgoing.timestamp = System.currentTimeMillis();
            outgoing.isOutgoing = true;
            outgoing.isRead = true;
            outgoing.isDismissed = true;
            db.chatMessageDao().insert(outgoing);
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
        return dispatchedToOriginalApp;
    }

    public static void executeAnswerCall(Context context, String conversationKey) {
        ActionCache.CachedActions cached = ActionCache.get(conversationKey);
        if (cached != null) {
            if (!TextUtils.isEmpty(cached.originalNotificationKey)) {
                ActionCache.clearActiveCallForSbnKey(cached.originalNotificationKey);
            }
            if (cached.answerCallIntent != null) {
                sendCallPendingIntent(cached.answerCallIntent);
            }
        }
        AlertAudioHelper.routeCallAudioToWatchBluetooth(context);
        PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        WearDataLayerManager.sendCallEndedAsync(context, conversationKey);
    }

    public static void executeDeclineCall(Context context, String conversationKey) {
        ActionCache.CachedActions cached = ActionCache.get(conversationKey);
        if (cached != null) {
            if (!TextUtils.isEmpty(cached.originalNotificationKey)) {
                ActionCache.clearActiveCallForSbnKey(cached.originalNotificationKey);
            }
            if (cached.declineCallIntent != null) {
                sendCallPendingIntent(cached.declineCallIntent);
            }
        }
        AlertAudioHelper.clearCallAudioRouting(context);
        PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        WearDataLayerManager.sendCallEndedAsync(context, conversationKey);
    }

    public static void executeCustomAction(Context context, String conversationKey, int actionIndex) {
        if (TextUtils.isEmpty(conversationKey) || actionIndex < 0) return;
        ActionCache.CachedActions cached = ActionCache.get(conversationKey);
        if (cached != null && cached.extraActions != null && actionIndex < cached.extraActions.size()) {
            Notification.Action act = cached.extraActions.get(actionIndex);
            if (act != null && act.actionIntent != null) {
                sendCallPendingIntent(act.actionIntent);
            }
        }
        PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().markConversationRead(conversationKey);
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    private static void sendCallPendingIntent(PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(null, 0, null, null, null, null, opts.toBundle());
            } else {
                pi.send();
            }
        } catch (Exception e) {
            try {
                pi.send();
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
    }

    public static void executeMarkAsRead(Context context, String conversationKey) {
        if (TextUtils.isEmpty(conversationKey)) return;
        ActionCache.CachedActions cached = ActionCache.get(conversationKey);
        if (cached != null && cached.markReadAction != null && cached.markReadAction.actionIntent != null) {
            try {
                cached.markReadAction.actionIntent.send();
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        BridgeNotificationListenerService.cancelOriginalForConversation(conversationKey);
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().markConversationRead(conversationKey);
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    public static void dismissConversation(Context context, String conversationKey) {
        if (TextUtils.isEmpty(conversationKey)) return;
        PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        BridgeNotificationListenerService.cancelOriginalForConversation(conversationKey);
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().dismissConversation(conversationKey);
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    public static void dismissSingleMessage(Context context, long messageId) {
        if (messageId <= 0) return;
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().dismissMessageById(messageId);
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    public static void deleteVaultOrHistoryItem(Context context, long messageId) {
        if (messageId <= 0) return;
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().deleteById(messageId);
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    public static void clearDeletedVault(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            db.chatMessageDao().deleteAllDeletedVault();
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    public static void snoozeChat(Context context, String conversationKey, int minutes) {
        if (TextUtils.isEmpty(conversationKey)) return;
        PrefsManager prefs = new PrefsManager(context);
        long until = System.currentTimeMillis() + (Math.max(1, minutes) * 60_000L);
        prefs.snoozeChatUntil(conversationKey, until);
        ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        ScheduleAndTimerReceiver.scheduleSnoozeWakeup(context, conversationKey, until);
        WearDataLayerManager.syncStateAsync(context);
        context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                .setPackage(context.getPackageName()));
    }

    public static void muteChat(Context context, String conversationKey, int minutes) {
        if (TextUtils.isEmpty(conversationKey)) return;
        PrefsManager prefs = new PrefsManager(context);
        if (minutes <= 0) {
            prefs.unmuteChat(conversationKey);
        } else {
            prefs.muteChatUntil(conversationKey, System.currentTimeMillis() + (minutes * 60_000L));
            PersistentRepeatForegroundService.stopChatSession(context, conversationKey);
            ProxyNotificationHelper.cancelNotificationForChat(context, conversationKey);
        }
        WearDataLayerManager.syncStateAsync(context);
        context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                .setPackage(context.getPackageName()));
    }

    public static void toggleVipForSender(Context context, String senderName, String packageName) {
        if (TextUtils.isEmpty(senderName)) return;
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            SenderRuleEntity existing = db.senderRuleDao().getExactByNameSync(senderName.trim());
            if (existing != null) {
                existing.isVip = !existing.isVip;
                if (existing.isVip) {
                    existing.actionMode = 0;
                    existing.cooldownSec = 0;
                }
                db.senderRuleDao().upsert(existing);
            } else {
                SenderRuleEntity vipRule = new SenderRuleEntity();
                vipRule.senderOrChatName = senderName.trim();
                vipRule.packageFilter = TextUtils.isEmpty(packageName) ? "*" : packageName;
                vipRule.chatTypeFilter = 0;
                vipRule.actionMode = 0;
                vipRule.repeatIntervalSec = 10;
                vipRule.cooldownSec = 0;
                vipRule.isVip = true;
                vipRule.phoneAlarmOverride = true;
                db.senderRuleDao().upsert(vipRule);
            }
            WearDataLayerManager.syncStateBlocking(context);
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
        });
    }

    public static void setPhoneRingerMode(Context context, int ringerMode) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
                    Intent i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                    return;
                }
            } else if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                if (nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                }
            }
            if (am != null) {
                am.setRingerMode(ringerMode);
            }
        } catch (Exception ignored) {
        }
        WearDataLayerManager.syncStateAsync(context);
    }

    public static void toggleAppEnabled(Context context, String packageName, boolean enabled) {
        if (TextUtils.isEmpty(packageName)) return;
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            AppRuleEntity r = db.appRuleDao().getByPackageSync(packageName);
            if (r != null) {
                r.enabled = enabled;
                db.appRuleDao().upsert(r);
                WearDataLayerManager.syncStateBlocking(context);
                context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                        .setPackage(context.getPackageName()));
            }
        });
    }

    public static void toggleAppGroupEnabled(Context context, String packageName, boolean groupEnabled) {
        if (TextUtils.isEmpty(packageName)) return;
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            AppRuleEntity r = db.appRuleDao().getByPackageSync(packageName);
            if (r != null) {
                r.groupEnabled = groupEnabled;
                db.appRuleDao().upsert(r);
                WearDataLayerManager.syncStateBlocking(context);
                context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                        .setPackage(context.getPackageName()));
            }
        });
    }

    public static void updateAppTimers(Context context, String packageName, int repeatSec, int cooldownSec) {
        if (TextUtils.isEmpty(packageName)) return;
        AppDatabase db = AppDatabase.getInstance(context);
        db.executor().execute(() -> {
            AppRuleEntity r = db.appRuleDao().getByPackageSync(packageName);
            if (r != null) {
                if (repeatSec > 0) r.directRepeatIntervalSec = repeatSec;
                if (cooldownSec >= 0) r.directCooldownSec = cooldownSec;
                db.appRuleDao().upsert(r);
                WearDataLayerManager.syncStateBlocking(context);
                context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                        .setPackage(context.getPackageName()));
            }
        });
    }

    public static void triggerTestAlert(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        PrefsManager prefs = new PrefsManager(context);
        db.executor().execute(() -> {
            String testKey = "test|" + AppDatabase.PKG_TEAMS + "|watchbridge-test";
            ChatMessageEntity msg = new ChatMessageEntity();
            msg.conversationKey = testKey;
            msg.packageName = AppDatabase.PKG_TEAMS;
            msg.appLabel = "Microsoft Teams";
            msg.conversationTitle = "WatchBridge Test Ping";
            msg.senderName = "Test Alert (Teams)";
            msg.messageText = context.getString(R.string.test_alert_message_body);
            msg.timestamp = System.currentTimeMillis();
            msg.isGroup = false;
            msg.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT;
            msg.canReply = true;
            msg.canMarkRead = true;
            msg.hasVoiceNote = true;
            db.chatMessageDao().insert(msg);

            List<ChatMessageEntity> thread = db.chatMessageDao().getActiveThreadSync(testKey);
            if (prefs.isPhoneBridgeNotifEnabled()) {
                ProxyNotificationHelper.postOrRepingProxyNotification(
                        context, testKey, AppDatabase.PKG_TEAMS, "Microsoft Teams",
                        "WatchBridge Test Ping", false, true, false, false, thread, prefs
                );
            }

            com.nitsutech.watchbridge.wear.WearProtocol.AlertTickPayload tick =
                    new com.nitsutech.watchbridge.wear.WearProtocol.AlertTickPayload();
            tick.conversationKey = testKey;
            tick.packageName = AppDatabase.PKG_TEAMS;
            tick.appLabel = "Microsoft Teams";
            tick.conversationTitle = "WatchBridge Test Ping";
            tick.senderName = "Test Alert (Teams)";
            tick.messageText = msg.messageText;
            tick.attemptCount = 1;
            tick.hapticPattern = 0;
            tick.escalationLevel = 1;
            tick.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT;
            tick.canReply = true;
            tick.canMarkRead = true;
            tick.isRepeating = true;
            tick.hasVoiceNote = true;
            tick.quickReplies = java.util.Arrays.asList(prefs.getQuickRepliesForPackage(AppDatabase.PKG_TEAMS));
            WearDataLayerManager.syncStateBlocking(context);
            WearDataLayerManager.sendAlertTickAsync(context, tick);

            PersistentRepeatForegroundService.enqueueRepeat(
                    context, testKey, AppDatabase.PKG_TEAMS, "Microsoft Teams",
                    "WatchBridge Test Ping", "Test Alert (Teams)",
                    msg.messageText, false, false, 10, 0, 0, true, ""
            );
        });
    }

    public static void triggerTestNormalTeamsMessage(Context context, java.util.function.Consumer<String> onResult) {
        AppDatabase db = AppDatabase.getInstance(context);
        PrefsManager prefs = new PrefsManager(context);
        db.executor().execute(() -> {
            db.ensureDefaultsSync();
            long now = System.currentTimeMillis();
            String convKey = "test-normal|" + AppDatabase.PKG_TEAMS + "|sample-chat";
            CooldownTracker.clearCooldown(convKey);

            ParsedMessage parsed = new ParsedMessage();
            parsed.conversationKey = convKey;
            parsed.packageName = AppDatabase.PKG_TEAMS;
            parsed.appLabel = "Microsoft Teams";
            parsed.conversationTitle = "Teams Normal Test";
            parsed.senderName = context.getString(R.string.test_normal_sender_name);
            parsed.messageText = context.getString(R.string.test_normal_message_body);
            parsed.timestamp = now;
            parsed.isGroup = false;
            parsed.isIncomingCall = false;
            parsed.isSourceSilent = false;
            parsed.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT;
            parsed.canReply = true;
            parsed.canMarkRead = true;

            AppRuleEntity appRule = db.appRuleDao().getByPackageSync(AppDatabase.PKG_TEAMS);
            List<SenderRuleEntity> senderRules = db.senderRuleDao().getAllSync();
            RuleEvaluator.Decision decision = RuleEvaluator.evaluate(
                    context, parsed, appRule, senderRules, prefs, now, true);

            if (!decision.shouldBridge) {
                if (decision.suppressedAfterHours) {
                    ChatMessageEntity entity = new ChatMessageEntity();
                    entity.conversationKey = convKey;
                    entity.packageName = parsed.packageName;
                    entity.appLabel = parsed.appLabel;
                    entity.conversationTitle = parsed.conversationTitle;
                    entity.senderName = parsed.senderName;
                    entity.messageText = parsed.messageText;
                    entity.timestamp = now;
                    entity.isDismissed = true;
                    entity.isAfterHoursMuted = true;
                    db.chatMessageDao().insert(entity);
                }
                String msg = context.getString(R.string.toast_test_normal_blocked);
                if (onResult != null) onResult.accept(msg);
                return;
            }

            ChatMessageEntity entity = new ChatMessageEntity();
            entity.conversationKey = convKey;
            entity.packageName = parsed.packageName;
            entity.appLabel = parsed.appLabel;
            entity.conversationTitle = parsed.conversationTitle;
            entity.senderName = parsed.senderName;
            entity.messageText = parsed.messageText;
            entity.timestamp = now;
            entity.isGroup = false;
            entity.notificationKind = parsed.notificationKind;
            entity.canReply = true;
            entity.canMarkRead = true;
            db.chatMessageDao().insert(entity);

            List<ChatMessageEntity> thread = db.chatMessageDao().getActiveThreadSync(convKey);
            if (prefs.isPhoneBridgeNotifEnabled()) {
                ProxyNotificationHelper.postOrRepingProxyNotification(
                        context, convKey, parsed.packageName, parsed.appLabel,
                        parsed.conversationTitle, false, false, decision.silentOnWatch,
                        false, decision.silentOnWatch, thread, prefs
                );
            }
            if (!decision.silentOnWatch) {
                AlertAudioHelper.playPhoneAlert(context, decision.phoneSoundMode, false);
            }

            com.nitsutech.watchbridge.wear.WearProtocol.AlertTickPayload tick =
                    new com.nitsutech.watchbridge.wear.WearProtocol.AlertTickPayload();
            tick.conversationKey = convKey;
            tick.packageName = parsed.packageName;
            tick.appLabel = parsed.appLabel;
            tick.conversationTitle = parsed.conversationTitle;
            tick.senderName = parsed.senderName;
            tick.messageText = parsed.messageText;
            tick.attemptCount = 1;
            tick.hapticPattern = decision.hapticPattern;
            tick.escalationLevel = 1;
            tick.notificationKind = parsed.notificationKind;
            tick.canReply = true;
            tick.canMarkRead = true;
            tick.isRepeating = decision.shouldRepeat;
            tick.silentOnWatch = decision.silentOnWatch;
            tick.quickReplies = java.util.Arrays.asList(prefs.getQuickRepliesForPackage(parsed.packageName));
            WearDataLayerManager.syncAndSendAlertBlocking(context, tick);

            String resultMsg;
            if (decision.silentOnWatch) {
                resultMsg = context.getString(R.string.toast_test_normal_silent);
            } else if (decision.shouldRepeat) {
                PersistentRepeatForegroundService.enqueueRepeat(
                        context, convKey, parsed.packageName, parsed.appLabel,
                        parsed.conversationTitle, parsed.senderName, parsed.messageText,
                        false, false, decision.repeatIntervalSec,
                        decision.phoneSoundMode, decision.hapticPattern, false, ""
                );
                resultMsg = context.getString(R.string.toast_test_normal_repeat_fmt, decision.repeatIntervalSec);
            } else {
                resultMsg = context.getString(R.string.toast_test_normal_once);
            }
            context.sendBroadcast(new Intent(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST)
                    .setPackage(context.getPackageName()));
            if (onResult != null) onResult.accept(resultMsg);
        });
    }

    public static void triggerTestCall(Context context) {
        com.nitsutech.watchbridge.wear.WearProtocol.AlertTickPayload tick =
                new com.nitsutech.watchbridge.wear.WearProtocol.AlertTickPayload();
        tick.conversationKey = "test|" + AppDatabase.PKG_WHATSAPP + "|test-call";
        tick.packageName = AppDatabase.PKG_WHATSAPP;
        tick.appLabel = "WhatsApp";
        tick.conversationTitle = "Test Call (WhatsApp)";
        tick.senderName = "Test Call (WhatsApp)";
        tick.messageText = "Incoming call";
        tick.attemptCount = 1;
        tick.hapticPattern = 3;
        tick.escalationLevel = 3;
        tick.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_CALL;
        tick.isCall = true;
        tick.canAnswerCall = true;
        tick.canDeclineCall = true;
        WearDataLayerManager.sendAlertTickAsync(context, tick);
    }
}
