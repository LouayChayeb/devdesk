package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.branch.BranchStacks;
import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.NoContainers;
import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseBackups;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.DatabaseTargets;
import io.github.louch.devdesk.core.data.DocumentBrowser;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.ObjectStore;
import io.github.louch.devdesk.core.data.SearchIndex;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.data.SqlEditor;
import io.github.louch.devdesk.core.diagnosis.DiagnosisContext;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Ghosts;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.diagnosis.ProjectLinks;
import io.github.louch.devdesk.core.doctor.Doctor;
import io.github.louch.devdesk.core.graph.DependencyCycleException;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.history.Recorder;
import io.github.louch.devdesk.core.history.StartupTimeline;
import io.github.louch.devdesk.core.history.Timeline;
import io.github.louch.devdesk.core.http.RecentRequests;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.leftovers.Survivors;
import io.github.louch.devdesk.core.logs.LogArchive;
import io.github.louch.devdesk.core.logs.LogLine;
import io.github.louch.devdesk.core.logs.LogQuery;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.network.PortTable;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.resources.ResourceSampler;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ProjectEvent;
import io.github.louch.devdesk.core.supervisor.ProjectSupervisor;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.docker.DockerContainerRuntime;
import io.github.louch.devdesk.linux.doctor.MachineFacts;
import io.github.louch.devdesk.linux.history.FileLogArchive;
import io.github.louch.devdesk.linux.history.JsonlHistoryStore;
import io.github.louch.devdesk.linux.project.ProjectLoader;
import io.github.louch.devdesk.linux.project.ProjectLock;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
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
            Map<DatabaseTarget.Kind, SqlBrowser> sql,
            Map<DatabaseTarget.Kind, SqlEditor> sqlEditors,
            DatabaseBackups fileBackups,
            Function<Path, List<DatabaseTarget>> sqliteFiles,
            KeyValueBrowser redis,
            DocumentBrowser documents,
            ObjectStore objects,
            SearchIndex search,
            Map<String, String> environment,
            Clock clock,
            RandomGenerator random) {}

    /** The result of an action, ready to become an HTTP answer. */
    record Answer(int status, Object body) {}

    private static final int OUTPUT_LINES_KEPT = 1000;

    /** The last CPU and memory reading per service (every 2 s while the project runs). */
    private Map<String, ResourceSample> latestResources = Map.of();

    /** The git branch the open project's spec was prepared for (per_branch volumes). */
    private Optional<String> branch = Optional.empty();
    /** Long enough for every service's grace period, one after the other in a big project. */
    private static final Duration SWITCH_WAIT = Duration.ofMinutes(2);

    private final Engine engine;
    private final List<EventClient> clients = new CopyOnWriteArrayList<>();

    // ---- guarded by "this" ----
    private Optional<ProjectLoader.Result.Loaded> project = Optional.empty();
    private Optional<ProjectSupervisor> supervisor = Optional.empty();
    private Optional<Recorder> recorder = Optional.empty();
    /** Every output line of the open project, kept for some days for search. */
    private Optional<LogArchive> archive = Optional.empty();
    /** Held while this engine runs the project, so a terminal `devdesk up` can't run it a second time. */
    private Optional<ProjectLock> lock = Optional.empty();

    private final Map<String, ServiceState> states = new LinkedHashMap<>();
    private final Map<String, Deque<Map<String, Object>>> output = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> findings = new LinkedHashMap<>();

    private long sequence;

    /** The API client's recent requests: an error line during one of them gets a link to it. */
    private final RecentRequests recentRequests;

    /** The profile each project runs with on this machine. */
    private final ActiveProfiles profiles;

    EngineSession(Engine engine, RecentRequests recentRequests, ActiveProfiles profiles) {
        this.engine = engine;
        this.recentRequests = recentRequests;
        this.profiles = profiles;
    }

    // ---- actions (called by the HTTP API) ----

    synchronized Answer open(Path path) {
        if (isRunning()) {
            return new Answer(409, ApiJson.error("stop the current project first: it's still running"));
        }
        return opened(ProjectLoader.load(path, engine.environment()));
    }

    /**
     * Stops the open project if it runs, waits until everything is really stopped, then opens another: switching
     * projects in one click. The wait happens outside the lock, because the stop events need it.
     */
    Answer switchTo(Path path) {
        CompletableFuture<Void> stopped;
        synchronized (this) {
            stopped = isRunning()
                    ? supervisor.orElseThrow().down().thenRun(this::releaseLock)
                    : CompletableFuture.completedFuture(null);
        }
        try {
            stopped.get(SWITCH_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return new Answer(
                    409,
                    ApiJson.error("the current project is still stopping after " + SWITCH_WAIT.toSeconds()
                            + " s: look at its services, then try again"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(503, ApiJson.error("interrupted while stopping the current project"));
        } catch (ExecutionException e) {
            return new Answer(500, ApiJson.error("stopping the current project failed: " + e.getCause()));
        }
        return open(path);
    }

    /**
     * Opens a devdesk.yaml that isn't saved yet (the setup wizard's test run) and trusts it: the user just saw every
     * command in the wizard and clicked "Test run". Saving the same text later keeps that trust.
     */
    synchronized Answer openUnsaved(Path folder, String content) {
        if (isRunning()) {
            return new Answer(409, ApiJson.error("stop the current project first: it's still running"));
        }
        Answer answer = opened(ProjectLoader.loadText(folder, content, engine.environment()));
        if (answer.status() == 200) {
            return trust();
        }
        return answer;
    }

    /**
     * Closes a project the wizard test-ran but never saved (its devdesk.yaml doesn't exist), so the app doesn't keep
     * showing a project that has no file. A saved project stays open.
     */
    synchronized Answer closeUnsaved() {
        if (isRunning()) {
            return new Answer(409, ApiJson.error("stop the test run first: services are still running"));
        }
        if (project.isPresent() && !Files.exists(project.get().file())) {
            supervisor.ifPresent(ProjectSupervisor::close);
            supervisor = Optional.empty();
            project = Optional.empty();
            states.clear();
            output.clear();
            findings.clear();
            Map<String, Object> none = new HashMap<>();
            none.put("project", null); // the app shows "Open a project" again
            broadcast("project", none);
        }
        return new Answer(200, Map.of("closed", project.isEmpty()));
    }

    /** The last {@code n} lines a service printed, oldest first (the setup shows what a one-time job said). */
    synchronized List<String> recentOutput(String service, int n) {
        List<String> lines = new ArrayList<>();
        output.getOrDefault(service, new ArrayDeque<>()).forEach(l -> lines.add(String.valueOf(l.get("text"))));
        return lines.subList(Math.max(0, lines.size() - n), lines.size());
    }

    /**
     * The project as it runs here: with its active profile's settings. A profile that no longer exists in devdesk.yaml
     * falls back to the default (no overrides).
     */
    private ProjectLoader.Result.Loaded withProfile(ProjectLoader.Result.Loaded l) {
        ProjectLoader.Result.Loaded profiled = profiles.of(l.project().key())
                .map(name -> l.project().profiles().get(name))
                .map(p -> new ProjectLoader.Result.Loaded(l.file(), l.content(), p.applyTo(l.project())))
                .orElse(l);
        // One stack per git branch: per_branch services get this branch's own data volumes.
        branch = gitBranch(profiled.project().root());
        return new ProjectLoader.Result.Loaded(
                profiled.file(), profiled.content(), BranchStacks.apply(profiled.project(), branch));
    }

    /** The git branch the project folder is on (from .git/HEAD, also in a worktree); empty when none or detached. */
    private Optional<String> gitBranch(Path root) {
        Path dotGit = root.resolve(".git");
        Optional<String> head = engine.system().readFile(dotGit.resolve("HEAD"));
        if (head.isEmpty()) {
            head = engine.system()
                    .readFile(dotGit)
                    .flatMap(BranchStacks::gitDirOf)
                    .flatMap(dir -> engine.system().readFile(root.resolve(dir).resolve("HEAD")));
        }
        return head.flatMap(BranchStacks::branch);
    }

    /**
     * Called every second by the watch loop: when the project's git branch changed and some of its services keep
     * their data per branch, the project is opened again for the new branch, and restarted if it was running.
     */
    void checkBranch() {
        Path root;
        boolean wasRunning;
        String from;
        String services;
        Optional<String> now;
        synchronized (this) {
            if (project.isEmpty()
                    || BranchStacks.perBranch(project.get().project()).isEmpty()) {
                return;
            }
            services = String.join(", ", BranchStacks.perBranch(project.get().project()));
            root = project.get().project().root();
            now = gitBranch(root);
            if (now.equals(branch)) {
                return;
            }
            wasRunning = isRunning();
            from = branch.orElse("(no branch)");
            branch = now; // so the next look doesn't start a second switch
        }
        String to = now.orElse("(no branch)");
        synchronized (this) {
            broadcast(
                    "warning",
                    Map.of(
                            "service",
                            "",
                            "message",
                            "git branch changed from " + from + " to " + to + ": "
                                    + (wasRunning ? "restarting with " : "") + to + "'s own data for " + services));
        }
        switchTo(root);
        if (wasRunning) {
            up();
        }
    }

    /** The active profile's name, or empty for the default. */
    private Optional<String> activeProfile(ProjectSpec spec) {
        return profiles.of(spec.key()).filter(spec.profiles()::containsKey);
    }

    /** Switches profile ("" = default). Only while stopped: running services would keep their old settings. */
    synchronized Answer selectProfile(String name) {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        if (isRunning()) {
            return new Answer(
                    409,
                    ApiJson.error(
                            "stop the project first: running services would keep the old settings until restarted"));
        }
        var spec = project.get().project();
        if (!name.isEmpty() && !spec.profiles().containsKey(name)) {
            return new Answer(
                    400,
                    ApiJson.error("no profile \"" + name + "\" in devdesk.yaml. Profiles: "
                            + (spec.profiles().isEmpty()
                                    ? "none"
                                    : String.join(", ", spec.profiles().keySet()))));
        }
        try {
            profiles.set(spec.key(), name.isEmpty() ? Optional.empty() : Optional.of(name));
        } catch (IOException e) {
            return new Answer(500, ApiJson.error("could not remember the profile: " + e.getMessage()));
        }
        Path file = project.get().file();
        return Files.exists(file) ? open(file.getParent()) : new Answer(200, view());
    }

    /**
     * A line from DevDesk itself in a service's logs ("devdesk: 2 files changed, restarting"), so the reason for what
     * happened sits right next to what the service printed.
     */
    synchronized void note(String service, String text) {
        Deque<Map<String, Object>> kept = output.get(service);
        if (kept == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("service", service);
        line.put("stream", "stdout");
        line.put("text", "devdesk: " + text);
        line.put("at", engine.clock().instant().toString());
        archive.ifPresent(a -> a.append(new LogLine(engine.clock().instant(), service, "stdout", "devdesk: " + text)));
        kept.addLast(line);
        if (kept.size() > OUTPUT_LINES_KEPT) {
            kept.removeFirst();
        }
        broadcast("output", line);
    }

    /** Past output of the open project's services (kept {@code FileLogArchive.KEEP_DAYS} days), newest first. */
    Answer searchLogs(String text, Set<String> services, boolean errorsOnly, Duration back, int limit) {
        Optional<LogArchive> a;
        synchronized (this) {
            a = archive;
        }
        if (a.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        Instant since = engine.clock().instant().minus(back);
        try {
            var matcher = new LogQuery(text, services, errorsOnly, since).matcher();
            // Read outside the lock: a search through days of logs must not hold up the event stream.
            List<LogLine> found = a.get().search(matcher, since, limit);
            return new Answer(
                    200,
                    Map.of(
                            "lines",
                            found.stream().map(EngineSession::logLine).toList(),
                            "more",
                            found.size() >= limit));
        } catch (LogQuery.InvalidQuery e) {
            return new Answer(400, ApiJson.error(e.getMessage()));
        }
    }

    /** The lines a service printed around a moment: the context of a search result. */
    Answer logsAround(String service, Instant at) {
        Optional<LogArchive> a;
        synchronized (this) {
            a = archive;
        }
        return a.map(x -> new Answer(
                        200,
                        Map.of(
                                "lines",
                                x.around(service, at, 20, 20).stream()
                                        .map(EngineSession::logLine)
                                        .toList())))
                .orElse(new Answer(404, ApiJson.error("no project is open")));
    }

    private static Map<String, Object> logLine(LogLine l) {
        return Map.of("at", l.at().toString(), "service", l.service(), "stream", l.stream(), "text", l.text());
    }

    /** Whether the open project's devdesk.yaml, as it is now, was trusted (tasks run its commands too). */
    synchronized boolean trusted() {
        return project.isPresent() && trusted(project.get());
    }

    /** Whether any service is starting, running or stopping (opening another project must wait). */
    synchronized boolean running() {
        return isRunning();
    }

    private Answer opened(ProjectLoader.Result result) {
        return switch (result) {
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
            case ProjectLoader.Result.Loaded raw -> {
                ProjectLoader.Result.Loaded l = withProfile(raw);
                archive = Optional.of(FileLogArchive.forProject(engine.environment(), l.project(), engine.clock()));
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
        beforeStart.run();
        Optional<Answer> problem = prepare();
        if (problem.isPresent()) {
            return problem.get();
        }
        supervisor.get().up(); // asynchronous: the app follows the events
        return new Answer(202, Map.of("started", true));
    }

    /**
     * "start", "stop" or "restart" one service (with what it needs, or what needs it), or "reload": restart only
     * this one, for restart on save.
     */
    synchronized Answer serviceAction(String name, String action) {
        if (project.isEmpty() || !project.get().project().services().containsKey(name)) {
            return new Answer(404, ApiJson.error("no service called " + name + " in the open project"));
        }
        beforeStart.run(); // a sleeping service's port is freed before anyone starts it by hand
        if (action.equals("start") || supervisor.isEmpty()) {
            if (!action.equals("start")) {
                return new Answer(409, ApiJson.error(name + " isn't running"));
            }
            Optional<Answer> problem = prepare();
            if (problem.isPresent()) {
                return problem.get();
            }
        }
        ProjectSupervisor s = supervisor.get();
        switch (action) {
            case "start" -> s.startService(name);
            case "stop" -> s.stopService(name);
            case "restart" -> s.restartService(name);
            case "reload" -> s.reloadService(name); // only this one: restart on save
            default -> {
                return new Answer(400, ApiJson.error("action must be start, stop or restart"));
            }
        }
        return new Answer(202, Map.of("service", name, "action", action));
    }

    /** Every listening port on the machine and who owns it (the Ports tab). Waits for the supervisor, so unlocked. */
    Answer ports() {
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
        var rows = PortTable.of(
                l.project(),
                pids,
                engine.system().allListeners(),
                engine.system()::devdeskProjectOf,
                ProcessHandle.current().pid());
        return new Answer(200, Map.of("ports", rows.stream().map(ApiJson::port).toList()));
    }

    // ---- the Workbench: data (DESIGN/VISION family B1) ----

    /** The project's databases, found from its services with no configuration, and SQLite files in its folder. */
    synchronized Answer databases() {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (DatabaseTarget t : targets(project.get().project())) {
            list.add(ApiJson.database(t, states.getOrDefault(t.service(), new ServiceState.Stopped())));
        }
        return new Answer(200, Map.of("databases", list));
    }

    /** The open project's databases (services and SQLite files), for snapshots of all of them at once. */
    synchronized List<DatabaseTarget> databaseTargets() {
        return project.map(l -> targets(l.project())).orElse(List.of());
    }

    private List<DatabaseTarget> targets(ProjectSpec p) {
        List<DatabaseTarget> all = new ArrayList<>(DatabaseTargets.find(p));
        all.addAll(engine.sqliteFiles().apply(p.root()));
        return all;
    }

    /**
     * Runs one call on a database (tables, rows, a typed query, a Redis key...). Not synchronized: a slow query must
     * never hold up the project's events. Errors come back as the database's message plus DevDesk's hint.
     *
     * @param sql true for routes that need tables and SQL, false for Redis routes, so neither talks to the other
     */
    Answer data(String service, boolean sql, Function<DatabaseTarget, Object> call) {
        return sql
                ? data(
                        service,
                        EnumSet.of(DatabaseTarget.Kind.POSTGRES, DatabaseTarget.Kind.MYSQL, DatabaseTarget.Kind.SQLITE),
                        "SQL",
                        call)
                : data(service, EnumSet.of(DatabaseTarget.Kind.REDIS), "Redis", call);
    }

    /**
     * Like {@link #data(String, boolean, Function)}, for routes of one family of databases ({@code kinds}), named in
     * the message when the service is another kind.
     */
    Answer data(String service, Set<DatabaseTarget.Kind> kinds, String family, Function<DatabaseTarget, Object> call) {
        Optional<DatabaseTarget> target;
        synchronized (this) {
            if (project.isEmpty()) {
                return new Answer(404, ApiJson.error("no project is open"));
            }
            target = targets(project.get().project()).stream()
                    .filter(t -> t.service().equals(service))
                    .findFirst();
        }
        if (target.isEmpty()) {
            return new Answer(
                    404,
                    ApiJson.error(service + " isn't a database DevDesk recognises (a Postgres, MySQL, MariaDB, Redis or"
                            + " MongoDB container with a published port, or a SQLite file in the project folder)"));
        }
        if (!kinds.contains(target.get().kind())) {
            return new Answer(
                    400,
                    ApiJson.error(service + " is a " + target.get().kind().label() + " database; this needs a " + family
                            + " one"));
        }
        try {
            return new Answer(200, call.apply(target.get()));
        } catch (DataException e) {
            return new Answer(422, ApiJson.error(e.getMessage()));
        }
    }

    /** Every service's output between two moments, oldest first: what one request made the project do. */
    synchronized List<Map<String, Object>> linesBetween(Instant from, Instant to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Deque<Map<String, Object>> lines : output.values()) {
            for (Map<String, Object> l : lines) {
                Instant at = Instant.parse((String) l.get("at"));
                if (!at.isBefore(from) && !at.isAfter(to)) {
                    out.add(l);
                }
            }
        }
        out.sort(java.util.Comparator.comparing(l -> (String) l.get("at")));
        return out;
    }

    /** The open project and its services' states right now, for the API client. */
    synchronized Optional<Current> current() {
        return project.map(l -> new Current(l.project(), Map.copyOf(states)));
    }

    /** A copy: reading it never races with the supervisor's events. */
    record Current(ProjectSpec project, Map<String, ServiceState> states) {}

    KeyValueBrowser redis() {
        return engine.redis();
    }

    DocumentBrowser documents() {
        return engine.documents();
    }

    /** Runs before a start or stop the user asked for: auto-sleep lets go of the ports it holds. */
    private Runnable beforeStart = () -> {};

    /** Services asleep (auto-sleep): shown as such, and woken by a connection. */
    private final Set<String> sleeping = java.util.concurrent.ConcurrentHashMap.newKeySet();

    void onBeforeStart(Runnable hook) {
        this.beforeStart = hook;
    }

    void markSleeping(String service, boolean asleep) {
        if (asleep) {
            sleeping.add(service);
        } else {
            sleeping.remove(service);
        }
        synchronized (this) {
            if (project.isPresent()) {
                broadcast("project", Map.of("project", view()));
            }
        }
    }

    /** Auto-sleep: stops only this service. */
    CompletableFuture<?> sleep(String service) {
        Optional<ProjectSupervisor> s;
        synchronized (this) {
            s = supervisor;
        }
        return s.map(x -> (CompletableFuture<?>) x.sleepService(service))
                .orElse(CompletableFuture.completedFuture(null));
    }

    /** Auto-sleep: starts a sleeping service again, on the connection that woke it. */
    void wake(String service) {
        Optional<ProjectSupervisor> s;
        synchronized (this) {
            s = supervisor;
        }
        s.ifPresent(x -> x.wakeService(service));
    }

    /** How many open connections the service's processes have on these local ports: is anyone using it? */
    int connectionsInto(String service, Set<Integer> ports) {
        Optional<ProjectSupervisor> running;
        synchronized (this) {
            running = supervisor;
        }
        if (running.isEmpty()) {
            return 0;
        }
        try {
            Set<Long> pids = running.get().pids().get(5, TimeUnit.SECONDS).getOrDefault(service, Set.of());
            return (int) engine.system().connectionsOf(pids).stream()
                    .filter(c -> ports.contains(c.localPort()))
                    .count();
        } catch (Exception e) {
            return 1; // can't tell: count it as used, so nothing sleeps on a guess
        }
    }

    /** The last CPU and memory reading of each running service. */
    synchronized Map<String, ResourceSample> latestResources() {
        return latestResources;
    }

    /** Docker (or Podman) as it answers now, or why not. */
    DockerConnection.Result docker() {
        return engine.docker().get();
    }

    /** S3-style file storage (MinIO, RustFS). */
    ObjectStore objects() {
        return engine.objects();
    }

    /** Elasticsearch or OpenSearch. */
    SearchIndex search() {
        return engine.search();
    }

    /** The browser for this database's kind (Postgres, MySQL, SQLite). */
    SqlBrowser sql(DatabaseTarget t) {
        return engine.sql().get(t.kind());
    }

    /** The one write path: only explicit actions (Save in the grid, an import, Stop) reach it. */
    SqlEditor sqlEditor(DatabaseTarget t) {
        return engine.sqlEditors().get(t.kind());
    }

    /** Container databases are saved by their own tools through Docker; SQLite files directly. */
    DatabaseBackups backups(DatabaseTarget t) {
        if (t.kind() == DatabaseTarget.Kind.SQLITE) {
            return engine.fileBackups();
        }
        if (engine.docker().get() instanceof DockerConnection.Result.Connected c) {
            return c.runtime().backups();
        }
        throw new DataException("Docker isn't reachable, and the database's own dump tool runs inside its container");
    }

    /**
     * What the service drawer shows. Like {@link #machine()}, not synchronized: it waits for the supervisor.
     */
    Answer details(String name) {
        ProjectLoader.Result.Loaded l;
        Optional<ProjectSupervisor> running;
        synchronized (this) {
            if (project.isEmpty() || !project.get().project().services().containsKey(name)) {
                return new Answer(404, ApiJson.error("no service called " + name + " in the open project"));
            }
            l = project.get();
            running = supervisor;
        }
        var spec = l.project().services().get(name);
        if (running.isEmpty()) { // never started: only what the file says
            return new Answer(
                    200,
                    ApiJson.serviceDetails(
                            spec,
                            Optional.empty(),
                            ServiceEnvironment.of(l.project(), spec, engine.environment()),
                            List.of(),
                            graph(l)));
        }
        try {
            var d = running.get().details(name).get(5, TimeUnit.SECONDS);
            List<Integer> listening = engine.system().listenersOf(d.history().pids()).stream()
                    .map(Listener::port)
                    .distinct()
                    .sorted()
                    .toList();
            return new Answer(200, ApiJson.serviceDetails(spec, Optional.of(d), d.environment(), listening, graph(l)));
        } catch (Exception e) {
            return new Answer(503, ApiJson.error("the engine is busy, try again: " + e));
        }
    }

    /**
     * One environment value, only when the user explicitly asks for it (principle 7: never shown by default). Not
     * recorded anywhere.
     */
    synchronized Answer reveal(String name, String key) {
        if (project.isEmpty() || !project.get().project().services().containsKey(name)) {
            return new Answer(404, ApiJson.error("no service called " + name + " in the open project"));
        }
        var l = project.get();
        return ServiceEnvironment.of(l.project(), l.project().services().get(name), engine.environment()).stream()
                .filter(v -> v.key().equals(key))
                .findFirst()
                .map(v -> new Answer(200, Map.of("key", key, "value", v.value())))
                .orElse(new Answer(404, ApiJson.error(name + " has no variable " + key)));
    }

    /**
     * Everything needed before anything may run: trust, Docker if needed, the supervisor, the project lock, the
     * recorder and survivors of a killed DevDesk. Empty when ready.
     */
    private Optional<Answer> prepare() {
        if (project.isEmpty()) {
            return Optional.of(new Answer(404, ApiJson.error("no project is open")));
        }
        var l = project.get();
        if (!trusted(l)) {
            return Optional.of(new Answer(
                    403,
                    Map.of(
                            "error",
                            "this project isn't trusted yet (or devdesk.yaml changed)",
                            "commands",
                            ProjectLoader.commands(l.project()))));
        }
        if (supervisor.isEmpty()) {
            // The flight recorder (ADR 0008): the same events the window shows are kept for `devdesk history`.
            recorder = Optional.of(
                    new Recorder(JsonlHistoryStore.forProject(engine.environment(), l.project()), engine.clock()));
            Optional<ContainerRuntime> containers = containers(l);
            if (containers.isEmpty()) {
                return Optional.of(new Answer(409, ApiJson.error(dockerProblem(l))));
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
                    return Optional.of(new Answer(
                            409,
                            ApiJson.error(l.project().name() + " is already running in another DevDesk (" + busy.owner()
                                    + "). Use that one, or stop it there first.")));
                }
                case ProjectLock.Result.Acquired acquired -> {
                    lock = Optional.of(acquired.lock());
                    // A new session: record it, and clean up what a killed DevDesk left (we hold the lock now).
                    findings.clear();
                    recorder.ifPresent(r -> r.sessionStarted("desktop app"));
                    cleanUpSurvivors(l);
                }
            }
        }
        return Optional.empty();
    }

    synchronized Answer down() {
        beforeStart.run();
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

    /** "Can this machine run the open project?" Like `devdesk doctor`: versions, Docker, packages, ports, disk. */
    Answer doctor() {
        ProjectSpec spec;
        Map<String, ServiceState> now;
        synchronized (this) {
            if (project.isEmpty()) {
                return new Answer(404, ApiJson.error("no project is open"));
            }
            spec = project.get().project();
            now = Map.copyOf(states);
        }
        // Outside the lock: asking tools for their version takes a moment, and must not block the event stream.
        return new Answer(200, doctor(spec, now));
    }

    /** The doctor's report for any project, also one the setup wizard hasn't saved yet. */
    Map<String, Object> doctor(ProjectSpec spec, Map<String, ServiceState> current) {
        var connection = engine.docker().get();
        var facts = MachineFacts.gather(
                spec.root(),
                engine.environment(),
                () -> connection instanceof DockerConnection.Result.Unavailable u
                        ? Optional.of(u.reason())
                        : Optional.empty(),
                () -> connection instanceof DockerConnection.Result.Connected c
                        ? Optional.of(c.runtime().localImages())
                        : Optional.empty());
        var ctx = new DiagnosisContext(
                spec,
                current,
                Map.of(),
                engine.environment(),
                engine.system(),
                engine.clock().instant());
        Doctor.Report report = Doctor.check(spec, facts, engine.diagnosis().preflight(ctx));
        return Map.of(
                "problems", report.problems().stream().map(ApiJson::finding).toList(),
                "passed", report.passed());
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

    /** What a crash bundle holds, taken in one go so its parts agree with each other. Values are still raw here. */
    record BundleParts(
            ProjectSpec project,
            String devdeskYaml,
            Map<String, Object> view,
            Map<String, List<Map<String, Object>>> findings,
            Map<String, List<String>> logs,
            Map<String, List<ServiceEnvironment.Variable>> environment,
            List<Map<String, Object>> history) {}

    /** The open project's parts for a crash bundle: the last 2000 lines per service, the last 24 hours of history. */
    synchronized Optional<BundleParts> bundleParts() {
        if (project.isEmpty()) {
            return Optional.empty();
        }
        var l = project.get();
        Map<String, List<String>> logs = new LinkedHashMap<>();
        l.project().services().keySet().forEach(name -> logs.put(name, recentOutput(name, 2000)));
        Map<String, List<ServiceEnvironment.Variable>> env = new LinkedHashMap<>();
        l.project()
                .services()
                .values()
                .forEach(s -> env.put(s.name(), ServiceEnvironment.of(l.project(), s, engine.environment())));
        Map<String, List<Map<String, Object>>> found = new LinkedHashMap<>();
        findings.forEach((k, v) -> found.put(k, List.copyOf(v)));
        List<Map<String, Object>> items = List.of();
        try {
            var store = JsonlHistoryStore.forProject(engine.environment(), l.project());
            items = Timeline.of(store.read(engine.clock().instant().minus(Duration.ofHours(24)))).stream()
                    .map(ApiJson::historyItem)
                    .toList();
        } catch (RuntimeException e) {
            // no history: the bundle goes without it
        }
        return Optional.of(new BundleParts(l.project(), l.content(), view(), found, logs, env, items));
    }

    /** The last startups (newest first): per service, how long it waited, started and got healthy. */
    synchronized Answer startups() {
        if (project.isEmpty()) {
            return new Answer(404, ApiJson.error("no project is open"));
        }
        var store =
                JsonlHistoryStore.forProject(engine.environment(), project.get().project());
        var startups = StartupTimeline.of(
                store.read(engine.clock().instant().minus(STARTUPS_BACK)),
                project.get().project(),
                STARTUPS_SHOWN);
        return new Answer(
                200, Map.of("startups", startups.stream().map(ApiJson::startup).toList()));
    }

    private static final Duration STARTUPS_BACK = Duration.ofDays(7);
    private static final int STARTUPS_SHOWN = 20;

    /**
     * The project service whose process holds the connection from {@code clientPort} to {@code serverPort} on this
     * machine: who made a call the recorder saw. Empty when it's nothing of the project (a browser, curl).
     */
    Optional<String> serviceUsing(int clientPort, int serverPort) {
        Optional<ProjectSupervisor> running;
        synchronized (this) {
            running = supervisor;
        }
        if (running.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Set<Long>> pids = running.get().pids().get(2, TimeUnit.SECONDS);
            for (Map.Entry<String, Set<Long>> e : pids.entrySet()) {
                boolean match = engine.system().connectionsOf(e.getValue()).stream()
                        .anyMatch(c -> c.localPort() == clientPort && c.remotePort() == serverPort);
                if (match) {
                    return Optional.of(e.getKey());
                }
            }
        } catch (Exception e) {
            // busy or gone: the call is recorded without its caller
        }
        return Optional.empty();
    }

    /**
     * Which of the project's running services hold a TCP connection to {@code port} on this machine, and how many:
     * "who is connected to RabbitMQ". Read from the kernel's socket tables, so it's true even for apps that don't
     * name their connections. Not synchronized, like {@link #machine()}: it waits for the supervisor.
     */
    Map<String, Integer> connectedTo(int port) {
        Optional<ProjectSupervisor> running;
        synchronized (this) {
            running = supervisor;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        if (running.isEmpty() || port <= 0) {
            return out;
        }
        Map<String, Set<Long>> pids;
        try {
            pids = running.get().pids().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return out; // busy: this is extra information, worth skipping rather than waiting
        }
        pids.forEach((service, set) -> {
            long n = engine.system().connectionsOf(set).stream()
                    .filter(c -> c.remotePort() == port && c.remoteIsLocal())
                    .count();
            if (n > 0) {
                out.put(service, (int) n);
            }
        });
        return out;
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
                Instant now = engine.clock().instant();
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("service", o.service());
                line.put("stream", o.line().stream().name().toLowerCase());
                line.put("text", o.line().text());
                line.put("at", now.toString());
                // A crash while the API client's request was running: the line links to that request.
                recentRequests
                        .cause(o.service(), o.line().text(), now)
                        .ifPresent(
                                r -> line.put("request", Map.of("id", r.id(), "method", r.method(), "url", r.url())));
                archive.ifPresent(a -> a.append(new LogLine(
                        now,
                        o.service(),
                        String.valueOf(line.get("stream")),
                        o.line().text())));
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
                latestResources = Map.copyOf(r.services());
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
        Map<String, Object> view = new LinkedHashMap<>(ApiJson.project(
                l.project(), l.file().toString(), trusted(l), ProjectLoader.commands(l.project()), states, graph(l)));
        view.put("profiles", List.copyOf(l.project().profiles().keySet()));
        view.put("profile", activeProfile(l.project()).orElse(""));
        view.put("branch", branch.orElse(null));
        view.put("perBranch", BranchStacks.perBranch(l.project()));
        view.put("sleeping", List.copyOf(sleeping));
        return view;
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
