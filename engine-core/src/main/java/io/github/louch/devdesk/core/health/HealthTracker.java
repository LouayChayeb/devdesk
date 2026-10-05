package io.github.louch.devdesk.core.health;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.state.ServiceEvent;
import java.time.Instant;
import java.util.Optional;

/**
 * Turns individual probe results into health events, with "hysteresis": one success makes a service healthy, but it
 * takes {@code retries} failures in a row to call it unhealthy, so one slow response doesn't flap the state.
 *
 * <p>Not thread-safe: owned by the project's single event thread.
 */
public final class HealthTracker {

    private final HealthCheckSpec.Timing timing;
    private final Instant startedAt;
    private int failuresInARow;
    private Optional<Boolean> reportedHealthy = Optional.empty();

    public HealthTracker(HealthCheckSpec.Timing timing, Instant startedAt) {
        this.timing = timing;
        this.startedAt = startedAt;
    }

    /** Records a result taken at {@code now}; returns the event to apply, if the health verdict changed. */
    public Optional<ServiceEvent> record(ProbeResult result, Instant now) {
        return switch (result) {
            case ProbeResult.Passed p -> {
                failuresInARow = 0;
                yield report(true, new ServiceEvent.HealthPassed());
            }
            case ProbeResult.Failed f -> {
                boolean booting = reportedHealthy.isEmpty() && now.isBefore(startedAt.plus(timing.startPeriod()));
                if (booting) {
                    yield Optional.empty(); // apps need time to boot: failures in start_period don't count
                }
                failuresInARow++;
                yield failuresInARow >= timing.retries()
                        ? report(
                                false,
                                new ServiceEvent.HealthFailed(f.detail() + " (" + failuresInARow + " checks in a row)"))
                        : Optional.empty();
            }
        };
    }

    private Optional<ServiceEvent> report(boolean healthy, ServiceEvent event) {
        if (reportedHealthy.equals(Optional.of(healthy))) {
            return Optional.empty(); // no change, no event
        }
        reportedHealthy = Optional.of(healthy);
        return Optional.of(event);
    }
}
