package io.github.louch.devdesk.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimelineTest {

    private static final Instant T0 = Instant.parse("2026-10-05T09:00:00Z");

    private static HistoryEntry session(int minute, String event) {
        return new HistoryEntry(
                T0.plusSeconds(minute * 60L),
                "",
                HistoryEntry.Kind.SESSION,
                "DevDesk " + event,
                Map.of("event", event));
    }

    private static HistoryEntry state(int minute, String to) {
        return new HistoryEntry(T0.plusSeconds(minute * 60L), "api", HistoryEntry.Kind.STATE, to, Map.of("to", to));
    }

    @Test
    void aStartWithoutAStopBeforeItIsMarked() {
        var items = Timeline.of(
                List.of(session(0, "started"), session(5, "stopped"), session(6, "started"), session(9, "started")));

        assertThat(items).extracting(Timeline.Item::afterUncleanStop).containsExactly(false, false, false, true);
    }

    @Test
    void waitingAndStoppingAreRoutine() {
        var items = Timeline.of(List.of(state(0, "waiting"), state(1, "running"), state(2, "stopping")));

        assertThat(items).extracting(Timeline.Item::routine).containsExactly(true, false, true);
    }
}
