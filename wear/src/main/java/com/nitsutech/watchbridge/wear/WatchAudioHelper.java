package com.nitsutech.watchbridge.wear;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.util.Locale;

/**
 * Plays WhatsApp voice note audio files on the Galaxy Watch 8 Classic built-in speaker
 * at 1.0x / 1.5x / 2.0x speed, and drives Text-To-Speech readout (Idea #2) + Escalation chime (Idea #8).
 */
public class WatchAudioHelper {

    private static MediaPlayer mediaPlayer;
    private static TextToSpeech tts;
    private static boolean ttsReady = false;
    private static float currentSpeed = 1.0f;
    private static volatile Ringtone activeCallRingtone;
    private static volatile boolean callRingtonePlaying = false;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    public static void playVoiceFile(Context context, File file, float speed, Runnable onComplete) {
        stopPlayback();
        currentSpeed = speed;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                stopPlayback();
                if (onComplete != null) HANDLER.post(onComplete);
            });
            mediaPlayer.start();
            if (Math.abs(speed - 1.0f) > 0.05f) {
                try {
                    PlaybackParams params = mediaPlayer.getPlaybackParams();
                    params.setSpeed(speed);
                    mediaPlayer.setPlaybackParams(params);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            stopPlayback();
            if (onComplete != null) HANDLER.post(onComplete);
        }
    }

    public static void updateSpeed(float speed) {
        currentSpeed = speed;
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(speed);
                mediaPlayer.setPlaybackParams(params);
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean isPlayingVoice() {
        try {
            return mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public static void stopPlayback() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception ignored) {
        }
        mediaPlayer = null;
    }

    public static void stopTts() {
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {
        }
    }

    public static void stopAllAudio() {
        stopPlayback();
        stopTts();
    }

    public static int getMediaVolumePct(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return 50;
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.round((cur * 100f) / max);
        } catch (Exception e) {
            return 50;
        }
    }

    public static int adjustMediaVolume(Context context, int delta) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return 50;
            int dir = delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
            return getMediaVolumePct(context);
        } catch (Exception e) {
            return getMediaVolumePct(context);
        }
    }

    public static void speakTts(Context context, String text) {
        speakTts(context, text, "es");
    }

    public static void speakTts(Context context, String text, String langCode) {
        if (text == null || text.trim().isEmpty()) return;
        Locale target = resolveTtsLocale(langCode);
        if (tts != null && ttsReady) {
            applyTtsSettings(tts, target);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wb_tts");
            return;
        }
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                applyTtsSettings(tts, target);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wb_tts");
            }
        });
    }

    private static Locale resolveTtsLocale(String langCode) {
        if ("en".equals(langCode)) return Locale.US;
        if ("auto".equals(langCode)) return Locale.getDefault();
        return new Locale("es", "AR");
    }

    private static void applyTtsSettings(TextToSpeech engine, Locale preferred) {
        try {
            engine.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            int res = engine.setLanguage(preferred);
            if ((res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED)
                    && "es".equals(preferred.getLanguage())) {
                res = engine.setLanguage(new Locale("es"));
            }
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault());
            }
        } catch (Exception ignored) {
        }
    }

    public static void startCallRingtone(Context context) {
        stopCallRingtone();
        callRingtonePlaying = true;
        final Context appCtx = context.getApplicationContext();
        Runnable ringTask = new Runnable() {
            @Override
            public void run() {
                if (!callRingtonePlaying) return;
                try {
                    if (activeCallRingtone == null) {
                        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                        if (uri == null) {
                            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                        }
                        if (uri != null) {
                            activeCallRingtone = RingtoneManager.getRingtone(appCtx, uri);
                            if (activeCallRingtone != null) {
                                activeCallRingtone.setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build());
                            }
                        }
                    }
                    if (activeCallRingtone != null && !activeCallRingtone.isPlaying()) {
                        activeCallRingtone.play();
                    }
                } catch (Exception ignored) {
                }
                HANDLER.postDelayed(this, 1800L);
            }
        };
        HANDLER.post(ringTask);
    }

    public static void stopCallRingtone() {
        callRingtonePlaying = false;
        HANDLER.removeCallbacksAndMessages(null);
        try {
            if (activeCallRingtone != null && activeCallRingtone.isPlaying()) {
                activeCallRingtone.stop();
            }
        } catch (Exception ignored) {
        }
        activeCallRingtone = null;
    }

    public static void playEscalationChime(Context context) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) return;
            Ringtone r = RingtoneManager.getRingtone(context.getApplicationContext(), uri);
            if (r != null) {
                r.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                r.play();
                HANDLER.postDelayed(() -> {
                    try {
                        if (r.isPlaying()) r.stop();
                    } catch (Exception ignored) {
                    }
                }, 1500L);
            }
        } catch (Exception ignored) {
        }
    }
}
