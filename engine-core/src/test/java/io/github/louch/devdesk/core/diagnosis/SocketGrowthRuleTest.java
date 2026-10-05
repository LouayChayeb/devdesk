package io.github.louch.devdesk.core.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class SocketGrowthRuleTest {

    private static final Instant T0 = Instant.parse("2026-10-04T14:00:00Z");
    private final FakeIntrospection machine = new FakeIntrospection();

    /** One sample every 10 s for {@code minutes}, sockets given per sample index. */
    private static List<ResourceSample> samples(int minutes, IntUnaryOperator sockets) {
        List<ResourceSample> list = new ArrayList<>();
        for (int i = 0; i <= minutes * 6; i++) {
            list.add(new ResourceSample(T0.plusSeconds(i * 10L), 5, 100, 1, sockets.applyAsInt(i)));
        }
        return list;
    }

    private List<Finding> evaluate(List<ResourceSample> samples) throws Exception {
        var project = ConfigParser.parse("""
                version: 1
                services:
                  db: { command: python3 db.py, ports: [5433] }
                  api: { command: node server.js, ports: [3001] }
                """, Path.of("/p"), Map.of(), f -> Optional.empty());
        var history = new ServiceHistory(List.of(), List.of(), List.of(), samples, true, Set.of(1L), false);
        var ctx = new DiagnosisContext(
                project,
                Map.of("api", new ServiceState.Healthy(1, 0)),
                Map.of("api", history),
                Map.of(),
                machine,
                T0.plusSeconds(3600));
        return new SocketGrowthRule().evaluate(ctx, "api");
    }

    @Test
    void steadyClimbIsReportedWithWhereTheConnectionsGo() throws Exception {
        for (int port = 40_000; port < 40_150; port++) {
            machine.connections.add(new Connection(1, "127.0.0.1", port, "127.0.0.1", 5433));
        }
        machine.connections.add(new Connection(1, "127.0.0.1", 3001, "127.0.0.1", 39_999)); // someone calling api

        // 5 sockets, +1 every 20 s: +3 per minute for 40 minutes.
        var findings = evaluate(samples(40, i -> 5 + i / 2));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("api keeps opening connections and never closes them");
            assertThat(f.explanation()).contains("+3 per minute").contains("most of them to db :5433");
            assertThat(f.evidence()).contains("open now: db :5433  150");
        });
    }

    @Test
    void theServiceReceivingTheLeakedConnectionsIsNotBlamed() throws Exception {
        // api's sockets climb, but almost all are callers connected *to* its port 3001: the leak is theirs.
        for (int port = 40_000; port < 40_150; port++) {
            machine.connections.add(new Connection(1, "127.0.0.1", 3001, "127.0.0.1", port));
        }
        machine.connections.add(new Connection(1, "127.0.0.1", 41_000, "127.0.0.1", 5433));

        assertThat(evaluate(samples(40, i -> 5 + i / 2))).isEmpty();
    }

    @Test
    void aBusyButStableServiceIsFine() throws Exception {
        // 200 sockets going up and down with traffic, but not climbing.
        assertThat(evaluate(samples(40, i -> 200 + (i % 7) * 10))).isEmpty();
    }

    @Test
    void smallGrowthIsIgnored() throws Exception {
        assertThat(evaluate(samples(40, i -> 10 + i / 20))).isEmpty(); // 10 → 22: steady but harmless
    }
}
