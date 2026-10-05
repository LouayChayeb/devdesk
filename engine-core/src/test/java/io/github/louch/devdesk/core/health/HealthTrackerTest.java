package io.github.louch.devdesk.core.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.state.ServiceEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HealthTrackerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final HealthCheckSpec.Timing TIMING =
            new HealthCheckSpec.Timing(Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofSeconds(30));
    private static final ProbeResult OK = new ProbeResult.Passed("HTTP 200");
    private static final ProbeResult DOWN = new ProbeResult.Failed("connection refused");

    private final HealthTracker tracker = new HealthTracker(TIMING, T0);

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void failuresWhileBootingDontCountAndFirstSuccessIsHealthy() {
        assertThat(tracker.record(DOWN, at(1))).isEmpty();
        assertThat(tracker.record(DOWN, at(2))).isEmpty();
        assertThat(tracker.record(DOWN, at(3))).isEmpty();
        assertThat(tracker.record(OK, at(4))).contains(new ServiceEvent.HealthPassed());
        assertThat(tracker.record(OK, at(5))).isEmpty(); // already healthy: no repeated event
    }

    @Test
    void itTakesRetriesFailuresInARowToBecomeUnhealthy() {
        tracker.record(OK, at(1));
        assertThat(tracker.record(DOWN, at(2))).isEmpty();
        assertThat(tracker.record(DOWN, at(3))).isEmpty();
        assertThat(tracker.record(DOWN, at(4)))
                .contains(new ServiceEvent.HealthFailed("connection refused (3 checks in a row)"));
        assertThat(tracker.record(DOWN, at(5))).isEmpty();
        assertThat(tracker.record(OK, at(6))).contains(new ServiceEvent.HealthPassed()); // one success recovers
    }

    @Test
    void oneGoodAnswerResetsTheFailureCount() {
        tracker.record(OK, at(1));
        tracker.record(DOWN, at(2));
        tracker.record(DOWN, at(3));
        tracker.record(OK, at(4));
        assertThat(tracker.record(DOWN, at(5))).isEmpty();
        assertThat(tracker.record(DOWN, at(6))).isEmpty();
    }

    @Test
    void neverHealthyAfterStartPeriodBecomesUnhealthy() {
        assertThat(tracker.record(DOWN, at(31))).isEmpty();
        assertThat(tracker.record(DOWN, at(32))).isEmpty();
        assertThat(tracker.record(DOWN, at(33)))
                .hasValueSatisfying(e -> assertThat(e).isInstanceOf(ServiceEvent.HealthFailed.class));
    }
}
