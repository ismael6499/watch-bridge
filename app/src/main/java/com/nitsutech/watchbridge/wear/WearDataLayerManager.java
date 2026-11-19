package com.nitsutech.watchbridge.wear;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.text.TextUtils;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.Gson;
import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.AppRuleEntity;
import com.nitsutech.watchbridge.data.ChatMessageEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.data.SenderRuleEntity;
import com.nitsutech.watchbridge.service.PersistentRepeatForegroundService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles all Phone -> Galaxy Watch 8 Classic synchronization over Wearable DataClient & MessageClient.
 */
public class WearDataLayerManager {

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor();
    private static final Gson GSON = new Gson();
    private static volatile String lastConnectedWatchName = "";

    public static String getLastConnectedWatchName() {
        return lastConnectedWatchName;
    }

    public static void syncStateAsync(Context context) {
        final Context appCtx = context.getApplicationContext();
        IO_EXEC.execute(() -> syncStateBlocking(appCtx));
    }

    public static void syncStateBlocking(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            db.ensureDefaultsSync();
            PrefsManager prefs = new PrefsManager(context);

            WearProtocol.WatchStatePayload state = buildStatePayload(context, db, prefs);
            String json = GSON.toJson(state);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            // 1. Update persistent DataItem so Watch gets state even after reconnect/reboot
            PutDataMapRequest dataReq = PutDataMapRequest.create(WearProtocol.PATH_STATE_DATA);
            dataReq.getDataMap().putString("state_json", json);
            dataReq.getDataMap().putLong("ts", System.currentTimeMillis());
            PutDataRequest putReq = dataReq.asPutDataRequest().setUrgent();
            Tasks.await(Wearable.getDataClient(context).putDataItem(putReq));

            // 2. Push immediate MessageClient RPC to connected Watch nodes
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
            if (nodes != null && !nodes.isEmpty()) {
                lastConnectedWatchName = nodes.get(0).getDisplayName();
                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(context)
                            .sendMessage(node.getId(), WearProtocol.PATH_SYNC_MSG, bytes));
                }
            } else {
                lastConnectedWatchName = "";
            }
        } catch (Exception ignored) {
        }
    }

    public static void syncAndSendAlertBlocking(Context context, WearProtocol.AlertTickPayload tick) {
        syncStateBlocking(context);
        sendAlertTickBlocking(context, tick);
    }

    public static void sendAlertTickBlocking(Context context, WearProtocol.AlertTickPayload tick) {
        try {
            byte[] bytes = GSON.toJson(tick).getBytes(StandardCharsets.UTF_8);
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes());
            if (nodes != null && !nodes.isEmpty()) {
                lastConnectedWatchName = nodes.get(0).getDisplayName();
                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(context)
                            .sendMessage(node.getId(), WearProtocol.PATH_ALERT_MSG, bytes));
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void sendAlertTickAsync(Context context, WearProtocol.AlertTickPayload tick) {
        final Context appCtx = context.getApplicationContext();
        IO_EXEC.execute(() -> sendAlertTickBlocking(appCtx, tick));
    }

    public static void sendCallEndedAsync(Context context, String conversationKey) {
        final Context appCtx = context.getApplicationContext();
        IO_EXEC.execute(() -> {
            try {
                byte[] bytes = (conversationKey == null ? "" : conversationKey).getBytes(StandardCharsets.UTF_8);
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(appCtx).getConnectedNodes());
                if (nodes != null && !nodes.isEmpty()) {
                    for (Node node : nodes) {
                        Tasks.await(Wearable.getMessageClient(appCtx)
                                .sendMessage(node.getId(), WearProtocol.PATH_CALL_END_MSG, bytes));
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static void sendVoiceNoteToWatchAsync(Context context, long messageId, String conversationKey) {
        final Context appCtx = context.getApplicationContext();
        IO_EXEC.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                ChatMessageEntity msg = messageId > 0
                        ? db.chatMessageDao().getByIdSync(messageId)
                        : db.chatMessageDao().getLatestIncomingSync(conversationKey);

                List<Node> nodes = Tasks.await(Wearable.getNodeClient(appCtx).getConnectedNodes());
                if (nodes == null || nodes.isEmpty()) return;

                WearProtocol.AudioTransferHeader header = new WearProtocol.AudioTransferHeader();
                header.conversationKey = conversationKey;
                PrefsManager prefs = new PrefsManager(appCtx);
                String storedUri = msg != null ? msg.voiceNoteUri : "";
                long msgTs = msg != null ? msg.timestamp : System.currentTimeMillis();
                byte[] audioBytes = com.nitsutech.watchbridge.engine.NotificationParser
                        .readVoiceNoteBytes(appCtx, storedUri, msgTs, prefs, 2_500_000);
                if (msg != null) {
                    header.fileName = "voice_" + msg.id + ".ogg";
                }

                if (audioBytes != null && audioBytes.length > 0) {
                    header.isAudioAvailable = true;
                    header.fallbackTtsText = (msg != null ? msg.senderName + ": " + msg.messageText : "");
                    byte[] headerJson = GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
                    // Send header first, then stream raw bytes over ChannelClient
                    for (Node node : nodes) {
                        Tasks.await(Wearable.getMessageClient(appCtx)
                                .sendMessage(node.getId(), WearProtocol.PATH_AUDIO_MSG, headerJson));
                        com.google.android.gms.wearable.ChannelClient.Channel channel =
                                Tasks.await(Wearable.getChannelClient(appCtx)
                                        .openChannel(node.getId(), WearProtocol.PATH_AUDIO_CHANNEL));
                        try (java.io.OutputStream os = Tasks.await(
                                Wearable.getChannelClient(appCtx).getOutputStream(channel))) {
                            os.write(audioBytes);
                            os.flush();
                        }
                        Wearable.getChannelClient(appCtx).close(channel);
                    }
                } else {
                    header.isAudioAvailable = false;
                    boolean hasPerm = com.nitsutech.watchbridge.engine.NotificationParser
                            .hasVoiceFolderAccess(appCtx, prefs);
                    header.fallbackTtsText = hasPerm
                            ? "No se encontró el archivo de audio de WhatsApp."
                            : "Falta habilitar permiso de archivos o carpeta de audios en WatchBridge del celular.";
                    byte[] headerJson = GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
                    for (Node node : nodes) {
                        Tasks.await(Wearable.getMessageClient(appCtx)
                                .sendMessage(node.getId(), WearProtocol.PATH_AUDIO_MSG, headerJson));
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    public interface NodeCheckCallback {
        void onResult(String watchDisplayName, int count);
    }

    public static void checkConnectedWatchAsync(Context context, NodeCheckCallback callback) {
        final Context appCtx = context.getApplicationContext();
        IO_EXEC.execute(() -> {
            String name = "";
            int count = 0;
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(appCtx).getConnectedNodes());
                if (nodes != null && !nodes.isEmpty()) {
                    count = nodes.size();
                    name = nodes.get(0).getDisplayName();
                    lastConnectedWatchName = name;
                } else {
                    lastConnectedWatchName = "";
                }
            } catch (Exception ignored) {
            }
            if (callback != null) {
                callback.onResult(name, count);
            }
        });
    }

    private static WearProtocol.WatchStatePayload buildStatePayload(Context context, AppDatabase db, PrefsManager prefs) {
        WearProtocol.WatchStatePayload state = new WearProtocol.WatchStatePayload();
        state.activeProfile = prefs.getActiveProfile();
        state.repeatEndTimeMs = prefs.getRepeatEndTimeMs();
        state.defaultRepeatDurationMin = prefs.getDefaultRepeatDurationMin();
        state.phoneBatteryPct = readBatteryPct(context);
        state.phoneRingerMode = readRingerMode(context);
        state.activeAlertCount = PersistentRepeatForegroundService.getActiveSessionCount();
        state.isFindingPhone = com.nitsutech.watchbridge.service.AlertAudioHelper.isFindPhoneRinging();
        state.ttsLanguage = prefs.getWatchTtsLanguage();
        state.syncedAtMs = System.currentTimeMillis();

        List<AppRuleEntity> rules = db.appRuleDao().getAllSync();
        for (AppRuleEntity r : rules) {
            WearProtocol.AppRuleDto dto = new WearProtocol.AppRuleDto();
            dto.packageName = r.packageName;
            dto.appName = r.appName;
            dto.enabled = r.enabled;
            dto.directEnabled = r.directEnabled;
            dto.directRepeatEnabled = r.directRepeatEnabled;
            dto.directRepeatIntervalSec = r.directRepeatIntervalSec;
            dto.directCooldownSec = r.directCooldownSec;
            dto.groupEnabled = r.groupEnabled;
            dto.groupRepeatEnabled = r.groupRepeatEnabled;
            dto.groupRepeatIntervalSec = r.groupRepeatIntervalSec;
            dto.groupCooldownSec = r.groupCooldownSec;
            state.appRules.add(dto);
        }

        List<SenderRuleEntity> senderRules = db.senderRuleDao().getAllSync();
        List<ChatMessageEntity> recent = db.chatMessageDao().getRecentAllSync();
        Map<String, WearProtocol.ConversationDto> grouped = new LinkedHashMap<>();

        for (ChatMessageEntity m : recent) {
            if (m.isDismissed) continue;
            WearProtocol.ConversationDto conv = grouped.get(m.conversationKey);
            if (conv == null) {
                if (grouped.size() >= 12) continue;
                conv = new WearProtocol.ConversationDto();
                conv.conversationKey = m.conversationKey;
                conv.packageName = m.packageName;
                conv.appLabel = m.appLabel;
                conv.conversationTitle = m.conversationTitle;
                conv.isGroup = m.isGroup;
                conv.isMuted = prefs.isChatMuted(m.conversationKey);
                conv.isRepeating = PersistentRepeatForegroundService.isConversationRepeating(m.conversationKey);
                conv.isVip = isVipContact(m.senderName, m.conversationTitle, senderRules);
                conv.notificationKind = m.notificationKind;
                conv.canReply = (m.notificationKind == WearProtocol.KIND_CHAT) && m.canReply;
                conv.canMarkRead = m.canMarkRead;
                conv.lastTimestamp = m.timestamp;
                conv.quickReplies = conv.canReply
                        ? Arrays.asList(prefs.getQuickRepliesForPackage(m.packageName))
                        : new ArrayList<>();
                com.nitsutech.watchbridge.engine.ActionCache.CachedActions cachedActions =
                        com.nitsutech.watchbridge.engine.ActionCache.get(m.conversationKey);
                if (cachedActions != null && cachedActions.extraActionTitles != null) {
                    conv.extraActionTitles = new ArrayList<>(cachedActions.extraActionTitles);
                }
                grouped.put(m.conversationKey, conv);
            }
            if (conv.messages.size() < 10) {
                WearProtocol.MessageDto dto = toMessageDto(m);
                if (!conv.messages.isEmpty()) {
                    dto.mediaBase64 = "";
                }
                conv.messages.add(0, dto);
            }
        }
        state.conversations = new ArrayList<>(grouped.values());

        List<ChatMessageEntity> vault = db.chatMessageDao().getDeletedVaultSync();
        for (int i = 0; i < Math.min(vault.size(), 25); i++) {
            WearProtocol.MessageDto dto = toMessageDto(vault.get(i));
            if (i >= 3) {
                dto.mediaBase64 = "";
            }
            state.deletedVault.add(dto);
        }

        return state;
    }

    private static WearProtocol.MessageDto toMessageDto(ChatMessageEntity m) {
        WearProtocol.MessageDto dto = new WearProtocol.MessageDto();
        dto.id = m.id;
        dto.conversationKey = m.conversationKey;
        dto.packageName = m.packageName;
        dto.appLabel = m.appLabel;
        dto.conversationTitle = m.conversationTitle;
        dto.senderName = m.senderName;
        dto.messageText = m.messageText;
        dto.timestamp = m.timestamp;
        dto.isGroup = m.isGroup;
        dto.isOutgoing = m.isOutgoing;
        dto.isDeletedBySender = m.isDeletedBySender;
        dto.isRead = m.isRead;
        dto.notificationKind = m.notificationKind;
        dto.canReply = m.canReply;
        dto.canMarkRead = m.canMarkRead;
        dto.mediaBase64 = m.mediaBase64;
        dto.hasVoiceNote = m.hasVoiceNote;
        dto.voiceNoteUri = m.voiceNoteUri;
        return dto;
    }

    private static boolean isVipContact(String sender, String title, List<SenderRuleEntity> rules) {
        if (rules == null) return false;
        String sLow = sender == null ? "" : sender.toLowerCase();
        String tLow = title == null ? "" : title.toLowerCase();
        for (SenderRuleEntity r : rules) {
            if (r.isVip && r.senderOrChatName != null) {
                String q = r.senderOrChatName.toLowerCase().trim();
                if (!q.isEmpty() && (sLow.contains(q) || tLow.contains(q))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int readBatteryPct(Context context) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int readRingerMode(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
        } catch (Exception e) {
            return AudioManager.RINGER_MODE_NORMAL;
        }
    }

    private static byte[] readLimitedBytes(Context context, Uri uri, int maxBytes) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = is.read(buf)) != -1) {
                if (total + read > maxBytes) break;
                baos.write(buf, 0, read);
                total += read;
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
