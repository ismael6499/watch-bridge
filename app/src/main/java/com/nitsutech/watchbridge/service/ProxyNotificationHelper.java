package com.nitsutech.watchbridge.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.IconCompat;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.WatchBridgeApp;
import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.ChatMessageEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.engine.ActionCache;
import com.nitsutech.watchbridge.receiver.ProxyActionReceiver;
import com.nitsutech.watchbridge.ui.MainActivity;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds Galaxy-Wearable-compatible MessagingStyle notifications with rolling history,
 * images/stickers, Direct Reply RemoteInput, and Cancel-then-Post Re-Ping ID cycling.
 */
public class ProxyNotificationHelper {

    public static final String KEY_TEXT_REPLY = "key_text_reply";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(4000);
    private static final ConcurrentHashMap<String, Integer> ACTIVE_NOTIF_IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> ID_TO_CONV = new ConcurrentHashMap<>();

    public static int postOrRepingProxyNotification(
            Context context,
            String conversationKey,
            String packageName,
            String appLabel,
            String conversationTitle,
            boolean isGroup,
            boolean forceFreshIdForWatchReping,
            boolean silentUpdateOnly,
            boolean escalatedUrgent,
            List<ChatMessageEntity> threadMessages,
            PrefsManager prefs
    ) {
        return postOrRepingProxyNotification(
                context, conversationKey, packageName, appLabel, conversationTitle,
                isGroup, forceFreshIdForWatchReping, silentUpdateOnly, escalatedUrgent, false,
                threadMessages, prefs
        );
    }

    public static int postOrRepingProxyNotification(
            Context context,
            String conversationKey,
            String packageName,
            String appLabel,
            String conversationTitle,
            boolean isGroup,
            boolean forceFreshIdForWatchReping,
            boolean silentUpdateOnly,
            boolean escalatedUrgent,
            boolean sourceSilentChannel,
            List<ChatMessageEntity> threadMessages,
            PrefsManager prefs
    ) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || threadMessages == null || threadMessages.isEmpty()) {
            return -1;
        }

        Integer existingId = ACTIVE_NOTIF_IDS.get(conversationKey);
        int notifId;
        if (forceFreshIdForWatchReping) {
            if (existingId != null) {
                nm.cancel(existingId);
                ID_TO_CONV.remove(existingId);
                try {
                    Thread.sleep(320L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            notifId = NEXT_ID.incrementAndGet();
        } else {
            notifId = existingId != null ? existingId : NEXT_ID.incrementAndGet();
        }

        ACTIVE_NOTIF_IDS.put(conversationKey, notifId);
        ID_TO_CONV.put(notifId, conversationKey);

        String channelId = sourceSilentChannel
                ? WatchBridgeApp.CHANNEL_PROXY_SILENT
                : (escalatedUrgent
                ? WatchBridgeApp.CHANNEL_PROXY_URGENT
                : (forceFreshIdForWatchReping ? WatchBridgeApp.CHANNEL_PROXY_REPEAT : WatchBridgeApp.CHANNEL_PROXY_NORMAL));

        ChatMessageEntity latestMsg = threadMessages.get(threadMessages.size() - 1);
        int notifKind = latestMsg.notificationKind;
        boolean allowReply = (notifKind == com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT) && latestMsg.canReply;
        boolean allowMarkRead = latestMsg.canMarkRead;

        String cleanTitle = !TextUtils.isEmpty(conversationTitle) ? conversationTitle : latestMsg.senderName;

        ActionCache.CachedActions cached = ActionCache.get(conversationKey);
        IconCompat avatarIcon = null;
        if (cached != null && cached.avatarBitmap != null) {
            avatarIcon = IconCompat.createWithBitmap(cached.avatarBitmap);
        }

        Uri latestMediaUri = null;
        NotificationCompat.Style notifStyle;
        if (notifKind == com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT) {
            Person me = new Person.Builder()
                    .setName(context.getString(R.string.me_label))
                    .build();
            NotificationCompat.MessagingStyle msgStyle = new NotificationCompat.MessagingStyle(me);
            msgStyle.setGroupConversation(isGroup);
            if (isGroup) {
                msgStyle.setConversationTitle(cleanTitle);
            }

            for (ChatMessageEntity m : threadMessages) {
                Person senderPerson = m.isOutgoing
                        ? me
                        : new Person.Builder()
                        .setName(TextUtils.isEmpty(m.senderName) ? conversationTitle : m.senderName)
                        .setIcon(avatarIcon)
                        .build();

                String displayText = m.isDeletedBySender
                        ? "🗑️ [" + context.getString(R.string.deleted_tag) + "] " + m.messageText
                        : m.messageText;

                NotificationCompat.MessagingStyle.Message msgItem =
                        new NotificationCompat.MessagingStyle.Message(displayText, m.timestamp, senderPerson);

                if (!TextUtils.isEmpty(m.mediaPath)) {
                    File f = new File(m.mediaPath);
                    if (f.exists()) {
                        try {
                            Uri contentUri = FileProvider.getUriForFile(
                                    context,
                                    context.getPackageName() + ".fileprovider",
                                    f
                            );
                            grantWearableUriPermission(context, contentUri);
                            msgItem.setData("image/png", contentUri);
                            latestMediaUri = contentUri;
                        } catch (Exception ignored) {
                        }
                    }
                }
                msgStyle.addMessage(msgItem);
            }
            notifStyle = msgStyle;
        } else {
            StringBuilder sb = new StringBuilder();
            for (ChatMessageEntity m : threadMessages) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(m.messageText);
            }
            notifStyle = new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(cleanTitle)
                    .bigText(sb.toString());
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.putExtra(MainActivity.EXTRA_OPEN_TAB, 0);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                context, notifId, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent deleteIntent = new Intent(context, ProxyActionReceiver.class);
        deleteIntent.setAction(ProxyActionReceiver.ACTION_DISMISSED_NOTIFICATION);
        deleteIntent.putExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY, conversationKey);
        deleteIntent.putExtra(ProxyActionReceiver.EXTRA_NOTIF_ID, notifId);
        PendingIntent deletePi = PendingIntent.getBroadcast(
                context, notifId + 10000, deleteIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_watch_bridge)
                .setContentTitle(cleanTitle)
                .setSubText(appLabel)
                .setContentText(latestMsg.messageText)
                .setStyle(notifStyle)
                .setCategory(notifKind == com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT
                        ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_EVENT)
                .setPriority(sourceSilentChannel ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(sourceSilentChannel || silentUpdateOnly)
                .setOnlyAlertOnce(sourceSilentChannel || silentUpdateOnly)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setDeleteIntent(deletePi);

        if (cached != null && cached.avatarBitmap != null) {
            builder.setLargeIcon(cached.avatarBitmap);
        }

        // Direct Reply Action ONLY if the real source notification supports Reply
        if (allowReply) {
            String[] quickReplies = prefs.getQuickRepliesForPackage(packageName);
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_TEXT_REPLY)
                    .setLabel(context.getString(R.string.action_reply))
                    .setChoices(quickReplies)
                    .build();

            Intent replyIntent = new Intent(context, ProxyActionReceiver.class);
            replyIntent.setAction(ProxyActionReceiver.ACTION_REPLY);
            replyIntent.putExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY, conversationKey);
            replyIntent.putExtra(ProxyActionReceiver.EXTRA_NOTIF_ID, notifId);
            int mutabilityFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent replyPi = PendingIntent.getBroadcast(context, notifId + 20000, replyIntent, mutabilityFlag);

            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_reply,
                    context.getString(R.string.action_reply),
                    replyPi
            ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build();
            builder.addAction(replyAction);
        }

        // Stop Alert right away when repeating so Galaxy Watch shows it immediately
        if (forceFreshIdForWatchReping || PersistentRepeatForegroundService.isConversationRepeating(conversationKey)) {
            Intent stopIntent = new Intent(context, ProxyActionReceiver.class);
            stopIntent.setAction(ProxyActionReceiver.ACTION_STOP_CHAT_ALERT);
            stopIntent.putExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY, conversationKey);
            stopIntent.putExtra(ProxyActionReceiver.EXTRA_NOTIF_ID, notifId);
            PendingIntent stopPi = PendingIntent.getBroadcast(
                    context, notifId + 50000, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_stop, context.getString(R.string.action_stop_alert), stopPi);
        }

        // Snooze 3 min Action
        if (!sourceSilentChannel) {
            Intent snoozeIntent = new Intent(context, ProxyActionReceiver.class);
            snoozeIntent.setAction(ProxyActionReceiver.ACTION_SNOOZE_3M);
            snoozeIntent.putExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY, conversationKey);
            snoozeIntent.putExtra(ProxyActionReceiver.EXTRA_NOTIF_ID, notifId);
            PendingIntent snoozePi = PendingIntent.getBroadcast(
                    context, notifId + 40000, snoozeIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_snooze, context.getString(R.string.action_snooze_3m), snoozePi);
        }

        // Mark as Read Action ONLY if source notification supports it
        if (allowMarkRead) {
            Intent readIntent = new Intent(context, ProxyActionReceiver.class);
            readIntent.setAction(ProxyActionReceiver.ACTION_MARK_READ);
            readIntent.putExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY, conversationKey);
            readIntent.putExtra(ProxyActionReceiver.EXTRA_NOTIF_ID, notifId);
            PendingIntent readPi = PendingIntent.getBroadcast(
                    context, notifId + 30000, readIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_check, context.getString(R.string.action_mark_read), readPi);
        }

        // Also attach WearableExtender for optimal Galaxy Wearable bridging
        NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender();
        if (latestMediaUri != null) {
            wearableExtender.setHintContentIntentLaunchesActivity(false);
        }
        builder.extend(wearableExtender);

        nm.notify(notifId, builder.build());
        return notifId;
    }

    public static void cancelNotificationForChat(Context context, String conversationKey) {
        if (conversationKey == null) return;
        Integer id = ACTIVE_NOTIF_IDS.remove(conversationKey);
        if (id != null) {
            ID_TO_CONV.remove(id);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(id);
            }
        }
    }

    public static void cancelAllNotifications(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (Integer id : ACTIVE_NOTIF_IDS.values()) {
            if (nm != null && id != null) {
                nm.cancel(id);
            }
        }
        ACTIVE_NOTIF_IDS.clear();
        ID_TO_CONV.clear();
    }

    public static String getConversationKeyForNotifId(int notifId) {
        return ID_TO_CONV.get(notifId);
    }

    private static void grantWearableUriPermission(Context context, Uri uri) {
        String[] watchPackages = new String[]{
                "com.samsung.android.app.watchmanager",
                "com.samsung.android.wearable.app",
                "com.google.android.wearable.app",
                "com.samsung.android.gearoplugin"
        };
        for (String pkg : watchPackages) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
    }
}
