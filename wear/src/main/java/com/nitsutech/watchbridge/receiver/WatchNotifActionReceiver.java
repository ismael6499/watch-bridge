package com.nitsutech.watchbridge.receiver;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.app.RemoteInput;

import com.nitsutech.watchbridge.wear.WatchDataClient;
import com.nitsutech.watchbridge.wear.WatchNotifHelper;
import com.nitsutech.watchbridge.wear.WearProtocol;

public class WatchNotifActionReceiver extends BroadcastReceiver {

    public static final String ACTION_WATCH_REPLY = "com.nitsutech.watchbridge.WATCH_ACTION_REPLY";
    public static final String ACTION_WATCH_STOP_ALERT = "com.nitsutech.watchbridge.WATCH_ACTION_STOP_ALERT";
    public static final String ACTION_WATCH_SNOOZE_3M = "com.nitsutech.watchbridge.WATCH_ACTION_SNOOZE_3M";
    public static final String ACTION_WATCH_MARK_READ = "com.nitsutech.watchbridge.WATCH_ACTION_MARK_READ";
    public static final String ACTION_DISMISS_WATCH_NOTIF = "com.nitsutech.watchbridge.WATCH_ACTION_DISMISS";

    public static final String EXTRA_CONV_KEY = "conv_key";
    public static final String EXTRA_NOTIF_ID = "notif_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        String convKey = intent.getStringExtra(EXTRA_CONV_KEY);
        int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1);

        if (ACTION_WATCH_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null && convKey != null) {
                CharSequence cs = results.getCharSequence(WatchNotifHelper.KEY_WATCH_TEXT_REPLY);
                if (cs != null) {
                    WatchDataClient.sendReply(context, convKey, cs.toString());
                    cancelLocalNotif(context, convKey, notifId);
                }
            }
        } else if (ACTION_WATCH_STOP_ALERT.equals(action)) {
            if (convKey != null) {
                WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
                cmd.action = WearProtocol.CMD_STOP_CHAT_ALERT;
                cmd.conversationKey = convKey;
                WatchDataClient.sendCommandAsync(context, cmd);
                cancelLocalNotif(context, convKey, notifId);
            }
        } else if (ACTION_WATCH_SNOOZE_3M.equals(action)) {
            if (convKey != null) {
                WatchDataClient.snoozeChat(context, convKey, 3);
                cancelLocalNotif(context, convKey, notifId);
            }
        } else if (ACTION_WATCH_MARK_READ.equals(action)) {
            if (convKey != null) {
                WatchDataClient.markRead(context, convKey);
                cancelLocalNotif(context, convKey, notifId);
            }
        } else if (ACTION_DISMISS_WATCH_NOTIF.equals(action)) {
            if (convKey != null) {
                WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
                cmd.action = WearProtocol.CMD_STOP_CHAT_ALERT;
                cmd.conversationKey = convKey;
                WatchDataClient.sendCommandAsync(context, cmd);
            }
        }
    }

    private void cancelLocalNotif(Context context, String convKey, int notifId) {
        WatchNotifHelper.cancelForChat(context, convKey);
        if (notifId > 0) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId);
        }
    }
}
