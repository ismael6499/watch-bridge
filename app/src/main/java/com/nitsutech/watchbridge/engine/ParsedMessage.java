package com.nitsutech.watchbridge.engine;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

public class ParsedMessage {

    public String notificationKey = "";
    public String conversationKey = "";
    public String packageName = "";
    public String appLabel = "";
    public String conversationTitle = "";
    public String senderName = "";
    public String messageText = "";
    public long timestamp = 0L;

    public boolean isGroup = false;
    public boolean isIncomingCall = false;
    public boolean isSourceSilent = false;
    public boolean isDeletedNotice = false;
    public boolean isChronometerCall = false;
    public int notificationKind = 0; // 0 = KIND_CHAT, 1 = KIND_EVENT, 2 = KIND_CALL
    public boolean canReply = false;
    public boolean canMarkRead = false;
    public boolean hasVoiceNote = false;
    public String voiceNoteUri = "";

    public String mediaPath = "";
    public String mediaBase64 = "";
    public String avatarBase64 = "";
    public String smallIconBase64 = "";
    public int appColor = 0;
    public Bitmap senderAvatar = null;

    public Notification.Action replyAction = null;
    public Notification.Action markReadAction = null;
    public PendingIntent answerCallIntent = null;
    public PendingIntent declineCallIntent = null;
    public final List<Notification.Action> extraActions = new ArrayList<>();
    public final List<String> extraActionTitles = new ArrayList<>();
}
