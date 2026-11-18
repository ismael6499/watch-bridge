package com.nitsutech.watchbridge.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.WatchBridgeApp;
import com.nitsutech.watchbridge.receiver.ProxyActionReceiver;
import com.nitsutech.watchbridge.ui.FindPhoneActivity;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;

public class AlertAudioHelper {

    private static final int FIND_PHONE_NOTIF_ID = 9550;
    private static volatile Ringtone activeFindPhoneRingtone;
    private static volatile boolean isRingingFindPhone = false;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static boolean isFindPhoneRinging() {
        return isRingingFindPhone;
    }

    public static void playPhoneAlert(Context context, int phoneSoundMode, boolean escalated) {
        if (phoneSoundMode == 0 && !escalated) {
            return; // Watch Only
        }
        vibratePhone(context, escalated);

        if (phoneSoundMode >= 2 || escalated) {
            playShortTone(context, RingtoneManager.TYPE_ALARM, 1800L);
        } else {
            playShortTone(context, RingtoneManager.TYPE_NOTIFICATION, 1200L);
        }
    }

    public static void vibratePhone(Context context, boolean strong) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;

            long[] pattern = strong
                    ? new long[]{0, 450, 140, 450, 140, 550}
                    : new long[]{0, 220, 100, 220};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) {
        }
    }

    public static void playShortTone(Context context, int ringtoneType, long stopAfterMs) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(ringtoneType);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (uri == null) return;
            Ringtone r = RingtoneManager.getRingtone(context.getApplicationContext(), uri);
            if (r == null) return;
            int usage = ringtoneType == RingtoneManager.TYPE_ALARM
                    ? AudioAttributes.USAGE_ALARM
                    : AudioAttributes.USAGE_NOTIFICATION;
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.play();
            MAIN_HANDLER.postDelayed(() -> {
                try {
                    if (r.isPlaying()) r.stop();
                } catch (Exception ignored) {
                }
            }, stopAfterMs);
        } catch (Exception ignored) {
        }
    }

    public static void triggerFindMyPhoneRing(Context context) {
        final Context appCtx = context.getApplicationContext();
        try {
            stopFindMyPhoneRing(appCtx);
            isRingingFindPhone = true;

            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            activeFindPhoneRingtone = RingtoneManager.getRingtone(appCtx, uri);
            if (activeFindPhoneRingtone != null) {
                activeFindPhoneRingtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                activeFindPhoneRingtone.play();
            }
            vibratePhone(appCtx, true);
            showFindPhoneNotificationAndFullScreen(appCtx);
            WearDataLayerManager.syncStateAsync(appCtx);
            MAIN_HANDLER.postDelayed(() -> stopFindMyPhoneRing(appCtx), 25000L);
        } catch (Exception ignored) {
        }
    }

    public static void stopFindMyPhoneRing(Context context) {
        boolean wasRinging = isRingingFindPhone;
        isRingingFindPhone = false;
        MAIN_HANDLER.removeCallbacksAndMessages(null);
        try {
            if (activeFindPhoneRingtone != null && activeFindPhoneRingtone.isPlaying()) {
                activeFindPhoneRingtone.stop();
            }
        } catch (Exception ignored) {
        }
        activeFindPhoneRingtone = null;

        if (context != null) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(FIND_PHONE_NOTIF_ID);
            }
            context.sendBroadcast(new Intent(FindPhoneActivity.ACTION_CLOSE_FIND_PHONE)
                    .setPackage(context.getPackageName()));
            if (wasRinging) {
                WearDataLayerManager.syncStateAsync(context);
            }
        }
    }

    private static void showFindPhoneNotificationAndFullScreen(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent fullScreenIntent = new Intent(context, FindPhoneActivity.class);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreenPi = PendingIntent.getActivity(
                context, 9551, fullScreenIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(context, ProxyActionReceiver.class);
        stopIntent.setAction(ProxyActionReceiver.ACTION_STOP_FIND_PHONE);
        PendingIntent stopPi = PendingIntent.getBroadcast(
                context, 9552, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification n = new NotificationCompat.Builder(context, WatchBridgeApp.CHANNEL_PROXY_URGENT)
                .setSmallIcon(R.drawable.ic_watch_bridge)
                .setContentTitle(context.getString(R.string.find_phone_title))
                .setContentText(context.getString(R.string.find_phone_subtitle))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .addAction(R.drawable.ic_stop, context.getString(R.string.find_phone_stop_btn), stopPi)
                .build();
        nm.notify(FIND_PHONE_NOTIF_ID, n);

        // Also attempt direct launch if screen is already unlocked/interactive
        try {
            context.startActivity(fullScreenIntent);
        } catch (Exception ignored) {
        }
    }
}
