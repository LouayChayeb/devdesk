package io.github.louch.devdesk.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.RestartPolicy;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    private static ServiceSpec service(String name, String... needs) {
        return new ServiceSpec(
                name,
                new Workload.Command("true"),
                Path.of("/"),
                Map.of(),
                Arrays.stream(needs)
                        .map(n -> new Dependency(n, Dependency.Condition.STARTED))
                        .toList(),
                List.of(),
                new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT),
                RestartPolicy.NO,
                Duration.ofSeconds(1));
    }

    // db and cache have no dependencies; api needs both; worker needs db; web needs api.
    private static final List<ServiceSpec> SHOP = List.of(
            service("web", "api"),
            service("api", "db", "cache"),
            service("worker", "db"),
            service("db"),
            service("cache"));

    @Test
    void servicesStartInLevelsAndInFileOrderWithinALevel() throws Exception {
        assertThat(DependencyGraph.of(SHOP).startLevels())
                .containsExactly(List.of("db", "cache"), List.of("api", "worker"), List.of("web"));
    }

    @Test
    void stopOrderIsStartOrderReversed() throws Exception {
        assertThat(DependencyGraph.of(SHOP).stopOrder()).containsExactly("web", "worker", "api", "cache", "db");
    }

    @Test
    void impactAndPartialStartFollowTheEdges() throws Exception {
        var graph = DependencyGraph.of(SHOP);

        assertThat(graph.dependentsOf("db")).containsExactlyInAnyOrder("api", "worker", "web");
        assertThat(graph.withDependencies("web")).containsExactlyInAnyOrder("web", "api", "db", "cache");
        assertThat(graph.withDependencies("db")).containsExactly("db");
    }

    @Test
    void cycleIsReportedWithItsPath() {
        var cycle = catchThrowableOfType(
                DependencyCycleException.class,
                () -> DependencyGraph.of(List.of(
                        service("db"),
                        service("api", "db", "worker"),
                        service("worker", "queue"),
                        service("queue", "api"))));

        assertThat(cycle.cycle()).containsExactly("api", "worker", "queue", "api");
        assertThat(cycle.getMessage()).startsWith("dependency cycle: api → worker → queue → api");
    }
}
