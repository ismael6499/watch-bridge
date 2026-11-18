package com.nitsutech.watchbridge;

import com.nitsutech.watchbridge.engine.CooldownTracker;

import org.junit.Test;

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
}
