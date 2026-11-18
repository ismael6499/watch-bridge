package com.nitsutech.watchbridge.complication;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.ui.WearMainActivity;
import com.nitsutech.watchbridge.wear.WatchPrefsManager;
import com.nitsutech.watchbridge.wear.WearProtocol;

/**
 * Idea #11: Watch Face Complication for Galaxy Watch 8 Classic.
 * Shows current active WatchBridge profile (RPT / ON / WRK / AH) right on the watch face.
 */
public class WatchBridgeComplicationService extends ComplicationDataSourceService {

    @Override
    public void onComplicationRequest(@NonNull ComplicationRequest request,
                                      @NonNull ComplicationRequestListener listener) {
        if (request.getComplicationType() != ComplicationType.SHORT_TEXT) {
            try {
                listener.onComplicationData(null);
            } catch (RemoteException ignored) {
            }
            return;
        }
        WatchPrefsManager prefs = new WatchPrefsManager(this);
        WearProtocol.WatchStatePayload state = prefs.getState();

        String shortLabel;
        switch (state.activeProfile) {
            case WatchPrefsManager.PROFILE_REPEAT:
                shortLabel = getString(R.string.comp_repeat);
                break;
            case WatchPrefsManager.PROFILE_WORK:
                shortLabel = getString(R.string.comp_work);
                break;
            case WatchPrefsManager.PROFILE_AFTER_HOURS:
                shortLabel = getString(R.string.comp_after_hours);
                break;
            default:
                shortLabel = getString(R.string.comp_normal);
                break;
        }

        Intent tapIntent = new Intent(this, WearMainActivity.class);
        tapIntent.putExtra(WearMainActivity.EXTRA_TOGGLE_REPEAT, true);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tapPi = PendingIntent.getActivity(
                this, 3101, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        ShortTextComplicationData data = new ShortTextComplicationData.Builder(
                new PlainComplicationText.Builder(shortLabel).build(),
                new PlainComplicationText.Builder(getString(R.string.app_name)).build()
        )
                .setMonochromaticImage(
                        new MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_watch_logo)).build()
                )
                .setTapAction(tapPi)
                .build();

        try {
            listener.onComplicationData(data);
        } catch (RemoteException ignored) {
        }
    }

    @Nullable
    @Override
    public ComplicationData getPreviewData(@NonNull ComplicationType type) {
        if (type != ComplicationType.SHORT_TEXT) return null;
        return new ShortTextComplicationData.Builder(
                new PlainComplicationText.Builder(getString(R.string.comp_repeat)).build(),
                new PlainComplicationText.Builder(getString(R.string.app_name)).build()
        )
                .setMonochromaticImage(
                        new MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_watch_logo)).build()
                )
                .build();
    }
}
