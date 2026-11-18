package com.nitsutech.watchbridge.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ChatMessageEntity msg);

    @Update
    void update(ChatMessageEntity msg);

    @Query("SELECT * FROM chat_messages WHERE conversationKey = :conversationKey AND isDismissed = 0 ORDER BY timestamp ASC LIMIT 20")
    List<ChatMessageEntity> getActiveThreadSync(String conversationKey);

    @Query("SELECT * FROM chat_messages WHERE conversationKey = :conversationKey ORDER BY timestamp DESC LIMIT 25")
    List<ChatMessageEntity> getFullConversationHistorySync(String conversationKey);

    @Query("SELECT * FROM chat_messages WHERE conversationKey = :conversationKey AND isOutgoing = 0 AND isDeletedBySender = 0 ORDER BY timestamp DESC LIMIT 1")
    ChatMessageEntity getLatestIncomingSync(String conversationKey);

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT 120")
    List<ChatMessageEntity> getRecentAllSync();

    @Query("SELECT * FROM chat_messages WHERE isDeletedBySender = 1 ORDER BY timestamp DESC LIMIT 100")
    List<ChatMessageEntity> getDeletedVaultSync();

    @Query("SELECT * FROM chat_messages WHERE wasCoalesced = 1 ORDER BY timestamp DESC LIMIT 100")
    List<ChatMessageEntity> getCoalescedSync();

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    ChatMessageEntity getByIdSync(long id);

    @Query("UPDATE chat_messages SET isDismissed = 1 WHERE conversationKey = :conversationKey")
    void dismissConversation(String conversationKey);

    @Query("UPDATE chat_messages SET isDismissed = 1 WHERE id = :id")
    void dismissMessageById(long id);

    @Query("UPDATE chat_messages SET isRead = 1, isDismissed = 1 WHERE conversationKey = :conversationKey")
    void markConversationRead(String conversationKey);

    @Query("DELETE FROM chat_messages WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM chat_messages WHERE isDeletedBySender = 1")
    void deleteAllDeletedVault();

    @Query("DELETE FROM chat_messages")
    void deleteAllMessages();

    @Query("SELECT * FROM chat_messages WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    List<ChatMessageEntity> getMessagesSinceSync(long sinceMs);
}
