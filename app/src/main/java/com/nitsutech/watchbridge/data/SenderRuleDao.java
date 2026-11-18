package com.nitsutech.watchbridge.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SenderRuleDao {

    @Query("SELECT * FROM sender_rules ORDER BY isVip DESC, senderOrChatName ASC")
    List<SenderRuleEntity> getAllSync();

    @Query("SELECT * FROM sender_rules WHERE id = :id LIMIT 1")
    SenderRuleEntity getByIdSync(long id);

    @Query("SELECT * FROM sender_rules WHERE LOWER(senderOrChatName) = LOWER(:name) LIMIT 1")
    SenderRuleEntity getExactByNameSync(String name);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(SenderRuleEntity rule);

    @Delete
    void delete(SenderRuleEntity rule);

    @Query("DELETE FROM sender_rules WHERE id = :id")
    void deleteById(long id);
}
