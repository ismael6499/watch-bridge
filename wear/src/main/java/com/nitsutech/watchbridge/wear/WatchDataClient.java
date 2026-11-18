package com.nitsutech.watchbridge.wear;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends Watch -> Phone commands over Wearable MessageClient.
 */
public class WatchDataClient {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Gson GSON = new Gson();

    public static void sendCommandAsync(Context context, WearProtocol.WatchCommand cmd) {
        final Context appCtx = context.getApplicationContext();
        IO.execute(() -> sendCommandBlocking(appCtx, cmd));
    }

    public static boolean sendCommandBlocking(Context context, WearProtocol.WatchCommand cmd) {
        try {
            byte[] payload = GSON.toJson(cmd).getBytes(StandardCharsets.UTF_8);
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
            if (nodes == null || nodes.isEmpty()) return false;
            for (Node node : nodes) {
                Tasks.await(Wearable.getMessageClient(context)
                        .sendMessage(node.getId(), WearProtocol.PATH_COMMAND, payload));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestSync(Context context) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_REQUEST_SYNC;
        sendCommandAsync(context, cmd);
    }

    public static void setProfile(Context context, int profile) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_SET_PROFILE;
        cmd.valueInt = profile;
        sendCommandAsync(context, cmd);
    }

    public static void toggleApp(Context context, String packageName, boolean enabled) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_TOGGLE_APP_RULE;
        cmd.packageName = packageName;
        cmd.valueBool = enabled;
        sendCommandAsync(context, cmd);
    }

    public static void toggleAppGroup(Context context, String packageName, boolean groupEnabled) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_TOGGLE_APP_GROUP;
        cmd.packageName = packageName;
        cmd.valueBool = groupEnabled;
        sendCommandAsync(context, cmd);
    }

    public static void updateAppTimers(Context context, String packageName, int repeatSec, int cooldownSec) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_UPDATE_APP_TIMERS;
        cmd.packageName = packageName;
        cmd.valueInt = repeatSec;
        cmd.replyText = String.valueOf(cooldownSec);
        sendCommandAsync(context, cmd);
    }

    public static void sendReply(Context context, String conversationKey, String replyText) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_SEND_REPLY;
        cmd.conversationKey = conversationKey;
        cmd.replyText = replyText;
        sendCommandAsync(context, cmd);
    }

    public static void markRead(Context context, String conversationKey) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_MARK_READ;
        cmd.conversationKey = conversationKey;
        sendCommandAsync(context, cmd);
    }

    public static void dismissChat(Context context, String conversationKey) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_DISMISS_CHAT;
        cmd.conversationKey = conversationKey;
        sendCommandAsync(context, cmd);
    }

    public static void dismissSingleMessage(Context context, long messageId) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_DISMISS_MESSAGE;
        cmd.messageId = messageId;
        sendCommandAsync(context, cmd);
    }

    public static void deleteVaultItem(Context context, long messageId) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_DELETE_VAULT_ITEM;
        cmd.messageId = messageId;
        sendCommandAsync(context, cmd);
    }

    public static void clearVault(Context context) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_CLEAR_VAULT;
        sendCommandAsync(context, cmd);
    }

    public static void snoozeChat(Context context, String conversationKey, int minutes) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_SNOOZE_ALERT;
        cmd.conversationKey = conversationKey;
        cmd.valueInt = minutes;
        sendCommandAsync(context, cmd);
    }

    public static void muteChat(Context context, String conversationKey, int minutes) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_MUTE_CHAT;
        cmd.conversationKey = conversationKey;
        cmd.valueInt = minutes;
        sendCommandAsync(context, cmd);
    }

    public static void toggleVip(Context context, String senderName, String packageName) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_TOGGLE_VIP;
        cmd.senderName = senderName;
        cmd.packageName = packageName;
        sendCommandAsync(context, cmd);
    }

    public static void requestVoiceAudio(Context context, long messageId, String conversationKey) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_REQUEST_AUDIO;
        cmd.messageId = messageId;
        cmd.conversationKey = conversationKey;
        sendCommandAsync(context, cmd);
    }

    public static void setPhoneRinger(Context context, int ringerMode) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_SET_PHONE_RINGER;
        cmd.valueInt = ringerMode;
        sendCommandAsync(context, cmd);
    }

    public static void ringFindPhone(Context context, boolean start) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_RING_FIND_PHONE;
        cmd.valueBool = start;
        sendCommandAsync(context, cmd);
    }

    public static void stopAllAlerts(Context context) {
        WearProtocol.WatchCommand cmd = new WearProtocol.WatchCommand();
        cmd.action = WearProtocol.CMD_STOP_ALL_ALERTS;
        sendCommandAsync(context, cmd);
    }
}
