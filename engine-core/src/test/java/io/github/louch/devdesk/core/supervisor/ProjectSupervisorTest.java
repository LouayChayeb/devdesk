package io.github.louch.devdesk.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.diagnosis.ProcessInfo;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.resources.ResourceSampler;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProjectSupervisorTest {

    private final ScriptedRuntime runtime = new ScriptedRuntime();
    private final ScriptedContainers containers = new ScriptedContainers();
    private final List<ProjectEvent> events = new CopyOnWriteArrayList<>();
    /** Services whose health probe fails; everything else passes. */
    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();

    private final AtomicInteger dbProbes = new AtomicInteger();
    private final Map<Integer, Listener> busyPorts = new ConcurrentHashMap<>();
    private ProjectSupervisor supervisor;

    private ProjectSupervisor supervisor(String yaml) throws Exception {
        ProjectSpec project = ConfigParser.parse(yaml, Path.of("/tmp"), Map.of(), f -> Optional.empty());
        supervisor = new ProjectSupervisor(
                project,
                DependencyGraph.of(project.services().values()),
                new ProjectSupervisor.Ports(
                        runtime,
                        containers,
                        (check, dir, env) -> {
                            // Health checks in these tests are "command" checks whose command is the service name.
                            String service =
                                    ((io.github.louch.devdesk.core.project.HealthCheckSpec.Command) check).command();
                            if (service.equals("db") && dbProbes.incrementAndGet() < 3) {
                                return new ProbeResult.Failed("db still booting");
                            }
                            return unhealthy.contains(service)
                                    ? new ProbeResult.Failed(service + " answers 503")
                                    : new ProbeResult.Passed(service + " ok");
                        },
                        machine(),
                        DiagnosisEngine.standard(),
                        pids -> new ResourceSampler.Usage(0, 0, 0)),
                new ProjectSupervisor.Settings(
                        Map.of(), Clock.systemUTC(), new Random(1), Duration.ofMillis(5), Duration.ofMillis(50)),
                events::add);
        return supervisor;
    }

    /** A pretend machine: only the ports a test marks as busy are taken, and no program is installed. */
    private Introspection machine() {
        return new Introspection() {
            public Optional<Listener> listenerOn(int port) {
                return Optional.ofNullable(busyPorts.get(port));
            }

            public List<Listener> listenersOf(Set<Long> pids) {
                return List.of();
            }

            @Override
            public List<Connection> connectionsOf(Set<Long> pids) {
                return List.of();
            }

            public Optional<Path> findExecutable(String program, String path) {
                return Optional.empty();
            }

            public Optional<ProcessInfo> process(long pid) {
                return Optional.empty();
            }

            public List<ProcessInfo> processesIn(Path folder) {
                return List.of();
            }

            public FileStatus fileStatus(Path file) {
                return FileStatus.MISSING;
            }

            public Optional<String> readFile(Path file) {
                return Optional.empty();
            }
        };
    }

    @AfterEach
    void close() {
        if (supervisor != null) {
            supervisor.close();
        }
    }

    private static final String FAST = "interval: 10ms, timeout: 1s, retries: 2, start_period: 0s";

    private static final String SHOP = """
            version: 1
            services:
              web:
                command: serve
                depends_on: [api]
              api:
                command: serve
                depends_on: { db: { condition: healthy } }
                health: { type: command, command: api, %1$s }
              db:
                command: serve
                health: { type: command, command: db, interval: 10ms, retries: 2, start_period: 5s }
            """.formatted(FAST);

    private List<String> transitions() {
        return events.stream()
                .filter(e -> e instanceof ProjectEvent.StateChanged)
                .map(e -> (ProjectEvent.StateChanged) e)
                .map(c -> c.service() + "→" + c.to().label())
                .toList();
    }

    @Test
    void startsInDependencyOrderAndWaitsForHealth() throws Exception {
        Map<String, ServiceState> states = supervisor(SHOP).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("db")).isInstanceOf(ServiceState.Healthy.class);
        assertThat(states.get("api")).isInstanceOf(ServiceState.Healthy.class);
        assertThat(states.get("web")).isInstanceOf(ServiceState.Running.class);
        assertThat(runtime.started).containsExactly("db", "api", "web");
        // api may only start after db's health check finally passed (it failed twice first).
        assertThat(transitions().indexOf("db→healthy")).isLessThan(transitions().indexOf("api→starting"));
        // web only needs api "started", so it may begin before api is healthy, but never before api runs.
        assertThat(transitions().indexOf("api→running"))
                .isLessThan(transitions().indexOf("web→starting"));
    }

    @Test
    void stopsDependentsBeforeTheirDependencies() throws Exception {
        var s = supervisor(SHOP);
        s.up().get(10, TimeUnit.SECONDS);

        Map<String, ServiceState> states = s.down().get(10, TimeUnit.SECONDS);

        assertThat(states.values()).allMatch(st -> st instanceof ServiceState.Stopped);
        assertThat(runtime.stopped).containsExactly("web", "api", "db");
    }

    @Test
    void brokenDependencyBlocksEverythingAboveItWithTheRootCause() throws Exception {
        Map<String, ServiceState> states = supervisor(
                        SHOP.replace("  db:\n    command: serve", "  db:\n    command: no-launch"))
                .up()
                .get(10, TimeUnit.SECONDS);

        assertThat(states.get("db")).isInstanceOf(ServiceState.FailedToStart.class);
        assertThat(states.get("api"))
                .isEqualTo(
                        new ServiceState.Blocked("db failed to start: working directory /nope does not exist", "db"));
        assertThat(states.get("web"))
                .isInstanceOfSatisfying(
                        ServiceState.Blocked.class,
                        b -> assertThat(b.reason())
                                .isEqualTo(
                                        "api is blocked: db failed to start: working directory /nope does not exist"));
        assertThat(runtime.started).isEmpty();
    }

    @Test
    void crashingServiceIsRestartedThenGivenUpOnAsCrashLoop() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  worker: { command: exit 1, restart: on-failure }
                """);
        s.up().get(10, TimeUnit.SECONDS); // "running" counts as settled for a moment, before the first crash

        awaitState(s, "worker", ServiceState.CrashLoop.class);
        assertThat(runtime.started).hasSize(ProjectSupervisor.MAX_RESTARTS + 1);
    }

    @Test
    void migrationMustCompleteBeforeTheApiStarts() throws Exception {
        Map<String, ServiceState> states = supervisor("""
                version: 1
                services:
                  migrate: { command: exit 0 }
                  api: { command: serve, depends_on: { migrate: { condition: completed_successfully } } }
                """).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("migrate")).isInstanceOf(ServiceState.Completed.class);
        assertThat(states.get("api")).isInstanceOf(ServiceState.Running.class);
        assertThat(runtime.started).containsExactly("migrate", "api");
    }

    @Test
    void failedMigrationBlocksTheApi() throws Exception {
        Map<String, ServiceState> states = supervisor("""
                version: 1
                services:
                  migrate: { command: exit 3 }
                  api: { command: serve, depends_on: { migrate: { condition: completed_successfully } } }
                """).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("api"))
                .isEqualTo(new ServiceState.Blocked("migrate crashed: exited with error code 3", "migrate"));
    }

    @Test
    void serviceThatNeverGetsHealthyBlocksWhatWaitsForIt() throws Exception {
        unhealthy.add("api");
        Map<String, ServiceState> states = supervisor("""
                version: 1
                services:
                  api:
                    command: serve
                    health: { type: command, command: api, %s }
                  web: { command: serve, depends_on: { api: { condition: healthy } } }
                """.formatted(FAST)).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("api"))
                .isInstanceOfSatisfying(
                        ServiceState.Unhealthy.class,
                        u -> assertThat(u.reason()).isEqualTo("api answers 503 (2 checks in a row)"));
        assertThat(states.get("web"))
                .isInstanceOfSatisfying(
                        ServiceState.Blocked.class, b -> assertThat(b.reason()).startsWith("api is unhealthy"));
    }

    @Test
    void stateChangesAreNumberedWithoutGaps() throws Exception {
        supervisor(SHOP).up().get(10, TimeUnit.SECONDS);

        List<Long> numbers = events.stream()
                .filter(e -> e instanceof ProjectEvent.StateChanged)
                .map(e -> ((ProjectEvent.StateChanged) e).sequence())
                .toList();
        assertThat(numbers).isSorted().first().isEqualTo(1L);
        assertThat(numbers.getLast()).isEqualTo(numbers.size());
    }

    @Test
    void takenPortIsExplainedWithWhoHoldsItAndHowToFreeIt() throws Exception {
        busyPorts.put(3001, new Listener(3001, "127.0.0.1", 1000, Optional.of(4121L), Optional.of("node server.js")));

        Map<String, ServiceState> states = supervisor("""
                version: 1
                services:
                  api: { command: serve, ports: [3001] }
                """).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("api"))
                .isEqualTo(new ServiceState.FailedToStart("port 3001 is already in use by "
                        + "PID 4121 (node server.js), which is not part of this project. Free it with: kill 4121"));
        assertThat(runtime.started).isEmpty();
    }

    @Test
    void containerAndCommandStartInOrderAndStopInReverse() throws Exception {
        var s = supervisor("""
                version: 1
                env_files: []
                services:
                  db:
                    container: { image: postgres:18.1, environment: { POSTGRES_PASSWORD: dev } }
                    health: { type: docker, interval: 10ms }
                  api:
                    command: serve
                    depends_on: { db: { condition: healthy } }
                """);

        Map<String, ServiceState> states = s.up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("db")).isInstanceOf(ServiceState.Healthy.class);
        assertThat(states.get("api")).isInstanceOf(ServiceState.Running.class);
        assertThat(containers.started).containsExactly("db");
        assertThat(runtime.started).containsExactly("api");
        // A container only gets what the project declares, never the host's environment.
        assertThat(containers.environments.get("db")).isEqualTo(Map.of("POSTGRES_PASSWORD", "dev"));

        s.down().get(10, TimeUnit.SECONDS);
        assertThat(runtime.stopped).containsExactly("api");
        assertThat(containers.stopped).containsExactly("db");
    }

    @Test
    void imageThatCantBePulledBlocksDependents() throws Exception {
        Map<String, ServiceState> states = supervisor("""
                version: 1
                services:
                  db: { container: { image: missing/image } }
                  api: { command: serve, depends_on: [db] }
                """).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("db"))
                .isEqualTo(new ServiceState.FailedToStart("image missing/image not found: check the name and tag"));
        assertThat(states.get("api")).isInstanceOf(ServiceState.Blocked.class);
    }

    @Test
    void unexpectedAdapterErrorFailsTheServiceInsteadOfHanging() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  db: { container: { image: buggy/adapter } }
                """);

        Map<String, ServiceState> states = s.up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("db"))
                .isInstanceOfSatisfying(
                        ServiceState.FailedToStart.class,
                        f -> assertThat(f.reason())
                                .startsWith("internal error while starting (a DevDesk bug, please report it)")
                                .contains("cmd was not specified"));
        assertThat(s.down().get(10, TimeUnit.SECONDS).get("db")).isInstanceOf(ServiceState.Stopped.class);
    }

    @Test
    void failureIsExplainedRightAwayWithAFinding() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  api: { command: exit 127 }
                """);
        s.up().get(10, TimeUnit.SECONDS);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (events.stream().noneMatch(e -> e instanceof ProjectEvent.Diagnosis)) {
            assertThat(System.nanoTime()).as("no diagnosis arrived").isLessThan(deadline);
            Thread.sleep(10);
        }
        var diagnosis = (ProjectEvent.Diagnosis) events.stream()
                .filter(e -> e instanceof ProjectEvent.Diagnosis)
                .findFirst()
                .orElseThrow();
        assertThat(diagnosis.service()).isEqualTo("api");
        assertThat(diagnosis.findings()).extracting(f -> f.ruleId()).containsExactly("program-missing");
    }

    private static void awaitState(ProjectSupervisor s, String service, Class<? extends ServiceState> type)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!type.isInstance(s.snapshot().get().get(service))) {
            assertThat(System.nanoTime())
                    .as("%s never became %s", service, type.getSimpleName())
                    .isLessThan(deadline);
            Thread.sleep(10);
        }
    }
}
