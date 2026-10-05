package io.github.louch.devdesk.core.resources;

import java.util.Set;

/** Measures CPU time, memory and open sockets of a group of processes. Linux reads /proc; tests use a fake. */
public interface ResourceSampler {

    /**
     * Totals right now. CPU time only ever grows, so a percentage needs two samples (see {@link ResourceSample}).
     *
     * @param cpuSeconds CPU time used so far (user + system), summed over the processes
     * @param memoryBytes resident memory (RAM really in use), summed
     * @param sockets open sockets (network connections, listeners, local sockets), summed
     */
    record Usage(double cpuSeconds, long memoryBytes, int sockets) {}

    /** Processes that vanish while being measured count as zero: normal, not an error. */
    Usage sample(Set<Long> pids);
}
