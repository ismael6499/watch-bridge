package com.nitsutech.watchbridge;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.receiver.ScheduleAndTimerReceiver;

public class WatchBridgeApp extends Application {

    public static final String CHANNEL_PROXY_NORMAL = "watchbridge_proxy_normal";
    public static final String CHANNEL_PROXY_SILENT = "watchbridge_proxy_silent";
    public static final String CHANNEL_PROXY_REPEAT = "watchbridge_proxy_repeat";
    public static final String CHANNEL_PROXY_URGENT = "watchbridge_proxy_urgent";
    public static final String CHANNEL_SERVICE = "watchbridge_service_v2";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        PrefsManager prefs = new PrefsManager(this);
        AppDatabase.getInstance(this).ensureDefaultsAsync();
        ScheduleAndTimerReceiver.scheduleAutoAfterHoursAlarms(this, prefs);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        Uri defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes notifAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel normalChannel = new NotificationChannel(
                CHANNEL_PROXY_NORMAL,
                getString(R.string.channel_proxy_normal_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        normalChannel.setDescription(getString(R.string.channel_proxy_normal_desc));
        normalChannel.enableVibration(true);
        normalChannel.setVibrationPattern(new long[]{0, 220, 120, 220});
        normalChannel.setSound(defaultSound, notifAttr);

        NotificationChannel repeatChannel = new NotificationChannel(
                CHANNEL_PROXY_REPEAT,
                getString(R.string.channel_proxy_repeat_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        repeatChannel.setDescription(getString(R.string.channel_proxy_repeat_desc));
        repeatChannel.enableVibration(true);
        repeatChannel.setVibrationPattern(new long[]{0, 450, 180, 450, 180, 450});
        repeatChannel.setSound(defaultSound, notifAttr);

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes alarmAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel urgentChannel = new NotificationChannel(
                CHANNEL_PROXY_URGENT,
                getString(R.string.channel_proxy_urgent_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        urgentChannel.setDescription(getString(R.string.channel_proxy_urgent_desc));
        urgentChannel.enableVibration(true);
        urgentChannel.setVibrationPattern(new long[]{0, 600, 150, 600, 150, 600});
        urgentChannel.setSound(alarmSound != null ? alarmSound : defaultSound, alarmAttr);
        urgentChannel.setBypassDnd(true);

        NotificationChannel silentChannel = new NotificationChannel(
                CHANNEL_PROXY_SILENT,
                getString(R.string.channel_proxy_silent_name),
                NotificationManager.IMPORTANCE_LOW
        );
        silentChannel.setDescription(getString(R.string.channel_proxy_silent_desc));
        silentChannel.enableVibration(false);
        silentChannel.setSound(null, null);

        nm.deleteNotificationChannel("watchbridge_service");
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_MIN
        );
        serviceChannel.setDescription(getString(R.string.channel_service_desc));
        serviceChannel.setShowBadge(false);
        serviceChannel.setSound(null, null);
        serviceChannel.enableVibration(false);

        nm.createNotificationChannel(normalChannel);
        nm.createNotificationChannel(silentChannel);
        nm.createNotificationChannel(repeatChannel);
        nm.createNotificationChannel(urgentChannel);
        nm.createNotificationChannel(serviceChannel);
    }
}
