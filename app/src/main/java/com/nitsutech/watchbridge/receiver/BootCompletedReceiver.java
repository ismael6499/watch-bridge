package com.nitsutech.watchbridge.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.service.PersistentRepeatForegroundService;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;
import com.nitsutech.watchbridge.widget.BridgeWidgetUpdater;

public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PrefsManager prefs = new PrefsManager(context);
        ScheduleAndTimerReceiver.scheduleAutoAfterHoursAlarms(context, prefs);
        if (prefs.isRepeatMode()) {
            long end = prefs.getRepeatEndTimeMs();
            if (end > 0 && System.currentTimeMillis() >= end) {
                prefs.setActiveProfile(PrefsManager.PROFILE_NORMAL);
                prefs.setRepeatEndTimeMs(0L);
            } else {
                PersistentRepeatForegroundService.startRepeatForeground(context);
                if (end > 0) {
                    ScheduleAndTimerReceiver.scheduleRepeatAutoOff(context, end);
                }
            }
        }
        BridgeWidgetUpdater.updateAllWidgets(context);
        WearDataLayerManager.syncStateAsync(context);
    }
}
