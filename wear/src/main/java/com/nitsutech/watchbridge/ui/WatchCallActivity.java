package com.nitsutech.watchbridge.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.service.WatchWearListenerService;
import com.nitsutech.watchbridge.wear.WatchAudioHelper;
import com.nitsutech.watchbridge.wear.WatchDataClient;
import com.nitsutech.watchbridge.wear.WatchHapticHelper;
import com.nitsutech.watchbridge.wear.WatchNotifHelper;

public class WatchCallActivity extends AppCompatActivity {

    public static final String EXTRA_CONV_KEY = "call_conv_key";
    public static final String EXTRA_CALLER_NAME = "call_caller_name";
    public static final String EXTRA_APP_LABEL = "call_app_label";
    public static final String EXTRA_CAN_ANSWER = "call_can_answer";
    public static final String EXTRA_CAN_DECLINE = "call_can_decline";

    private String conversationKey = "";

    private final BroadcastReceiver callEndReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finishSafely();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_watch_call);
        bindFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindFromIntent(intent);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(WatchWearListenerService.ACTION_WATCH_CALL_ENDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(callEndReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(callEndReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void bindFromIntent(Intent intent) {
        if (intent == null) return;
        conversationKey = intent.getStringExtra(EXTRA_CONV_KEY);
        if (conversationKey == null) conversationKey = "";
        String caller = intent.getStringExtra(EXTRA_CALLER_NAME);
        String appLabel = intent.getStringExtra(EXTRA_APP_LABEL);
        boolean canAnswer = intent.getBooleanExtra(EXTRA_CAN_ANSWER, false);
        boolean canDecline = intent.getBooleanExtra(EXTRA_CAN_DECLINE, false);

        TextView tvApp = findViewById(R.id.tv_call_app);
        TextView tvCaller = findViewById(R.id.tv_call_caller);
        Button btnAnswer = findViewById(R.id.btn_call_answer);
        Button btnDecline = findViewById(R.id.btn_call_decline);
        Button btnSilence = findViewById(R.id.btn_call_silence);

        tvApp.setText(!TextUtils.isEmpty(appLabel) ? appLabel : getString(R.string.app_name));
        tvCaller.setText(!TextUtils.isEmpty(caller) ? caller : getString(R.string.wear_call_incoming));

        btnAnswer.setVisibility(canAnswer ? View.VISIBLE : View.GONE);
        btnDecline.setVisibility(canDecline ? View.VISIBLE : View.GONE);

        btnAnswer.setOnClickListener(v -> {
            stopLocalRinging();
            WatchDataClient.answerCall(this, conversationKey);
            finishSafely();
        });

        btnDecline.setOnClickListener(v -> {
            stopLocalRinging();
            WatchDataClient.declineCall(this, conversationKey);
            finishSafely();
        });

        btnSilence.setOnClickListener(v -> {
            stopLocalRinging();
            finishSafely();
        });
    }

    private void stopLocalRinging() {
        WatchHapticHelper.stopCallRingLoop();
        WatchAudioHelper.stopCallRingtone();
        WatchNotifHelper.cancelCallNotification(this);
        if (!TextUtils.isEmpty(conversationKey)) {
            WatchNotifHelper.cancelForChat(this, conversationKey);
        }
    }

    private void finishSafely() {
        if (!isFinishing()) {
            finish();
        }
    }
}
