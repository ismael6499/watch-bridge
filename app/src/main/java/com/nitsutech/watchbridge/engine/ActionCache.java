package com.nitsutech.watchbridge.engine;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of original app notification actions (Direct Reply RemoteInput & Mark-as-Read),
 * avatars, and recent notification sources for the rule picker.
 */
public class ActionCache {

    public static class CachedActions {
        public Notification.Action replyAction;
        public Notification.Action markReadAction;
        public PendingIntent answerCallIntent;
        public PendingIntent declineCallIntent;
        public Bitmap avatarBitmap;
        public String originalNotificationKey;
        public String packageName;
        public String appLabel;
        public String conversationTitle;
        public String senderName;
        public boolean isGroup;
        public int notificationKind;
        public boolean canReply;
        public boolean canMarkRead;
        public long updatedAt;
    }

    private static final ConcurrentHashMap<String, CachedActions> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SBN_TO_CONV = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ACTIVE_CALL_SBN_HAD_ANSWER = new ConcurrentHashMap<>();
    private static final Map<String, String> DEDUP_HASH = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 64;
                }
            }
    );

    public static void put(ParsedMessage msg) {
        if (msg == null || msg.conversationKey.isEmpty()) return;
        CachedActions entry = CACHE.get(msg.conversationKey);
        if (entry == null) {
            entry = new CachedActions();
        }
        if (msg.replyAction != null) {
            entry.replyAction = msg.replyAction;
        }
        if (msg.markReadAction != null) {
            entry.markReadAction = msg.markReadAction;
        }
        if (msg.answerCallIntent != null) {
            entry.answerCallIntent = msg.answerCallIntent;
        }
        if (msg.declineCallIntent != null) {
            entry.declineCallIntent = msg.declineCallIntent;
        }
        if (msg.senderAvatar != null) {
            entry.avatarBitmap = msg.senderAvatar;
        }
        if (!msg.notificationKey.isEmpty()) {
            entry.originalNotificationKey = msg.notificationKey;
            SBN_TO_CONV.put(msg.notificationKey, msg.conversationKey);
        }
        entry.packageName = msg.packageName;
        entry.appLabel = msg.appLabel;
        entry.conversationTitle = msg.conversationTitle;
        entry.senderName = msg.senderName;
        entry.isGroup = msg.isGroup;
        entry.notificationKind = msg.notificationKind;
        entry.canReply = msg.canReply;
        entry.canMarkRead = msg.canMarkRead;
        entry.updatedAt = System.currentTimeMillis();
        CACHE.put(msg.conversationKey, entry);
    }

    public static CachedActions get(String conversationKey) {
        return conversationKey == null ? null : CACHE.get(conversationKey);
    }

    public static String getConversationKeyForSbnKey(String sbnKey) {
        return sbnKey == null ? null : SBN_TO_CONV.remove(sbnKey);
    }

    public static void markCallActive(String sbnKey, boolean hadAnswerIntent) {
        if (sbnKey != null) {
            ACTIVE_CALL_SBN_HAD_ANSWER.put(sbnKey, hadAnswerIntent);
        }
    }

    public static Boolean getActiveCallHadAnswer(String sbnKey) {
        return sbnKey == null ? null : ACTIVE_CALL_SBN_HAD_ANSWER.get(sbnKey);
    }

    public static boolean clearActiveCallForSbnKey(String sbnKey) {
        return sbnKey != null && ACTIVE_CALL_SBN_HAD_ANSWER.remove(sbnKey) != null;
    }

    public static boolean isDuplicateRecent(String conversationKey, String sender, String text, long timestamp) {
        String fingerprint = conversationKey + "|" + sender + "|" + text;
        String prev = DEDUP_HASH.get(fingerprint);
        long now = System.currentTimeMillis();
        if (prev != null) {
            try {
                long prevTime = Long.parseLong(prev);
                if (Math.abs(now - prevTime) < 3500L) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        DEDUP_HASH.put(fingerprint, String.valueOf(now));
        return false;
    }

    public static List<CachedActions> getRecentContacts() {
        List<CachedActions> list = new ArrayList<>(CACHE.values());
        list.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return list;
    }
}
