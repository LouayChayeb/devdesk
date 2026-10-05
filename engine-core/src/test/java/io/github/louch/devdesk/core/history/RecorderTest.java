package io.github.louch.devdesk.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ProjectEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecorderTest {

    private static final Instant T0 = Instant.parse("2026-10-04T14:00:00Z");
    private final List<HistoryEntry> kept = new ArrayList<>();
    private final HistoryStore store = new HistoryStore() {
        public void append(HistoryEntry e) {
            kept.add(e);
        }

        public List<HistoryEntry> read(Instant since) {
            return kept;
        }
    };
    private final Recorder recorder = new Recorder(store, Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void stateChangesFindingsAndWarningsAreKeptButOutputIsNot() {
        recorder.accept(new ProjectEvent.StateChanged(
                1,
                "api",
                new ServiceState.Running(7, 0),
                new ServiceState.Crashed(ExitStatus.of(1), 0),
                "exited with error code 1",
                T0));
        recorder.accept(new ProjectEvent.Output("api", new OutputLine(StreamKind.STDERR, "password=hunter2")));
        recorder.accept(new ProjectEvent.Diagnosis(
                "api",
                List.of(new Finding(
                        "crash",
                        Finding.Severity.ERROR,
                        "api",
                        "api crashed",
                        "It exited with error code 1.",
                        List.of(),
                        List.of()))));
        recorder.accept(new ProjectEvent.Warning("api", "ignored SIGTERM for 10s"));

        assertThat(kept)
                .extracting(HistoryEntry::kind, HistoryEntry::summary)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                HistoryEntry.Kind.STATE, "crashed: exited with error code 1"),
                        org.assertj.core.groups.Tuple.tuple(HistoryEntry.Kind.FINDING, "api crashed"),
                        org.assertj.core.groups.Tuple.tuple(HistoryEntry.Kind.WARNING, "ignored SIGTERM for 10s"));
        assertThat(kept.toString()).doesNotContain("hunter2");
    }

    @Test
    void resourcesAreThinnedToOneEveryTenSecondsPerService() {
        for (int i = 0; i < 15; i++) { // 30 s of samples every 2 s
            recorder.accept(new ProjectEvent.Resources(Map.of(
                    "api", new ResourceSample(T0.plusSeconds(i * 2L), 5, 100, 1, 2),
                    "db", new ResourceSample(T0.plusSeconds(i * 2L), 1, 200, 1, 2))));
        }

        assertThat(kept.stream().filter(e -> e.service().equals("api"))).hasSize(3); // at 0 s, 10 s and 20 s
        assertThat(kept.stream().filter(e -> e.service().equals("db"))).hasSize(3);
    }
}
