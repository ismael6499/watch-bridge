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
            if (prefs.isAutoAfterHoursEnabled()) {
                if (prefs.getActiveProfile() == PrefsManager.PROFILE_AFTER_HOURS) {
                    ProfileController.setProfile(context, PrefsManager.PROFILE_NORMAL, 0);
                }
            }
            scheduleAutoAfterHoursAlarms(context, prefs);
        }
    }

    public static final int DAYS_ALL = 127;       // Sun..Sat (bits 0..6)
    public static final int DAYS_WEEKDAYS = 62;   // Mon..Fri (bits 1..5)

    private static boolean shouldRunDay(int dayOfWeek, boolean weekdaysOnly) {
        if (!weekdaysOnly) return true;
        return dayOfWeek >= Calendar.MONDAY && dayOfWeek <= Calendar.FRIDAY;
    }

    private static boolean matchesDaysMask(int dayOfWeek, int daysMask) {
        int bit = 1 << (dayOfWeek - 1);
        return (daysMask & bit) != 0;
    }

    private boolean shouldRunToday(PrefsManager prefs) {
        return shouldRunDay(Calendar.getInstance().get(Calendar.DAY_OF_WEEK), prefs.isAfterHoursWeekdaysOnly());
    }

    public static boolean isWithinDaysWindow(
            int nowDow, int nowMinutesOfDay,
            int startHour, int startMin,
            int endHour, int endMin,
            int daysMask
    ) {
        if (daysMask == 0) return false;
        int startTotal = startHour * 60 + startMin;
        int endTotal = endHour * 60 + endMin;
        if (startTotal == endTotal) {
            // Equal start/end means all 24h for selected days
            return matchesDaysMask(nowDow, daysMask);
        }
        if (startTotal < endTotal) {
            return matchesDaysMask(nowDow, daysMask)
                    && nowMinutesOfDay >= startTotal
                    && nowMinutesOfDay < endTotal;
        }
        if (nowMinutesOfDay >= startTotal && matchesDaysMask(nowDow, daysMask)) {
            return true;
        }
        int prevDow = nowDow == Calendar.SUNDAY ? Calendar.SATURDAY : nowDow - 1;
        return nowMinutesOfDay < endTotal && matchesDaysMask(prevDow, daysMask);
    }

    public static boolean isSilentRuleActiveNow(
            int silentMode, int startHour, int startMin, int endHour, int endMin, int daysMask,
            int nowDow, int nowMinutesOfDay
    ) {
        if (silentMode == 1) return true;
        if (silentMode == 2) {
            return isWithinDaysWindow(nowDow, nowMinutesOfDay, startHour, startMin, endHour, endMin, daysMask);
        }
        return false;
    }

    public static boolean isSilentRuleActiveNow(
            int silentMode, int startHour, int startMin, int endHour, int endMin, int daysMask,
            long nowMs
    ) {
        if (silentMode == 1) return true;
        if (silentMode != 2) return false;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return isWithinDaysWindow(cal.get(Calendar.DAY_OF_WEEK), nowMin, startHour, startMin, endHour, endMin, daysMask);
    }

    public static boolean isWithinAfterHoursWindow(
            int nowDow, int nowMinutesOfDay,
            int startHour, int startMin,
            int endHour, int endMin,
            boolean weekdaysOnly
    ) {
        int startTotal = startHour * 60 + startMin;
        int endTotal = endHour * 60 + endMin;
        if (startTotal == endTotal) {
            return false;
        }
        return isWithinDaysWindow(
                nowDow, nowMinutesOfDay, startHour, startMin, endHour, endMin,
                weekdaysOnly ? DAYS_WEEKDAYS : DAYS_ALL
        );
    }

    public static void evaluateNowAndReschedule(Context context, PrefsManager prefs) {
        scheduleAutoAfterHoursAlarms(context, prefs);
        if (!prefs.isAutoAfterHoursEnabled()) {
            return;
        }
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean activeNow = isWithinAfterHoursWindow(
                now.get(Calendar.DAY_OF_WEEK),
                nowMin,
                prefs.getAfterHoursStartHour(),
                prefs.getAfterHoursStartMin(),
                prefs.getAfterHoursEndHour(),
                prefs.getAfterHoursEndMin(),
                prefs.isAfterHoursWeekdaysOnly()
        );
        int curProfile = prefs.getActiveProfile();
        if (activeNow && (curProfile == PrefsManager.PROFILE_NORMAL || curProfile == PrefsManager.PROFILE_WORK)) {
            ProfileController.setProfile(context, PrefsManager.PROFILE_AFTER_HOURS, 0);
        } else if (!activeNow && curProfile == PrefsManager.PROFILE_AFTER_HOURS) {
            ProfileController.setProfile(context, PrefsManager.PROFILE_NORMAL, 0);
        }
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
