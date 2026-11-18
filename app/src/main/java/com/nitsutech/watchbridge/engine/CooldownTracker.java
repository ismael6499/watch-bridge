package com.nitsutech.watchbridge.engine;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks burst cooling time windows per conversation so rapid message bursts do not buzz repeatedly.
 */
public class CooldownTracker {

    private static final ConcurrentHashMap<String, Long> LAST_ALERT_MS = new ConcurrentHashMap<>();

    public static synchronized boolean isInCooldown(String conversationKey, int cooldownSeconds, long nowMs) {
        if (cooldownSeconds <= 0 || conversationKey == null) {
            return false;
        }
        Long last = LAST_ALERT_MS.get(conversationKey);
        if (last == null) {
            return false;
        }
        return (nowMs - last) < (cooldownSeconds * 1000L);
    }

    public static synchronized void recordAlertDispatched(String conversationKey, long nowMs) {
        if (conversationKey != null) {
            LAST_ALERT_MS.put(conversationKey, nowMs);
        }
    }

    public static synchronized void clearCooldown(String conversationKey) {
        if (conversationKey != null) {
            LAST_ALERT_MS.remove(conversationKey);
        }
    }

    public static synchronized void clearAll() {
        LAST_ALERT_MS.clear();
    }
}
