package io.github.louch.devdesk.core.sleep;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers when each service was last busy (a connection to its ports, or CPU in use) and says when it has been
 * idle long enough to sleep. Time comes from the caller, so it's testable.
 */
public final class IdleTracker {

    /** Below this CPU a service counts as doing nothing (timers and housekeeping use a little). */
    public static final double BUSY_CPU_PERCENT = 2.0;

    private final Map<String, Instant> lastBusy = new HashMap<>();

    /**
     * @param busy a connection to its ports right now, or CPU above {@link #BUSY_CPU_PERCENT}
     * @return true once it has been idle for {@code after} (its first look counts as busy: no sleeping on start)
     */
    public synchronized boolean idleFor(String service, boolean busy, Instant now, Duration after) {
        Instant last = lastBusy.get(service);
        if (busy || last == null) {
            lastBusy.put(service, now);
            return false;
        }
        return !Duration.between(last, now).minus(after).isNegative();
    }

    /** Starts over for a service (it woke up, or was started by hand). */
    public synchronized void forget(String service) {
        lastBusy.remove(service);
    }
}
