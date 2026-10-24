package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.NoContainers;
import io.github.louch.devdesk.core.crashpoint.CrashPoint;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.graph.DependencyCycleException;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.history.Recorder;
import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.leftovers.Survivors;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.resources.ResourceSampler;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ProjectEvent;
import io.github.louch.devdesk.core.supervisor.ProjectSupervisor;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.docker.DockerContainerRuntime;
import io.github.louch.devdesk.linux.changes.KnownGoodFiles;
import io.github.louch.devdesk.linux.history.JsonlHistoryStore;
import io.github.louch.devdesk.linux.project.ProjectLock;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code devdesk up}: starts the whole project in dependency order and keeps it running until Ctrl+C. */
@Command(
        name = "up",
        description = "Start every service in devdesk.yaml in dependency order, wait until they're ready, "
                + "and stop them all (in reverse order) on Ctrl+C.")
final class UpCommand implements Callable<Integer> {

    /** Exit code for "this project isn't trusted yet". */
    static final int UNTRUSTED = 3;

    /** Exit code for "the project has containers, but Docker can't be used". */
    static final int NO_DOCKER = 4;

    private static final java.time.format.DateTimeFormatter SINCE =
            java.time.format.DateTimeFormatter.ofPattern("MMM dd HH:mm").withZone(java.time.ZoneId.systemDefault());

    /** Exit code for "another DevDesk already runs this project". */
    static final int ALREADY_RUNNING = 5;

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    /** Everything up needs from the outside world, wired by hand in Main. */
    record Engine(
            ProcessRuntime runtime,
            Supplier<DockerConnection.Result> docker,
            HealthProbe probe,
            Introspection system,
            DiagnosisEngine diagnosis,
            LeftoverFinder leftovers,
            ResourceSampler resources,
            TrustStore trust,
            KnownGoodFiles knownGood,
            Clock clock,
            RandomGenerator random) {}

    private final Engine engine;
    private final Map<String, String> env;
    private final PrintStream out;
    private final PrintStream err;
    /** Set in {@link #call()}: the queue in front of {@code out}. */
    private TerminalWriter terminal;

    private PrintStream screen;

    UpCommand(Engine engine, Map<String, String> env, PrintStream out, PrintStream err) {
        this.engine = engine;
        this.env = env;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() throws Exception {
        Optional<ProjectFile> loaded = ProjectFile.load(file, env, err);
        if (loaded.isEmpty()) {
            return ProjectFile.INVALID;
        }
        ProjectFile project = loaded.get();
        if (!engine.trust().isTrusted(project.path(), TrustStore.fingerprint(project.content()))) {
            err.println("devdesk: this project isn't trusted yet (or devdesk.yaml changed since you trusted it)."
                    + " It would run:");
            project.printCommands(err);
            err.println("If that looks right, run: devdesk trust");
            return UNTRUSTED;
        }

        Optional<ContainerRuntime> containers = containers(project);
        if (containers.isEmpty()) {
            return NO_DOCKER;
        }
        // One DevDesk per project: the kernel holds this lock for us and drops it if we die, however we die.
        ProjectLock lock;
        switch (ProjectLock.acquire(
                env, project.project(), "devdesk up in " + project.project().root())) {
            case ProjectLock.Result.Busy busy -> {
                err.println("devdesk: " + project.project().name() + " is already running in another DevDesk ("
                        + busy.owner() + "). Use that one, or stop it there first.");
                return ALREADY_RUNNING;
            }
            case ProjectLock.Result.Acquired acquired -> lock = acquired.lock();
        }
        CrashPoint.reach("up.locked");
        // From here on everything goes through the terminal writer: the engine must never wait for the terminal.
        terminal = new TerminalWriter(out);
        screen = terminal.stream();
        var printer = new ProgressPrinter(screen, project.project().services().keySet());
        // The flight recorder listens next to the printer: everything shown is also kept for `devdesk history`.
        var recorder = new Recorder(JsonlHistoryStore.forProject(env, project.project()), engine.clock());
        recorder.sessionStarted("devdesk up");
        cleanUpSurvivors(project, containers.get(), recorder);
        CrashPoint.reach("up.cleaned");
        var supervisor = new ProjectSupervisor(
                project.project(),
                graph(project),
                new ProjectSupervisor.Ports(
                        engine.runtime(),
                        containers.get(),
                        engine.probe(),
                        engine.system(),
                        engine.diagnosis(),
                        engine.resources()),
                ProjectSupervisor.Settings.defaults(env, engine.clock(), engine.random()),
                event -> {
                    printer.print(event);
                    recorder.accept(event);
                });
        // Ctrl+C: the JVM runs this hook before exiting (with code 130), so everything is stopped in reverse order.
        Thread onCtrlC = new Thread(() -> {
            stopAll(supervisor, project, containers.get(), recorder);
            recorder.sessionEnded("Ctrl+C or terminal closed");
            CrashPoint.reach("stop.done");
            lock.close();
        });
        Runtime.getRuntime().addShutdownHook(onCtrlC);

        screen.println("Starting " + project.project().name() + " ("
                + project.project().services().size()
                + (project.project().services().size() == 1 ? " service)" : " services)"));
        Map<String, ServiceState> states = supervisor.up().join();
        printer.summary(states);
        if (states.values().stream().allMatch(UpCommand::up)) {
            // A clean start: what `devdesk why` compares with the next time something breaks.
            CrashPoint.reach("up.all-up");
            engine.knownGood().save(project.project(), engine.knownGood().take(project.project(), this::imageIds));
        } else if (engine.knownGood().load(project.project()).isPresent()) {
            // Only when there's a clean start to compare with; otherwise the hint would lead nowhere.
            screen.println("Run devdesk why to see what changed since it last worked.");
        }
        if (states.values().stream().noneMatch(ServiceState::isActive)) {
            Runtime.getRuntime().removeShutdownHook(onCtrlC); // nothing runs, so there's nothing to stop
            supervisor.close();
            recorder.sessionEnded("nothing came up");
            lock.close();
            terminal.flush(Duration.ofSeconds(2));
            return 1;
        }
        new CountDownLatch(1).await(); // keep running until Ctrl+C
        return 0;
    }

    private static boolean up(ServiceState s) {
        return s instanceof ServiceState.Healthy
                || s instanceof ServiceState.Running
                || s instanceof ServiceState.Completed;
    }

    private Map<String, String> imageIds(java.util.Collection<String> images) {
        return engine.docker().get() instanceof DockerConnection.Result.Connected c
                ? c.runtime().imageIds(images)
                : Map.of();
    }

    private void stopAll(
            ProjectSupervisor supervisor, ProjectFile project, ContainerRuntime containers, Recorder recorder) {
        screen.println("Stopping everything (dependents first)...");
        CrashPoint.reach("stop.begin");
        try {
            supervisor.down().get(2, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            // Something is stuck (a stop that never finishes): don't walk away from what's still running. Stop it
            // directly, by the same proof of ownership used after a crash (scope / group record / container label).
            err.println("devdesk: stopping in order took over 2 minutes; stopping what's still running directly");
            supervisor.close();
            List<Survivors.Report> rest = survivors(project, containers).cleanUp(project.project());
            rest.forEach(r -> {
                String what = r.leftover().service() + ": " + r.leftover().description() + " → " + r.result();
                err.println("    " + what);
                recorder.accept(new ProjectEvent.Warning(r.leftover().service(), "stopped directly: " + what));
            });
        } catch (Exception e) {
            err.println("devdesk: stopping failed: " + e);
        } finally {
            supervisor.close();
            terminal.flush(Duration.ofSeconds(2)); // show the "■ stopped" lines before the JVM exits
        }
    }

    /** What DevDesk started for this project, found by proof of ownership: process scopes or groups, containers. */
    private Survivors survivors(ProjectFile project, ContainerRuntime containers) {
        List<LeftoverFinder> finders = new ArrayList<>(List.of(engine.leftovers()));
        if (containers instanceof DockerContainerRuntime docker) {
            finders.add(docker.leftovers());
        }
        return new Survivors(finders);
    }

    /**
     * We hold the lock, so nothing found now belongs to a living DevDesk: it's what a killed one left behind. It's
     * stopped before starting again, otherwise it would hold the same ports and confuse everything.
     */
    private void cleanUpSurvivors(ProjectFile project, ContainerRuntime containers, Recorder recorder) {
        if (containers instanceof DockerContainerRuntime docker) {
            // "Prove it" replicas whose DevDesk was killed mid-proof (their owner label names a dead process).
            List<String> replicas = docker.imageReplay(engine.random()).removeAbandoned();
            if (!replicas.isEmpty()) {
                screen.println(
                        "! Removed what a DevDesk killed during \"Prove it\" left: " + String.join(", ", replicas));
            }
        }
        List<Survivors.Report> reports = survivors(project, containers).cleanUp(project.project());
        if (reports.isEmpty()) {
            return;
        }
        screen.println("! A previous DevDesk was killed or crashed and left " + reports.size()
                + " thing(s) running for "
                + project.project().name() + ". They're provably DevDesk's own (systemd scope, process-group record"
                + " or container label with this project's key), so they're stopped first:");
        for (Survivors.Report r : reports) {
            Leftover l = r.leftover();
            String what = l.service() + ": " + l.description() + (l.pids().isEmpty() ? "" : " (PIDs " + l.pids() + ")")
                    + l.since()
                            .map(t -> ", running since " + SINCE.format(t)) // local time, like `devdesk history`
                            .orElse("");
            screen.println("    " + what + "  →  " + r.result());
            recorder.accept(new ProjectEvent.Warning(
                    l.service(), "left behind by a DevDesk that was killed: " + what + " → " + r.result()));
        }
    }

    /** Connects to Docker only when the project has a container, and says why up front if it can't. */
    private Optional<ContainerRuntime> containers(ProjectFile project) {
        List<String> needDocker = project.project().services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Container)
                .map(ServiceSpec::name)
                .toList();
        if (needDocker.isEmpty()) {
            return Optional.of(new NoContainers());
        }
        return switch (engine.docker().get()) {
            case DockerConnection.Result.Connected c -> Optional.of(c.runtime());
            case DockerConnection.Result.Unavailable u -> {
                err.println("devdesk: " + String.join(", ", needDocker) + " need" + (needDocker.size() == 1 ? "s" : "")
                        + " Docker, but " + u.reason());
                yield Optional.empty();
            }
        };
    }

    private static DependencyGraph graph(ProjectFile project) {
        try {
            return DependencyGraph.of(project.project().services().values());
        } catch (DependencyCycleException e) {
            throw new IllegalStateException("the config parser already rejects cycles", e);
        }
    }
}
