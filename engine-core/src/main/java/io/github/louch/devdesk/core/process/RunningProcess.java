package io.github.louch.devdesk.core.process;

import java.time.Instant;

/**
 * A process DevDesk started and still owns. Only the runtime that created it may signal it.
 *
 * @param pid the top process of the service (the {@code /bin/sh -c} wrapper)
 * @param startedAt when DevDesk started it, for display and durations in logs
 */
public record RunningProcess(String name, long pid, Instant startedAt) {}
