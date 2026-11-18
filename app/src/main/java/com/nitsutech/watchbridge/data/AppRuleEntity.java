package com.nitsutech.watchbridge.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_rules")
public class AppRuleEntity {

    @PrimaryKey
    @NonNull
    public String packageName = "";

    public String appName = "";
    public boolean enabled = true;

    // 1-on-1 Direct Chats
    public boolean directEnabled = true;
    public boolean directRepeatEnabled = true;
    public int directRepeatIntervalSec = 12;
    public int directCooldownSec = 0;

    // Group Chats / Channels
    public boolean groupEnabled = true;
    public boolean groupRepeatEnabled = false;
    public int groupRepeatIntervalSec = 20;
    public int groupCooldownSec = 0;

    // 0 = Watch Only, 1 = Watch + Phone Notification/Vibrate, 2 = Watch + Loud Phone Alarm
    public int phoneSoundMode = 1;

    // 0 = Teams Double Pulse, 1 = WhatsApp Long-Short, 2 = Group Short, 3 = Urgent Escalating
    public int hapticPattern = 0;

    public AppRuleEntity() {
    }

    @Ignore
    public AppRuleEntity(@NonNull String packageName, String appName, boolean enabled,
                         boolean directEnabled, boolean directRepeatEnabled,
                         int directRepeatIntervalSec, int directCooldownSec,
                         boolean groupEnabled, boolean groupRepeatEnabled,
                         int groupRepeatIntervalSec, int groupCooldownSec,
                         int phoneSoundMode, int hapticPattern) {
        this.packageName = packageName;
        this.appName = appName;
        this.enabled = enabled;
        this.directEnabled = directEnabled;
        this.directRepeatEnabled = directRepeatEnabled;
        this.directRepeatIntervalSec = directRepeatIntervalSec;
        this.directCooldownSec = directCooldownSec;
        this.groupEnabled = groupEnabled;
        this.groupRepeatEnabled = groupRepeatEnabled;
        this.groupRepeatIntervalSec = groupRepeatIntervalSec;
        this.groupCooldownSec = groupCooldownSec;
        this.phoneSoundMode = phoneSoundMode;
        this.hapticPattern = hapticPattern;
    }
}
