package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.TaskSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import io.github.louch.devdesk.core.testing.TestReport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Runs the open project's tasks (seed, test, reset-db): the services a task needs first, then its command, with the
 * settings services get. Keeps each task's recent runs, also on disk, so "did the seed work yesterday?" has an answer.
 * A second instance runs each service's tests the same way (the Tests page).
 */
final class TaskRunner {

    private static final int RUNS_KEPT = 20;
    // Test runs print a lot; the names of failed tests come before the summary at the end.
    private static final int OUTPUT_LINES = 2000;
    /** A needed database can take a while on its first start (image download, initialisation). */
    private static final Duration READY_LIMIT = Duration.ofMinutes(3);

    /** One run. {@code status}: waiting (for needed services), running, ok, failed, blocked, cancelled. */
    private static final class Run {
        final String id;
        final String task;
        final Instant started;
        volatile Instant ended;
        volatile String status = "waiting";
        volatile String detail = "";
        volatile Integer exitCode;
        final Deque<String> output = new ArrayDeque<>();
        volatile Process process;
        volatile boolean cancelled;

        Run(String id, String task, Instant started) {
            this.id = id;
            this.task = task;
            this.started = started;
        }
    }

    private final EngineSession session;
    private final Map<String, String> environment;
    private final Path historyFolder;
    private final Clock clock;
    /** Which commands this runner runs: the project's tasks, or its services' tests. */
    private final Function<ProjectSpec, Map<String, TaskSpec>> source;
    /** "task" or "test", for messages. */
    private final String noun;

    private volatile boolean stopAll;
    /** Per project key, per task: newest run first. */
    private final Map<String, Map<String, Deque<Run>>> runs = new ConcurrentHashMap<>();

    TaskRunner(
            EngineSession session,
            Map<String, String> environment,
            Path historyFolder,
            Clock clock,
            Function<ProjectSpec, Map<String, TaskSpec>> source,
            String noun) {
        this.session = session;
        this.environment = environment;
        this.historyFolder = historyFolder;
        this.clock = clock;
        this.source = source;
        this.noun = noun;
    }

    /** The open project's tasks, each with its last run. */
    EngineSession.Answer list() {
        Optional<ProjectSpec> project = project();
        if (project.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        ProjectSpec p = project.get();
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskSpec t : source.apply(p).values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("command", t.command());
            m.put("directory", p.root().relativize(t.directory()).toString());
            m.put("needs", t.needs());
            m.put("description", t.description());
            m.put("confirm", t.confirm().orElse(null));
            Deque<Run> history = history(p, t.name());
            m.put("last", history.isEmpty() ? null : summary(history.peekFirst(), false));
            tasks.add(m);
        }
        return new EngineSession.Answer(200, Map.of("tasks", tasks));
    }

    /** One task's recent runs, newest first, with their output. */
    EngineSession.Answer runs(String name) {
        Optional<ProjectSpec> project = project();
        if (project.isEmpty() || !source.apply(project.get()).containsKey(name)) {
            return notFound(name);
        }
        return new EngineSession.Answer(
                200,
                Map.of(
                        "runs",
                        history(project.get(), name).stream()
                                .map(r -> summary(r, true))
                                .toList()));
    }

    EngineSession.Answer run(String name) {
        Optional<ProjectSpec> project = project();
        if (project.isEmpty() || !source.apply(project.get()).containsKey(name)) {
            return notFound(name);
        }
        if (!session.trusted()) {
            return untrusted();
        }
        ProjectSpec p = project.get();
        TaskSpec task = source.apply(p).get(name);
        Deque<Run> history = history(p, name);
        synchronized (history) {
            if (!history.isEmpty() && isActive(history.peekFirst())) {
                return new EngineSession.Answer(409, ApiJson.error(name + " is already running"));
            }
            Run run = new Run(Long.toString(clock.millis(), 36), name, clock.instant());
            history.addFirst(run);
            while (history.size() > RUNS_KEPT) {
                history.removeLast();
            }
            Thread.ofVirtual().name(noun + "-" + name).start(() -> execute(p, task, run));
            return new EngineSession.Answer(202, summary(run, false));
        }
    }

    EngineSession.Answer cancel(String name) {
        Optional<ProjectSpec> project = project();
        if (project.isEmpty() || !source.apply(project.get()).containsKey(name)) {
            return notFound(name);
        }
        Deque<Run> history = history(project.get(), name);
        Run run = history.peekFirst();
        if (run == null || !isActive(run)) {
            return new EngineSession.Answer(409, ApiJson.error(name + " isn't running"));
        }
        run.cancelled = true;
        Process p = run.process;
        if (p != null) {
            // Test runners and package managers start children: stop them too, then the shell.
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
        return new EngineSession.Answer(200, summary(run, false));
    }

    /**
     * Runs every one, one after the other: test suites running at once fight over ports, databases and CPU.
     * Returns at once; each result shows up as it ends.
     */
    EngineSession.Answer runAll() {
        Optional<ProjectSpec> project = project();
        if (project.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        if (!session.trusted()) {
            return untrusted();
        }
        List<String> names = List.copyOf(source.apply(project.get()).keySet());
        stopAll = false;
        Thread.ofVirtual().name(noun + "-all").start(() -> {
            for (String name : names) {
                if (stopAll) {
                    return;
                }
                if (run(name).status() >= 300) {
                    continue; // already running, or not trusted (then each one says so)
                }
                Deque<Run> history = history(project.get(), name);
                Run started = history.peekFirst();
                while (started != null && isActive(started)) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
        return new EngineSession.Answer(202, Map.of("queued", names));
    }

    /** Stops "run all" before the next one, and the one running now. */
    EngineSession.Answer cancelAll() {
        stopAll = true;
        project()
                .ifPresent(p -> source.apply(p).keySet().forEach(name -> {
                    Run r = history(p, name).peekFirst();
                    if (r != null && isActive(r)) {
                        cancel(name);
                    }
                }));
        return new EngineSession.Answer(200, Map.of("cancelled", true));
    }

    private EngineSession.Answer untrusted() {
        return new EngineSession.Answer(
                403,
                ApiJson.error("this project isn't trusted yet: review its commands first (a " + noun
                        + " runs a command of the project)"));
    }

    private EngineSession.Answer notFound(String name) {
        return new EngineSession.Answer(404, ApiJson.error("no " + noun + " called " + name + " in the open project"));
    }

    private void execute(ProjectSpec project, TaskSpec task, Run run) {
        try {
            Optional<String> notReady = waitForNeeds(project, task, run);
            if (notReady.isPresent()) {
                run.status = run.cancelled ? "cancelled" : "blocked";
                run.detail = notReady.get();
                return;
            }
            run.status = "running";
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", task.command())
                    .directory(task.directory().toFile())
                    .redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(ServiceEnvironment.forTask(project, task, environment));
            Process process = builder.start();
            run.process = process;
            process.getOutputStream().close(); // nothing types into a task: one waiting for input fails, not hangs
            try (var lines =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    add(run, line);
                }
            }
            process.waitFor(10, TimeUnit.SECONDS); // the output ended: the process is ending too
            run.exitCode = process.isAlive() ? null : process.exitValue();
            if (run.cancelled) {
                run.status = "cancelled";
                run.detail = "stopped by you";
            } else if (Integer.valueOf(0).equals(run.exitCode)) {
                run.status = "ok";
                run.detail = "finished OK";
            } else {
                run.status = "failed";
                run.detail = "exited with code " + run.exitCode + lastLine(run);
            }
        } catch (IOException e) {
            run.status = "failed";
            run.detail = "could not run \"" + task.command() + "\": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.status = "cancelled";
        } finally {
            run.ended = clock.instant();
            run.process = null;
            save(project, run);
        }
    }

    /**
     * Starts what the task needs (with what those need) and waits until each is ready. Returns why not, if not.
     * Services already running are left alone: a task never restarts your project.
     */
    private Optional<String> waitForNeeds(ProjectSpec project, TaskSpec task, Run run) throws InterruptedException {
        for (String service : task.needs()) {
            if (!ready(project, service)) {
                add(run, "devdesk: starting " + service + ", which " + task.name() + " needs");
                EngineSession.Answer started = session.serviceAction(service, "start");
                if (started.status() >= 300) {
                    return Optional.of(service + " could not start: " + ApiJson.write(started.body()));
                }
            }
        }
        long deadline = System.nanoTime() + READY_LIMIT.toNanos();
        for (String service : task.needs()) {
            while (!ready(project, service)) {
                if (run.cancelled) {
                    return Optional.of("stopped by you");
                }
                String label = ApiJson.state(state(service)).get("label").toString();
                if (List.of("failed", "crashed", "blocked").contains(label)) {
                    return Optional.of(service + " is " + label + ": see Diagnosis for why");
                }
                if (System.nanoTime() > deadline) {
                    return Optional.of(
                            service + " wasn't ready after " + READY_LIMIT.toMinutes() + " minutes (" + label + ")");
                }
                Thread.sleep(500);
            }
            add(run, "devdesk: " + service + " is ready");
        }
        return Optional.empty();
    }

    /** Healthy, or running when it has no health check, or a one-time service that finished OK. */
    private boolean ready(ProjectSpec project, String service) {
        String label = ApiJson.state(state(service)).get("label").toString();
        boolean noCheck = project.services().get(service).health() instanceof HealthCheckSpec.ProcessAlive;
        return label.equals("healthy") || label.equals("completed") || (noCheck && label.equals("running"));
    }

    private ServiceState state(String service) {
        return session.current()
                .map(c -> c.states().getOrDefault(service, new ServiceState.Stopped()))
                .orElse(new ServiceState.Stopped());
    }

    private Optional<ProjectSpec> project() {
        return session.current().map(EngineSession.Current::project);
    }

    private static boolean isActive(Run run) {
        return run.status.equals("waiting") || run.status.equals("running");
    }

    private void add(Run run, String line) {
        synchronized (run.output) {
            run.output.addLast(line);
            while (run.output.size() > OUTPUT_LINES) {
                run.output.removeFirst();
            }
        }
    }

    private static String lastLine(Run run) {
        synchronized (run.output) {
            return run.output.isEmpty() ? "" : ": " + run.output.peekLast();
        }
    }

    private Map<String, Object> summary(Run run, boolean withOutput) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.id);
        m.put("task", run.task);
        m.put("status", run.status);
        m.put("detail", run.detail);
        m.put("started", run.started.toString());
        m.put("ended", run.ended == null ? null : run.ended.toString());
        m.put(
                "seconds",
                Duration.between(run.started, run.ended == null ? clock.instant() : run.ended)
                                .toMillis()
                        / 1000.0);
        m.put("exitCode", run.exitCode);
        // How many tests passed and failed, read from the output (also useful for a task that runs tests).
        List<String> output;
        synchronized (run.output) {
            output = List.copyOf(run.output);
        }
        m.put("report", TestReport.parse(output).orElse(null));
        if (withOutput) {
            synchronized (run.output) {
                m.put("output", List.copyOf(run.output));
            }
        }
        return m;
    }

    // ---- history on disk: one JSON line per finished run, per project ----

    private Deque<Run> history(ProjectSpec project, String task) {
        Map<String, Deque<Run>> byTask = runs.computeIfAbsent(project.key(), k -> load(project));
        return byTask.computeIfAbsent(task, t -> new ArrayDeque<>());
    }

    private Path file(ProjectSpec project) {
        return historyFolder.resolve(project.key() + ".jsonl");
    }

    private void save(ProjectSpec project, Run run) {
        try {
            Files.createDirectories(historyFolder);
            Map<String, Object> line = summary(run, true);
            Files.writeString(
                    file(project),
                    ApiJson.write(line) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            add(run, "devdesk: could not save this run in the history: " + e.getMessage());
        }
    }

    /** Reads the saved runs back; a broken line is skipped (the file is only a convenience). */
    private Map<String, Deque<Run>> load(ProjectSpec project) {
        Map<String, Deque<Run>> byTask = new ConcurrentHashMap<>();
        Path file = file(project);
        if (!Files.isRegularFile(file)) {
            return byTask;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    Map<?, ?> m = ApiJson.MAPPER.readValue(line, Map.class);
                    Run run = new Run(
                            String.valueOf(m.get("id")),
                            String.valueOf(m.get("task")),
                            Instant.parse(String.valueOf(m.get("started"))));
                    run.ended = m.get("ended") instanceof String e ? Instant.parse(e) : run.started;
                    run.status = String.valueOf(m.get("status"));
                    run.detail = String.valueOf(m.get("detail"));
                    run.exitCode = m.get("exitCode") instanceof Number n ? n.intValue() : null;
                    if (m.get("output") instanceof List<?> out) {
                        out.forEach(o -> run.output.addLast(String.valueOf(o)));
                    }
                    Deque<Run> history = byTask.computeIfAbsent(run.task, t -> new ArrayDeque<>());
                    history.addFirst(run);
                    while (history.size() > RUNS_KEPT) {
                        history.removeLast();
                    }
                } catch (RuntimeException e) {
                    // a half-written or old-format line: skip it
                }
            }
        } catch (IOException e) {
            // unreadable history: start empty
        }
        return byTask;
    }
}
