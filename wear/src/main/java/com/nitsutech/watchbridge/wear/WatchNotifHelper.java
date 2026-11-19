package com.nitsutech.watchbridge.wear;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.receiver.WatchNotifActionReceiver;
import com.nitsutech.watchbridge.ui.WearMainActivity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Posts native Wear OS notifications directly on the Galaxy Watch 8 Classic
 * so WatchBridge does NOT need to create duplicate notifications in the Phone's shade.
 */
public class WatchNotifHelper {

    public static final String KEY_WATCH_TEXT_REPLY = "key_watch_text_reply";
    private static final String CHANNEL_WATCH_NORMAL = "watchbridge_watch_normal";
    private static final String CHANNEL_WATCH_REPEAT = "watchbridge_watch_repeat";
    private static final String CHANNEL_WATCH_CALLS = "watchbridge_watch_calls";
    private static final int CALL_NOTIF_ID = 7701;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(6000);
    private static final ConcurrentHashMap<String, Integer> ACTIVE_IDS = new ConcurrentHashMap<>();

    public static void postWatchNotification(Context context, WearProtocol.AlertTickPayload tick,
                                             WearProtocol.WatchStatePayload state) {
        if (tick == null || TextUtils.isEmpty(tick.conversationKey)) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannels(context, nm);

        Integer prevId = ACTIVE_IDS.get(tick.conversationKey);
        if (prevId != null) {
            nm.cancel(prevId);
            try {
                Thread.sleep(180L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        int notifId = NEXT_ID.incrementAndGet();
        ACTIVE_IDS.put(tick.conversationKey, notifId);

        String channelId = tick.isRepeating ? CHANNEL_WATCH_REPEAT : CHANNEL_WATCH_NORMAL;
        String cleanTitle = !TextUtils.isEmpty(tick.conversationTitle) ? tick.conversationTitle : tick.senderName;
        boolean isChat = tick.notificationKind == WearProtocol.KIND_CHAT;

        Bitmap avatarBmp = decodeBase64Bitmap(tick.avatarBase64);
        IconCompat avatarIcon = avatarBmp != null ? IconCompat.createWithBitmap(avatarBmp) : null;

        NotificationCompat.Style style;
        if (isChat) {
            Person me = new Person.Builder().setName(context.getString(R.string.wear_me_label)).build();
            NotificationCompat.MessagingStyle msgStyle = new NotificationCompat.MessagingStyle(me);
            msgStyle.setGroupConversation(tick.isGroup);
            if (tick.isGroup) {
                msgStyle.setConversationTitle(cleanTitle);
            }

            boolean addedHistory = false;
            String lastAddedText = "";
            if (state != null && state.conversations != null) {
                for (WearProtocol.ConversationDto c : state.conversations) {
                    if (tick.conversationKey.equals(c.conversationKey) && c.messages != null && !c.messages.isEmpty()) {
                        for (WearProtocol.MessageDto m : c.messages) {
                            Person p = m.isOutgoing ? me : new Person.Builder()
                                    .setName(m.senderName)
                                    .setIcon(avatarIcon)
                                    .build();
                            msgStyle.addMessage(m.messageText, m.timestamp, p);
                            lastAddedText = m.messageText != null ? m.messageText : "";
                        }
                        addedHistory = true;
                        break;
                    }
                }
            }
            if (!addedHistory || (!TextUtils.isEmpty(tick.messageText) && !tick.messageText.equals(lastAddedText))) {
                Person p = new Person.Builder()
                        .setName(tick.senderName)
                        .setIcon(avatarIcon)
                        .build();
                msgStyle.addMessage(tick.messageText, System.currentTimeMillis(), p);
            }
            style = msgStyle;
        } else {
            style = new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(cleanTitle)
                    .bigText(tick.messageText);
        }

        // If media sticker/image exists and it's a chat/media, decode Bitmap
        Bitmap mediaBmp = null;
        if (!TextUtils.isEmpty(tick.mediaBase64)) {
            try {
                byte[] b = Base64.decode(tick.mediaBase64, Base64.DEFAULT);
                mediaBmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            } catch (Exception ignored) {
            }
        }
        if (mediaBmp != null) {
            style = new NotificationCompat.BigPictureStyle()
                    .bigPicture(mediaBmp)
                    .setBigContentTitle(cleanTitle)
                    .setSummaryText(isChat ? (tick.senderName + ": " + tick.messageText) : tick.messageText);
        }

        Intent openIntent = new Intent(context, WearMainActivity.class);
        openIntent.putExtra(WearMainActivity.EXTRA_OPEN_CHAT_KEY, tick.conversationKey);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                context, notifId, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent deleteIntent = new Intent(context, WatchNotifActionReceiver.class);
        deleteIntent.setAction(WatchNotifActionReceiver.ACTION_DISMISS_WATCH_NOTIF);
        deleteIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
        deleteIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, notifId);
        PendingIntent deletePi = PendingIntent.getBroadcast(
                context, notifId + 10000, deleteIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(cleanTitle)
                .setSubText(tick.appLabel)
                .setContentText(tick.messageText)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(isChat
                        ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(contentPi)
                .setDeleteIntent(deletePi);

        applyOriginalBranding(builder, tick, avatarBmp);

        if (tick.escalationLevel >= 3) {
            builder.setFullScreenIntent(contentPi, true);
        }

        // ONLY attach Reply if this is a real chat that supports Reply!
        if (isChat && tick.canReply) {
            String[] choices = tick.quickReplies != null
                    ? tick.quickReplies.toArray(new String[0])
                    : new String[0];
            RemoteInput ri = new RemoteInput.Builder(KEY_WATCH_TEXT_REPLY)
                    .setLabel(context.getString(R.string.wear_btn_reply))
                    .setChoices(choices)
                    .build();

            Intent replyIntent = new Intent(context, WatchNotifActionReceiver.class);
            replyIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_REPLY);
            replyIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
            replyIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, notifId);
            int mut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent replyPi = PendingIntent.getBroadcast(context, notifId + 20000, replyIntent, mut);

            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_watch_logo,
                    context.getString(R.string.wear_notif_reply),
                    replyPi
            ).addRemoteInput(ri).build());
        }

        // Attach real 1-tap custom actions from original notification (e.g., Teams "Like")
        if (tick.extraActionTitles != null && !tick.extraActionTitles.isEmpty()) {
            int max = Math.min(tick.extraActionTitles.size(), 3);
            for (int i = 0; i < max; i++) {
                String title = tick.extraActionTitles.get(i);
                if (TextUtils.isEmpty(title)) continue;
                Intent actIntent = new Intent(context, WatchNotifActionReceiver.class);
                actIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_CUSTOM_ACTION);
                actIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
                actIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, notifId);
                actIntent.putExtra(WatchNotifActionReceiver.EXTRA_ACTION_INDEX, i);
                PendingIntent actPi = PendingIntent.getBroadcast(
                        context, notifId + 60000 + i, actIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                builder.addAction(R.drawable.ic_watch_logo, title, actPi);
            }
        }

        if (tick.isRepeating) {
            Intent stopIntent = new Intent(context, WatchNotifActionReceiver.class);
            stopIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_STOP_ALERT);
            stopIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
            stopIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, notifId);
            PendingIntent stopPi = PendingIntent.getBroadcast(
                    context, notifId + 30000, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_watch_logo, context.getString(R.string.wear_notif_stop), stopPi);

            Intent snoozeIntent = new Intent(context, WatchNotifActionReceiver.class);
            snoozeIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_SNOOZE_3M);
            snoozeIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
            snoozeIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, notifId);
            PendingIntent snoozePi = PendingIntent.getBroadcast(
                    context, notifId + 40000, snoozeIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_watch_logo, context.getString(R.string.wear_btn_snooze), snoozePi);
        }

        // ONLY attach Mark Read if original notification supports Mark Read!
        if (tick.canMarkRead) {
            Intent readIntent = new Intent(context, WatchNotifActionReceiver.class);
            readIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_MARK_READ);
            readIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
            readIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, notifId);
            PendingIntent readPi = PendingIntent.getBroadcast(
                    context, notifId + 50000, readIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_watch_logo, context.getString(R.string.wear_btn_mark_read), readPi);
        }

        nm.notify(notifId, builder.build());
    }

    public static void reconcileWithState(Context context, WearProtocol.WatchStatePayload state) {
        if (state == null || state.conversations == null) return;
        java.util.Set<String> activeKeys = new java.util.HashSet<>();
        for (WearProtocol.ConversationDto c : state.conversations) {
            if (c != null && c.conversationKey != null) {
                activeKeys.add(c.conversationKey);
            }
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (java.util.Iterator<java.util.Map.Entry<String, Integer>> it = ACTIVE_IDS.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, Integer> entry = it.next();
            if (!activeKeys.contains(entry.getKey())) {
                if (nm != null && entry.getValue() != null) {
                    nm.cancel(entry.getValue());
                }
                it.remove();
            }
        }
    }

    public static void postIncomingCallNotification(Context context, WearProtocol.AlertTickPayload tick) {
        if (tick == null) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannels(context, nm);

        String caller = !TextUtils.isEmpty(tick.senderName) ? tick.senderName : tick.conversationTitle;
        if (TextUtils.isEmpty(caller)) caller = tick.appLabel;

        Intent callUi = new Intent(context, com.nitsutech.watchbridge.ui.WatchCallActivity.class);
        callUi.putExtra(com.nitsutech.watchbridge.ui.WatchCallActivity.EXTRA_CONV_KEY, tick.conversationKey);
        callUi.putExtra(com.nitsutech.watchbridge.ui.WatchCallActivity.EXTRA_CALLER_NAME, caller);
        callUi.putExtra(com.nitsutech.watchbridge.ui.WatchCallActivity.EXTRA_APP_LABEL, tick.appLabel);
        callUi.putExtra(com.nitsutech.watchbridge.ui.WatchCallActivity.EXTRA_CAN_ANSWER, tick.canAnswerCall);
        callUi.putExtra(com.nitsutech.watchbridge.ui.WatchCallActivity.EXTRA_CAN_DECLINE, tick.canDeclineCall);
        callUi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(
                context, CALL_NOTIF_ID, callUi,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_WATCH_CALLS)
                .setContentTitle(caller)
                .setSubText(tick.appLabel)
                .setContentText(context.getString(R.string.wear_call_incoming))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi);

        applyOriginalBranding(builder, tick, decodeBase64Bitmap(tick.avatarBase64));

        if (tick.canAnswerCall) {
            Intent ansIntent = new Intent(context, WatchNotifActionReceiver.class);
            ansIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_ANSWER_CALL);
            ansIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
            ansIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, CALL_NOTIF_ID);
            PendingIntent ansPi = PendingIntent.getBroadcast(
                    context, CALL_NOTIF_ID + 10, ansIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_watch_logo, context.getString(R.string.wear_call_answer), ansPi);
        }

        if (tick.canDeclineCall) {
            Intent decIntent = new Intent(context, WatchNotifActionReceiver.class);
            decIntent.setAction(WatchNotifActionReceiver.ACTION_WATCH_DECLINE_CALL);
            decIntent.putExtra(WatchNotifActionReceiver.EXTRA_CONV_KEY, tick.conversationKey);
            decIntent.putExtra(WatchNotifActionReceiver.EXTRA_NOTIF_ID, CALL_NOTIF_ID);
            PendingIntent decPi = PendingIntent.getBroadcast(
                    context, CALL_NOTIF_ID + 20, decIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(R.drawable.ic_watch_logo, context.getString(R.string.wear_call_decline), decPi);
        }

        nm.notify(CALL_NOTIF_ID, builder.build());
    }

    public static void cancelCallNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CALL_NOTIF_ID);
    }

    public static void cancelForChat(Context context, String conversationKey) {
        if (conversationKey == null) return;
        Integer id = ACTIVE_IDS.remove(conversationKey);
        if (id != null) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(id);
        }
    }

    public static void cancelAll(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (Integer id : ACTIVE_IDS.values()) {
            if (nm != null && id != null) nm.cancel(id);
        }
        ACTIVE_IDS.clear();
    }

    private static void ensureChannels(Context context, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_WATCH_NORMAL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_WATCH_NORMAL,
                    context.getString(R.string.wear_channel_normal),
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_WATCH_REPEAT) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_WATCH_REPEAT,
                    context.getString(R.string.wear_channel_repeat),
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_WATCH_CALLS) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_WATCH_CALLS,
                    context.getString(R.string.wear_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    private static void applyOriginalBranding(NotificationCompat.Builder builder,
                                            WearProtocol.AlertTickPayload tick,
                                            Bitmap avatarBmp) {
        Bitmap smallBmp = decodeBase64Bitmap(tick.smallIconBase64);
        if (smallBmp != null) {
            builder.setSmallIcon(IconCompat.createWithBitmap(smallBmp));
        } else if ("com.whatsapp".equals(tick.packageName) || "com.whatsapp.w4b".equals(tick.packageName)) {
            builder.setSmallIcon(R.drawable.ic_app_whatsapp);
        } else if ("com.microsoft.teams".equals(tick.packageName)) {
            builder.setSmallIcon(R.drawable.ic_app_teams);
        } else {
            builder.setSmallIcon(R.drawable.ic_watch_logo);
        }

        int accent = tick.appColor;
        if (accent == 0) {
            if ("com.whatsapp".equals(tick.packageName) || "com.whatsapp.w4b".equals(tick.packageName)) {
                accent = 0xFF21C063;
            } else if ("com.microsoft.teams".equals(tick.packageName)) {
                accent = 0xFF7F85F5;
            }
        }
        if (accent != 0) {
            builder.setColor(accent);
        }
        if (avatarBmp != null) {
            builder.setLargeIcon(avatarBmp);
        }
    }

    private static Bitmap decodeBase64Bitmap(String b64) {
        if (TextUtils.isEmpty(b64)) return null;
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) {
            return null;
        }
    }
}
