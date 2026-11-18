package com.nitsutech.watchbridge.wear;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

public class WatchPrefsManager {

    public static final int PROFILE_NORMAL = 0;
    public static final int PROFILE_REPEAT = 1;
    public static final int PROFILE_WORK = 2;
    public static final int PROFILE_AFTER_HOURS = 3;

    private static final String PREF_NAME = "watchbridge_wear_prefs";
    private static final String KEY_STATE_JSON = "cached_state_json";
    private static final String KEY_LAST_ALERT_JSON = "last_alert_json";
    private static final String KEY_LAST_FALLBACK_TTS = "last_audio_fallback_tts";
    private static final String KEY_PLAYBACK_SPEED = "playback_speed";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public WatchPrefsManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveStateJson(String json) {
        if (json != null) {
            prefs.edit().putString(KEY_STATE_JSON, json).apply();
        }
    }

    public WearProtocol.WatchStatePayload getState() {
        String raw = prefs.getString(KEY_STATE_JSON, "");
        if (!raw.isEmpty()) {
            try {
                WearProtocol.WatchStatePayload parsed = gson.fromJson(raw, WearProtocol.WatchStatePayload.class);
                if (parsed != null) return parsed;
            } catch (Exception ignored) {
            }
        }
        return new WearProtocol.WatchStatePayload();
    }

    public void saveLastAlert(WearProtocol.AlertTickPayload tick) {
        if (tick != null) {
            prefs.edit().putString(KEY_LAST_ALERT_JSON, gson.toJson(tick)).apply();
        }
    }

    public WearProtocol.AlertTickPayload getLastAlert() {
        String raw = prefs.getString(KEY_LAST_ALERT_JSON, "");
        if (!raw.isEmpty()) {
            try {
                return gson.fromJson(raw, WearProtocol.AlertTickPayload.class);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public void setLastFallbackTts(String text) {
        prefs.edit().putString(KEY_LAST_FALLBACK_TTS, text == null ? "" : text).apply();
    }

    public String getLastFallbackTts() {
        return prefs.getString(KEY_LAST_FALLBACK_TTS, "");
    }

    public float getPlaybackSpeed() {
        return prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f);
    }

    public void setPlaybackSpeed(float speed) {
        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply();
    }
}
