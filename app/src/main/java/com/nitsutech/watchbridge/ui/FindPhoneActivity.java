package com.nitsutech.watchbridge.ui;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.service.AlertAudioHelper;

public class FindPhoneActivity extends AppCompatActivity {

    public static final String ACTION_CLOSE_FIND_PHONE = "com.nitsutech.watchbridge.CLOSE_FIND_PHONE";

    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) {
                km.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
        setContentView(R.layout.activity_find_phone);

        findViewById(R.id.btn_find_phone_stop).setOnClickListener(v -> {
            AlertAudioHelper.stopFindMyPhoneRing(this);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(ACTION_CLOSE_FIND_PHONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(closeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
