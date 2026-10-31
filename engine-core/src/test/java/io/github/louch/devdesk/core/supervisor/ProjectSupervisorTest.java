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
    private final List<String> tcpProbed = new CopyOnWriteArrayList<>();
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
                            if (check instanceof io.github.louch.devdesk.core.project.HealthCheckSpec.Tcp t) {
                                tcpProbed.add(t.host() + ":" + t.port());
                                return new ProbeResult.Passed(t.host() + ":" + t.port() + " accepts connections");
                            }
                            // Other health checks in these tests are "command" checks named after their service.
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
            public List<Listener> allListeners() {
                return List.of();
            }

            @Override
            public Optional<String> devdeskProjectOf(long pid) {
                return Optional.empty();
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
    void stoppingOneServiceAlsoStopsWhatNeedsItButNotWhatItNeeds() throws Exception {
        var s = supervisor(SHOP);
        s.up().get(10, TimeUnit.SECONDS);

        Map<String, ServiceState> states = s.stopService("api").get(10, TimeUnit.SECONDS);

        assertThat(runtime.stopped).containsExactly("web", "api"); // web needs api; db doesn't
        assertThat(states.get("db")).isInstanceOf(ServiceState.Healthy.class);
        assertThat(states.get("web")).isInstanceOf(ServiceState.Stopped.class);
    }

    @Test
    void startingOneServiceStartsWhatItNeedsFirst() throws Exception {
        Map<String, ServiceState> states = supervisor(SHOP).startService("api").get(10, TimeUnit.SECONDS);

        assertThat(runtime.started).containsExactly("db", "api"); // not web: nothing asked for it
        assertThat(states.get("api")).isInstanceOf(ServiceState.Healthy.class);
        assertThat(states.get("web")).isInstanceOf(ServiceState.Stopped.class);
    }

    @Test
    void restartingAServiceRestartsItsDependentsToo() throws Exception {
        var s = supervisor(SHOP);
        s.up().get(10, TimeUnit.SECONDS);
        runtime.started.clear();

        Map<String, ServiceState> states = s.restartService("api").get(10, TimeUnit.SECONDS);

        assertThat(runtime.stopped).containsExactly("web", "api");
        assertThat(runtime.started).containsExactly("api", "web"); // db kept running
        assertThat(states.get("web")).isInstanceOf(ServiceState.Running.class);
    }

    @Test
    void reloadingAfterACodeChangeRestartsOnlyThatService() throws Exception {
        var s = supervisor(SHOP);
        s.up().get(10, TimeUnit.SECONDS);
        runtime.started.clear();

        Map<String, ServiceState> states = s.reloadService("api").get(10, TimeUnit.SECONDS);

        assertThat(runtime.stopped).containsExactly("api"); // web keeps running and reconnects
        assertThat(runtime.started).containsExactly("api");
        assertThat(states.get("api")).isInstanceOf(ServiceState.Healthy.class);
        assertThat(states.get("web")).isInstanceOf(ServiceState.Running.class);
    }

    @Test
    void detailsShowStateSinceProbesAndEnvironmentSources() throws Exception {
        var s = supervisor(SHOP);
        s.up().get(10, TimeUnit.SECONDS);

        var db = s.details("db").get(10, TimeUnit.SECONDS);

        assertThat(db.state()).isInstanceOf(ServiceState.Healthy.class);
        assertThat(db.since()).isNotNull();
        assertThat(db.history().probes()).last().isInstanceOf(ProbeResult.Passed.class);
        assertThat(db.environment())
                .anySatisfy(v -> assertThat(v.source()).isEqualTo(ServiceEnvironment.Source.DEVDESK));
    }

    @Test
    void aStoppedServiceIsNotRestartedAutomatically() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  worker: { command: serve, restart: always }
                """);
        s.up().get(10, TimeUnit.SECONDS);

        s.stopService("worker").get(10, TimeUnit.SECONDS);
        Thread.sleep(100); // longer than the 5 ms restart delay of these tests

        assertThat(runtime.started).containsExactly("worker");
        assertThat(s.snapshot().get().get("worker")).isInstanceOf(ServiceState.Stopped.class);
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
    void aTcpCheckOnAPublishedPortConnectsToTheContainerItself() throws Exception {
        // On the host, Docker's port forwarder accepts connections even when nothing listens inside: paperless's
        // webserver looked healthy 0.1 s after start while it was failing to boot.
        containers.insides.put(8000, new java.net.InetSocketAddress("172.18.0.5", 8000));
        var s = supervisor("""
                version: 1
                services:
                  web:
                    container: { image: paperless, ports: ["8000:8000"] }
                    health: { type: tcp, port: 8000, interval: 10ms }
                  cache:
                    container: { image: valkey, ports: ["6379:6379"] }
                    health: { type: tcp, port: 6379, interval: 10ms }
                """);

        Map<String, ServiceState> states = s.up().get(10, TimeUnit.SECONDS);

        assertThat(states.values()).allMatch(st -> st instanceof ServiceState.Healthy);
        assertThat(tcpProbed).contains("172.18.0.5:8000").doesNotContain("127.0.0.1:8000");
        // No inside address (e.g. Docker Desktop): the published port is all there is.
        assertThat(tcpProbed).contains("127.0.0.1:6379");
        s.down().get(10, TimeUnit.SECONDS);
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
    void stoppingDuringALongImagePullDoesNotWaitForTheDownload() throws Exception {
        // Found on a real project: Ctrl+C while a big image downloaded made "stop" wait minutes, then give up.
        var s = supervisor("""
                version: 1
                services:
                  db: { container: { image: postgres:18 } }
                  admin: { container: { image: slow/pull }, depends_on: [db] }
                """);
        s.up(); // admin is still "pulling" when the stop comes
        Thread.sleep(300);

        long started = System.nanoTime();
        Map<String, ServiceState> after = s.down().get(10, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(after.values()).allMatch(st -> st instanceof ServiceState.Stopped);
        assertThat(containers.stopped).contains("db");
        assertThat(containers.started).doesNotContain("admin");
    }

    @Test
    void aBlockedServiceNamesTheDependencyThatStillFailsNotOneThatRecovered() throws Exception {
        // directus: api was blocked by postgres (slow first start); postgres became healthy, maria never did, and
        // the explanation still said "postgres is the one that failed".
        unhealthy.add("pg");
        unhealthy.add("maria");
        var s = supervisor("""
                version: 1
                services:
                  pg:
                    command: pg
                    health: { type: command, command: pg, %1$s }
                  maria:
                    command: maria
                    health: { type: command, command: maria, %1$s }
                  api:
                    command: api
                    depends_on: { pg: { condition: healthy }, maria: { condition: healthy } }
                """.formatted(FAST));
        s.up();
        awaitState(s, "api", ServiceState.Blocked.class);

        unhealthy.remove("pg");

        awaitState(s, "pg", ServiceState.Healthy.class);
        long deadline = System.currentTimeMillis() + 5000;
        while (!(s.snapshot().get().get("api") instanceof ServiceState.Blocked b
                        && b.cause().equals("maria"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(s.snapshot().get().get("api"))
                .isInstanceOfSatisfying(
                        ServiceState.Blocked.class, b -> assertThat(b.cause()).isEqualTo("maria"));
        s.down().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aServiceBlockedByAFailedDependencyStartsWhenTheDependencyRecovers() throws Exception {
        // Twenty: the server failed its first health checks while it set up its database, the worker was blocked,
        // and stayed blocked after the server became healthy.
        unhealthy.add("store");
        var s = supervisor("""
                version: 1
                services:
                  store:
                    command: serve
                    health: { type: command, command: store, %s }
                  worker:
                    command: work
                    depends_on: { store: { condition: healthy } }
                """.formatted(FAST));
        s.up();
        awaitState(s, "worker", ServiceState.Blocked.class);

        unhealthy.remove("store"); // its setup finished: the next check passes

        awaitState(s, "worker", ServiceState.Running.class);
        assertThat(events).anySatisfy(e -> assertThat(e.toString()).contains("store recovered: starting it now"));
        s.down().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aCrashedContainerIsRemovedNotLeftBehind() throws Exception {
        supervisor("""
                version: 1
                services:
                  db: { container: { image: crashing/image } }
                """).up().get(10, TimeUnit.SECONDS);

        // Removed in the background right after the exit: wait for it, briefly.
        for (int i = 0; i < 100 && containers.stopped.isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertThat(containers.stopped).containsExactly("db");
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

    @Test
    void aServiceThatIgnoresSigtermIsForceKilledAndThePersonIsTold() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  worker: { command: stubborn, stop_grace_period: 1s }
                """);
        s.up().get(10, TimeUnit.SECONDS);

        var states = s.down().get(10, TimeUnit.SECONDS);

        assertThat(states.get("worker")).isInstanceOf(ServiceState.Stopped.class);
        assertThat(events)
                .contains(new ProjectEvent.Warning("worker", "ignored SIGTERM for 1s and was force-killed (SIGKILL)"));
    }

    @Test
    void restartsWaitLongerEachTime() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  worker: { command: exit 1, restart: on-failure }
                """);
        s.up().get(10, TimeUnit.SECONDS);
        awaitState(s, "worker", ServiceState.CrashLoop.class);

        List<Long> delays = events.stream()
                .filter(e ->
                        e instanceof ProjectEvent.StateChanged c && c.reason().startsWith("restarting after "))
                .map(e -> ((ProjectEvent.StateChanged) e).reason())
                .peek(r -> assertThat(r).endsWith(" ms (on-failure)"))
                .map(r -> Long.parseLong(r.substring("restarting after ".length(), r.indexOf(" ms"))))
                .toList();
        // 5 ms base in these tests, doubling each time, plus at most 10% jitter.
        assertThat(delays).hasSize(ProjectSupervisor.MAX_RESTARTS);
        for (int i = 0; i < delays.size(); i++) {
            long base = 5L << i;
            assertThat(delays.get(i)).as("restart %d", i + 1).isBetween(base, base + base / 10);
        }
        assertThat(transitions()).last().isEqualTo("worker→crash-loop");
    }

    @Test
    void onFailureLeavesACleanExitAloneButAlwaysRestartsIt() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  once: { command: exit 0, restart: on-failure }
                  again: { command: exit 0, restart: always }
                """);
        s.up().get(10, TimeUnit.SECONDS);
        // "always" restarts even after code 0. A clean exit isn't a crash, so it never becomes a crash loop: it keeps
        // restarting, waiting longer each time (like Docker's restart: always).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (runtime.started.stream().filter("again"::equals).count() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runtime.started.stream().filter("again"::equals)).hasSizeGreaterThanOrEqualTo(3);

        assertThat(runtime.started.stream().filter("once"::equals)).hasSize(1);
        assertThat(s.snapshot().get().get("once")).isInstanceOf(ServiceState.Completed.class);
    }

    @Test
    void whyADependencyWillNeverBeReadyIsSaidInItsOwnWords() throws Exception {
        Map<String, ServiceState> states = supervisor("""
                version: 1
                services:
                  seed: { command: exit 0, health: { type: command, command: seed, %s } }
                  setup: { command: exit 0 }
                  api: { command: serve, depends_on: { seed: { condition: healthy } } }
                  web: { command: serve, depends_on: [setup] }
                """.formatted(FAST)).up().get(10, TimeUnit.SECONDS);

        assertThat(states.get("api"))
                .isEqualTo(new ServiceState.Blocked("seed exited (code 0) before ever becoming healthy", "seed"));
        // "started" is satisfied by a service that ran and finished: web isn't blocked by setup.
        assertThat(states.get("web")).isInstanceOf(ServiceState.Running.class);
    }

    @Test
    void aStoppedDependencyBlocksWhatIsStartedAlone() throws Exception {
        var s = supervisor("""
                version: 1
                services:
                  db: { command: serve }
                  api: { command: serve, depends_on: [db] }
                """);
        s.up().get(10, TimeUnit.SECONDS);
        s.stopService("db").get(10, TimeUnit.SECONDS); // takes api down with it

        var states = s.startService("api").get(10, TimeUnit.SECONDS); // starts db again first

        assertThat(states.get("db")).isInstanceOf(ServiceState.Running.class);
        assertThat(states.get("api")).isInstanceOf(ServiceState.Running.class);
        assertThat(runtime.started).containsExactly("db", "api", "db", "api");
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
