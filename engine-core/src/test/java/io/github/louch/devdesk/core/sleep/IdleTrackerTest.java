package io.github.louch.devdesk.core.sleep;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdleTrackerTest {

    private static final Instant T = Instant.parse("2026-10-16T10:00:00Z");
    private static final Duration AFTER = Duration.ofMinutes(15);

    @Test
    void sleepsOnlyAfterBeingIdleLongEnough() {
        var idle = new IdleTracker();
        assertThat(idle.idleFor("api", false, T, AFTER)).isFalse(); // first look counts as busy
        assertThat(idle.idleFor("api", false, T.plus(Duration.ofMinutes(14)), AFTER))
                .isFalse();
        assertThat(idle.idleFor("api", false, T.plus(AFTER), AFTER)).isTrue();
    }

    @Test
    void anyUseStartsTheWaitAgain() {
        var idle = new IdleTracker();
        idle.idleFor("api", false, T, AFTER);
        idle.idleFor("api", true, T.plus(Duration.ofMinutes(10)), AFTER);
        assertThat(idle.idleFor("api", false, T.plus(Duration.ofMinutes(20)), AFTER))
                .isFalse();
        assertThat(idle.idleFor("api", false, T.plus(Duration.ofMinutes(25)), AFTER))
                .isTrue();
        idle.forget("api");
        assertThat(idle.idleFor("api", false, T.plus(Duration.ofHours(5)), AFTER))
                .isFalse();
    }
}
