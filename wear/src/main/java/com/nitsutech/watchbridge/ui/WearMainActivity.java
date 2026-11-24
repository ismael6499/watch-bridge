package com.nitsutech.watchbridge.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.InputDeviceCompat;
import androidx.wear.widget.SwipeDismissFrameLayout;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.service.WatchWearListenerService;
import com.nitsutech.watchbridge.wear.WatchAudioHelper;
import com.nitsutech.watchbridge.wear.WatchDataClient;
import com.nitsutech.watchbridge.wear.WatchPrefsManager;
import com.nitsutech.watchbridge.wear.WearProtocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Native Galaxy Watch 8 Classic UI with physical bezel scrolling, full navigation back-stack,
 * Ghost Read chat view, sticker/image preview, Watch speaker voice note player (1x/1.5x/2x),
 * TTS readout, Watch-to-Phone App ON/OFF toggles, Deleted Vault, and Phone Remote.
 */
public class WearMainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_CHAT_KEY = "open_chat_key";
    public static final String EXTRA_TOGGLE_REPEAT = "toggle_repeat";
    public static final String EXTRA_ESCALATED = "escalated";

    private static final int SCREEN_DASHBOARD = 0;
    private static final int SCREEN_CHATS = 1;
    private static final int SCREEN_CHAT_DETAIL = 2;
    private static final int SCREEN_APPS = 3;
    private static final int SCREEN_VAULT = 4;
    private static final int SCREEN_REMOTE = 5;

    private WatchPrefsManager prefs;
    private WearProtocol.WatchStatePayload state;

    private SwipeDismissFrameLayout swipeRoot;
    private ScrollView mainScroll;
    private LinearLayout screenDashboard;
    private LinearLayout screenChats;
    private LinearLayout screenChatDetail;
    private LinearLayout screenApps;
    private LinearLayout screenVault;
    private LinearLayout screenRemote;

    private TextView tvDashProfile;
    private TextView tvDashAlerts;
    private LinearLayout llChatsList;
    private LinearLayout llChatMessages;
    private LinearLayout llQuickReplies;
    private TextView tvChatHeader;
    private Button btnAudioSpeed;
    private TextView tvWearVolStatus;
    private LinearLayout llAppsList;
    private LinearLayout llVaultList;
    private TextView tvRemoteBattery;
    private Button btnRemoteFindPhone;

    private GestureDetector backSwipeDetector;
    private final Deque<Integer> screenBackStack = new ArrayDeque<>();
    private int currentScreen = SCREEN_DASHBOARD;
    private String currentChatKey = "";

    private final ActivityResultLauncher<Intent> voiceReplyLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                    ArrayList<String> matches = res.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    if (matches != null && !matches.isEmpty() && !TextUtils.isEmpty(currentChatKey)) {
                        WatchDataClient.sendReply(this, currentChatKey, matches.get(0));
                        Toast.makeText(this, R.string.wear_toast_reply_sent, Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            state = prefs.getState();
            renderCurrentScreen();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_wear_main);

        prefs = new WatchPrefsManager(this);
        state = prefs.getState();

        bindViews();
        setupNavigationBackStack();
        setupButtonListeners();
        requestWatchNotificationPermissionIfNeeded();
        handleIntentExtras(getIntent());
        WatchDataClient.requestSync(this);
    }

    private void requestWatchNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 901);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WatchWearListenerService.ACTION_WATCH_STATE_UPDATED);
        filter.addAction(WatchWearListenerService.ACTION_WATCH_ALERT_TICK);
        filter.addAction(WatchWearListenerService.ACTION_WATCH_AUDIO_READY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, filter);
        }
        state = prefs.getState();
        renderCurrentScreen();
        WatchDataClient.requestSync(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(syncReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (backSwipeDetector != null) {
            backSwipeDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Galaxy Watch 8 Classic Physical Rotating Bezel support
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)) {
            float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * 140f;
            if (mainScroll != null) {
                mainScroll.smoothScrollBy(0, Math.round(delta));
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private void bindViews() {
        swipeRoot = findViewById(R.id.wear_swipe_root);
        mainScroll = findViewById(R.id.wear_main_scroll);
        screenDashboard = findViewById(R.id.screen_dashboard);
        screenChats = findViewById(R.id.screen_chats);
        screenChatDetail = findViewById(R.id.screen_chat_detail);
        screenApps = findViewById(R.id.screen_apps);
        screenVault = findViewById(R.id.screen_vault);
        screenRemote = findViewById(R.id.screen_remote);

        tvDashProfile = findViewById(R.id.tv_wear_profile);
        tvDashAlerts = findViewById(R.id.tv_wear_alerts);
        llChatsList = findViewById(R.id.ll_wear_chats_list);
        llChatMessages = findViewById(R.id.ll_wear_chat_messages);
        llQuickReplies = findViewById(R.id.ll_wear_quick_replies);
        tvChatHeader = findViewById(R.id.tv_wear_chat_header);
        btnAudioSpeed = findViewById(R.id.btn_wear_audio_speed);
        tvWearVolStatus = findViewById(R.id.tv_wear_vol_status);
        llAppsList = findViewById(R.id.ll_wear_apps_list);
        llVaultList = findViewById(R.id.ll_wear_vault_list);
        tvRemoteBattery = findViewById(R.id.tv_wear_phone_battery);
        btnRemoteFindPhone = findViewById(R.id.btn_remote_find_phone);
    }

    private void setupNavigationBackStack() {
        swipeRoot.addCallback(new SwipeDismissFrameLayout.Callback() {
            @Override
            public void onDismissed(SwipeDismissFrameLayout layout) {
                finish();
            }
        });

        backSwipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = Math.abs(e2.getY() - e1.getY());
                if (currentScreen != SCREEN_DASHBOARD && dx > 80f && dx > dy * 1.5f) {
                    navigateBack();
                    return true;
                }
                return false;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (navigateBack()) {
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private boolean navigateBack() {
        if (!screenBackStack.isEmpty()) {
            currentScreen = screenBackStack.pop();
            renderCurrentScreen();
            return true;
        }
        if (currentScreen != SCREEN_DASHBOARD) {
            currentScreen = SCREEN_DASHBOARD;
            renderCurrentScreen();
            return true;
        }
        return false;
    }

    private void navigateTo(int targetScreen, boolean pushHistory) {
        if (pushHistory && currentScreen != targetScreen) {
            screenBackStack.push(currentScreen);
        }
        currentScreen = targetScreen;
        renderCurrentScreen();
        mainScroll.post(() -> mainScroll.scrollTo(0, 0));
    }

    private void handleIntentExtras(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_ESCALATED, false)) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1.0f;
            getWindow().setAttributes(lp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (intent.getBooleanExtra(EXTRA_TOGGLE_REPEAT, false)) {
            int next = state.activeProfile == WatchPrefsManager.PROFILE_REPEAT
                    ? WatchPrefsManager.PROFILE_NORMAL
                    : WatchPrefsManager.PROFILE_REPEAT;
            WatchDataClient.setProfile(this, next);
            state.activeProfile = next;
        }
        String openKey = intent.getStringExtra(EXTRA_OPEN_CHAT_KEY);
        if (!TextUtils.isEmpty(openKey)) {
            currentChatKey = openKey;
            navigateTo(SCREEN_CHAT_DETAIL, false);
        } else {
            renderCurrentScreen();
        }
    }

    private void setupButtonListeners() {
        // Dashboard Profile switches
        findViewById(R.id.btn_wear_mode_repeat).setOnClickListener(v -> sendProfile(WatchPrefsManager.PROFILE_REPEAT));
        findViewById(R.id.btn_wear_mode_normal).setOnClickListener(v -> sendProfile(WatchPrefsManager.PROFILE_NORMAL));
        findViewById(R.id.btn_wear_mode_work).setOnClickListener(v -> sendProfile(WatchPrefsManager.PROFILE_WORK));
        findViewById(R.id.btn_wear_mode_after).setOnClickListener(v -> sendProfile(WatchPrefsManager.PROFILE_AFTER_HOURS));

        findViewById(R.id.btn_wear_stop_all).setOnClickListener(v -> {
            WatchDataClient.stopAllAlerts(this);
            state.activeAlertCount = 0;
            renderCurrentScreen();
            Toast.makeText(this, R.string.wear_toast_stopped, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_wear_open_chats).setOnClickListener(v -> navigateTo(SCREEN_CHATS, true));
        findViewById(R.id.btn_wear_open_apps).setOnClickListener(v -> navigateTo(SCREEN_APPS, true));
        findViewById(R.id.btn_wear_open_vault).setOnClickListener(v -> navigateTo(SCREEN_VAULT, true));
        findViewById(R.id.btn_wear_open_remote).setOnClickListener(v -> navigateTo(SCREEN_REMOTE, true));
        findViewById(R.id.btn_wear_sync_now).setOnClickListener(v -> {
            WatchDataClient.requestSync(this);
            Toast.makeText(this, R.string.wear_toast_syncing, Toast.LENGTH_SHORT).show();
        });

        // Chat Detail Actions
        findViewById(R.id.btn_wear_tts).setOnClickListener(v -> speakCurrentChat());
        findViewById(R.id.btn_wear_vol_down).setOnClickListener(v -> {
            int pct = WatchAudioHelper.adjustMediaVolume(this, -1);
            tvWearVolStatus.setText(getString(R.string.wear_vol_fmt, pct));
        });
        findViewById(R.id.btn_wear_vol_up).setOnClickListener(v -> {
            int pct = WatchAudioHelper.adjustMediaVolume(this, 1);
            tvWearVolStatus.setText(getString(R.string.wear_vol_fmt, pct));
        });
        findViewById(R.id.btn_wear_stop_audio).setOnClickListener(v -> WatchAudioHelper.stopAllAudio());
        findViewById(R.id.btn_wear_play_voice).setOnClickListener(v -> {
            WearProtocol.ConversationDto conv = findConversation(currentChatKey);
            long msgId = (conv != null && !conv.messages.isEmpty())
                    ? conv.messages.get(conv.messages.size() - 1).id
                    : 0L;
            WatchDataClient.requestVoiceAudio(this, msgId, currentChatKey);
            Toast.makeText(this, R.string.wear_toast_loading_audio, Toast.LENGTH_SHORT).show();
        });
        btnAudioSpeed.setOnClickListener(v -> cycleAudioSpeed());
        findViewById(R.id.btn_wear_reply_voice).setOnClickListener(v -> launchVoiceOrTextReply());
        findViewById(R.id.btn_wear_mark_read).setOnClickListener(v -> {
            WatchDataClient.markRead(this, currentChatKey);
            Toast.makeText(this, R.string.wear_toast_marked_read, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_wear_snooze_3m).setOnClickListener(v -> {
            WatchDataClient.snoozeChat(this, currentChatKey, 3);
            Toast.makeText(this, R.string.wear_toast_snoozed, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_wear_mute_1h).setOnClickListener(v -> {
            WatchDataClient.muteChat(this, currentChatKey, 60);
            Toast.makeText(this, R.string.wear_toast_muted_1h, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_wear_toggle_vip).setOnClickListener(v -> {
            WearProtocol.ConversationDto conv = findConversation(currentChatKey);
            if (conv != null) {
                String name = conv.isGroup ? conv.conversationTitle :
                        (!conv.messages.isEmpty() ? conv.messages.get(conv.messages.size() - 1).senderName : conv.conversationTitle);
                WatchDataClient.toggleVip(this, name, conv.packageName);
                conv.isVip = !conv.isVip;
                renderChatDetail();
            }
        });
        findViewById(R.id.btn_wear_dismiss_chat).setOnClickListener(v -> {
            WatchDataClient.dismissChat(this, currentChatKey);
            getOnBackPressedDispatcher().onBackPressed();
        });

        // Vault Clear All
        findViewById(R.id.btn_wear_vault_clear_all).setOnClickListener(v -> {
            WatchDataClient.clearVault(this);
            state.deletedVault.clear();
            renderVault();
        });

        // Sub-screen Back buttons
        findViewById(R.id.btn_back_chats).setOnClickListener(v -> navigateBack());
        findViewById(R.id.btn_back_chat_detail).setOnClickListener(v -> navigateBack());
        findViewById(R.id.btn_back_apps).setOnClickListener(v -> navigateBack());
        findViewById(R.id.btn_back_vault).setOnClickListener(v -> navigateBack());
        findViewById(R.id.btn_back_remote).setOnClickListener(v -> navigateBack());

        // Phone Remote (Idea #7)
        findViewById(R.id.btn_remote_sound).setOnClickListener(v -> WatchDataClient.setPhoneRinger(this, AudioManager.RINGER_MODE_NORMAL));
        findViewById(R.id.btn_remote_vibrate).setOnClickListener(v -> WatchDataClient.setPhoneRinger(this, AudioManager.RINGER_MODE_VIBRATE));
        findViewById(R.id.btn_remote_silent).setOnClickListener(v -> WatchDataClient.setPhoneRinger(this, AudioManager.RINGER_MODE_SILENT));
        btnRemoteFindPhone.setOnClickListener(v -> {
            boolean nextRing = !state.isFindingPhone;
            state.isFindingPhone = nextRing;
            WatchDataClient.ringFindPhone(this, nextRing);
            renderRemote();
            if (nextRing) {
                Toast.makeText(this, R.string.wear_toast_ringing_phone, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendProfile(int profile) {
        state.activeProfile = profile;
        WatchDataClient.setProfile(this, profile);
        renderDashboard();
    }

    private void renderCurrentScreen() {
        swipeRoot.setSwipeable(currentScreen == SCREEN_DASHBOARD && screenBackStack.isEmpty());
        screenDashboard.setVisibility(currentScreen == SCREEN_DASHBOARD ? View.VISIBLE : View.GONE);
        screenChats.setVisibility(currentScreen == SCREEN_CHATS ? View.VISIBLE : View.GONE);
        screenChatDetail.setVisibility(currentScreen == SCREEN_CHAT_DETAIL ? View.VISIBLE : View.GONE);
        screenApps.setVisibility(currentScreen == SCREEN_APPS ? View.VISIBLE : View.GONE);
        screenVault.setVisibility(currentScreen == SCREEN_VAULT ? View.VISIBLE : View.GONE);
        screenRemote.setVisibility(currentScreen == SCREEN_REMOTE ? View.VISIBLE : View.GONE);

        if (currentScreen == SCREEN_DASHBOARD) renderDashboard();
        else if (currentScreen == SCREEN_CHATS) renderChatsList();
        else if (currentScreen == SCREEN_CHAT_DETAIL) renderChatDetail();
        else if (currentScreen == SCREEN_APPS) renderAppsControl();
        else if (currentScreen == SCREEN_VAULT) renderVault();
        else if (currentScreen == SCREEN_REMOTE) renderRemote();
    }

    private void renderDashboard() {
        String pLabel;
        switch (state.activeProfile) {
            case WatchPrefsManager.PROFILE_REPEAT:
                pLabel = getString(R.string.wear_profile_repeat);
                break;
            case WatchPrefsManager.PROFILE_WORK:
                pLabel = getString(R.string.wear_profile_work);
                break;
            case WatchPrefsManager.PROFILE_AFTER_HOURS:
                pLabel = getString(R.string.wear_profile_after_hours);
                break;
            default:
                pLabel = getString(R.string.wear_profile_normal);
                break;
        }
        tvDashProfile.setText(pLabel);
        tvDashAlerts.setText(getString(R.string.wear_alerts_count_fmt, state.activeAlertCount));
    }

    private void renderChatsList() {
        llChatsList.removeAllViews();
        if (state.conversations == null || state.conversations.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.wear_empty_chats);
            empty.setTextColor(0xFFB0BEC5);
            empty.setPadding(8, 12, 8, 12);
            llChatsList.addView(empty);
            return;
        }
        for (WearProtocol.ConversationDto c : state.conversations) {
            Button b = new Button(this);
            String kindTag = c.notificationKind == WearProtocol.KIND_EVENT ? "📅 " : "";
            String prefix = (c.isVip ? "⭐ " : "") + kindTag;
            String last = c.messages != null && !c.messages.isEmpty()
                    ? c.messages.get(c.messages.size() - 1).messageText
                    : "";
            b.setText(prefix + c.conversationTitle + " (" + c.appLabel + ")\n" + last);
            b.setAllCaps(false);
            b.setTextSize(11f);
            b.setOnClickListener(v -> {
                currentChatKey = c.conversationKey;
                navigateTo(SCREEN_CHAT_DETAIL, true);
            });
            llChatsList.addView(b);
        }
    }

    private void renderChatDetail() {
        WearProtocol.ConversationDto conv = findConversation(currentChatKey);
        if (conv == null) {
            tvChatHeader.setText(R.string.wear_empty_chats);
            llChatMessages.removeAllViews();
            llQuickReplies.removeAllViews();
            return;
        }
        boolean isChat = conv.notificationKind == WearProtocol.KIND_CHAT;
        String kindLabel = isChat ? "" : "📅 ";
        tvChatHeader.setText((conv.isVip ? "⭐ " : "") + kindLabel + conv.conversationTitle + " • " + conv.appLabel);
        updateSpeedButtonText();
        tvWearVolStatus.setText(getString(R.string.wear_vol_fmt, WatchAudioHelper.getMediaVolumePct(this)));

        boolean anyVoiceNote = false;
        llChatMessages.removeAllViews();
        for (WearProtocol.MessageDto m : conv.messages) {
            if (m.hasVoiceNote) anyVoiceNote = true;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(6, 6, 6, 6);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView tvMsg = new TextView(this);
            tvMsg.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            String delTag = m.isDeletedBySender ? "🗑️ [" + getString(R.string.wear_deleted_tag) + "] " : "";
            String lineText = (isChat && !TextUtils.isEmpty(m.senderName) && !m.senderName.equals(conv.conversationTitle))
                    ? (m.senderName + ": " + delTag + m.messageText)
                    : (delTag + m.messageText);
            tvMsg.setText(lineText);
            tvMsg.setTextColor(0xFFFFFFFF);
            tvMsg.setTextSize(12f);

            Button btnDelOne = new Button(this);
            btnDelOne.setText(R.string.wear_btn_x);
            btnDelOne.setTextSize(10f);
            btnDelOne.setOnClickListener(v -> {
                WatchDataClient.dismissSingleMessage(this, m.id);
                conv.messages.remove(m);
                renderChatDetail();
            });
            top.addView(tvMsg);
            top.addView(btnDelOne);
            row.addView(top);

            if (!TextUtils.isEmpty(m.mediaBase64)) {
                try {
                    byte[] bytes = Base64.decode(m.mediaBase64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        ImageView iv = new ImageView(this);
                        iv.setImageBitmap(bmp);
                        iv.setAdjustViewBounds(true);
                        iv.setMaxHeight(260);
                        row.addView(iv);
                    }
                } catch (Exception ignored) {
                }
            }
            llChatMessages.addView(row);
        }

        findViewById(R.id.btn_wear_play_voice).setVisibility(anyVoiceNote ? View.VISIBLE : View.GONE);
        btnAudioSpeed.setVisibility(anyVoiceNote ? View.VISIBLE : View.GONE);

        llQuickReplies.removeAllViews();
        View btnReply = findViewById(R.id.btn_wear_reply_voice);
        View btnMarkRead = findViewById(R.id.btn_wear_mark_read);
        boolean showReply = isChat && conv.canReply;
        boolean hasExtras = conv.extraActionTitles != null && !conv.extraActionTitles.isEmpty();
        btnReply.setVisibility(showReply ? View.VISIBLE : View.GONE);
        llQuickReplies.setVisibility((showReply || hasExtras) ? View.VISIBLE : View.GONE);
        btnMarkRead.setVisibility(conv.canMarkRead ? View.VISIBLE : View.GONE);

        if (hasExtras) {
            for (int i = 0; i < conv.extraActionTitles.size(); i++) {
                final int idx = i;
                String title = conv.extraActionTitles.get(i);
                if (TextUtils.isEmpty(title)) continue;
                Button ab = new Button(this);
                ab.setText(title);
                ab.setAllCaps(false);
                ab.setTextSize(11f);
                ab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3949AB));
                ab.setTextColor(0xFFFFFFFF);
                ab.setOnClickListener(v -> {
                    WatchDataClient.execCustomAction(this, conv.conversationKey, idx);
                    Toast.makeText(this, R.string.wear_toast_action_sent, Toast.LENGTH_SHORT).show();
                });
                llQuickReplies.addView(ab);
            }
        }

        if (showReply && conv.quickReplies != null) {
            for (String qr : conv.quickReplies) {
                Button qb = new Button(this);
                qb.setText(qr);
                qb.setAllCaps(false);
                qb.setTextSize(11f);
                qb.setOnClickListener(v -> {
                    WatchDataClient.sendReply(this, conv.conversationKey, qr);
                    Toast.makeText(this, R.string.wear_toast_reply_sent, Toast.LENGTH_SHORT).show();
                });
                llQuickReplies.addView(qb);
            }
        }
    }

    private void renderAppsControl() {
        llAppsList.removeAllViews();
        if (state.appRules == null || state.appRules.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.wear_empty_apps);
            tv.setTextColor(0xFFB0BEC5);
            llAppsList.addView(tv);
            return;
        }
        for (WearProtocol.AppRuleDto r : state.appRules) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(6, 6, 6, 10);

            TextView title = new TextView(this);
            title.setText(r.appName);
            title.setTextColor(0xFF80DEEA);
            title.setTextSize(13f);
            card.addView(title);

            Button btnToggleApp = new Button(this);
            btnToggleApp.setText(r.enabled ? getString(R.string.wear_app_on) : getString(R.string.wear_app_off));
            btnToggleApp.setAllCaps(false);
            btnToggleApp.setTextSize(11f);
            btnToggleApp.setOnClickListener(v -> {
                r.enabled = !r.enabled;
                WatchDataClient.toggleApp(this, r.packageName, r.enabled);
                renderAppsControl();
            });
            card.addView(btnToggleApp);

            Button btnToggleGrp = new Button(this);
            btnToggleGrp.setText(r.groupEnabled ? getString(R.string.wear_groups_on) : getString(R.string.wear_groups_off));
            btnToggleGrp.setAllCaps(false);
            btnToggleGrp.setTextSize(11f);
            btnToggleGrp.setOnClickListener(v -> {
                r.groupEnabled = !r.groupEnabled;
                WatchDataClient.toggleAppGroup(this, r.packageName, r.groupEnabled);
                renderAppsControl();
            });
            card.addView(btnToggleGrp);

            Button btnCycleTimers = new Button(this);
            btnCycleTimers.setText(getString(R.string.wear_timers_fmt, r.directRepeatIntervalSec, r.directCooldownSec));
            btnCycleTimers.setAllCaps(false);
            btnCycleTimers.setTextSize(10f);
            btnCycleTimers.setOnClickListener(v -> {
                int nextRep = r.directRepeatIntervalSec == 10 ? 15 : (r.directRepeatIntervalSec == 15 ? 30 : 10);
                int nextCool = r.directCooldownSec == 10 ? 25 : (r.directCooldownSec == 25 ? 60 : 10);
                r.directRepeatIntervalSec = nextRep;
                r.directCooldownSec = nextCool;
                WatchDataClient.updateAppTimers(this, r.packageName, nextRep, nextCool);
                renderAppsControl();
            });
            card.addView(btnCycleTimers);

            llAppsList.addView(card);
        }
    }

    private void renderVault() {
        llVaultList.removeAllViews();
        if (state.deletedVault == null || state.deletedVault.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.wear_empty_vault);
            tv.setTextColor(0xFFB0BEC5);
            llVaultList.addView(tv);
            return;
        }
        for (WearProtocol.MessageDto m : state.deletedVault) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(4, 6, 4, 6);

            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText("🗑️ " + m.senderName + ": " + m.messageText);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(11f);

            Button btnDel = new Button(this);
            btnDel.setText(R.string.wear_btn_x);
            btnDel.setTextSize(10f);
            btnDel.setOnClickListener(v -> {
                WatchDataClient.deleteVaultItem(this, m.id);
                state.deletedVault.remove(m);
                renderVault();
            });
            row.addView(tv);
            row.addView(btnDel);
            llVaultList.addView(row);
        }
    }

    private void renderRemote() {
        tvRemoteBattery.setText(state.phoneBatteryPct >= 0
                ? getString(R.string.wear_phone_battery_fmt, state.phoneBatteryPct)
                : getString(R.string.wear_phone_battery_unknown));
        if (state.isFindingPhone) {
            btnRemoteFindPhone.setText(R.string.wear_remote_stop_ring);
            btnRemoteFindPhone.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFC62828));
        } else {
            btnRemoteFindPhone.setText(R.string.wear_remote_ring);
            btnRemoteFindPhone.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF512DA8));
        }
    }

    private void speakCurrentChat() {
        WearProtocol.ConversationDto conv = findConversation(currentChatKey);
        if (conv == null || conv.messages == null || conv.messages.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(conv.appLabel).append(". ");
        for (WearProtocol.MessageDto m : conv.messages) {
            sb.append(m.senderName).append(": ").append(m.messageText).append(". ");
        }
        WatchAudioHelper.speakTts(this, sb.toString(), state != null ? state.ttsLanguage : "es");
    }

    private void cycleAudioSpeed() {
        float cur = prefs.getPlaybackSpeed();
        float next = cur < 1.2f ? 1.5f : (cur < 1.8f ? 2.0f : 1.0f);
        prefs.setPlaybackSpeed(next);
        WatchAudioHelper.updateSpeed(next);
        updateSpeedButtonText();
    }

    private void updateSpeedButtonText() {
        float sp = prefs.getPlaybackSpeed();
        btnAudioSpeed.setText(getString(R.string.wear_speed_fmt, sp));
    }

    private void launchVoiceOrTextReply() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.wear_btn_reply));
            voiceReplyLauncher.launch(intent);
        } catch (Exception e) {
            EditText et = new EditText(this);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.wear_btn_reply)
                    .setView(et)
                    .setPositiveButton(R.string.wear_btn_send, (d, w) -> {
                        String txt = et.getText().toString().trim();
                        if (!txt.isEmpty()) {
                            WatchDataClient.sendReply(this, currentChatKey, txt);
                            Toast.makeText(this, R.string.wear_toast_reply_sent, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.wear_btn_cancel, null)
                    .show();
        }
    }

    private WearProtocol.ConversationDto findConversation(String key) {
        if (state == null || state.conversations == null || TextUtils.isEmpty(key)) return null;
        for (WearProtocol.ConversationDto c : state.conversations) {
            if (key.equals(c.conversationKey)) return c;
        }
        return null;
    }
}
