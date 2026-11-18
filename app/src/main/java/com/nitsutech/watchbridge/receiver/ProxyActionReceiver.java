package com.nitsutech.watchbridge.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.app.RemoteInput;

import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.service.PersistentRepeatForegroundService;
import com.nitsutech.watchbridge.service.ProfileController;
import com.nitsutech.watchbridge.service.ProxyNotificationHelper;

public class ProxyActionReceiver extends BroadcastReceiver {

    public static final String ACTION_REPLY = "com.nitsutech.watchbridge.ACTION_REPLY";
    public static final String ACTION_MARK_READ = "com.nitsutech.watchbridge.ACTION_MARK_READ";
    public static final String ACTION_SNOOZE_3M = "com.nitsutech.watchbridge.ACTION_SNOOZE_3M";
    public static final String ACTION_STOP_CHAT_ALERT = "com.nitsutech.watchbridge.ACTION_STOP_CHAT_ALERT";
    public static final String ACTION_END_REPEAT_MODE = "com.nitsutech.watchbridge.ACTION_END_REPEAT_MODE";
    public static final String ACTION_DISMISSED_NOTIFICATION = "com.nitsutech.watchbridge.ACTION_DISMISSED_NOTIFICATION";
    public static final String ACTION_WIDGET_TOGGLE_REPEAT = "com.nitsutech.watchbridge.ACTION_WIDGET_TOGGLE_REPEAT";
    public static final String ACTION_WIDGET_STOP_ALERTS = "com.nitsutech.watchbridge.ACTION_WIDGET_STOP_ALERTS";
    public static final String ACTION_WIDGET_CYCLE_PROFILE = "com.nitsutech.watchbridge.ACTION_WIDGET_CYCLE_PROFILE";
    public static final String ACTION_STOP_FIND_PHONE = "com.nitsutech.watchbridge.ACTION_STOP_FIND_PHONE";

    public static final String EXTRA_CONVERSATION_KEY = "conversation_key";
    public static final String EXTRA_NOTIF_ID = "notif_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        String convKey = intent.getStringExtra(EXTRA_CONVERSATION_KEY);

        if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null) {
                CharSequence cs = results.getCharSequence(ProxyNotificationHelper.KEY_TEXT_REPLY);
                if (cs != null && convKey != null) {
                    ProfileController.executeReply(context, convKey, cs.toString());
                }
            }
        } else if (ACTION_MARK_READ.equals(action)) {
            ProfileController.executeMarkAsRead(context, convKey);
        } else if (ACTION_SNOOZE_3M.equals(action)) {
            int mins = new PrefsManager(context).getDefaultSnoozeMinutes();
            ProfileController.snoozeChat(context, convKey, mins);
        } else if (ACTION_STOP_CHAT_ALERT.equals(action)) {
            PersistentRepeatForegroundService.stopChatSession(context, convKey);
        } else if (ACTION_END_REPEAT_MODE.equals(action) || ACTION_WIDGET_TOGGLE_REPEAT.equals(action)) {
            ProfileController.toggleRepeatMode(context);
        } else if (ACTION_WIDGET_STOP_ALERTS.equals(action)) {
            PersistentRepeatForegroundService.stopAllSessions(context);
        } else if (ACTION_WIDGET_CYCLE_PROFILE.equals(action)) {
            PrefsManager prefs = new PrefsManager(context);
            int next = (prefs.getActiveProfile() + 1) % 4;
            ProfileController.setProfile(context, next, -1);
        } else if (ACTION_DISMISSED_NOTIFICATION.equals(action)) {
            if (convKey != null) {
                PersistentRepeatForegroundService.stopChatSession(context, convKey);
            }
        } else if (ACTION_STOP_FIND_PHONE.equals(action)) {
            com.nitsutech.watchbridge.service.AlertAudioHelper.stopFindMyPhoneRing(context);
        }
    }
}
