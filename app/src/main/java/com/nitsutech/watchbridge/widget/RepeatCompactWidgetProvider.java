package com.nitsutech.watchbridge.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class RepeatCompactWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            BridgeWidgetUpdater.updateCompactWidget(context, appWidgetManager, id);
        }
    }
}
