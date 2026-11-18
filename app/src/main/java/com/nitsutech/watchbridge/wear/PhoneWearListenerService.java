package com.nitsutech.watchbridge.wear;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.gson.Gson;
import com.nitsutech.watchbridge.service.AlertAudioHelper;
import com.nitsutech.watchbridge.service.PersistentRepeatForegroundService;
import com.nitsutech.watchbridge.service.ProfileController;

import java.nio.charset.StandardCharsets;

/**
 * Receives commands from the Galaxy Watch 8 Classic native Wear OS app (:wear)
 * and executes them bidirectionally on the phone.
 */
public class PhoneWearListenerService extends WearableListenerService {

    private final Gson gson = new Gson();

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        if (!WearProtocol.PATH_COMMAND.equals(messageEvent.getPath())) {
            return;
        }
        String raw = new String(messageEvent.getData(), StandardCharsets.UTF_8);
        WearProtocol.WatchCommand cmd;
        try {
            cmd = gson.fromJson(raw, WearProtocol.WatchCommand.class);
        } catch (Exception e) {
            return;
        }
        if (cmd == null || cmd.action == null) return;

        switch (cmd.action) {
            case WearProtocol.CMD_REQUEST_SYNC:
                WearDataLayerManager.syncStateAsync(this);
                break;
            case WearProtocol.CMD_SET_PROFILE:
                ProfileController.setProfile(this, cmd.valueInt, -1);
                break;
            case WearProtocol.CMD_TOGGLE_APP_RULE:
                ProfileController.toggleAppEnabled(this, cmd.packageName, cmd.valueBool);
                break;
            case WearProtocol.CMD_TOGGLE_APP_GROUP:
                ProfileController.toggleAppGroupEnabled(this, cmd.packageName, cmd.valueBool);
                break;
            case WearProtocol.CMD_UPDATE_APP_TIMERS:
                ProfileController.updateAppTimers(this, cmd.packageName, cmd.valueInt,
                        cmd.replyText != null ? parseIntSafe(cmd.replyText, -1) : -1);
                break;
            case WearProtocol.CMD_SEND_REPLY:
                ProfileController.executeReply(this, cmd.conversationKey, cmd.replyText);
                break;
            case WearProtocol.CMD_MARK_READ:
                ProfileController.executeMarkAsRead(this, cmd.conversationKey);
                break;
            case WearProtocol.CMD_DISMISS_CHAT:
                ProfileController.dismissConversation(this, cmd.conversationKey);
                break;
            case WearProtocol.CMD_DISMISS_MESSAGE:
                ProfileController.dismissSingleMessage(this, cmd.messageId);
                break;
            case WearProtocol.CMD_DELETE_VAULT_ITEM:
                ProfileController.deleteVaultOrHistoryItem(this, cmd.messageId);
                break;
            case WearProtocol.CMD_CLEAR_VAULT:
                ProfileController.clearDeletedVault(this);
                break;
            case WearProtocol.CMD_SNOOZE_ALERT:
                ProfileController.snoozeChat(this, cmd.conversationKey, Math.max(1, cmd.valueInt));
                break;
            case WearProtocol.CMD_MUTE_CHAT:
                ProfileController.muteChat(this, cmd.conversationKey, cmd.valueInt);
                break;
            case WearProtocol.CMD_TOGGLE_VIP:
                ProfileController.toggleVipForSender(this, cmd.senderName, cmd.packageName);
                break;
            case WearProtocol.CMD_REQUEST_AUDIO:
                WearDataLayerManager.sendVoiceNoteToWatchAsync(this, cmd.messageId, cmd.conversationKey);
                break;
            case WearProtocol.CMD_SET_PHONE_RINGER:
                ProfileController.setPhoneRingerMode(this, cmd.valueInt);
                break;
            case WearProtocol.CMD_RING_FIND_PHONE:
                if (cmd.valueBool) {
                    AlertAudioHelper.triggerFindMyPhoneRing(this);
                } else {
                    AlertAudioHelper.stopFindMyPhoneRing(this);
                }
                break;
            case WearProtocol.CMD_STOP_CHAT_ALERT:
                PersistentRepeatForegroundService.stopChatSession(this, cmd.conversationKey);
                break;
            case WearProtocol.CMD_STOP_ALL_ALERTS:
                PersistentRepeatForegroundService.stopAllSessions(this);
                break;
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
