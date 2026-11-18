package com.nitsutech.watchbridge.engine;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.AppRuleEntity;
import com.nitsutech.watchbridge.data.PrefsManager;
import com.nitsutech.watchbridge.data.SenderRuleEntity;

import java.util.List;
import java.util.Locale;

public class RuleEvaluator {

    public static class Decision {
        public boolean shouldBridge = false;
        public boolean shouldRepeat = false;
        public boolean sourceSilent = false;
        public boolean coalescedByCooldown = false;
        public boolean suppressedPhoneInUse = false;
        public boolean suppressedAfterHours = false;
        public boolean isVip = false;
        public boolean isUrgentKeyword = false;
        public int repeatIntervalSec = 12;
        public int cooldownSec = 10;
        public int phoneSoundMode = 1; // 0 watch only, 1 watch+phone, 2 loud phone alarm
        public int hapticPattern = 0;
        public String matchedRuleName = "Default";
    }

    public static Decision evaluate(Context context, ParsedMessage msg, AppRuleEntity appRule,
                                    List<SenderRuleEntity> senderRules, PrefsManager prefs, long nowMs) {
        Decision d = new Decision();
        if (msg == null || appRule == null) {
            return d;
        }

        int profile = prefs.getActiveProfile();
        boolean repeatMode = profile == PrefsManager.PROFILE_REPEAT;
        boolean workMode = profile == PrefsManager.PROFILE_WORK;
        boolean afterHoursMode = profile == PrefsManager.PROFILE_AFTER_HOURS;

        // 1. Check urgent keywords
        d.isUrgentKeyword = containsUrgentKeyword(msg.senderName + " " + msg.messageText, prefs.getUrgentKeywordsList());

        // 2. Priority 1: Match most specific SenderRule
        SenderRuleEntity matchedSender = findMatchingSenderRule(msg, senderRules);
        if (matchedSender != null) {
            d.isVip = matchedSender.isVip;
            d.matchedRuleName = matchedSender.senderOrChatName;
            d.repeatIntervalSec = Math.max(3, matchedSender.repeatIntervalSec);
            d.cooldownSec = Math.max(0, matchedSender.cooldownSec);
            if (matchedSender.actionMode == 2 && !d.isVip && !d.isUrgentKeyword) {
                d.shouldBridge = false;
                return d;
            }
            d.shouldBridge = true;
            d.shouldRepeat = repeatMode && (matchedSender.actionMode == 0 || d.isVip || d.isUrgentKeyword);
            d.phoneSoundMode = matchedSender.phoneAlarmOverride ? 2 : appRule.phoneSoundMode;
            d.hapticPattern = (d.isVip || d.isUrgentKeyword) ? 3 : appRule.hapticPattern;
        } else {
            // 3. Priority 2: App-level & Chat-Type rule
            if (!appRule.enabled && !d.isUrgentKeyword) {
                return d;
            }

            boolean typeEnabled = msg.isGroup ? appRule.groupEnabled : appRule.directEnabled;
            if (!typeEnabled && !d.isUrgentKeyword) {
                return d;
            }

            d.shouldBridge = true;
            d.matchedRuleName = appRule.appName + (msg.isGroup ? " (Group)" : " (Direct)");
            boolean typeRepeat = msg.isGroup ? appRule.groupRepeatEnabled : appRule.directRepeatEnabled;
            d.shouldRepeat = repeatMode && (typeRepeat || d.isUrgentKeyword || msg.isIncomingCall);
            d.repeatIntervalSec = msg.isGroup ? Math.max(3, appRule.groupRepeatIntervalSec) : Math.max(3, appRule.directRepeatIntervalSec);
            d.cooldownSec = msg.isGroup ? Math.max(0, appRule.groupCooldownSec) : Math.max(0, appRule.directCooldownSec);
            d.phoneSoundMode = appRule.phoneSoundMode;
            d.hapticPattern = msg.isGroup ? 2 : appRule.hapticPattern;
        }

        // Call Boost during Repeat Mode
        if (msg.isIncomingCall && repeatMode && prefs.isCallBoostEnabled()) {
            d.shouldBridge = true;
            d.shouldRepeat = true;
            d.repeatIntervalSec = 4;
            d.cooldownSec = 0;
            d.phoneSoundMode = 2;
            d.hapticPattern = 3;
        }

        // Temporary Chat Mute check (can only be bypassed by VIP or incoming call)
        if (prefs.isChatMuted(msg.conversationKey) && !d.isVip && !msg.isIncomingCall) {
            d.shouldBridge = false;
            d.shouldRepeat = false;
            return d;
        }

        // After-Hours silenced app check: do not alert Watch/Phone, but flag so service still saves message to app history
        if (afterHoursMode && prefs.isPackageMutedInAfterHours(msg.packageName)
                && !d.isVip && !d.isUrgentKeyword && !msg.isIncomingCall) {
            d.shouldBridge = false;
            d.shouldRepeat = false;
            d.suppressedAfterHours = true;
            return d;
        }

        // Idea #9: Optional Smart Phone-In-Use Suppression in non-Repeat profiles
        if (!repeatMode && prefs.isPhoneInUseSuppressionEnabled() && !d.isVip && !msg.isIncomingCall && isPhoneActivelyInUse(context)) {
            d.shouldBridge = false;
            d.shouldRepeat = false;
            d.suppressedPhoneInUse = true;
            return d;
        }

        // Source chat is muted / silent (no sound & no vibration in WhatsApp/Teams):
        // Bridge normally to thread & watch without repeating and without vibrating the watch.
        if (msg.isSourceSilent && !d.isVip && !d.isUrgentKeyword && !msg.isIncomingCall) {
            d.sourceSilent = true;
            d.shouldRepeat = false;
            d.phoneSoundMode = 0;
            return d;
        }

        // Burst Cooling Time check (VIPs, Calls, and Urgent Keywords bypass burst cooldown)
        if (!d.isVip && !d.isUrgentKeyword && !msg.isIncomingCall) {
            if (CooldownTracker.isInCooldown(msg.conversationKey, d.cooldownSec, nowMs)) {
                d.coalescedByCooldown = true;
                return d;
            }
        }

        CooldownTracker.recordAlertDispatched(msg.conversationKey, nowMs);
        return d;
    }

    private static SenderRuleEntity findMatchingSenderRule(ParsedMessage msg, List<SenderRuleEntity> rules) {
        if (rules == null || rules.isEmpty()) return null;
        String senderLower = msg.senderName.toLowerCase(Locale.ROOT);
        String titleLower = msg.conversationTitle.toLowerCase(Locale.ROOT);
        SenderRuleEntity best = null;
        int bestScore = -1;
        for (SenderRuleEntity r : rules) {
            if (r == null || r.senderOrChatName == null || r.senderOrChatName.trim().isEmpty()) continue;
            if (!"*".equals(r.packageFilter) && !r.packageFilter.equals(msg.packageName)) continue;
            if (r.chatTypeFilter == 1 && msg.isGroup) continue;
            if (r.chatTypeFilter == 2 && !msg.isGroup) continue;

            String q = r.senderOrChatName.toLowerCase(Locale.ROOT).trim();
            boolean matches = senderLower.contains(q) || titleLower.contains(q);
            if (!matches) continue;

            int score = q.length() + (r.isVip ? 100 : 0) + ("*".equals(r.packageFilter) ? 0 : 20);
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private static boolean containsUrgentKeyword(String haystack, List<String> keywords) {
        if (haystack == null || keywords == null || keywords.isEmpty()) return false;
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private static boolean isPhoneActivelyInUse(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            boolean screenOn = pm != null && pm.isInteractive();
            boolean unlocked = km == null || !km.isKeyguardLocked();
            return screenOn && unlocked;
        } catch (Exception e) {
            return false;
        }
    }
}
