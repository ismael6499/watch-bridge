package com.nitsutech.watchbridge.tile;

import android.content.ComponentName;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.complication.WatchBridgeComplicationService;
import com.nitsutech.watchbridge.ui.WearMainActivity;
import com.nitsutech.watchbridge.wear.WatchDataClient;
import com.nitsutech.watchbridge.wear.WatchPrefsManager;
import com.nitsutech.watchbridge.wear.WearProtocol;

/**
 * Idea #5: Native Wear OS Quick Tile on Galaxy Watch 8 Classic.
 * Supports 1-tap toggle of Repeat Mode, Teams, WhatsApp, Stop All Alerts, and opening the full app.
 */
public class WatchBridgeTileService extends TileService {

    private static final String RESOURCES_VERSION = "1";
    private static final String ID_TOGGLE_REPEAT = "id_toggle_repeat";
    private static final String ID_TOGGLE_TEAMS = "id_toggle_teams";
    private static final String ID_TOGGLE_WA = "id_toggle_wa";
    private static final String ID_STOP_ALL = "id_stop_all";

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest requestParams) {
        WatchPrefsManager prefs = new WatchPrefsManager(this);
        WearProtocol.WatchStatePayload state = prefs.getState();

        String clicked = requestParams.getCurrentState() != null
                ? requestParams.getCurrentState().getLastClickableId()
                : "";

        if (ID_TOGGLE_REPEAT.equals(clicked)) {
            int nextProfile = state.activeProfile == WatchPrefsManager.PROFILE_REPEAT
                    ? WatchPrefsManager.PROFILE_NORMAL
                    : WatchPrefsManager.PROFILE_REPEAT;
            state.activeProfile = nextProfile;
            WatchDataClient.setProfile(this, nextProfile);
            prefs.saveStateJson(new com.google.gson.Gson().toJson(state));
            requestComplicationRefresh();
        } else if (ID_TOGGLE_TEAMS.equals(clicked)) {
            toggleAppOptimistic(prefs, state, "com.microsoft.teams");
        } else if (ID_TOGGLE_WA.equals(clicked)) {
            toggleAppOptimistic(prefs, state, "com.whatsapp");
        } else if (ID_STOP_ALL.equals(clicked)) {
            state.activeAlertCount = 0;
            WatchDataClient.stopAllAlerts(this);
            prefs.saveStateJson(new com.google.gson.Gson().toJson(state));
        }

        boolean isRepeat = state.activeProfile == WatchPrefsManager.PROFILE_REPEAT;
        boolean teamsOn = isAppEnabled(state, "com.microsoft.teams", true);
        boolean waOn = isAppEnabled(state, "com.whatsapp", true);

        LayoutElementBuilders.Column root = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(buildTitleText(isRepeat))
                .addContent(spacer(6f))
                .addContent(new LayoutElementBuilders.Row.Builder()
                        .addContent(buildTileButton(
                                ID_TOGGLE_REPEAT,
                                isRepeat ? getString(R.string.tile_repeat_on) : getString(R.string.tile_repeat_off),
                                isRepeat ? 0xFF512DA8 : 0xFF263238
                        ))
                        .addContent(hSpacer(6f))
                        .addContent(buildTileButton(
                                ID_STOP_ALL,
                                getString(R.string.tile_stop),
                                0xFFB71C1C
                        ))
                        .build())
                .addContent(spacer(6f))
                .addContent(new LayoutElementBuilders.Row.Builder()
                        .addContent(buildTileButton(
                                ID_TOGGLE_TEAMS,
                                teamsOn ? getString(R.string.tile_teams_on) : getString(R.string.tile_teams_off),
                                teamsOn ? 0xFF4A148C : 0xFF37474F
                        ))
                        .addContent(hSpacer(6f))
                        .addContent(buildTileButton(
                                ID_TOGGLE_WA,
                                waOn ? getString(R.string.tile_wa_on) : getString(R.string.tile_wa_off),
                                waOn ? 0xFF1B5E20 : 0xFF37474F
                        ))
                        .build())
                .addContent(spacer(6f))
                .addContent(buildOpenAppChip())
                .build();

        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build();

        return Futures.immediateFuture(tile);
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest requestParams) {
        return Futures.immediateFuture(
                new ResourceBuilders.Resources.Builder()
                        .setVersion(RESOURCES_VERSION)
                        .build()
        );
    }

    private void toggleAppOptimistic(WatchPrefsManager prefs, WearProtocol.WatchStatePayload state, String pkg) {
        boolean next = !isAppEnabled(state, pkg, true);
        for (WearProtocol.AppRuleDto r : state.appRules) {
            if (pkg.equals(r.packageName)) {
                r.enabled = next;
                break;
            }
        }
        WatchDataClient.toggleApp(this, pkg, next);
        prefs.saveStateJson(new com.google.gson.Gson().toJson(state));
    }

    private boolean isAppEnabled(WearProtocol.WatchStatePayload state, String pkg, boolean fallback) {
        if (state.appRules == null) return fallback;
        for (WearProtocol.AppRuleDto r : state.appRules) {
            if (pkg.equals(r.packageName)) return r.enabled;
        }
        return fallback;
    }

    private LayoutElementBuilders.LayoutElement buildTitleText(boolean isRepeat) {
        String title = isRepeat ? getString(R.string.tile_header_repeat) : getString(R.string.app_name);
        return new LayoutElementBuilders.Text.Builder()
                .setText(title)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(14f))
                        .setColor(ColorBuilders.argb(isRepeat ? 0xFFFFD54F : 0xFF80DEEA))
                        .build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement buildTileButton(String id, String label, int bgColorArgb) {
        ModifiersBuilders.Clickable clickable = new ModifiersBuilders.Clickable.Builder()
                .setId(id)
                .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                .build();

        ModifiersBuilders.Modifiers mods = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(clickable)
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(bgColorArgb))
                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(18f))
                                .build())
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setStart(DimensionBuilders.dp(10f))
                        .setEnd(DimensionBuilders.dp(10f))
                        .setTop(DimensionBuilders.dp(8f))
                        .setBottom(DimensionBuilders.dp(8f))
                        .build())
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setModifiers(mods)
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(label)
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(12f))
                                .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                .build())
                        .build())
                .build();
    }

    private LayoutElementBuilders.LayoutElement buildOpenAppChip() {
        ActionBuilders.LaunchAction launchAction = new ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(getPackageName())
                        .setClassName(WearMainActivity.class.getName())
                        .build())
                .build();

        ModifiersBuilders.Modifiers mods = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId("open_app")
                        .setOnClick(launchAction)
                        .build())
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(0xFF1E293B))
                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(14f))
                                .build())
                        .build())
                .setPadding(new ModifiersBuilders.Padding.Builder()
                        .setStart(DimensionBuilders.dp(12f))
                        .setEnd(DimensionBuilders.dp(12f))
                        .setTop(DimensionBuilders.dp(6f))
                        .setBottom(DimensionBuilders.dp(6f))
                        .build())
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setModifiers(mods)
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(getString(R.string.tile_open_app))
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(11f))
                                .setColor(ColorBuilders.argb(0xFFB2EBF2))
                                .build())
                        .build())
                .build();
    }

    private LayoutElementBuilders.Spacer spacer(float dp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(dp))
                .build();
    }

    private LayoutElementBuilders.Spacer hSpacer(float dp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(dp))
                .build();
    }

    private void requestComplicationRefresh() {
        try {
            ComplicationDataSourceUpdateRequester.create(
                    this,
                    new ComponentName(this, WatchBridgeComplicationService.class)
            ).requestUpdateAll();
        } catch (Exception ignored) {
        }
    }
}
