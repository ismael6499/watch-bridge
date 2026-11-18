package com.nitsutech.watchbridge.wear;

import android.content.Context;
import android.media.AudioAttributes;
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
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(speed);
            mediaPlayer.setPlaybackParams(params);
            mediaPlayer.setOnCompletionListener(mp -> {
                stopPlayback();
                if (onComplete != null) HANDLER.post(onComplete);
            });
            mediaPlayer.start();
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

    public static void speakTts(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wb_tts");
            return;
        }
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                int res = tts.setLanguage(Locale.getDefault());
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wb_tts");
            }
        });
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
