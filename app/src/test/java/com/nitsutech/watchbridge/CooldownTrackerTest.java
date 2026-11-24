package com.nitsutech.watchbridge;

import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.AppRuleEntity;
import com.nitsutech.watchbridge.data.SenderRuleEntity;
import com.nitsutech.watchbridge.engine.CooldownTracker;
import com.nitsutech.watchbridge.engine.NotificationParser;
import com.nitsutech.watchbridge.engine.RuleEvaluator;
import com.nitsutech.watchbridge.receiver.ScheduleAndTimerReceiver;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CooldownTrackerTest {

    @Test
    public void cooldownWindowAbsorbsBurstsAndExpiresCleanly() {
        CooldownTracker.clearAll();
        String key = "com.whatsapp|dm|juan";

        assertFalse(CooldownTracker.isInCooldown(key, 15, 10_000L));
        CooldownTracker.recordAlertDispatched(key, 10_000L);

        // Within 15 seconds cooling window -> absorbed
        assertTrue(CooldownTracker.isInCooldown(key, 15, 15_000L));
        assertTrue(CooldownTracker.isInCooldown(key, 15, 24_999L));

        // After 15 seconds -> next alert fires normally
        assertFalse(CooldownTracker.isInCooldown(key, 15, 25_000L));
    }

    @Test
    public void afterHoursOvernightAndWeekdaysWindowChecksWork() {
        // Mon-Fri 18:00 - 09:00
        assertFalse(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.WEDNESDAY, 17 * 60 + 59, 18, 0, 9, 0, true));
        assertTrue(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.WEDNESDAY, 18 * 60, 18, 0, 9, 0, true));
        assertTrue(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.THURSDAY, 8 * 60 + 30, 18, 0, 9, 0, true));
        assertFalse(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.THURSDAY, 9 * 60, 18, 0, 9, 0, true));

        // Friday night continues into Saturday 08:30, then Saturday night stays off when weekdaysOnly=true
        assertTrue(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.SATURDAY, 8 * 60 + 30, 18, 0, 9, 0, true));
        assertFalse(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.SATURDAY, 20 * 60, 18, 0, 9, 0, true));
        // Daily (weekdaysOnly=false) includes Saturday night
        assertTrue(ScheduleAndTimerReceiver.isWithinAfterHoursWindow(
                Calendar.SATURDAY, 20 * 60, 18, 0, 9, 0, false));
    }

    @Test
    public void perDaySilentScheduleHandlesCustomDaysAndAllDay() {
        int onlyTueAndFriMask = (1 << (Calendar.TUESDAY - 1)) | (1 << (Calendar.FRIDAY - 1));
        assertTrue(ScheduleAndTimerReceiver.isSilentRuleActiveNow(
                2, 9, 0, 18, 0, onlyTueAndFriMask, Calendar.TUESDAY, 10 * 60));
        assertFalse(ScheduleAndTimerReceiver.isSilentRuleActiveNow(
                2, 9, 0, 18, 0, onlyTueAndFriMask, Calendar.WEDNESDAY, 10 * 60));
        assertTrue(ScheduleAndTimerReceiver.isSilentRuleActiveNow(
                1, 0, 0, 0, 0, 0, Calendar.SUNDAY, 3 * 60));
    }

    @Test
    public void silentOnWatchTwoLevelPrecedenceAndVipWins() {
        AppRuleEntity app = new AppRuleEntity(
                AppDatabase.PKG_TEAMS, "Microsoft Teams", true,
                true, true, 12, 0,
                true, false, 20, 0,
                1, 0
        );
        app.silentWatchMode = 1; // App silent all day on watch even if phone sounds

        // 1. Inherits AppRule silent all day when no SenderRule overrides it
        assertTrue(RuleEvaluator.shouldSilenceOnWatch(app, null, false, false, false, 1_000L));

        // 2. SenderRule with silentWatchMode=0 inherits AppRule silence
        SenderRuleEntity chatRule = new SenderRuleEntity();
        chatRule.senderOrChatName = "Tomas";
        chatRule.packageFilter = AppDatabase.PKG_TEAMS;
        chatRule.silentWatchMode = 0;
        assertTrue(RuleEvaluator.shouldSilenceOnWatch(app, chatRule, false, false, false, 1_000L));

        // 3. SenderRule with silentWatchMode=3 explicitly overrides AppRule silence
        chatRule.silentWatchMode = 3;
        assertFalse(RuleEvaluator.shouldSilenceOnWatch(app, chatRule, false, false, false, 2_000L));

        // 4. SenderRule silent all day works even when AppRule is off
        app.silentWatchMode = 0;
        chatRule.silentWatchMode = 1;
        assertTrue(RuleEvaluator.shouldSilenceOnWatch(app, chatRule, false, false, false, 3_000L));

        // 5. VIP / Urgent / Calls win over any silent rule
        assertFalse(RuleEvaluator.shouldSilenceOnWatch(app, chatRule, true, false, false, 3_000L));
        assertFalse(RuleEvaluator.shouldSilenceOnWatch(app, chatRule, false, true, false, 3_000L));
        assertFalse(RuleEvaluator.shouldSilenceOnWatch(app, chatRule, false, false, true, 3_000L));
    }

    @Test
    public void photoCountParsingDetectsSingleAndMultiPhotoNotifications() {
        assertEquals(4, NotificationParser.parseExpectedPhotoCount("📷 4 photos"));
        assertEquals(4, NotificationParser.parseExpectedPhotoCount("4 fotos"));
        assertEquals(4, NotificationParser.parseExpectedPhotoCount("📷 7 photos"));
        assertEquals(1, NotificationParser.parseExpectedPhotoCount("📷 Photo"));
        assertEquals(1, NotificationParser.parseExpectedPhotoCount("Foto"));
        assertEquals(0, NotificationParser.parseExpectedPhotoCount("Hola todo bien"));
    }
}
