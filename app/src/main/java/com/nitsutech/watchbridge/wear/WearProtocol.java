package com.nitsutech.watchbridge.wear;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON protocol DTOs for bidirectional sync between Phone (:app) and Galaxy Watch 8 Classic (:wear).
 */
public class WearProtocol {

    public static final String PATH_STATE_DATA = "/watchbridge/state";
    public static final String PATH_SYNC_MSG = "/watchbridge/sync";
    public static final String PATH_ALERT_MSG = "/watchbridge/alert";
    public static final String PATH_AUDIO_MSG = "/watchbridge/audio_data";
    public static final String PATH_AUDIO_CHANNEL = "/watchbridge/audio_channel";
    public static final String PATH_CALL_END_MSG = "/watchbridge/call_end";
    public static final String PATH_COMMAND = "/watchbridge/command";

    public static final String CMD_REQUEST_SYNC = "REQUEST_SYNC";
    public static final String CMD_SET_PROFILE = "SET_PROFILE";
    public static final String CMD_TOGGLE_APP_RULE = "TOGGLE_APP_RULE";
    public static final String CMD_TOGGLE_APP_GROUP = "TOGGLE_APP_GROUP";
    public static final String CMD_UPDATE_APP_TIMERS = "UPDATE_APP_TIMERS";
    public static final String CMD_SEND_REPLY = "SEND_REPLY";
    public static final String CMD_MARK_READ = "MARK_READ";
    public static final String CMD_DISMISS_CHAT = "DISMISS_CHAT";
    public static final String CMD_DISMISS_MESSAGE = "DISMISS_MESSAGE";
    public static final String CMD_DELETE_VAULT_ITEM = "DELETE_VAULT_ITEM";
    public static final String CMD_CLEAR_VAULT = "CLEAR_VAULT";
    public static final String CMD_SNOOZE_ALERT = "SNOOZE_ALERT";
    public static final String CMD_MUTE_CHAT = "MUTE_CHAT";
    public static final String CMD_TOGGLE_VIP = "TOGGLE_VIP";
    public static final String CMD_REQUEST_AUDIO = "REQUEST_AUDIO";
    public static final String CMD_SET_PHONE_RINGER = "SET_PHONE_RINGER";
    public static final String CMD_RING_FIND_PHONE = "RING_FIND_PHONE";
    public static final String CMD_STOP_ALL_ALERTS = "STOP_ALL_ALERTS";
    public static final String CMD_STOP_CHAT_ALERT = "STOP_CHAT_ALERT";
    public static final String CMD_ANSWER_CALL = "ANSWER_CALL";
    public static final String CMD_DECLINE_CALL = "DECLINE_CALL";

    public static final int KIND_CHAT = 0;
    public static final int KIND_EVENT = 1;
    public static final int KIND_CALL = 2;

    public static class WatchStatePayload {
        public int activeProfile;
        public long repeatEndTimeMs;
        public int defaultRepeatDurationMin;
        public int phoneBatteryPct;
        public int phoneRingerMode; // AudioManager.RINGER_MODE_*
        public int activeAlertCount;
        public boolean isFindingPhone;
        public String ttsLanguage = "es";
        public long syncedAtMs;
        public List<AppRuleDto> appRules = new ArrayList<>();
        public List<ConversationDto> conversations = new ArrayList<>();
        public List<MessageDto> deletedVault = new ArrayList<>();
    }

    public static class AppRuleDto {
        public String packageName;
        public String appName;
        public boolean enabled;
        public boolean directEnabled;
        public boolean directRepeatEnabled;
        public int directRepeatIntervalSec;
        public int directCooldownSec;
        public boolean groupEnabled;
        public boolean groupRepeatEnabled;
        public int groupRepeatIntervalSec;
        public int groupCooldownSec;
    }

    public static class ConversationDto {
        public String conversationKey;
        public String packageName;
        public String appLabel;
        public String conversationTitle;
        public boolean isGroup;
        public boolean isVip;
        public boolean isMuted;
        public boolean isRepeating;
        public int notificationKind;
        public boolean canReply;
        public boolean canMarkRead;
        public long lastTimestamp;
        public List<String> quickReplies = new ArrayList<>();
        public List<MessageDto> messages = new ArrayList<>();
    }

    public static class MessageDto {
        public long id;
        public String conversationKey;
        public String packageName;
        public String appLabel;
        public String conversationTitle;
        public String senderName;
        public String messageText;
        public long timestamp;
        public boolean isGroup;
        public boolean isOutgoing;
        public boolean isDeletedBySender;
        public boolean isRead;
        public int notificationKind;
        public boolean canReply;
        public boolean canMarkRead;
        public String mediaBase64;
        public boolean hasVoiceNote;
        public String voiceNoteUri;
    }

    public static class AlertTickPayload {
        public String conversationKey;
        public String packageName;
        public String appLabel;
        public String conversationTitle;
        public String senderName;
        public String messageText;
        public int attemptCount;
        public int hapticPattern;
        public int escalationLevel; // 1 = gentle, 2 = medium, 3+ = max wake-up escalation
        public int notificationKind;
        public boolean canReply;
        public boolean canMarkRead;
        public boolean isRepeating;
        public boolean isGroup;
        public boolean isCall;
        public boolean canAnswerCall;
        public boolean canDeclineCall;
        public boolean autoTts;
        public boolean hasVoiceNote;
        public String mediaBase64;
        public String avatarBase64;
        public String smallIconBase64;
        public int appColor;
        public List<String> quickReplies = new ArrayList<>();
    }

    public static class WatchCommand {
        public String action;
        public String packageName;
        public String conversationKey;
        public String senderName;
        public long messageId;
        public String replyText;
        public int valueInt;
        public boolean valueBool;
    }

    public static class AudioTransferHeader {
        public String conversationKey;
        public String fileName;
        public String fallbackTtsText;
        public boolean isAudioAvailable;
    }
}
