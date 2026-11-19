package com.nitsutech.watchbridge.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sender_rules")
public class SenderRuleEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String senderOrChatName = "";

    // "*" = any app, or packageName (e.g. com.whatsapp, com.microsoft.teams)
    public String packageFilter = "*";

    // 0 = Both, 1 = Direct 1-on-1 Only, 2 = Group Only
    public int chatTypeFilter = 0;

    // 0 = Repeat Alert, 1 = Notify Once (No Repeat), 2 = Mute / Ignore
    public int actionMode = 0;

    public int repeatIntervalSec = 10;
    public int cooldownSec = 0;

    // Idea #15: VIP overrides After-Hours, Group Mutes, and Cooldowns
    public boolean isVip = false;
    public boolean phoneAlarmOverride = false;

    // Watch Silent Mode: 0 = Follow App / Off, 1 = All Day Silent, 2 = Scheduled Silent, 3 = Never Silence (Override App)
    public int silentWatchMode = 0;
    public int silentStartHour = 9;
    public int silentStartMin = 0;
    public int silentEndHour = 18;
    public int silentEndMin = 0;
    public int silentDaysMask = 127; // Bitmask (Calendar.DAY_OF_WEEK - 1): 127 = All 7 days

    public SenderRuleEntity() {
    }
}
