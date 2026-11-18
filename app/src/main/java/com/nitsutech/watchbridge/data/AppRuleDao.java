package com.nitsutech.watchbridge.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AppRuleDao {

    @Query("SELECT * FROM app_rules ORDER BY appName ASC")
    List<AppRuleEntity> getAllSync();

    @Query("SELECT * FROM app_rules WHERE packageName = :packageName LIMIT 1")
    AppRuleEntity getByPackageSync(String packageName);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AppRuleEntity rule);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertIfMissing(AppRuleEntity rule);

    @Delete
    void delete(AppRuleEntity rule);

    @Query("SELECT COUNT(*) FROM app_rules")
    int countSync();
}
