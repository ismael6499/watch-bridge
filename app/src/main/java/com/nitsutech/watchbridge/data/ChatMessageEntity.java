package com.nitsutech.watchbridge.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "chat_messages")
public class ChatMessageEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String conversationKey = "";
    public String packageName = "";
    public String appLabel = "";
    public String conversationTitle = "";
    public String senderName = "";
    public String messageText = "";
    public long timestamp = 0L;

    public boolean isGroup = false;
    public boolean isOutgoing = false;
    public int notificationKind = 0;
    public boolean canReply = false;
    public boolean canMarkRead = false;

    // Idea #4: Anti-Delete Shield
    public boolean isDeletedBySender = false;

    // Idea #3: Ghost Read (true only when user explicitly marks read or replies)
    public boolean isRead = false;

    // Rolling notification dismissal tracking
    public boolean isDismissed = false;

    // Coalesced by Cooling Time
    public boolean wasCoalesced = false;

    // Cached image/sticker path (FileProvider) & compact Base64 thumbnail for Watch
    public String mediaPath = "";
    public String mediaBase64 = "";

    // Voice note detection
    public boolean hasVoiceNote = false;
    public String voiceNoteUri = "";

    public ChatMessageEntity() {
    }
}
