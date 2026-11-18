package com.nitsutech.watchbridge.wear;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Custom differentiated haptic waveforms for Galaxy Watch 8 Classic:
 * 0 = Teams Double Pulse
 * 1 = WhatsApp Long-Short
 * 2 = Group Short Tap
 * 3 = Urgent / Progressive Escalation Wake-Up
 */
public class WatchHapticHelper {

    public static void playPattern(Context context, int patternId, int escalationLevel) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;

            long[] timings;
            int[] amplitudes;

            if (escalationLevel >= 3 || patternId == 3) {
                // Strong wake-up rhythm
                timings = new long[]{0, 400, 120, 400, 120, 500, 120, 500};
                amplitudes = new int[]{0, 255, 0, 255, 0, 255, 0, 255};
            } else if (patternId == 0) {
                // Teams Double Pulse
                timings = new long[]{0, 180, 90, 180};
                amplitudes = new int[]{0, 210, 0, 210};
            } else if (patternId == 1) {
                // WhatsApp Long-Short
                timings = new long[]{0, 320, 100, 120};
                amplitudes = new int[]{0, 220, 0, 160};
            } else {
                // Group Short Tap
                timings = new long[]{0, 140};
                amplitudes = new int[]{0, 165};
            }

            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
        } catch (Exception ignored) {
        }
    }
}
