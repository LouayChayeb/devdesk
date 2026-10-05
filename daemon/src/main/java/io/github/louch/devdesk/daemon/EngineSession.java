package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.NoContainers;
import io.github.louch.devdesk.core.diagnosis.DiagnosisContext;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Ghosts;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.diagnosis.ProjectLinks;
import io.github.louch.devdesk.core.graph.DependencyCycleException;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.history.Recorder;
import io.github.louch.devdesk.core.history.Timeline;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.leftovers.Survivors;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.resources.ResourceSampler;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ProjectEvent;
import io.github.louch.devdesk.core.supervisor.ProjectSupervisor;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.docker.DockerContainerRuntime;
import io.github.louch.devdesk.linux.history.JsonlHistoryStore;
import io.github.louch.devdesk.linux.project.ProjectLoader;
import io.github.louch.devdesk.linux.project.ProjectLock;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The project the desktop app has open, and everything the app needs to show it. One project at a time.
 *
 * <p>The supervisor still makes every decision (one writer per project). This class only keeps the latest picture
 * (states, recent output, findings) so a window that connects late gets the full picture first, then live events.
 */
final class EngineSession implements AutoCloseable {

    /** Everything the session needs from the outside world, wired by hand in Main. */
    record Engine(
            ProcessRuntime runtime,
            Supplier<DockerConnection.Result> docker,
            HealthProbe probe,
            Introspection system,
            DiagnosisEngine diagnosis,
            LeftoverFinder leftovers,
            TrustStore trust,
            ResourceSampler resources,
            Map<String, String> environment,
            Clock clock,
            RandomGenerator random) {}

    /** The result of an action, ready to become an HTTP answer. */
    record Answer(int status, Object body) {}

    private static final int OUTPUT_LINES_KEPT = 1000;

    private final Engine engine;
    private final List<EventClient> clients = new CopyOnWriteArrayList<>();

    // ---- guarded by "this" ----
    private Optional<ProjectLoader.Result.Loaded> project = Optional.empty();
    private Optional<ProjectSupervisor> supervisor = Optional.empty();
    private Optional<Recorder> recorder = Optional.empty();
    /** Held while this engine runs the project, so a terminal `devdesk up` can't run it a second time. */
    private Optional<ProjectLock> lock = Optional.empty();

    private final Map<String, ServiceState> states = new LinkedHashMap<>();
    private final Map<String, Deque<Map<String, Object>>> output = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> findings = new LinkedHashMap<>();
    private long sequence;

    EngineSession(Engine engine) {
        this.engine = engine;
    }

    // ---- actions (called by the HTTP API) ----

    synchronized Answer open(Path path) {
        if (isRunning()) {
            return new Answer(409, ApiJson.error("stop the current project first: it's still running"));
        }
        return switch (ProjectLoader.load(path, engine.environment())) {
            case ProjectLoader.Result.Missing m -> new Answer(404, ApiJson.error(m.message()));
            case ProjectLoader.Result.Invalid i ->
                new Answer(
                        400,
                        Map.of(
                                "error", "devdesk.yaml has mistakes",
                                "file", i.file().toString(),
                                "problems",
                                        i.problems().stream()
                                                .map(ApiJson::problem)
                                                .toList(),
                                "message", i.message()));
            case ProjectLoader.Result.Loaded l -> {
                supervisor.ifPresent(ProjectSupervisor::close);
                supervisor = Optional.empty();
                project = Optional.of(l);
                states.clear();
                output.clear();
                findings.clear();
                l.project().services().keySet().forEach(name -> {
                    states.put(name, new ServiceState.Stopped());
                    output.put(name, new ArrayDeque<>());
                });
                Map<String, Object> view = view();
                broadcast("project", Map.of("project", view));
                yield new Answer(200, view);
            }
        };
    }

    synchronized Answer project() {
        return project.isEmpty() ? new Answer(404, ApiJson.error("no project is open")) : new Answer(200, view());
    }

    synchronized Answer trust() {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        var l = project.get();
        engine.trust().trust(l.file(), TrustStore.fingerprint(l.content()));
        Map<String, Object> view = view();
        broadcast("project", Map.of("project", view));
        return new Answer(200, view);
    }

    synchronized Answer up() {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        var l = project.get();
        if (!trusted(l)) {
            return new Answer(
                    403,
                    Map.of(
                            "error",
                            "this project isn't trusted yet (or devdesk.yaml changed)",
                            "commands",
                            ProjectLoader.commands(l.project())));
        }
        if (supervisor.isEmpty()) {
            // The flight recorder (ADR 0008): the same events the window shows are kept for `devdesk history`.
            recorder = Optional.of(
                    new Recorder(JsonlHistoryStore.forProject(engine.environment(), l.project()), engine.clock()));
            Optional<ContainerRuntime> containers = containers(l);
            if (containers.isEmpty()) {
                return new Answer(409, ApiJson.error(dockerProblem(l)));
            }
            supervisor = Optional.of(new ProjectSupervisor(
                    l.project(),
                    graph(l),
                    new ProjectSupervisor.Ports(
                            engine.runtime(),
                            containers.get(),
                            engine.probe(),
                            engine.system(),
                            engine.diagnosis(),
                            engine.resources()),
                    ProjectSupervisor.Settings.defaults(engine.environment(), engine.clock(), engine.random()),
                    event -> {
                        onEvent(event);
                        recorder.ifPresent(r -> r.accept(event));
                    }));
        }
        if (lock.isEmpty()) {
            switch (ProjectLock.acquire(engine.environment(), l.project(), "the desktop app")) {
                case ProjectLock.Result.Busy busy -> {
                    return new Answer(
                            409,
                            ApiJson.error(l.project().name() + " is already running in another DevDesk (" + busy.owner()
                                    + "). Use that one, or stop it there first."));
                }
                case ProjectLock.Result.Acquired acquired -> lock = Optional.of(acquired.lock());
            }
        }
        findings.clear();
        recorder.ifPresent(r -> r.sessionStarted("desktop app"));
        cleanUpSurvivors(l);
        supervisor.get().up(); // asynchronous: the app follows the events
        return new Answer(202, Map.of("started", true));
    }

    synchronized Answer down() {
        // Release the project once everything is stopped, so `devdesk up` in a terminal may run it.
        supervisor.ifPresent(s -> s.down().thenRun(this::releaseLock));
        return new Answer(202, Map.of("stopping", true));
    }

    /** Checks before starting, like `devdesk why`: runs nothing. */
    synchronized Answer why() {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        var ctx = new DiagnosisContext(
                project.get().project(),
                Map.copyOf(states),
                Map.of(),
                engine.environment(),
                engine.system(),
                engine.clock().instant());
        return new Answer(
                200,
                Map.of(
                        "findings",
                        engine.diagnosis().preflight(ctx).stream()
                                .map(ApiJson::finding)
                                .toList()));
    }

    /** The flight recorder's timeline (ADR 0008), like `devdesk history`. Readable even while nothing runs. */
    synchronized Answer history(Duration back) {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        var store =
                JsonlHistoryStore.forProject(engine.environment(), project.get().project());
        List<Map<String, Object>> items = Timeline.of(
                        store.read(engine.clock().instant().minus(back)))
                .stream()
                .map(ApiJson::historyItem)
                .toList();
        return new Answer(200, Map.of("folder", store.directory().toString(), "items", items));
    }

    /**
     * What the machine shows right now, like `devdesk ps`: who talks to whom, and ghosts. Not synchronized: waiting
     * for the supervisor while holding this lock could deadlock with its events, which also take the lock.
     */
    Answer machine() {
        ProjectLoader.Result.Loaded l;
        Optional<ProjectSupervisor> running;
        synchronized (this) {
            if (project.isEmpty()) {
                return new Answer(404, ApiJson.error("no project is open"));
            }
            l = project.get();
            running = supervisor;
        }
        Map<String, Set<Long>> pids = Map.of();
        if (running.isPresent()) {
            try {
                pids = running.get().pids().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                return new Answer(503, ApiJson.error("the engine is busy, try again: " + e));
            }
        }
        Set<Long> managed = new HashSet<>();
        pids.values().forEach(managed::addAll);
        List<Map<String, Object>> links = ProjectLinks.of(l.project(), pids, engine.system()).stream()
                .map(ApiJson::link)
                .toList();
        List<Map<String, Object>> ghosts =
                Ghosts.find(
                                l.project(),
                                engine.system(),
                                managed,
                                Set.of(ProcessHandle.current().pid()))
                        .stream()
                        .map(g -> ApiJson.ghost(g, l.project()))
                        .toList();
        return new Answer(200, Map.of("connections", links, "ghosts", ghosts));
    }

    // ---- live events ----

    /** A new window connected: it gets the full picture first, then every event after it. */
    synchronized void connect(EventClient client) {
        clients.add(client);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("seq", sequence);
        snapshot.put("type", "snapshot");
        snapshot.put("project", project.isEmpty() ? null : view());
        snapshot.put("output", output);
        snapshot.put("findings", findings);
        client.offer(ApiJson.write(snapshot));
    }

    void disconnect(EventClient client) {
        clients.remove(client);
        client.close();
    }

    /** Called by the supervisor from several threads. */
    private synchronized void onEvent(ProjectEvent event) {
        switch (event) {
            case ProjectEvent.StateChanged c -> {
                states.put(c.service(), c.to());
                if (c.to() instanceof ServiceState.Starting) {
                    findings.remove(c.service()); // a new attempt: old findings no longer apply
                }
                broadcast(
                        "state",
                        Map.of(
                                "service",
                                c.service(),
                                "state",
                                ApiJson.state(c.to()),
                                "reason",
                                c.reason(),
                                "at",
                                c.at().toString()));
            }
            case ProjectEvent.Output o -> {
                Map<String, Object> line = Map.of(
                        "service",
                        o.service(),
                        "stream",
                        o.line().stream().name().toLowerCase(),
                        "text",
                        o.line().text(),
                        "at",
                        engine.clock().instant().toString());
                Deque<Map<String, Object>> kept = output.get(o.service());
                kept.addLast(line);
                if (kept.size() > OUTPUT_LINES_KEPT) {
                    kept.removeFirst();
                }
                broadcast("output", line);
            }
            case ProjectEvent.Diagnosis d -> {
                List<Map<String, Object>> list =
                        d.findings().stream().map(ApiJson::finding).toList();
                findings.put(d.service(), list);
                broadcast("diagnosis", Map.of("service", d.service(), "findings", list));
            }
            case ProjectEvent.Resources r -> {
                Map<String, Object> perService = new LinkedHashMap<>();
                r.services()
                        .forEach((name, sample) -> perService.put(
                                name,
                                Map.of(
                                        "cpu", Math.round(sample.cpuPercent() * 10) / 10.0,
                                        "memory", sample.memoryBytes(),
                                        "processes", sample.processes(),
                                        "sockets", sample.sockets())));
                broadcast("resources", Map.of("services", perService));
            }
            case ProjectEvent.Warning w -> broadcast("warning", Map.of("service", w.service(), "message", w.message()));
        }
    }

    /** Stops everything (dependents first) and waits: used when the app closes. */
    @Override
    public void close() {
        Optional<ProjectSupervisor> current;
        synchronized (this) {
            current = supervisor;
        }
        current.ifPresent(s -> {
            try {
                s.down().get(2, TimeUnit.MINUTES);
                recorder.ifPresent(r -> r.sessionEnded("desktop app closed"));
                lock.ifPresent(ProjectLock::close);
            } catch (Exception e) {
                System.err.println("devdeskd: stopping took too long or failed: " + e);
            } finally {
                s.close();
            }
        });
        clients.forEach(EventClient::close);
    }

    // ---- helpers ----

    private synchronized void releaseLock() {
        lock.ifPresent(ProjectLock::close);
        lock = Optional.empty();
        recorder.ifPresent(r -> r.sessionEnded("stopped from the desktop app"));
    }

    /** We hold the lock, so anything found now was left behind by a DevDesk that was killed (see Survivors). */
    private void cleanUpSurvivors(ProjectLoader.Result.Loaded l) {
        List<LeftoverFinder> finders = new ArrayList<>(List.of(engine.leftovers()));
        if (containers(l).orElse(null) instanceof DockerContainerRuntime docker) {
            finders.add(docker.leftovers());
        }
        for (Survivors.Report r : new Survivors(finders).cleanUp(l.project())) {
            String message = "left behind by a DevDesk that was killed: "
                    + r.leftover().description() + " (PIDs " + r.leftover().pids() + ") → " + r.result();
            onEvent(new ProjectEvent.Warning(r.leftover().service(), message));
            recorder.ifPresent(
                    rec -> rec.accept(new ProjectEvent.Warning(r.leftover().service(), message)));
        }
    }

    private boolean isRunning() {
        return states.values().stream().anyMatch(ServiceState::isActive);
    }

    private boolean trusted(ProjectLoader.Result.Loaded l) {
        return engine.trust().isTrusted(l.file(), TrustStore.fingerprint(l.content()));
    }

    private Map<String, Object> view() {
        var l = project.orElseThrow();
        return ApiJson.project(
                l.project(), l.file().toString(), trusted(l), ProjectLoader.commands(l.project()), states);
    }

    private void broadcast(String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", ++sequence);
        event.put("type", type);
        event.putAll(data);
        String json = ApiJson.write(event);
        for (EventClient c : clients) {
            if (!c.offer(json)) {
                clients.remove(c);
            }
        }
    }

    private Optional<ContainerRuntime> containers(ProjectLoader.Result.Loaded l) {
        boolean needDocker =
                l.project().services().values().stream().anyMatch(s -> s.workload() instanceof Workload.Container);
        if (!needDocker) {
            return Optional.of(new NoContainers());
        }
        return engine.docker().get() instanceof DockerConnection.Result.Connected c
                ? Optional.of(c.runtime())
                : Optional.empty();
    }

    private String dockerProblem(ProjectLoader.Result.Loaded l) {
        List<String> names = new ArrayList<>();
        l.project().services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Container)
                .forEach(s -> names.add(s.name()));
        String reason = engine.docker().get() instanceof DockerConnection.Result.Unavailable u
                ? u.reason()
                : "Docker is unavailable";
        return String.join(", ", names) + " need Docker, but " + reason;
    }

    private static DependencyGraph graph(ProjectLoader.Result.Loaded l) {
        try {
            return DependencyGraph.of(l.project().services().values());
        } catch (DependencyCycleException e) {
            throw new IllegalStateException("the config parser already rejects cycles", e);
        }
    }
}
