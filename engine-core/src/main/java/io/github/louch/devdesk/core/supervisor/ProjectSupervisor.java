package io.github.louch.devdesk.core.supervisor;

import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.RunningContainer;
import io.github.louch.devdesk.core.diagnosis.DiagnosisContext;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.diagnosis.PortOwners;
import io.github.louch.devdesk.core.diagnosis.ServiceHistory;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.HealthTracker;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.GracefulStop;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.resources.ResourceSampler;
import io.github.louch.devdesk.core.state.ServiceEvent;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.state.ServiceStateMachine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Runs one project: starts services in dependency order, watches their health, restarts crashed ones and stops
 * everything in reverse order.
 *
 * <p>Concurrency: every piece of state below is touched only by {@link #actor}, a single thread that handles one
 * message at a time (the "actor" style, CLAUDE.md principle 4). Slow work (launching, probing, stopping) runs on
 * virtual threads and sends its result back to the actor as a new message, so nothing ever needs a lock.
 */
public final class ProjectSupervisor implements AutoCloseable {

    /** More restarts than this within {@link #CRASH_WINDOW} means a crash loop. */
    static final int MAX_RESTARTS = 5;

    static final Duration CRASH_WINDOW = Duration.ofMinutes(2);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    /** Output lines kept per service: crash reasons are near the end. */
    private static final int HISTORY_LINES = 50;

    private static final int HISTORY_ENTRIES = 10;
    /** At one sample every 2 s, half an hour of CPU/memory per service. */
    private static final int RESOURCE_SAMPLES_KEPT = 900;
    /** How often "slow" problems (like memory that keeps growing) are looked for in running services. */
    private static final Duration WATCH_EVERY = Duration.ofSeconds(30);
    /** Set unless the user overrides them: most tools then print immediately and keep their colours. */
    private static final Map<String, String> OUTPUT_FRIENDLY_DEFAULTS =
            Map.of("PYTHONUNBUFFERED", "1", "FORCE_COLOR", "1");

    private final ProjectSpec project;
    private final DependencyGraph graph;
    private final ProcessRuntime runtime;
    private final ContainerRuntime containers;
    private final HealthProbe probe;
    private final Introspection system;
    private final DiagnosisEngine diagnosis;
    private final ResourceSampler resources;
    private final Map<String, String> baseEnvironment;
    private final Clock clock;
    private final RandomGenerator random;
    private final Duration restartBaseDelay;
    private final Consumer<ProjectEvent> listener;
    private final ScheduledExecutorService actor;
    /**
     * Diagnoses run one at a time, in the order the failures happened, so the root cause is always explained before
     * the services blocked by it ("see the finding for api above" must come after api's finding).
     */
    private final java.util.concurrent.ExecutorService diagnoser = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("diagnose").factory());

    // ---- actor-confined state ----
    private final Map<String, Service> services = new LinkedHashMap<>();
    private final List<CompletableFuture<Map<String, ServiceState>>> waitingForUp = new ArrayList<>();
    private final List<CompletableFuture<Map<String, ServiceState>>> waitingForDown = new ArrayList<>();
    private boolean shuttingDown;
    private boolean evaluating;
    private int diagnosesRunning;
    private long sequence;
    private Instant lastWatch = Instant.EPOCH;

    /** One service's live data. Mutable, but only ever touched by the actor thread. */
    private static final class Service {
        final ServiceSpec spec;
        ServiceState state = new ServiceState.Stopped();
        Optional<Handle> handle = Optional.empty();
        HealthTracker health;
        String lastProbe = "";
        ScheduledFuture<?> probeTask;
        ScheduledFuture<?> restartTask;
        final Deque<Instant> recentRestarts = new ArrayDeque<>();
        // ---- history for diagnosis ----
        /** Written by output reader threads, so a concurrent queue (the only non-actor state). */
        final Deque<OutputLine> recentOutput = new ConcurrentLinkedDeque<>();

        final Deque<ExitStatus> exits = new ArrayDeque<>();
        final Deque<ProbeResult> probes = new ArrayDeque<>();
        boolean everHealthy;
        boolean stoppedByDevDesk;
        /** CPU and memory over the last half hour, oldest first (for growth/leak findings and the app's charts). */
        final Deque<ResourceSample> resources = new ArrayDeque<>();

        Optional<ResourceSampler.Usage> lastUsage = Optional.empty();
        Instant lastUsageAt = Instant.EPOCH;
        String lastWatchFindings = "";

        Service(ServiceSpec spec) {
            this.spec = spec;
        }
    }

    /** The outside world the supervisor works through: every OS, Docker and machine question goes via these ports. */
    public record Ports(
            ProcessRuntime runtime,
            ContainerRuntime containers,
            HealthProbe probe,
            Introspection system,
            DiagnosisEngine diagnosis,
            ResourceSampler resources) {}

    /**
     * Knobs, injected so tests can make time and randomness predictable (CLAUDE.md principle 6).
     *
     * @param baseEnvironment the environment services start from (login env)
     * @param restartBaseDelay first back-off delay (doubles per restart, up to 60 s); tests use a few milliseconds
     * @param sampleInterval how often CPU and memory are measured
     */
    public record Settings(
            Map<String, String> baseEnvironment,
            Clock clock,
            RandomGenerator random,
            Duration restartBaseDelay,
            Duration sampleInterval) {

        public static Settings defaults(Map<String, String> env, Clock clock, RandomGenerator random) {
            return new Settings(env, clock, random, Duration.ofSeconds(1), Duration.ofSeconds(2));
        }
    }

    /**
     * @param listener receives every event. It is called from several threads (output arrives from reader threads),
     *     so it must be thread-safe and quick
     */
    public ProjectSupervisor(
            ProjectSpec project,
            DependencyGraph graph,
            Ports ports,
            Settings settings,
            Consumer<ProjectEvent> listener) {
        this.project = project;
        this.graph = graph;
        this.runtime = ports.runtime();
        this.containers = ports.containers();
        this.probe = ports.probe();
        this.system = ports.system();
        this.diagnosis = ports.diagnosis();
        this.resources = ports.resources();
        this.baseEnvironment = Map.copyOf(settings.baseEnvironment());
        this.clock = settings.clock();
        this.random = settings.random();
        this.restartBaseDelay = settings.restartBaseDelay();
        this.listener = listener;
        this.actor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("project-" + project.name()).daemon().factory());
        project.services().values().forEach(s -> services.put(s.name(), new Service(s)));
        long every = settings.sampleInterval().toMillis();
        actor.scheduleWithFixedDelay(() -> onActorNow(this::sampleResources), every, every, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts every service. Completes once each one has "settled": healthy, running without a health check,
     * completed, or failed in a way that won't fix itself (blocked, crashed with no restart, crash loop...).
     */
    public CompletableFuture<Map<String, ServiceState>> up() {
        CompletableFuture<Map<String, ServiceState>> done = new CompletableFuture<>();
        onActor(() -> {
            shuttingDown = false;
            waitingForUp.add(done);
            for (String name :
                    graph.startLevels().stream().flatMap(List::stream).toList()) {
                apply(services.get(name), new ServiceEvent.StartRequested(), "start requested");
            }
            evaluate();
        });
        return done;
    }

    /** Stops every service, dependents before their dependencies. Completes when all are stopped. */
    public CompletableFuture<Map<String, ServiceState>> down() {
        CompletableFuture<Map<String, ServiceState>> done = new CompletableFuture<>();
        onActor(() -> {
            shuttingDown = true;
            waitingForDown.add(done);
            services.values().forEach(ProjectSupervisor::cancelRestart);
            evaluate();
        });
        return done;
    }

    /**
     * The PIDs each running service owns right now (all of them, children included), for resource numbers. For a
     * container it's the host PID of its main process.
     */
    public CompletableFuture<Map<String, Set<Long>>> pids() {
        return CompletableFuture.supplyAsync(
                        () -> {
                            Map<String, Optional<Handle>> handles = new LinkedHashMap<>();
                            services.forEach((name, s) -> handles.put(name, s.handle));
                            return handles;
                        },
                        actor)
                // Reading cgroup.procs or /proc is I/O, so it happens off the actor thread.
                .thenApplyAsync(
                        handles -> {
                            Map<String, Set<Long>> result = new LinkedHashMap<>();
                            handles.forEach((name, handle) -> result.put(
                                    name,
                                    handle.map(h -> switch (h) {
                                                case Handle.Process p -> runtime.members(p.process());
                                                case Handle.Container c ->
                                                    c.pid() > 0 ? Set.of(c.pid()) : Set.<Long>of();
                                            })
                                            .orElse(Set.of())));
                            return result;
                        },
                        runnable -> Thread.ofVirtual().start(runnable));
    }

    /** The current state of every service. */
    public CompletableFuture<Map<String, ServiceState>> snapshot() {
        return CompletableFuture.supplyAsync(this::states, actor);
    }

    @Override
    public void close() {
        actor.shutdownNow();
        diagnoser.shutdownNow();
    }

    // ---- state changes ----

    /** Applies one event to one service through the state machine, and reacts to the new state. */
    private void apply(Service s, ServiceEvent event, String reason) {
        ServiceState from = s.state;
        ServiceState to = ServiceStateMachine.next(from, event);
        if (to.equals(from)) {
            return;
        }
        s.state = to;
        listener.accept(new ProjectEvent.StateChanged(++sequence, s.spec.name(), from, to, reason, clock.instant()));
        entered(s, to);
        evaluate();
    }

    /** Side effects of entering a state: launch, probe, stop, restart. */
    private void entered(Service s, ServiceState state) {
        switch (state) {
            case ServiceState.Starting starting -> {
                s.lastUsage = Optional.empty(); // a new process: CPU time counts from zero again
                s.everHealthy = false;
                s.stoppedByDevDesk = false;
                launch(s);
            }
            case ServiceState.Healthy healthy -> s.everHealthy = true;
            case ServiceState.Running running -> {
                if (!(s.spec.health() instanceof HealthCheckSpec.ProcessAlive)) {
                    s.health = new HealthTracker(s.spec.health().timing(), clock.instant());
                    // First probe soon, with a little jitter so many services don't probe in the same millisecond.
                    scheduleProbe(s, Duration.ofMillis(100 + random.nextInt(100)));
                }
            }
            case ServiceState.Stopping stopping -> {
                s.stoppedByDevDesk = true;
                cancelProbe(s);
                cancelRestart(s);
                s.handle.ifPresent(h -> stopInBackground(s, h));
                // No process yet: it's still launching, and onLaunched() stops it as soon as it exists.
            }
            case ServiceState.Crashed crashed -> {
                cancelProbe(s);
                restartIfWanted(s, crashed.exit());
                if (s.restartTask == null && s.state instanceof ServiceState.Crashed) {
                    explain(s); // final: no restart is coming
                }
            }
            case ServiceState.CrashLoop loop -> explain(s);
            case ServiceState.FailedToStart failed -> explain(s);
            case ServiceState.Blocked blocked -> explain(s);
            case ServiceState.Unhealthy unhealthy -> explain(s);
            case ServiceState.Completed completed -> {
                cancelProbe(s);
                restartIfWanted(s, ExitStatus.of(0));
            }
            default -> cancelProbe(s);
        }
    }

    /**
     * Moves waiting services forward (start them, or block them) and, during {@link #down()}, stops whatever may now
     * be stopped. Re-entrant calls are skipped: the outermost call loops until nothing changes.
     */
    private void evaluate() {
        if (evaluating) {
            return;
        }
        evaluating = true;
        try {
            boolean changed = true;
            while (changed) {
                changed = shuttingDown ? stopWhatCanStop() : startWhatCanStart();
            }
        } finally {
            evaluating = false;
        }
        completeWaiters();
    }

    private boolean startWhatCanStart() {
        boolean changed = false;
        for (Service s : services.values()) {
            if (s.state instanceof ServiceState.WaitingDeps) {
                Optional<ServiceEvent> verdict = dependencyVerdict(s);
                if (verdict.isPresent()) {
                    String reason = verdict.get() instanceof ServiceEvent.DependencyFailed f
                            ? f.reason()
                            : "dependencies ready";
                    apply(s, verdict.get(), reason);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Stops a service once nothing that depends on it is still running (reverse dependency order). */
    private boolean stopWhatCanStop() {
        boolean changed = false;
        for (String name : graph.stopOrder()) {
            Service s = services.get(name);
            boolean dependentsDown =
                    graph.dependentsOf(name).stream().map(services::get).noneMatch(d -> d.state.isActive());
            if (!(s.state instanceof ServiceState.Stopped)
                    && !(s.state instanceof ServiceState.Stopping)
                    && dependentsDown) {
                apply(s, new ServiceEvent.StopRequested(), "stop requested");
                changed = true;
            }
        }
        return changed;
    }

    /** Empty while a service must keep waiting; otherwise "go" or "blocked, because...". */
    private Optional<ServiceEvent> dependencyVerdict(Service s) {
        boolean allReady = true;
        for (Dependency d : s.spec.dependsOn()) {
            Service dep = services.get(d.service());
            Optional<String> failure = failure(dep, d.condition());
            if (failure.isPresent()) {
                return Optional.of(new ServiceEvent.DependencyFailed(d.service(), failure.get()));
            }
            allReady &= satisfies(dep.state, d.condition());
        }
        return allReady ? Optional.of(new ServiceEvent.DependenciesReady()) : Optional.empty();
    }

    private static boolean satisfies(ServiceState state, Dependency.Condition condition) {
        return switch (condition) {
            case STARTED ->
                state instanceof ServiceState.Running
                        || state instanceof ServiceState.Healthy
                        || state instanceof ServiceState.Unhealthy
                        || state instanceof ServiceState.Completed;
            case HEALTHY -> state instanceof ServiceState.Healthy;
            case COMPLETED_SUCCESSFULLY -> state instanceof ServiceState.Completed;
        };
    }

    /** Why {@code dep} will never satisfy {@code condition}, if that's already certain. */
    private static Optional<String> failure(Service dep, Dependency.Condition condition) {
        String name = dep.spec.name();
        return switch (dep.state) {
            case ServiceState.Blocked b -> Optional.of(name + " is blocked: " + b.reason());
            case ServiceState.FailedToStart f -> Optional.of(name + " failed to start: " + f.reason());
            case ServiceState.CrashLoop c ->
                Optional.of(name + " keeps crashing (" + c.restarts() + " restarts), last: "
                        + c.lastExit().explanation());
            case ServiceState.Crashed c
            when dep.restartTask == null ->
                Optional.of(name + " crashed: " + c.exit().explanation());
            case ServiceState.Completed c
            when condition == Dependency.Condition.HEALTHY ->
                Optional.of(name + " exited (code 0) before ever becoming healthy");
            case ServiceState.Unhealthy u
            when condition == Dependency.Condition.HEALTHY -> Optional.of(name + " is unhealthy: " + u.reason());
            case ServiceState.Stopped st -> Optional.of(name + " is stopped");
            default -> Optional.empty();
        };
    }

    // ---- launching and exits ----

    /** What a service is running right now: an OS process or a container. */
    private sealed interface Handle {
        long pid();

        record Process(RunningProcess process) implements Handle {
            public long pid() {
                return process.pid();
            }
        }

        record Container(RunningContainer container) implements Handle {
            public long pid() {
                return container.pid();
            }
        }
    }

    private void launch(Service s) {
        Consumer<OutputLine> output = line -> {
            remember(s.recentOutput, line, HISTORY_LINES);
            listener.accept(new ProjectEvent.Output(s.spec.name(), line));
        };
        Thread.ofVirtual().name("launch-" + s.spec.name()).start(() -> {
            Optional<String> busy = busyPort(s.spec);
            if (busy.isPresent()) {
                onActor(() -> apply(s, new ServiceEvent.LaunchFailed(busy.get()), busy.get()));
                return;
            }
            try {
                Handle handle = switch (s.spec.workload()) {
                    case Workload.Command c ->
                        new Handle.Process(runtime.start(
                                new ProcessLaunchSpec(
                                        project.key(),
                                        s.spec.name(),
                                        c.commandLine(),
                                        s.spec.directory(),
                                        environment(s)),
                                output));
                    case Workload.Container c ->
                        new Handle.Container(containers.start(
                                new ContainerLaunchSpec(
                                        project.name(), project.key(), s.spec.name(), c, containerEnvironment(s)),
                                output));
                };
                onActor(() -> onLaunched(s, handle));
            } catch (LaunchException e) {
                onActor(() -> launchFailed(s, e.getMessage()));
            } catch (RuntimeException e) {
                // A bug in an adapter must fail this one service loudly, never leave it stuck in "starting".
                onActor(() -> launchFailed(s, "internal error while starting (a DevDesk bug, please report it): " + e));
            }
        });
    }

    private void launchFailed(Service s, String reason) {
        if (s.state instanceof ServiceState.Stopping) {
            apply(s, new ServiceEvent.StopFinished(), "stopped before it could start");
        } else {
            apply(s, new ServiceEvent.LaunchFailed(reason), reason);
        }
    }

    /**
     * Checked right before launching, so the user hears "port 3001 is taken by PID 4121" instead of a cryptic
     * EADDRINUSE crash from inside the app. Runs off the actor thread: scanning /proc takes a moment.
     */
    private Optional<String> busyPort(ServiceSpec spec) {
        for (int port : spec.ports()) {
            Optional<Listener> taken = system.listenerOn(port);
            if (taken.isPresent()) {
                PortOwners.Explanation who = PortOwners.explain(taken.get(), system, project, clock.instant());
                return Optional.of(
                        "port " + port + " is already in use by " + who.who() + ". " + capitalize(who.fix()));
            }
        }
        return Optional.empty();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void onLaunched(Service s, Handle handle) {
        s.handle = Optional.of(handle);
        CompletableFuture<ExitStatus> exit = switch (handle) {
            case Handle.Process p -> runtime.onExit(p.process());
            case Handle.Container c -> containers.onExit(c.container());
        };
        exit.thenAccept(status -> onActor(() -> onExited(s, handle, status)));
        if (s.state instanceof ServiceState.Stopping) {
            stopInBackground(s, handle); // stop arrived while it was launching
        } else {
            String what = handle instanceof Handle.Container c
                    ? "container " + c.container().name() + ", "
                    : "";
            apply(s, new ServiceEvent.Launched(handle.pid()), "started (" + what + "PID " + handle.pid() + ")");
        }
    }

    private void onExited(Service s, Handle handle, ExitStatus exit) {
        if (s.handle.filter(handle::equals).isEmpty()
                || !s.state.isActive()
                || s.state instanceof ServiceState.Starting) {
            return; // an older run of this service, or already handled
        }
        remember(s.exits, exit, HISTORY_ENTRIES);
        apply(s, new ServiceEvent.Exited(exit), exit.explanation());
    }

    private Map<String, String> environment(Service s) {
        Map<String, String> env = new LinkedHashMap<>(OUTPUT_FRIENDLY_DEFAULTS);
        env.putAll(baseEnvironment); // later wins: login env, then env_files, then the service's own block
        env.putAll(project.environment());
        env.putAll(s.spec.environment());
        return env;
    }

    /** Containers get only what the project declares: the host's environment (and its secrets) stays outside. */
    private Map<String, String> containerEnvironment(Service s) {
        Map<String, String> env = new LinkedHashMap<>(project.environment());
        env.putAll(s.spec.environment());
        return env;
    }

    // ---- stopping ----

    private void stopInBackground(Service s, Handle handle) {
        Thread.ofVirtual().name("stop-" + s.spec.name()).start(() -> {
            StopResult result;
            try {
                result = switch (handle) {
                    case Handle.Process p -> new GracefulStop(runtime).stop(p.process(), s.spec.stopGracePeriod());
                    case Handle.Container c -> containers.stop(c.container(), s.spec.stopGracePeriod());
                };
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Same rule when stopping: report it, and don't let "down" wait forever.
                onActor(() -> {
                    listener.accept(
                            new ProjectEvent.Warning(s.spec.name(), "stopping failed (it may still be running): " + e));
                    s.handle = Optional.empty();
                    apply(s, new ServiceEvent.StopFinished(), "stop failed");
                });
                return;
            }
            onActor(() -> {
                switch (result) {
                    case StopResult.Stopped(var exit, boolean forced)
                    when forced ->
                        listener.accept(new ProjectEvent.Warning(
                                s.spec.name(),
                                "ignored SIGTERM for "
                                        + s.spec.stopGracePeriod().toSeconds() + "s and was force-killed (SIGKILL)"));
                    case StopResult.LeftBehind(var exit, var pids) ->
                        listener.accept(new ProjectEvent.Warning(
                                s.spec.name(),
                                "still running even after SIGKILL: PIDs " + pids
                                        + ". They escaped DevDesk; inspect them with: ps -o pid,ppid,cmd -p "
                                        + String.join(
                                                ",",
                                                pids.stream()
                                                        .map(String::valueOf)
                                                        .toList())));
                    default -> {}
                }
                s.handle = Optional.empty();
                apply(s, new ServiceEvent.StopFinished(), "stopped");
            });
        });
    }

    // ---- health probes ----

    private void scheduleProbe(Service s, Duration delay) {
        Handle current = s.handle.orElseThrow();
        s.probeTask = actor.schedule(
                () -> Thread.ofVirtual().name("probe-" + s.spec.name()).start(() -> {
                    ProbeResult result =
                            s.spec.health() instanceof HealthCheckSpec.Docker && current instanceof Handle.Container c
                                    ? containers.health(c.container())
                                    : probe.probe(s.spec.health(), s.spec.directory(), environment(s));
                    onActor(() -> onProbeResult(s, current, result));
                }),
                delay.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void onProbeResult(Service s, Handle probed, ProbeResult result) {
        boolean stillTheSameRun = s.handle.filter(probed::equals).isPresent();
        if (!stillTheSameRun || !s.state.isActive() || s.state instanceof ServiceState.Stopping) {
            return; // the service stopped or restarted while this probe was running
        }
        s.lastProbe = result.detail();
        remember(s.probes, result, HISTORY_ENTRIES);
        s.health.record(result, clock.instant()).ifPresent(e -> apply(s, e, result.detail()));
        if (s.state.isActive() && !(s.state instanceof ServiceState.Stopping)) {
            scheduleProbe(s, s.spec.health().timing().interval());
        }
    }

    private static void cancelProbe(Service s) {
        if (s.probeTask != null) {
            s.probeTask.cancel(false);
            s.probeTask = null;
        }
    }

    // ---- restarts ----

    private void restartIfWanted(Service s, ExitStatus exit) {
        boolean wanted = !shuttingDown
                && switch (s.spec.restart()) {
                    case NO -> false;
                    case ON_FAILURE -> exit.code() != 0;
                    case ALWAYS -> true;
                };
        if (!wanted) {
            return;
        }
        Instant now = clock.instant();
        s.recentRestarts.addLast(now);
        while (s.recentRestarts.getFirst().isBefore(now.minus(CRASH_WINDOW))) {
            s.recentRestarts.removeFirst();
        }
        if (s.recentRestarts.size() > MAX_RESTARTS && s.state instanceof ServiceState.Crashed) {
            apply(
                    s,
                    new ServiceEvent.CrashLoopDetected(),
                    "crashed " + s.recentRestarts.size() + " times within " + CRASH_WINDOW.toMinutes()
                            + " minutes; not restarting it again");
            return;
        }
        Duration delay = backoff(s.recentRestarts.size());
        s.restartTask = actor.schedule(
                () -> onActorNow(() -> {
                    s.restartTask = null;
                    apply(
                            s,
                            new ServiceEvent.RestartDue(),
                            "restarting after " + delay.toMillis() + " ms ("
                                    + s.spec.restart().name().toLowerCase().replace('_', '-') + ")");
                }),
                delay.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 1x, 2x, 4x ... the base delay, capped at 60 s, plus up to 10% jitter so restarts don't line up. */
    private Duration backoff(int attempt) {
        long base = restartBaseDelay.toMillis() << Math.min(attempt - 1, 20);
        long capped = Math.min(base, MAX_BACKOFF.toMillis());
        return Duration.ofMillis(capped + random.nextLong(capped / 10 + 1));
    }

    private static void cancelRestart(Service s) {
        if (s.restartTask != null) {
            s.restartTask.cancel(false);
            s.restartTask = null;
        }
    }

    // ---- diagnosis ----

    /**
     * Runs the diagnosis rules for a service that just failed, and reports the findings as an event. The snapshot is
     * taken here on the actor; the rules (which read /proc and files) run on a virtual thread.
     */
    private void explain(Service s) {
        Map<String, ServiceState> states = states();
        Map<String, ServiceHistory> history = new LinkedHashMap<>();
        services.forEach((name, other) -> history.put(name, historyOf(other, Set.of())));
        Optional<Handle> handle = s.handle;
        String name = s.spec.name();
        diagnosesRunning++; // up() waits for it, so the explanation prints before the summary
        diagnoser.execute(() -> {
            // PIDs are read here, off the actor: for a running (unhealthy) service, its ports are useful evidence.
            Set<Long> pids = handle.map(h -> switch (h) {
                        case Handle.Process p -> runtime.members(p.process());
                        case Handle.Container c -> Set.<Long>of();
                    })
                    .orElse(Set.of());
            history.put(name, historyOf(s, pids));
            try {
                var ctx = new DiagnosisContext(project, states, history, baseEnvironment, system, clock.instant());
                List<Finding> findings = diagnosis.explain(ctx, name);
                if (!findings.isEmpty()) {
                    listener.accept(new ProjectEvent.Diagnosis(name, findings));
                }
            } finally {
                onActor(() -> {
                    diagnosesRunning--;
                    completeWaiters();
                });
            }
        });
    }

    private static ServiceHistory historyOf(Service s, Set<Long> pids) {
        return new ServiceHistory(
                List.copyOf(s.recentOutput),
                List.copyOf(s.exits),
                List.copyOf(s.probes),
                List.copyOf(s.resources),
                s.everHealthy,
                pids,
                s.stoppedByDevDesk);
    }

    /** Appends to a bounded history, dropping the oldest entries. */
    private static <T> void remember(Deque<T> history, T item, int max) {
        history.addLast(item);
        while (history.size() > max) {
            history.pollFirst();
        }
    }

    // ---- resources ----

    /**
     * Every few seconds: measure each running service (off the actor, it reads /proc), keep the sample, publish it, and
     * now and then look for slow problems such as memory that keeps growing.
     */
    private void sampleResources() {
        Map<String, Handle> running = new LinkedHashMap<>();
        services.forEach((name, s) -> s.handle.filter(h -> s.state.isActive()).ifPresent(h -> running.put(name, h)));
        if (running.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("sample-" + project.name()).start(() -> {
            Map<String, Set<Long>> pids = new LinkedHashMap<>();
            Map<String, ResourceSampler.Usage> usage = new LinkedHashMap<>();
            running.forEach((name, h) -> {
                Set<Long> p = switch (h) {
                    case Handle.Process proc -> runtime.members(proc.process());
                    case Handle.Container c -> c.pid() > 0 ? Set.of(c.pid()) : Set.<Long>of();
                };
                pids.put(name, p);
                usage.put(name, resources.sample(p));
            });
            Instant now = clock.instant();
            onActor(() -> recordSamples(usage, pids, now));
        });
    }

    private void recordSamples(Map<String, ResourceSampler.Usage> usage, Map<String, Set<Long>> pids, Instant now) {
        Map<String, ResourceSample> published = new LinkedHashMap<>();
        usage.forEach((name, u) -> {
            Service s = services.get(name);
            if (!s.state.isActive()) {
                return; // stopped while it was being measured
            }
            double cpu = s.lastUsage
                    .map(before -> ResourceSample.cpuPercent(
                            before.cpuSeconds(), u.cpuSeconds(), Duration.between(s.lastUsageAt, now)))
                    .orElse(0.0);
            ResourceSample sample =
                    new ResourceSample(now, cpu, u.memoryBytes(), pids.get(name).size(), u.sockets());
            s.lastUsage = Optional.of(u);
            s.lastUsageAt = now;
            remember(s.resources, sample, RESOURCE_SAMPLES_KEPT);
            published.put(name, sample);
        });
        if (!published.isEmpty()) {
            listener.accept(new ProjectEvent.Resources(published));
        }
        if (!now.isBefore(lastWatch.plus(WATCH_EVERY))) {
            lastWatch = now;
            watch(pids);
        }
    }

    /** Runs the "continuous" rules on running services; reports a finding only when it changed since last time. */
    private void watch(Map<String, Set<Long>> pids) {
        Map<String, ServiceState> states = states();
        Map<String, ServiceHistory> history = new LinkedHashMap<>();
        // With PIDs, rules can look at the machine too (e.g. where leaked connections go).
        services.forEach((name, s) -> history.put(name, historyOf(s, pids.getOrDefault(name, Set.of()))));
        List<String> active = services.values().stream()
                .filter(s -> s.state.isActive())
                .map(s -> s.spec.name())
                .toList();
        Thread.ofVirtual().name("watch-" + project.name()).start(() -> {
            var ctx = new DiagnosisContext(project, states, history, baseEnvironment, system, clock.instant());
            for (String name : active) {
                List<Finding> findings = diagnosis.watch(ctx, name);
                String key = findings.stream().map(Finding::title).toList().toString();
                onActor(() -> {
                    Service s = services.get(name);
                    if (!key.equals(s.lastWatchFindings)) {
                        s.lastWatchFindings = key;
                        if (!findings.isEmpty()) {
                            listener.accept(new ProjectEvent.Diagnosis(name, findings));
                        }
                    }
                });
            }
        });
    }

    // ---- plumbing ----

    private void completeWaiters() {
        if (!waitingForUp.isEmpty()
                && diagnosesRunning == 0
                && services.values().stream().allMatch(this::settled)) {
            waitingForUp.forEach(f -> f.complete(states()));
            waitingForUp.clear();
        }
        if (!waitingForDown.isEmpty()
                && services.values().stream().allMatch(s -> s.state instanceof ServiceState.Stopped)) {
            waitingForDown.forEach(f -> f.complete(states()));
            waitingForDown.clear();
        }
    }

    /** A state that won't change unless something new happens (a crash, a health change, a user action). */
    private boolean settled(Service s) {
        return switch (s.state) {
            case ServiceState.Healthy h -> true;
            case ServiceState.Running r -> s.spec.health() instanceof HealthCheckSpec.ProcessAlive;
            case ServiceState.Unhealthy u -> true;
            case ServiceState.Completed c -> s.restartTask == null;
            case ServiceState.Crashed c -> s.restartTask == null;
            case ServiceState.Blocked b -> true;
            case ServiceState.FailedToStart f -> true;
            case ServiceState.CrashLoop c -> true;
            default -> false;
        };
    }

    private Map<String, ServiceState> states() {
        Map<String, ServiceState> result = new LinkedHashMap<>();
        services.forEach((name, s) -> result.put(name, s.state));
        return result;
    }

    /** Queues work for the actor thread. Any exception is a DevDesk bug: report it loudly, never swallow it. */
    private void onActor(Runnable task) {
        actor.execute(() -> onActorNow(task));
    }

    private void onActorNow(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            listener.accept(new ProjectEvent.Warning("devdesk", "internal error (a bug, please report it): " + e));
            waitingForUp.forEach(f -> f.completeExceptionally(e));
            waitingForDown.forEach(f -> f.completeExceptionally(e));
            waitingForUp.clear();
            waitingForDown.clear();
        }
    }
}
