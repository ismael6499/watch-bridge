package com.nitsutech.watchbridge.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.receiver.ProxyActionReceiver;
import com.nitsutech.watchbridge.service.PersistentRepeatForegroundService;
import com.nitsutech.watchbridge.ui.MainActivity;

public class BridgeWidgetUpdater {

    public static void updateAllWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;

        int[] compactIds = mgr.getAppWidgetIds(new ComponentName(context, RepeatCompactWidgetProvider.class));
        for (int id : compactIds) {
            updateCompactWidget(context, mgr, id);
        }

        int[] controlIds = mgr.getAppWidgetIds(new ComponentName(context, BridgeControlWidgetProvider.class));
        for (int id : controlIds) {
            updateControlWidget(context, mgr, id);
        }
    }

    public static void updateCompactWidget(Context context, AppWidgetManager mgr, int appWidgetId) {
        PrefsManager prefs = new PrefsManager(context);
        boolean repeatActive = prefs.isRepeatMode();

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_repeat_compact);
        views.setTextViewText(R.id.tv_widget_compact_icon, repeatActive ? "🔁" : "🔔");
        views.setTextViewText(
                R.id.tv_widget_compact_label,
                repeatActive ? context.getString(R.string.widget_repeat_on) : context.getString(R.string.widget_repeat_off)
        );
        views.setInt(
                R.id.widget_compact_root,
                "setBackgroundResource",
                repeatActive ? R.drawable.bg_widget_repeat_on : R.drawable.bg_widget_repeat_off
        );

        Intent toggleIntent = new Intent(context, ProxyActionReceiver.class);
        toggleIntent.setAction(ProxyActionReceiver.ACTION_WIDGET_TOGGLE_REPEAT);
        PendingIntent togglePi = PendingIntent.getBroadcast(
                context, 7001, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        views.setOnClickPendingIntent(R.id.widget_compact_root, togglePi);
        mgr.updateAppWidget(appWidgetId, views);
    }

    public static void updateControlWidget(Context context, AppWidgetManager mgr, int appWidgetId) {
        PrefsManager prefs = new PrefsManager(context);
        int profile = prefs.getActiveProfile();
        int activeAlerts = PersistentRepeatForegroundService.getActiveSessionCount();

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_bridge_control);
        views.setTextViewText(R.id.tv_widget_profile, profileLabel(context, profile));
        String subText = buildSubText(context, prefs, profile, activeAlerts);
        views.setTextViewText(R.id.tv_widget_status, subText);
        views.setInt(
                R.id.widget_control_root,
                "setBackgroundResource",
                profile == PrefsManager.PROFILE_REPEAT ? R.drawable.bg_widget_repeat_on : R.drawable.bg_widget_repeat_off
        );

        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                context, 7010, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        views.setOnClickPendingIntent(R.id.tv_widget_profile, openPi);

        Intent repeatIntent = new Intent(context, ProxyActionReceiver.class);
        repeatIntent.setAction(ProxyActionReceiver.ACTION_WIDGET_TOGGLE_REPEAT);
        PendingIntent repeatPi = PendingIntent.getBroadcast(
                context, 7011, repeatIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        views.setOnClickPendingIntent(R.id.btn_widget_repeat_toggle, repeatPi);

        Intent modeIntent = new Intent(context, ProxyActionReceiver.class);
        modeIntent.setAction(ProxyActionReceiver.ACTION_WIDGET_CYCLE_PROFILE);
        PendingIntent modePi = PendingIntent.getBroadcast(
                context, 7012, modeIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        views.setOnClickPendingIntent(R.id.btn_widget_cycle_mode, modePi);

        Intent stopIntent = new Intent(context, ProxyActionReceiver.class);
        stopIntent.setAction(ProxyActionReceiver.ACTION_WIDGET_STOP_ALERTS);
        PendingIntent stopPi = PendingIntent.getBroadcast(
                context, 7013, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        views.setOnClickPendingIntent(R.id.btn_widget_stop_alerts, stopPi);

        mgr.updateAppWidget(appWidgetId, views);
    }

    private static String profileLabel(Context context, int profile) {
        switch (profile) {
            case PrefsManager.PROFILE_REPEAT:
                return context.getString(R.string.profile_repeat_badge);
            case PrefsManager.PROFILE_WORK:
                return context.getString(R.string.profile_work_badge);
            case PrefsManager.PROFILE_AFTER_HOURS:
                return context.getString(R.string.profile_after_hours_badge);
            default:
                return context.getString(R.string.profile_normal_badge);
        }
    }

    private static String buildSubText(Context context, PrefsManager prefs, int profile, int activeAlerts) {
        if (profile == PrefsManager.PROFILE_REPEAT && prefs.getRepeatEndTimeMs() > System.currentTimeMillis()) {
            long remMin = Math.max(1L, (prefs.getRepeatEndTimeMs() - System.currentTimeMillis()) / 60_000L);
            return context.getString(R.string.widget_status_repeat_timer, remMin, activeAlerts);
        }
        return context.getString(R.string.widget_status_alerts, activeAlerts);
    }
}
