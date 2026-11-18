package com.nitsutech.watchbridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PrefsManager {

    public static final int PROFILE_NORMAL = 0;
    public static final int PROFILE_REPEAT = 1;
    public static final int PROFILE_WORK = 2;
    public static final int PROFILE_AFTER_HOURS = 3;

    private static final String PREF_NAME = "watchbridge_prefs";
    private static final String KEY_PROFILE = "active_profile";
    private static final String KEY_REPEAT_DURATION_MIN = "repeat_default_duration_min";
    private static final String KEY_REPEAT_END_TIME_MS = "repeat_end_time_ms";
    private static final String KEY_REPEAT_START_TIME_MS = "repeat_start_time_ms";
    private static final String KEY_MAX_REPEAT_MIN = "max_repeat_min";
    private static final String KEY_PROGRESSIVE_ESCALATION = "progressive_escalation";
    private static final String KEY_PHONE_IN_USE_SUPPRESSION = "phone_in_use_suppression";
    private static final String KEY_AUTO_TTS_REPEAT = "auto_tts_repeat";
    private static final String KEY_AFTER_HOURS_AUTO = "after_hours_auto";
    private static final String KEY_AFTER_HOURS_START_HOUR = "after_hours_start_hour";
    private static final String KEY_AFTER_HOURS_START_MIN = "after_hours_start_min";
    private static final String KEY_AFTER_HOURS_END_HOUR = "after_hours_end_hour";
    private static final String KEY_AFTER_HOURS_END_MIN = "after_hours_end_min";
    private static final String KEY_AFTER_HOURS_WEEKDAYS_ONLY = "after_hours_weekdays_only";
    private static final String KEY_AFTER_HOURS_MUTED_APPS = "after_hours_muted_apps_json";
    private static final String KEY_URGENT_KEYWORDS = "urgent_keywords";
    private static final String KEY_TEAMS_REPLIES = "teams_quick_replies";
    private static final String KEY_WA_REPLIES = "wa_quick_replies";
    private static final String KEY_VOICE_NOTES_TREE_URI = "voice_notes_tree_uri";
    private static final String KEY_MUTED_CHATS_JSON = "muted_chats_json";
    private static final String KEY_SNOOZED_CHATS_JSON = "snoozed_chats_json";
    private static final String KEY_CALL_BOOST = "call_boost_enabled";
    private static final String KEY_PHONE_BRIDGE_NOTIF = "phone_bridge_notif_enabled";
    private static final String KEY_REPEAT_POSTED_NOTIFS = "repeat_summary_notifs";
    private static final String KEY_REPEAT_BURSTS_ABSORBED = "repeat_summary_bursts";

    private static final String DEFAULT_KEYWORDS = "urgent, urgente, deploy, produccion, producción, caido, caído, llamame, call me";
    public static final String DEFAULT_TEAMS_REPLIES_ES = "Ahí lo reviso|Dame 10 minutos|Estoy en reunión, ya te respondo|Ok, gracias";
    public static final String DEFAULT_WA_REPLIES_ES = "En un ratito te respondo|Ya te llamo|Dale, genial|Ok!";
    public static final String DEFAULT_TEAMS_REPLIES_EN = "Checking it now|Give me 10 min|In a meeting, I'll reply soon|Ok, thanks";
    public static final String DEFAULT_WA_REPLIES_EN = "I'll reply in a bit|Calling you soon|Sounds good!|Ok!";
    private static final String DEFAULT_TEAMS_REPLIES = DEFAULT_TEAMS_REPLIES_ES;
    private static final String DEFAULT_WA_REPLIES = DEFAULT_WA_REPLIES_ES;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public PrefsManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getActiveProfile() {
        return prefs.getInt(KEY_PROFILE, PROFILE_NORMAL);
    }

    public void setActiveProfile(int profile) {
        prefs.edit().putInt(KEY_PROFILE, profile).apply();
    }

    public boolean isRepeatMode() {
        return getActiveProfile() == PROFILE_REPEAT;
    }

    public int getDefaultRepeatDurationMin() {
        return prefs.getInt(KEY_REPEAT_DURATION_MIN, 45);
    }

    public void setDefaultRepeatDurationMin(int minutes) {
        prefs.edit().putInt(KEY_REPEAT_DURATION_MIN, minutes).apply();
    }

    public long getRepeatEndTimeMs() {
        return prefs.getLong(KEY_REPEAT_END_TIME_MS, 0L);
    }

    public void setRepeatEndTimeMs(long endTimeMs) {
        prefs.edit().putLong(KEY_REPEAT_END_TIME_MS, endTimeMs).apply();
    }

    public long getRepeatStartTimeMs() {
        return prefs.getLong(KEY_REPEAT_START_TIME_MS, 0L);
    }

    public void setRepeatStartTimeMs(long startTimeMs) {
        prefs.edit().putLong(KEY_REPEAT_START_TIME_MS, startTimeMs).apply();
    }

    public int getMaxRepeatMinutes() {
        return prefs.getInt(KEY_MAX_REPEAT_MIN, 5);
    }

    public void setMaxRepeatMinutes(int minutes) {
        prefs.edit().putInt(KEY_MAX_REPEAT_MIN, minutes).apply();
    }

    public boolean isProgressiveEscalationEnabled() {
        return prefs.getBoolean(KEY_PROGRESSIVE_ESCALATION, true);
    }

    public void setProgressiveEscalationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PROGRESSIVE_ESCALATION, enabled).apply();
    }

    public boolean isPhoneInUseSuppressionEnabled() {
        return prefs.getBoolean(KEY_PHONE_IN_USE_SUPPRESSION, false);
    }

    public void setPhoneInUseSuppressionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PHONE_IN_USE_SUPPRESSION, enabled).apply();
    }

    public boolean isAutoTtsInRepeatEnabled() {
        return prefs.getBoolean(KEY_AUTO_TTS_REPEAT, false);
    }

    public void setAutoTtsInRepeatEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_TTS_REPEAT, enabled).apply();
    }

    public boolean isCallBoostEnabled() {
        return prefs.getBoolean(KEY_CALL_BOOST, true);
    }

    public void setCallBoostEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CALL_BOOST, enabled).apply();
    }

    public boolean isPhoneBridgeNotifEnabled() {
        return prefs.getBoolean(KEY_PHONE_BRIDGE_NOTIF, false);
    }

    public void setPhoneBridgeNotifEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PHONE_BRIDGE_NOTIF, enabled).apply();
    }

    public boolean isAutoAfterHoursEnabled() {
        return prefs.getBoolean(KEY_AFTER_HOURS_AUTO, false);
    }

    public void setAutoAfterHoursEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AFTER_HOURS_AUTO, enabled).apply();
    }

    public int getAfterHoursStartHour() {
        return prefs.getInt(KEY_AFTER_HOURS_START_HOUR, 18);
    }

    public int getAfterHoursStartMin() {
        return prefs.getInt(KEY_AFTER_HOURS_START_MIN, 0);
    }

    public void setAfterHoursStart(int hour, int minute) {
        prefs.edit()
                .putInt(KEY_AFTER_HOURS_START_HOUR, hour)
                .putInt(KEY_AFTER_HOURS_START_MIN, minute)
                .apply();
    }

    public int getAfterHoursEndHour() {
        return prefs.getInt(KEY_AFTER_HOURS_END_HOUR, 9);
    }

    public int getAfterHoursEndMin() {
        return prefs.getInt(KEY_AFTER_HOURS_END_MIN, 0);
    }

    public void setAfterHoursEnd(int hour, int minute) {
        prefs.edit()
                .putInt(KEY_AFTER_HOURS_END_HOUR, hour)
                .putInt(KEY_AFTER_HOURS_END_MIN, minute)
                .apply();
    }

    public boolean isAfterHoursWeekdaysOnly() {
        return prefs.getBoolean(KEY_AFTER_HOURS_WEEKDAYS_ONLY, true);
    }

    public void setAfterHoursWeekdaysOnly(boolean weekdaysOnly) {
        prefs.edit().putBoolean(KEY_AFTER_HOURS_WEEKDAYS_ONLY, weekdaysOnly).apply();
    }

    public Set<String> getAfterHoursMutedPackages() {
        if (!prefs.contains(KEY_AFTER_HOURS_MUTED_APPS)) {
            Set<String> def = new HashSet<>();
            def.add(AppDatabase.PKG_TEAMS);
            return def;
        }
        String json = prefs.getString(KEY_AFTER_HOURS_MUTED_APPS, "[]");
        try {
            Type type = new TypeToken<HashSet<String>>() {}.getType();
            Set<String> set = gson.fromJson(json, type);
            return set != null ? set : new HashSet<>();
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    public void setAfterHoursMutedPackages(Set<String> packages) {
        Set<String> clean = packages != null ? packages : new HashSet<>();
        prefs.edit().putString(KEY_AFTER_HOURS_MUTED_APPS, gson.toJson(clean)).apply();
    }

    public boolean isPackageMutedInAfterHours(String packageName) {
        return packageName != null && getAfterHoursMutedPackages().contains(packageName);
    }

    public String getUrgentKeywordsRaw() {
        return prefs.getString(KEY_URGENT_KEYWORDS, DEFAULT_KEYWORDS);
    }

    public void setUrgentKeywordsRaw(String raw) {
        prefs.edit().putString(KEY_URGENT_KEYWORDS, raw == null ? "" : raw).apply();
    }

    public List<String> getUrgentKeywordsList() {
        String raw = getUrgentKeywordsRaw();
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String part : raw.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    public String getTeamsQuickRepliesRaw() {
        return prefs.getString(KEY_TEAMS_REPLIES, DEFAULT_TEAMS_REPLIES);
    }

    public void setTeamsQuickRepliesRaw(String raw) {
        prefs.edit().putString(KEY_TEAMS_REPLIES, raw == null ? DEFAULT_TEAMS_REPLIES : raw).apply();
    }

    public String getWhatsAppQuickRepliesRaw() {
        return prefs.getString(KEY_WA_REPLIES, DEFAULT_WA_REPLIES);
    }

    public void setWhatsAppQuickRepliesRaw(String raw) {
        prefs.edit().putString(KEY_WA_REPLIES, raw == null ? DEFAULT_WA_REPLIES : raw).apply();
    }

    public String[] getQuickRepliesForPackage(String packageName) {
        String raw = AppDatabase.PKG_TEAMS.equals(packageName)
                ? getTeamsQuickRepliesRaw()
                : getWhatsAppQuickRepliesRaw();
        String[] parts = raw.split("\\|");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list.toArray(new String[0]);
    }

    public String getVoiceNotesTreeUri() {
        return prefs.getString(KEY_VOICE_NOTES_TREE_URI, "");
    }

    public void setVoiceNotesTreeUri(String uri) {
        prefs.edit().putString(KEY_VOICE_NOTES_TREE_URI, uri == null ? "" : uri).apply();
    }

    public void resetRepeatCounters() {
        prefs.edit()
                .putLong(KEY_REPEAT_START_TIME_MS, System.currentTimeMillis())
                .putInt(KEY_REPEAT_POSTED_NOTIFS, 0)
                .putInt(KEY_REPEAT_BURSTS_ABSORBED, 0)
                .apply();
    }

    public void incrementRepeatNotifs() {
        prefs.edit().putInt(KEY_REPEAT_POSTED_NOTIFS, prefs.getInt(KEY_REPEAT_POSTED_NOTIFS, 0) + 1).apply();
    }

    public void incrementRepeatBurstsAbsorbed() {
        prefs.edit().putInt(KEY_REPEAT_BURSTS_ABSORBED, prefs.getInt(KEY_REPEAT_BURSTS_ABSORBED, 0) + 1).apply();
    }

    public int getRepeatPostedNotifs() {
        return prefs.getInt(KEY_REPEAT_POSTED_NOTIFS, 0);
    }

    public int getRepeatBurstsAbsorbed() {
        return prefs.getInt(KEY_REPEAT_BURSTS_ABSORBED, 0);
    }

    public Map<String, Long> getMutedChats() {
        return readMap(KEY_MUTED_CHATS_JSON);
    }

    public void muteChatUntil(String conversationKey, long untilMs) {
        Map<String, Long> map = getMutedChats();
        map.put(conversationKey, untilMs);
        saveMap(KEY_MUTED_CHATS_JSON, map);
    }

    public void unmuteChat(String conversationKey) {
        Map<String, Long> map = getMutedChats();
        map.remove(conversationKey);
        saveMap(KEY_MUTED_CHATS_JSON, map);
    }

    public boolean isChatMuted(String conversationKey) {
        Map<String, Long> map = getMutedChats();
        Long until = map.get(conversationKey);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            map.remove(conversationKey);
            saveMap(KEY_MUTED_CHATS_JSON, map);
            return false;
        }
        return true;
    }

    public Map<String, Long> getSnoozedChats() {
        return readMap(KEY_SNOOZED_CHATS_JSON);
    }

    public void snoozeChatUntil(String conversationKey, long untilMs) {
        Map<String, Long> map = getSnoozedChats();
        map.put(conversationKey, untilMs);
        saveMap(KEY_SNOOZED_CHATS_JSON, map);
    }

    public void clearSnooze(String conversationKey) {
        Map<String, Long> map = getSnoozedChats();
        map.remove(conversationKey);
        saveMap(KEY_SNOOZED_CHATS_JSON, map);
    }

    private Map<String, Long> readMap(String key) {
        String json = prefs.getString(key, "{}");
        try {
            Type type = new TypeToken<HashMap<String, Long>>() {}.getType();
            Map<String, Long> map = gson.fromJson(json, type);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void saveMap(String key, Map<String, Long> map) {
        prefs.edit().putString(key, gson.toJson(map)).apply();
    }
}
