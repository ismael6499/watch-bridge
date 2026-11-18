package com.nitsutech.watchbridge.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.service.ProfileController;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;

import java.util.Calendar;

/**
 * Handles Repeat Auto-Off timer expiration, Snooze 3m/5m wakeups (Idea #1),
 * and Auto After-Hours Schedule transitions (Idea #6).
 */
public class ScheduleAndTimerReceiver extends BroadcastReceiver {

    public static final String ACTION_REPEAT_AUTO_OFF = "com.nitsutech.watchbridge.ACTION_REPEAT_AUTO_OFF";
    public static final String ACTION_SNOOZE_WAKEUP = "com.nitsutech.watchbridge.ACTION_SNOOZE_WAKEUP";
    public static final String ACTION_AFTER_HOURS_START = "com.nitsutech.watchbridge.ACTION_AFTER_HOURS_START";
    public static final String ACTION_AFTER_HOURS_END = "com.nitsutech.watchbridge.ACTION_AFTER_HOURS_END";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        PrefsManager prefs = new PrefsManager(context);

        if (ACTION_REPEAT_AUTO_OFF.equals(action)) {
            if (prefs.isRepeatMode()) {
                ProfileController.setProfile(context, PrefsManager.PROFILE_NORMAL, 0);
            }
        } else if (ACTION_SNOOZE_WAKEUP.equals(action)) {
            String convKey = intent.getStringExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY);
            if (convKey != null) {
                prefs.clearSnooze(convKey);
                WearDataLayerManager.syncStateAsync(context);
            }
        } else if (ACTION_AFTER_HOURS_START.equals(action)) {
            if (prefs.isAutoAfterHoursEnabled() && shouldRunToday(prefs)) {
                if (prefs.getActiveProfile() == PrefsManager.PROFILE_NORMAL
                        || prefs.getActiveProfile() == PrefsManager.PROFILE_WORK) {
                    ProfileController.setProfile(context, PrefsManager.PROFILE_AFTER_HOURS, 0);
                }
            }
            scheduleAutoAfterHoursAlarms(context, prefs);
        } else if (ACTION_AFTER_HOURS_END.equals(action)) {
            if (prefs.isAutoAfterHoursEnabled() && shouldRunToday(prefs)) {
                if (prefs.getActiveProfile() == PrefsManager.PROFILE_AFTER_HOURS) {
                    ProfileController.setProfile(context, PrefsManager.PROFILE_NORMAL, 0);
                }
            }
            scheduleAutoAfterHoursAlarms(context, prefs);
        }
    }

    private boolean shouldRunToday(PrefsManager prefs) {
        if (!prefs.isAfterHoursWeekdaysOnly()) return true;
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return dow >= Calendar.MONDAY && dow <= Calendar.FRIDAY;
    }

    public static void scheduleRepeatAutoOff(Context context, long triggerAtMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ScheduleAndTimerReceiver.class);
        i.setAction(ACTION_REPEAT_AUTO_OFF);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 9001, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        scheduleExactOrFallback(am, triggerAtMs, pi);
    }

    public static void cancelRepeatAutoOff(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ScheduleAndTimerReceiver.class);
        i.setAction(ACTION_REPEAT_AUTO_OFF);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 9001, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE
        );
        if (pi != null) {
            am.cancel(pi);
        }
    }

    public static void scheduleSnoozeWakeup(Context context, String convKey, long triggerAtMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ScheduleAndTimerReceiver.class);
        i.setAction(ACTION_SNOOZE_WAKEUP);
        i.putExtra(ProxyActionReceiver.EXTRA_CONVERSATION_KEY, convKey);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, Math.abs(convKey.hashCode()) % 5000 + 9100, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        scheduleExactOrFallback(am, triggerAtMs, pi);
    }

    public static void scheduleAutoAfterHoursAlarms(Context context, PrefsManager prefs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent startI = new Intent(context, ScheduleAndTimerReceiver.class);
        startI.setAction(ACTION_AFTER_HOURS_START);
        PendingIntent startPi = PendingIntent.getBroadcast(
                context, 9201, startI, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent endI = new Intent(context, ScheduleAndTimerReceiver.class);
        endI.setAction(ACTION_AFTER_HOURS_END);
        PendingIntent endPi = PendingIntent.getBroadcast(
                context, 9202, endI, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        if (!prefs.isAutoAfterHoursEnabled()) {
            am.cancel(startPi);
            am.cancel(endPi);
            return;
        }

        long nextStart = nextTimeTodayOrTomorrow(prefs.getAfterHoursStartHour(), prefs.getAfterHoursStartMin());
        long nextEnd = nextTimeTodayOrTomorrow(prefs.getAfterHoursEndHour(), prefs.getAfterHoursEndMin());
        scheduleExactOrFallback(am, nextStart, startPi);
        scheduleExactOrFallback(am, nextEnd, endPi);
    }

    private static long nextTimeTodayOrTomorrow(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 5000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    private static void scheduleExactOrFallback(AlarmManager am, long triggerAtMs, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
        }
    }
}
