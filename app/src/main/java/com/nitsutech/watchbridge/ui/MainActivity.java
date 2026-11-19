package com.nitsutech.watchbridge.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.AppRuleEntity;
import com.nitsutech.watchbridge.data.ChatMessageEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.data.SenderRuleEntity;
import com.nitsutech.watchbridge.engine.ActionCache;
import com.nitsutech.watchbridge.receiver.ScheduleAndTimerReceiver;
import com.nitsutech.watchbridge.service.BridgeNotificationListenerService;
import com.nitsutech.watchbridge.service.PersistentRepeatForegroundService;
import com.nitsutech.watchbridge.service.ProfileController;
import com.nitsutech.watchbridge.wear.WearDataLayerManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_TAB = "open_tab";

    private PrefsManager prefs;
    private AppDatabase db;

    // Navigation & Back-Stack History
    private BottomNavigationView bottomNav;
    private View tabHome;
    private View tabApps;
    private View tabRules;
    private View tabVault;
    private final Deque<Integer> navBackStack = new ArrayDeque<>();
    private int currentTabId = R.id.nav_home;
    private boolean isRestoringBack = false;

    // Tab 1: Home / Hub
    private TextView tvWatchConnStatus;
    private TextView tvActiveProfileStatus;
    private TextView tvPermListenerStatus;
    private TextView tvPermPostStatus;
    private TextView tvPermDndStatus;
    private TextView tvPermBatteryStatus;
    private TextView tvPermVoiceFolderStatus;
    private TextView tvActiveAlertsCount;
    private TextView tvSessionBriefing;
    private ChipGroup cgRepeatDuration;

    // Tab 2: Apps
    private AppRulesAdapter appRulesAdapter;

    // Tab 3: Sender Rules & Keywords
    private SenderRulesAdapter senderRulesAdapter;
    private EditText etUrgentKeywords;
    private EditText etTeamsReplies;
    private EditText etWaReplies;

    // Tab 4: Vault, History & Settings
    private VaultHistoryAdapter vaultAdapter;
    private ChipGroup cgVaultFilter;
    private int currentVaultFilter = 0; // 0 = All, 1 = Deleted Vault, 2 = Coalesced
    private SwitchMaterial swRepeatAllModes;
    private SwitchMaterial swProgressiveEscalation;
    private SwitchMaterial swPhoneInUseSuppression;
    private SwitchMaterial swSavePhoneInUseVault;
    private SwitchMaterial swRespectSourceMuted;
    private SwitchMaterial swWorkMuteGroups;
    private SwitchMaterial swSyncPhoneDismiss;
    private SwitchMaterial swAutoTtsRepeat;
    private SwitchMaterial swCallBoost;
    private SwitchMaterial swAutoAfterHours;
    private Button btnConfigAfterHours;
    private SwitchMaterial swPhoneBridgeNotif;
    private Spinner spMaxRepeatMinutes;
    private Spinner spEscalationAttempt;
    private Spinner spSnoozeMinutes;
    private Spinner spWatchTtsLang;

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> refreshUi());

    private final ActivityResultLauncher<Uri> voiceTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                        prefs.setVoiceNotesTreeUri(uri.toString());
                    } catch (Exception ignored) {
                    }
                    refreshUi();
                }
            });

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new PrefsManager(this);
        db = AppDatabase.getInstance(this);

        bindViews();
        setupNavigationAndBackStack();
        setupTab1Listeners();
        setupTab2Listeners();
        setupTab3Listeners();
        setupTab4Listeners();

        int openTab = getIntent() != null ? getIntent().getIntExtra(EXTRA_OPEN_TAB, 0) : 0;
        if (openTab == 1) selectTab(R.id.nav_apps, false);
        else if (openTab == 2) selectTab(R.id.nav_rules, false);
        else if (openTab == 3) selectTab(R.id.nav_vault, false);
        else selectTab(R.id.nav_home, false);

        requestBluetoothConnectIfNeeded();
    }

    private void requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PersistentRepeatForegroundService.ACTION_STATE_BROADCAST);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        refreshUi();
        checkConnectedWatch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void bindViews() {
        bottomNav = findViewById(R.id.bottom_navigation);
        tabHome = findViewById(R.id.tab_home_container);
        tabApps = findViewById(R.id.tab_apps_container);
        tabRules = findViewById(R.id.tab_rules_container);
        tabVault = findViewById(R.id.tab_vault_container);

        tvWatchConnStatus = findViewById(R.id.tv_watch_conn_status);
        tvActiveProfileStatus = findViewById(R.id.tv_active_profile_status);
        tvPermListenerStatus = findViewById(R.id.tv_perm_listener);
        tvPermPostStatus = findViewById(R.id.tv_perm_post_notif);
        tvPermDndStatus = findViewById(R.id.tv_perm_dnd);
        tvPermBatteryStatus = findViewById(R.id.tv_perm_battery);
        tvPermVoiceFolderStatus = findViewById(R.id.tv_perm_voice_folder);
        tvActiveAlertsCount = findViewById(R.id.tv_active_alerts_count);
        tvSessionBriefing = findViewById(R.id.tv_session_briefing);
        cgRepeatDuration = findViewById(R.id.cg_repeat_duration);

        etUrgentKeywords = findViewById(R.id.et_urgent_keywords);
        etTeamsReplies = findViewById(R.id.et_teams_replies);
        etWaReplies = findViewById(R.id.et_wa_replies);

        cgVaultFilter = findViewById(R.id.cg_vault_filter);
        swRepeatAllModes = findViewById(R.id.sw_repeat_all_modes);
        swProgressiveEscalation = findViewById(R.id.sw_progressive_escalation);
        swPhoneInUseSuppression = findViewById(R.id.sw_phone_in_use_suppression);
        swSavePhoneInUseVault = findViewById(R.id.sw_save_phone_in_use_vault);
        swRespectSourceMuted = findViewById(R.id.sw_respect_source_muted);
        swWorkMuteGroups = findViewById(R.id.sw_work_mute_groups);
        swSyncPhoneDismiss = findViewById(R.id.sw_sync_phone_dismiss);
        swAutoTtsRepeat = findViewById(R.id.sw_auto_tts_repeat);
        swCallBoost = findViewById(R.id.sw_call_boost);
        swAutoAfterHours = findViewById(R.id.sw_auto_after_hours);
        btnConfigAfterHours = findViewById(R.id.btn_config_after_hours);
        swPhoneBridgeNotif = findViewById(R.id.sw_phone_bridge_notif);
        spMaxRepeatMinutes = findViewById(R.id.sp_max_repeat_minutes);
        spEscalationAttempt = findViewById(R.id.sp_escalation_attempt);
        spSnoozeMinutes = findViewById(R.id.sp_snooze_minutes);
        spWatchTtsLang = findViewById(R.id.sp_watch_tts_lang);
    }

    private void setupNavigationAndBackStack() {
        bottomNav.setOnItemSelectedListener(item -> {
            selectTab(item.getItemId(), !isRestoringBack);
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentTabId == R.id.nav_vault && currentVaultFilter != 0) {
                    currentVaultFilter = 0;
                    cgVaultFilter.check(R.id.chip_vault_all);
                    loadVaultHistoryAsync();
                    return;
                }
                if (!navBackStack.isEmpty()) {
                    Integer prevTab = navBackStack.pop();
                    isRestoringBack = true;
                    bottomNav.setSelectedItemId(prevTab);
                    isRestoringBack = false;
                    return;
                }
                if (currentTabId != R.id.nav_home) {
                    isRestoringBack = true;
                    bottomNav.setSelectedItemId(R.id.nav_home);
                    isRestoringBack = false;
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void selectTab(int tabId, boolean pushHistory) {
        if (pushHistory && currentTabId != tabId) {
            if (navBackStack.isEmpty() || navBackStack.peek() != currentTabId) {
                navBackStack.push(currentTabId);
            }
        }
        currentTabId = tabId;
        tabHome.setVisibility(tabId == R.id.nav_home ? View.VISIBLE : View.GONE);
        tabApps.setVisibility(tabId == R.id.nav_apps ? View.VISIBLE : View.GONE);
        tabRules.setVisibility(tabId == R.id.nav_rules ? View.VISIBLE : View.GONE);
        tabVault.setVisibility(tabId == R.id.nav_vault ? View.VISIBLE : View.GONE);
    }

    private void setupTab1Listeners() {
        findViewById(R.id.btn_sync_watch_now).setOnClickListener(v -> {
            WearDataLayerManager.syncStateAsync(this);
            checkConnectedWatch();
            Toast.makeText(this, R.string.toast_sync_sent, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_profile_normal).setOnClickListener(v -> ProfileController.setProfile(this, PrefsManager.PROFILE_NORMAL, 0));
        findViewById(R.id.btn_profile_repeat).setOnClickListener(v -> ProfileController.setProfile(this, PrefsManager.PROFILE_REPEAT, prefs.getDefaultRepeatDurationMin()));
        findViewById(R.id.btn_profile_work).setOnClickListener(v -> ProfileController.setProfile(this, PrefsManager.PROFILE_WORK, 0));
        findViewById(R.id.btn_profile_after_hours).setOnClickListener(v -> ProfileController.setProfile(this, PrefsManager.PROFILE_AFTER_HOURS, 0));

        cgRepeatDuration.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            int mins = 45;
            if (id == R.id.chip_repeat_manual) mins = 0;
            else if (id == R.id.chip_repeat_30) mins = 30;
            else if (id == R.id.chip_repeat_45) mins = 45;
            else if (id == R.id.chip_repeat_60) mins = 60;
            else if (id == R.id.chip_repeat_90) mins = 90;
            prefs.setDefaultRepeatDurationMin(mins);
            if (prefs.isRepeatMode()) {
                ProfileController.setProfile(this, PrefsManager.PROFILE_REPEAT, mins);
            }
        });

        findViewById(R.id.btn_grant_listener).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btn_grant_post_notif).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        });

        findViewById(R.id.btn_grant_dnd).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        findViewById(R.id.btn_grant_battery).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        findViewById(R.id.btn_grant_voice_folder).setOnClickListener(v -> showVoiceAccessPicker());

        findViewById(R.id.btn_stop_all_alerts).setOnClickListener(v -> {
            PersistentRepeatForegroundService.stopAllSessions(this);
            Toast.makeText(this, R.string.toast_alerts_stopped, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_test_watch_alert).setOnClickListener(v -> {
            ProfileController.triggerTestAlert(this);
            Toast.makeText(this, R.string.toast_test_alert_sent, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_test_watch_call).setOnClickListener(v -> {
            ProfileController.triggerTestCall(this);
            Toast.makeText(this, R.string.toast_test_call_sent, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_test_replay_active).setOnClickListener(v ->
                BridgeNotificationListenerService.replayActiveNotificationsNow(this, sentCount ->
                        runOnUiThread(() -> {
                            if (sentCount < 0) {
                                Toast.makeText(this, R.string.toast_replay_no_listener, Toast.LENGTH_SHORT).show();
                            } else if (sentCount == 0) {
                                Toast.makeText(this, R.string.toast_replay_empty, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, getString(R.string.toast_replay_sent_fmt, sentCount), Toast.LENGTH_SHORT).show();
                            }
                            refreshUi();
                        })));
    }

    private void setupTab2Listeners() {
        RecyclerView rvApps = findViewById(R.id.rv_app_rules);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        appRulesAdapter = new AppRulesAdapter(new AppRulesAdapter.Listener() {
            @Override
            public void onToggleEnabled(AppRuleEntity rule, boolean enabled) {
                rule.enabled = enabled;
                db.executor().execute(() -> {
                    db.appRuleDao().upsert(rule);
                    WearDataLayerManager.syncStateBlocking(MainActivity.this);
                });
            }

            @Override
            public void onEditTimers(AppRuleEntity rule) {
                showEditAppRuleDialog(rule);
            }
        });
        rvApps.setAdapter(appRulesAdapter);

        findViewById(R.id.btn_add_app_rule).setOnClickListener(v -> showPickInstalledAppDialog());
    }

    private void setupTab3Listeners() {
        RecyclerView rvRules = findViewById(R.id.rv_sender_rules);
        rvRules.setLayoutManager(new LinearLayoutManager(this));
        senderRulesAdapter = new SenderRulesAdapter(new SenderRulesAdapter.Listener() {
            @Override
            public void onEditRule(SenderRuleEntity rule) {
                showEditSenderRuleDialog(rule);
            }

            @Override
            public void onDeleteRule(SenderRuleEntity rule) {
                db.executor().execute(() -> {
                    db.senderRuleDao().delete(rule);
                    WearDataLayerManager.syncStateBlocking(MainActivity.this);
                    runOnUiThread(MainActivity.this::loadSenderRulesAsync);
                });
            }
        });
        rvRules.setAdapter(senderRulesAdapter);

        findViewById(R.id.btn_add_sender_rule).setOnClickListener(v -> showEditSenderRuleDialog(null));
        findViewById(R.id.btn_pick_recent_sender).setOnClickListener(v -> showPickRecentSenderDialog());

        etUrgentKeywords.setText(prefs.getUrgentKeywordsRaw());
        etTeamsReplies.setText(prefs.getTeamsQuickRepliesRaw());
        etWaReplies.setText(prefs.getWhatsAppQuickRepliesRaw());

        findViewById(R.id.btn_replies_preset_es).setOnClickListener(v -> {
            etTeamsReplies.setText(PrefsManager.DEFAULT_TEAMS_REPLIES_ES);
            etWaReplies.setText(PrefsManager.DEFAULT_WA_REPLIES_ES);
            prefs.setTeamsQuickRepliesRaw(PrefsManager.DEFAULT_TEAMS_REPLIES_ES);
            prefs.setWhatsAppQuickRepliesRaw(PrefsManager.DEFAULT_WA_REPLIES_ES);
            WearDataLayerManager.syncStateAsync(this);
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_replies_preset_en).setOnClickListener(v -> {
            etTeamsReplies.setText(PrefsManager.DEFAULT_TEAMS_REPLIES_EN);
            etWaReplies.setText(PrefsManager.DEFAULT_WA_REPLIES_EN);
            prefs.setTeamsQuickRepliesRaw(PrefsManager.DEFAULT_TEAMS_REPLIES_EN);
            prefs.setWhatsAppQuickRepliesRaw(PrefsManager.DEFAULT_WA_REPLIES_EN);
            WearDataLayerManager.syncStateAsync(this);
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save_keywords_replies).setOnClickListener(v -> {
            prefs.setUrgentKeywordsRaw(etUrgentKeywords.getText().toString().trim());
            prefs.setTeamsQuickRepliesRaw(etTeamsReplies.getText().toString().trim());
            prefs.setWhatsAppQuickRepliesRaw(etWaReplies.getText().toString().trim());
            WearDataLayerManager.syncStateAsync(this);
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupTab4Listeners() {
        RecyclerView rvVault = findViewById(R.id.rv_vault_history);
        rvVault.setLayoutManager(new LinearLayoutManager(this));
        vaultAdapter = new VaultHistoryAdapter(item -> {
            ProfileController.deleteVaultOrHistoryItem(this, item.id);
            loadVaultHistoryAsync();
        });
        rvVault.setAdapter(vaultAdapter);

        cgVaultFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_vault_deleted) currentVaultFilter = 1;
            else if (id == R.id.chip_vault_coalesced) currentVaultFilter = 2;
            else if (id == R.id.chip_vault_after_hours) currentVaultFilter = 3;
            else currentVaultFilter = 0;
            loadVaultHistoryAsync();
        });

        findViewById(R.id.btn_clear_vault).setOnClickListener(v -> {
            if (currentVaultFilter == 1) {
                ProfileController.clearDeletedVault(this);
            } else {
                db.executor().execute(() -> {
                    db.chatMessageDao().deleteAllMessages();
                    WearDataLayerManager.syncStateBlocking(this);
                    runOnUiThread(this::loadVaultHistoryAsync);
                });
            }
        });

        swRepeatAllModes.setChecked(prefs.isRepeatInAllModesEnabled());
        swRepeatAllModes.setOnCheckedChangeListener((b, c) -> prefs.setRepeatInAllModesEnabled(c));

        swProgressiveEscalation.setChecked(prefs.isProgressiveEscalationEnabled());
        swProgressiveEscalation.setOnCheckedChangeListener((b, c) -> prefs.setProgressiveEscalationEnabled(c));

        swPhoneInUseSuppression.setChecked(prefs.isPhoneInUseSuppressionEnabled());
        swPhoneInUseSuppression.setOnCheckedChangeListener((b, c) -> prefs.setPhoneInUseSuppressionEnabled(c));

        swSavePhoneInUseVault.setChecked(prefs.isSavePhoneInUseToVaultEnabled());
        swSavePhoneInUseVault.setOnCheckedChangeListener((b, c) -> prefs.setSavePhoneInUseToVaultEnabled(c));

        swRespectSourceMuted.setChecked(prefs.isRespectSourceMutedEnabled());
        swRespectSourceMuted.setOnCheckedChangeListener((b, c) -> prefs.setRespectSourceMutedEnabled(c));

        swWorkMuteGroups.setChecked(prefs.isWorkMuteGroupsEnabled());
        swWorkMuteGroups.setOnCheckedChangeListener((b, c) -> prefs.setWorkMuteGroupsEnabled(c));

        swSyncPhoneDismiss.setChecked(prefs.isSyncDismissOnPhoneClearEnabled());
        swSyncPhoneDismiss.setOnCheckedChangeListener((b, c) -> prefs.setSyncDismissOnPhoneClearEnabled(c));

        swAutoTtsRepeat.setChecked(prefs.isAutoTtsInRepeatEnabled());
        swAutoTtsRepeat.setOnCheckedChangeListener((b, c) -> prefs.setAutoTtsInRepeatEnabled(c));

        swCallBoost.setChecked(prefs.isCallBoostEnabled());
        swCallBoost.setOnCheckedChangeListener((b, c) -> prefs.setCallBoostEnabled(c));

        swAutoAfterHours.setChecked(prefs.isAutoAfterHoursEnabled());
        updateAfterHoursSummaryLabel();
        swAutoAfterHours.setOnCheckedChangeListener((b, c) -> {
            prefs.setAutoAfterHoursEnabled(c);
            ScheduleAndTimerReceiver.evaluateNowAndReschedule(this, prefs);
            updateHomeDashboard();
        });

        btnConfigAfterHours.setOnClickListener(v -> showConfigureAfterHoursDialog());

        swPhoneBridgeNotif.setChecked(prefs.isPhoneBridgeNotifEnabled());
        swPhoneBridgeNotif.setOnCheckedChangeListener((b, c) -> prefs.setPhoneBridgeNotifEnabled(c));

        final int[] maxValues = new int[]{1, 2, 3, 5, 10, 15, 0};
        String[] maxOpts = new String[]{
                getString(R.string.max_repeat_1m),
                getString(R.string.max_repeat_2m),
                getString(R.string.max_repeat_3m),
                getString(R.string.max_repeat_5m),
                getString(R.string.max_repeat_10m),
                getString(R.string.max_repeat_15m),
                getString(R.string.max_repeat_unlimited)
        };
        spMaxRepeatMinutes.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, maxOpts));
        int maxMin = prefs.getMaxRepeatMinutes();
        int maxIdx = 3; // default 5m
        for (int i = 0; i < maxValues.length; i++) {
            if (maxValues[i] == maxMin) {
                maxIdx = i;
                break;
            }
        }
        spMaxRepeatMinutes.setSelection(maxIdx);
        spMaxRepeatMinutes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                prefs.setMaxRepeatMinutes(maxValues[pos]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final int[] escValues = new int[]{2, 3, 4, 5};
        String[] escOpts = new String[]{
                getString(R.string.esc_attempt_2),
                getString(R.string.esc_attempt_3),
                getString(R.string.esc_attempt_4),
                getString(R.string.esc_attempt_5)
        };
        spEscalationAttempt.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, escOpts));
        int escVal = prefs.getEscalationAttemptThreshold();
        int escIdx = 1;
        for (int i = 0; i < escValues.length; i++) {
            if (escValues[i] == escVal) {
                escIdx = i;
                break;
            }
        }
        spEscalationAttempt.setSelection(escIdx);
        spEscalationAttempt.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                prefs.setEscalationAttemptThreshold(escValues[pos]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final int[] snoozeValues = new int[]{2, 3, 5, 10, 15};
        String[] snoozeOpts = new String[]{
                getString(R.string.snooze_2m),
                getString(R.string.snooze_3m),
                getString(R.string.snooze_5m),
                getString(R.string.snooze_10m),
                getString(R.string.snooze_15m)
        };
        spSnoozeMinutes.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, snoozeOpts));
        int snoozeVal = prefs.getDefaultSnoozeMinutes();
        int snoozeIdx = 1;
        for (int i = 0; i < snoozeValues.length; i++) {
            if (snoozeValues[i] == snoozeVal) {
                snoozeIdx = i;
                break;
            }
        }
        spSnoozeMinutes.setSelection(snoozeIdx);
        spSnoozeMinutes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                prefs.setDefaultSnoozeMinutes(snoozeValues[pos]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final String[] ttsLangValues = new String[]{"es", "en", "auto"};
        String[] ttsLangOpts = new String[]{
                getString(R.string.tts_lang_es),
                getString(R.string.tts_lang_en),
                getString(R.string.tts_lang_auto)
        };
        spWatchTtsLang.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ttsLangOpts));
        String curTts = prefs.getWatchTtsLanguage();
        int ttsIdx = "en".equals(curTts) ? 1 : ("auto".equals(curTts) ? 2 : 0);
        spWatchTtsLang.setSelection(ttsIdx);
        spWatchTtsLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (!ttsLangValues[pos].equals(prefs.getWatchTtsLanguage())) {
                    prefs.setWatchTtsLanguage(ttsLangValues[pos]);
                    WearDataLayerManager.syncStateAsync(MainActivity.this);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btn_export_rules_json).setOnClickListener(v -> showExportJsonDialog());
        findViewById(R.id.btn_import_rules_json).setOnClickListener(v -> showImportJsonDialog());
    }

    private void refreshUi() {
        updateHomeDashboard();
        updateAfterHoursSummaryLabel();
        loadAppRulesAsync();
        loadSenderRulesAsync();
        loadVaultHistoryAsync();
    }

    private void updateHomeDashboard() {
        int profile = prefs.getActiveProfile();
        String profileText;
        switch (profile) {
            case PrefsManager.PROFILE_REPEAT:
                long end = prefs.getRepeatEndTimeMs();
                if (end > System.currentTimeMillis()) {
                    long rem = Math.max(1L, (end - System.currentTimeMillis()) / 60_000L);
                    profileText = getString(R.string.status_repeat_timed, rem);
                } else {
                    profileText = getString(R.string.status_repeat_manual);
                }
                break;
            case PrefsManager.PROFILE_WORK:
                profileText = getString(R.string.profile_work_badge);
                break;
            case PrefsManager.PROFILE_AFTER_HOURS:
                profileText = getString(R.string.profile_after_hours_badge);
                break;
            default:
                profileText = getString(R.string.profile_normal_badge);
                break;
        }
        tvActiveProfileStatus.setText(profileText);

        int dur = prefs.getDefaultRepeatDurationMin();
        if (dur == 0) cgRepeatDuration.check(R.id.chip_repeat_manual);
        else if (dur == 30) cgRepeatDuration.check(R.id.chip_repeat_30);
        else if (dur == 60) cgRepeatDuration.check(R.id.chip_repeat_60);
        else if (dur == 90) cgRepeatDuration.check(R.id.chip_repeat_90);
        else cgRepeatDuration.check(R.id.chip_repeat_45);

        tvActiveAlertsCount.setText(getString(R.string.active_alerts_fmt, PersistentRepeatForegroundService.getActiveSessionCount()));

        // Post-Session Briefing summary (Idea #12)
        int posted = prefs.getRepeatPostedNotifs();
        int bursts = prefs.getRepeatBurstsAbsorbed();
        if (posted > 0 || bursts > 0) {
            tvSessionBriefing.setVisibility(View.VISIBLE);
            tvSessionBriefing.setText(getString(R.string.repeat_briefing_fmt, posted, bursts));
        } else {
            tvSessionBriefing.setVisibility(View.GONE);
        }

        // Permissions status
        boolean listenerOk = isNotificationListenerEnabled();
        tvPermListenerStatus.setText(getString(R.string.perm_row_fmt,
                getString(R.string.perm_name_listener),
                listenerOk ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));

        boolean postOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        tvPermPostStatus.setText(getString(R.string.perm_row_fmt,
                getString(R.string.perm_name_post),
                postOk ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));

        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean dndOk = nm != null && nm.isNotificationPolicyAccessGranted();
        tvPermDndStatus.setText(getString(R.string.perm_row_fmt,
                getString(R.string.perm_name_dnd),
                dndOk ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        tvPermBatteryStatus.setText(getString(R.string.perm_row_fmt,
                getString(R.string.perm_name_battery),
                batteryOk ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));

        boolean voiceOk = com.nitsutech.watchbridge.engine.NotificationParser.hasVoiceFolderAccess(this, prefs);
        tvPermVoiceFolderStatus.setText(getString(R.string.perm_row_fmt,
                getString(R.string.perm_name_voice),
                voiceOk ? getString(R.string.perm_ok) : getString(R.string.perm_optional)));
    }

    private void checkConnectedWatch() {
        WearDataLayerManager.checkConnectedWatchAsync(this, (name, count) -> runOnUiThread(() -> {
            if (count > 0) {
                tvWatchConnStatus.setText(getString(R.string.watch_connected_fmt, name));
            } else {
                tvWatchConnStatus.setText(R.string.watch_disconnected);
            }
        }));
    }

    private void loadAppRulesAsync() {
        db.executor().execute(() -> {
            db.ensureDefaultsSync();
            List<AppRuleEntity> list = db.appRuleDao().getAllSync();
            runOnUiThread(() -> appRulesAdapter.submitList(list));
        });
    }

    private void loadSenderRulesAsync() {
        db.executor().execute(() -> {
            List<SenderRuleEntity> list = db.senderRuleDao().getAllSync();
            runOnUiThread(() -> senderRulesAdapter.submitList(list));
        });
    }

    private void loadVaultHistoryAsync() {
        db.executor().execute(() -> {
            List<ChatMessageEntity> list;
            if (currentVaultFilter == 1) list = db.chatMessageDao().getDeletedVaultSync();
            else if (currentVaultFilter == 2) list = db.chatMessageDao().getCoalescedSync();
            else if (currentVaultFilter == 3) list = db.chatMessageDao().getAfterHoursSync();
            else list = db.chatMessageDao().getRecentAllSync();
            runOnUiThread(() -> vaultAdapter.submitList(list));
        });
    }

    private void showVoiceAccessPicker() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_wa_audio_access_title)
                .setMessage(R.string.dlg_wa_audio_access_msg)
                .setPositiveButton(R.string.dlg_wa_grant_all_files, (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                            return;
                        } catch (Exception ignored) {
                        }
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                    launchVoiceTreeWithHint();
                })
                .setNeutralButton(R.string.dlg_wa_pick_folder, (d, w) -> launchVoiceTreeWithHint())
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void launchVoiceTreeWithHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Uri hint = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2FWhatsApp%20Voice%20Notes");
                voiceTreeLauncher.launch(hint);
                return;
            } catch (Exception ignored) {
            }
        }
        voiceTreeLauncher.launch(null);
    }

    private void updateAfterHoursSummaryLabel() {
        String startStr = String.format(Locale.US, "%02d:%02d",
                prefs.getAfterHoursStartHour(), prefs.getAfterHoursStartMin());
        String endStr = String.format(Locale.US, "%02d:%02d",
                prefs.getAfterHoursEndHour(), prefs.getAfterHoursEndMin());
        String daysStr = getString(prefs.isAfterHoursWeekdaysOnly()
                ? R.string.after_hours_days_weekdays
                : R.string.after_hours_days_daily);
        int appCount = prefs.getAfterHoursMutedPackages().size();
        swAutoAfterHours.setText(getString(
                R.string.setting_auto_after_hours_fmt,
                daysStr, startStr, endStr, appCount
        ));
    }

    private void showConfigureAfterHoursDialog() {
        db.executor().execute(() -> {
            db.ensureDefaultsSync();
            List<AppRuleEntity> apps = db.appRuleDao().getAllSync();
            runOnUiThread(() -> openAfterHoursDialogWithApps(apps));
        });
    }

    private void openAfterHoursDialogWithApps(List<AppRuleEntity> apps) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_after_hours_config, null);
        Button btnStart = v.findViewById(R.id.btn_ah_start_time);
        Button btnEnd = v.findViewById(R.id.btn_ah_end_time);
        CheckBox cbWeekdays = v.findViewById(R.id.cb_ah_weekdays_only);
        LinearLayout llApps = v.findViewById(R.id.ll_ah_apps_list);

        final int[] startHm = new int[]{prefs.getAfterHoursStartHour(), prefs.getAfterHoursStartMin()};
        final int[] endHm = new int[]{prefs.getAfterHoursEndHour(), prefs.getAfterHoursEndMin()};
        Runnable refreshTimeBtns = () -> {
            btnStart.setText(getString(R.string.dlg_ah_start_btn_fmt,
                    String.format(Locale.US, "%02d:%02d", startHm[0], startHm[1])));
            btnEnd.setText(getString(R.string.dlg_ah_end_btn_fmt,
                    String.format(Locale.US, "%02d:%02d", endHm[0], endHm[1])));
        };
        refreshTimeBtns.run();

        btnStart.setOnClickListener(view -> new TimePickerDialog(this, (picker, h, m) -> {
            startHm[0] = h;
            startHm[1] = m;
            refreshTimeBtns.run();
        }, startHm[0], startHm[1], true).show());

        btnEnd.setOnClickListener(view -> new TimePickerDialog(this, (picker, h, m) -> {
            endHm[0] = h;
            endHm[1] = m;
            refreshTimeBtns.run();
        }, endHm[0], endHm[1], true).show());

        cbWeekdays.setChecked(prefs.isAfterHoursWeekdaysOnly());

        Set<String> currentMuted = prefs.getAfterHoursMutedPackages();
        List<CheckBox> appCheckBoxes = new ArrayList<>();
        for (AppRuleEntity app : apps) {
            CheckBox cb = new CheckBox(this);
            cb.setText(app.appName);
            cb.setTag(app.packageName);
            cb.setChecked(currentMuted.contains(app.packageName));
            llApps.addView(cb);
            appCheckBoxes.add(cb);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_after_hours_title)
                .setView(v)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    prefs.setAfterHoursStart(startHm[0], startHm[1]);
                    prefs.setAfterHoursEnd(endHm[0], endHm[1]);
                    prefs.setAfterHoursWeekdaysOnly(cbWeekdays.isChecked());

                    Set<String> selected = new HashSet<>();
                    for (CheckBox cb : appCheckBoxes) {
                        if (cb.isChecked() && cb.getTag() instanceof String) {
                            selected.add((String) cb.getTag());
                        }
                    }
                    prefs.setAfterHoursMutedPackages(selected);

                    ScheduleAndTimerReceiver.evaluateNowAndReschedule(this, prefs);
                    WearDataLayerManager.syncStateAsync(this);
                    refreshUi();
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private static class SilentWatchUiHelper {
        Spinner spMode;
        int startHour;
        int startMin;
        int endHour;
        int endMin;
        CheckBox[] dayBoxes;
        int[] dayConstants;

        int getSelectedMode() {
            return Math.max(0, spMode.getSelectedItemPosition());
        }

        int readDaysMask() {
            int mask = 0;
            for (int i = 0; i < dayBoxes.length; i++) {
                if (dayBoxes[i].isChecked()) {
                    mask |= (1 << (dayConstants[i] - 1));
                }
            }
            return mask;
        }
    }

    private SilentWatchUiHelper bindSilentWatchUi(
            View root,
            boolean isSenderRule,
            int mode,
            int startH, int startM,
            int endH, int endM,
            int daysMask
    ) {
        SilentWatchUiHelper helper = new SilentWatchUiHelper();
        helper.spMode = root.findViewById(R.id.sp_dlg_silent_mode);
        helper.startHour = startH;
        helper.startMin = startM;
        helper.endHour = endH;
        helper.endMin = endM;

        View schedBox = root.findViewById(R.id.ll_dlg_silent_schedule);
        Button btnStart = root.findViewById(R.id.btn_dlg_silent_start);
        Button btnEnd = root.findViewById(R.id.btn_dlg_silent_end);

        helper.dayConstants = new int[]{
                java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY,
                java.util.Calendar.THURSDAY, java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY,
                java.util.Calendar.SUNDAY
        };
        helper.dayBoxes = new CheckBox[]{
                root.findViewById(R.id.cb_day_mon),
                root.findViewById(R.id.cb_day_tue),
                root.findViewById(R.id.cb_day_wed),
                root.findViewById(R.id.cb_day_thu),
                root.findViewById(R.id.cb_day_fri),
                root.findViewById(R.id.cb_day_sat),
                root.findViewById(R.id.cb_day_sun)
        };
        int effMask = daysMask == 0 ? ScheduleAndTimerReceiver.DAYS_ALL : daysMask;
        for (int i = 0; i < helper.dayBoxes.length; i++) {
            int bit = 1 << (helper.dayConstants[i] - 1);
            helper.dayBoxes[i].setChecked((effMask & bit) != 0);
        }

        Runnable refreshTimes = () -> {
            btnStart.setText(getString(R.string.dlg_ah_start_btn_fmt,
                    String.format(Locale.US, "%02d:%02d", helper.startHour, helper.startMin)));
            btnEnd.setText(getString(R.string.dlg_ah_end_btn_fmt,
                    String.format(Locale.US, "%02d:%02d", helper.endHour, helper.endMin)));
        };
        refreshTimes.run();

        btnStart.setOnClickListener(v -> new TimePickerDialog(this, (picker, h, m) -> {
            helper.startHour = h;
            helper.startMin = m;
            refreshTimes.run();
        }, helper.startHour, helper.startMin, true).show());

        btnEnd.setOnClickListener(v -> new TimePickerDialog(this, (picker, h, m) -> {
            helper.endHour = h;
            helper.endMin = m;
            refreshTimes.run();
        }, helper.endHour, helper.endMin, true).show());

        String[] opts = isSenderRule
                ? new String[]{
                        getString(R.string.silent_mode_follow_app),
                        getString(R.string.silent_mode_all_day),
                        getString(R.string.silent_mode_schedule),
                        getString(R.string.silent_mode_never_override)
                }
                : new String[]{
                        getString(R.string.silent_mode_off),
                        getString(R.string.silent_mode_all_day),
                        getString(R.string.silent_mode_schedule)
                };
        helper.spMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, opts));
        int safeMode = Math.min(opts.length - 1, Math.max(0, mode));
        helper.spMode.setSelection(safeMode);
        schedBox.setVisibility(safeMode == 2 ? View.VISIBLE : View.GONE);
        helper.spMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                schedBox.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        return helper;
    }

    private void showEditAppRuleDialog(AppRuleEntity rule) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_app_rule, null);
        SwitchMaterial swDirectEnabled = v.findViewById(R.id.sw_dlg_direct_enabled);
        SwitchMaterial swDirectRepeat = v.findViewById(R.id.sw_dlg_direct_repeat);
        EditText etDirectRepeatSec = v.findViewById(R.id.et_dlg_direct_repeat_sec);
        EditText etDirectCooldownSec = v.findViewById(R.id.et_dlg_direct_cooldown_sec);

        SwitchMaterial swGroupEnabled = v.findViewById(R.id.sw_dlg_group_enabled);
        SwitchMaterial swGroupRepeat = v.findViewById(R.id.sw_dlg_group_repeat);
        EditText etGroupRepeatSec = v.findViewById(R.id.et_dlg_group_repeat_sec);
        EditText etGroupCooldownSec = v.findViewById(R.id.et_dlg_group_cooldown_sec);

        Spinner spSoundMode = v.findViewById(R.id.sp_dlg_phone_sound);
        Spinner spHaptic = v.findViewById(R.id.sp_dlg_haptic_pattern);
        SilentWatchUiHelper silentUi = bindSilentWatchUi(
                v, false,
                rule.silentWatchMode,
                rule.silentStartHour, rule.silentStartMin,
                rule.silentEndHour, rule.silentEndMin,
                rule.silentDaysMask
        );

        swDirectEnabled.setChecked(rule.directEnabled);
        swDirectRepeat.setChecked(rule.directRepeatEnabled);
        etDirectRepeatSec.setText(String.valueOf(rule.directRepeatIntervalSec));
        etDirectCooldownSec.setText(String.valueOf(rule.directCooldownSec));

        swGroupEnabled.setChecked(rule.groupEnabled);
        swGroupRepeat.setChecked(rule.groupRepeatEnabled);
        etGroupRepeatSec.setText(String.valueOf(rule.groupRepeatIntervalSec));
        etGroupCooldownSec.setText(String.valueOf(rule.groupCooldownSec));

        spSoundMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                getString(R.string.sound_mode_watch_only),
                getString(R.string.sound_mode_watch_and_phone),
                getString(R.string.sound_mode_loud_alarm)
        }));
        spSoundMode.setSelection(Math.min(2, Math.max(0, rule.phoneSoundMode)));

        spHaptic.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                getString(R.string.haptic_teams_double),
                getString(R.string.haptic_wa_long_short),
                getString(R.string.haptic_group_short),
                getString(R.string.haptic_urgent_wake)
        }));
        spHaptic.setSelection(Math.min(3, Math.max(0, rule.hapticPattern)));

        new AlertDialog.Builder(this)
                .setTitle(rule.appName)
                .setView(v)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    rule.directEnabled = swDirectEnabled.isChecked();
                    rule.directRepeatEnabled = swDirectRepeat.isChecked();
                    rule.directRepeatIntervalSec = parsePositiveInt(etDirectRepeatSec.getText().toString(), 12);
                    rule.directCooldownSec = parseNonNegInt(etDirectCooldownSec.getText().toString(), 0);

                    rule.groupEnabled = swGroupEnabled.isChecked();
                    rule.groupRepeatEnabled = swGroupRepeat.isChecked();
                    rule.groupRepeatIntervalSec = parsePositiveInt(etGroupRepeatSec.getText().toString(), 20);
                    rule.groupCooldownSec = parseNonNegInt(etGroupCooldownSec.getText().toString(), 0);

                    rule.phoneSoundMode = spSoundMode.getSelectedItemPosition();
                    rule.hapticPattern = spHaptic.getSelectedItemPosition();
                    rule.silentWatchMode = silentUi.getSelectedMode();
                    rule.silentStartHour = silentUi.startHour;
                    rule.silentStartMin = silentUi.startMin;
                    rule.silentEndHour = silentUi.endHour;
                    rule.silentEndMin = silentUi.endMin;
                    rule.silentDaysMask = silentUi.readDaysMask();

                    db.executor().execute(() -> {
                        db.appRuleDao().upsert(rule);
                        WearDataLayerManager.syncStateBlocking(this);
                        runOnUiThread(this::loadAppRulesAsync);
                    });
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void showEditSenderRuleDialog(SenderRuleEntity existing) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_sender_rule, null);
        EditText etName = v.findViewById(R.id.et_dlg_sender_name);
        Spinner spApp = v.findViewById(R.id.sp_dlg_sender_app);
        Spinner spChatType = v.findViewById(R.id.sp_dlg_sender_chat_type);
        Spinner spActionMode = v.findViewById(R.id.sp_dlg_sender_action);
        EditText etRepeatSec = v.findViewById(R.id.et_dlg_sender_repeat_sec);
        EditText etCooldownSec = v.findViewById(R.id.et_dlg_sender_cooldown_sec);
        CheckBox cbVip = v.findViewById(R.id.cb_dlg_sender_vip);
        CheckBox cbPhoneAlarm = v.findViewById(R.id.cb_dlg_sender_alarm);

        String[] appCodes = new String[]{"*", AppDatabase.PKG_TEAMS, AppDatabase.PKG_WHATSAPP, AppDatabase.PKG_WHATSAPP_BUSINESS};
        String[] appLabels = new String[]{
                getString(R.string.scope_all_apps),
                "Microsoft Teams",
                "WhatsApp",
                "WhatsApp Business"
        };
        spApp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, appLabels));

        spChatType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                getString(R.string.chat_type_both),
                getString(R.string.chat_type_direct),
                getString(R.string.chat_type_group)
        }));

        spActionMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                getString(R.string.rule_mode_repeat),
                getString(R.string.rule_mode_once),
                getString(R.string.rule_mode_mute)
        }));

        SenderRuleEntity target = existing != null ? existing : new SenderRuleEntity();
        etName.setText(target.senderOrChatName);
        for (int i = 0; i < appCodes.length; i++) {
            if (appCodes[i].equals(target.packageFilter)) {
                spApp.setSelection(i);
                break;
            }
        }
        spChatType.setSelection(Math.min(2, Math.max(0, target.chatTypeFilter)));
        spActionMode.setSelection(Math.min(2, Math.max(0, target.actionMode)));
        etRepeatSec.setText(String.valueOf(target.repeatIntervalSec));
        etCooldownSec.setText(String.valueOf(target.cooldownSec));
        cbVip.setChecked(target.isVip);
        cbPhoneAlarm.setChecked(target.phoneAlarmOverride);
        SilentWatchUiHelper silentUi = bindSilentWatchUi(
                v, true,
                target.silentWatchMode,
                target.silentStartHour, target.silentStartMin,
                target.silentEndHour, target.silentEndMin,
                target.silentDaysMask
        );

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.dlg_new_sender_rule : R.string.dlg_edit_sender_rule)
                .setView(v)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    target.senderOrChatName = name;
                    target.packageFilter = appCodes[spApp.getSelectedItemPosition()];
                    target.chatTypeFilter = spChatType.getSelectedItemPosition();
                    target.actionMode = spActionMode.getSelectedItemPosition();
                    target.repeatIntervalSec = parsePositiveInt(etRepeatSec.getText().toString(), 10);
                    target.cooldownSec = parseNonNegInt(etCooldownSec.getText().toString(), 0);
                    target.isVip = cbVip.isChecked();
                    target.phoneAlarmOverride = cbPhoneAlarm.isChecked();
                    target.silentWatchMode = silentUi.getSelectedMode();
                    target.silentStartHour = silentUi.startHour;
                    target.silentStartMin = silentUi.startMin;
                    target.silentEndHour = silentUi.endHour;
                    target.silentEndMin = silentUi.endMin;
                    target.silentDaysMask = silentUi.readDaysMask();

                    db.executor().execute(() -> {
                        db.senderRuleDao().upsert(target);
                        WearDataLayerManager.syncStateBlocking(this);
                        runOnUiThread(this::loadSenderRulesAsync);
                    });
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void showPickRecentSenderDialog() {
        List<ActionCache.CachedActions> recent = ActionCache.getRecentContacts();
        if (recent.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_recent_contacts, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            ActionCache.CachedActions c = recent.get(i);
            labels[i] = (c.isGroup ? c.conversationTitle : c.senderName) + " (" + c.appLabel + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_pick_recent)
                .setItems(labels, (d, idx) -> {
                    ActionCache.CachedActions c = recent.get(idx);
                    SenderRuleEntity draft = new SenderRuleEntity();
                    draft.senderOrChatName = c.isGroup ? c.conversationTitle : c.senderName;
                    draft.packageFilter = c.packageName;
                    draft.chatTypeFilter = c.isGroup ? 2 : 1;
                    draft.repeatIntervalSec = 10;
                    draft.cooldownSec = 0;
                    showEditSenderRuleDialog(draft);
                })
                .show();
    }

    private void showPickInstalledAppDialog() {
        PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
        List<ApplicationInfo> launchable = new ArrayList<>();
        java.util.Set<String> seenPkgs = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo ri : resolved) {
            if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                String pkg = ri.activityInfo.packageName;
                if (!getPackageName().equals(pkg) && seenPkgs.add(pkg)) {
                    launchable.add(ri.activityInfo.applicationInfo);
                }
            }
        }
        launchable.sort((a, b) -> pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
        String[] labels = new String[launchable.size()];
        for (int i = 0; i < launchable.size(); i++) {
            labels[i] = pm.getApplicationLabel(launchable.get(i)).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_add_app)
                .setItems(labels, (d, idx) -> {
                    ApplicationInfo ai = launchable.get(idx);
                    String label = pm.getApplicationLabel(ai).toString();
                    AppRuleEntity rule = new AppRuleEntity(
                            ai.packageName, label, true,
                            true, false, 15, 0,
                            true, false, 25, 0,
                            0, 1
                    );
                    db.executor().execute(() -> {
                        db.appRuleDao().upsert(rule);
                        WearDataLayerManager.syncStateBlocking(this);
                        runOnUiThread(this::loadAppRulesAsync);
                    });
                })
                .show();
    }

    private void showExportJsonDialog() {
        db.executor().execute(() -> {
            List<AppRuleEntity> apps = db.appRuleDao().getAllSync();
            List<SenderRuleEntity> senders = db.senderRuleDao().getAllSync();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(java.util.Map.of("appRules", apps, "senderRules", senders));
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.btn_export_rules)
                    .setMessage(json)
                    .setNeutralButton(R.string.btn_copy, (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("WatchBridgeRules", json));
                            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton(R.string.btn_ok, null)
                    .show());
        });
    }

    private void showImportJsonDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.hint_paste_rules_json);
        input.setMinLines(6);
        input.setMaxLines(12);
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_import_rules)
                .setView(input)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    db.executor().execute(() -> {
                        try {
                            Gson gson = new Gson();
                            JsonElement el = JsonParser.parseString(raw);
                            JsonObject obj = el.getAsJsonObject();
                            if (obj.has("appRules") && obj.get("appRules").isJsonArray()) {
                                List<AppRuleEntity> importedApps = gson.fromJson(
                                        obj.get("appRules"),
                                        new TypeToken<List<AppRuleEntity>>() {}.getType()
                                );
                                if (importedApps != null) {
                                    for (AppRuleEntity r : importedApps) {
                                        if (r != null && !TextUtils.isEmpty(r.packageName)) {
                                            db.appRuleDao().upsert(r);
                                        }
                                    }
                                }
                            }
                            if (obj.has("senderRules") && obj.get("senderRules").isJsonArray()) {
                                List<SenderRuleEntity> importedSenders = gson.fromJson(
                                        obj.get("senderRules"),
                                        new TypeToken<List<SenderRuleEntity>>() {}.getType()
                                );
                                if (importedSenders != null) {
                                    for (SenderRuleEntity sr : importedSenders) {
                                        if (sr != null && !TextUtils.isEmpty(sr.senderOrChatName)) {
                                            db.senderRuleDao().upsert(sr);
                                        }
                                    }
                                }
                            }
                            WearDataLayerManager.syncStateBlocking(this);
                            runOnUiThread(() -> {
                                refreshUi();
                                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, R.string.toast_import_error, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        ComponentName cn = new ComponentName(this, BridgeNotificationListenerService.class);
        return flat.contains(cn.flattenToString()) || flat.contains(getPackageName());
    }

    private static int parsePositiveInt(String raw, int def) {
        try {
            return Math.max(3, Integer.parseInt(raw.trim()));
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseNonNegInt(String raw, int def) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (Exception e) {
            return def;
        }
    }
}
