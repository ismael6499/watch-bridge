package com.nitsutech.watchbridge;

import com.nitsutech.watchbridge.engine.CooldownTracker;
import com.nitsutech.watchbridge.receiver.ScheduleAndTimerReceiver;

import org.junit.Test;

import java.util.Calendar;

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
}
