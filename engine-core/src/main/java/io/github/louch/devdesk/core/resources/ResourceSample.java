package io.github.louch.devdesk.core.resources;

import java.time.Duration;
import java.time.Instant;

/**
 * One measurement of a service.
 *
 * @param cpuPercent CPU use since the previous sample, in % of one core (200% = two cores busy)
 * @param processes how many processes the service had (children included)
 * @param sockets how many sockets they had open; a count that only ever climbs is a connection leak
 */
public record ResourceSample(Instant at, double cpuPercent, long memoryBytes, int processes, int sockets) {

    /** CPU % between two readings of cumulative CPU time. */
    public static double cpuPercent(double cpuSecondsBefore, double cpuSecondsNow, Duration between) {
        double wall = between.toNanos() / 1e9;
        if (wall <= 0) {
            return 0;
        }
        return Math.max(0, cpuSecondsNow - cpuSecondsBefore) * 100.0 / wall; // a process that exited can make it shrink
    }
}
