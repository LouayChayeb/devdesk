package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.setup.FreshSetup;
import io.github.louch.devdesk.core.state.ServiceState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs a {@link FreshSetup} plan, one step after the other, on a background thread. The app polls {@link #status()}.
 * The first failing step stops the run: later steps usually need it (no packages, no migration).
 */
final class FreshSetupRunner {

    /** Installs and image downloads can be slow on a fresh machine; longer than this, something is stuck. */
    private static final Duration STEP_LIMIT = Duration.ofMinutes(20);

    private static final Duration JOB_LIMIT = Duration.ofMinutes(5);
    private static final int OUTPUT_LINES = 200;

    /** One step's progress. {@code status}: waiting, running, ok, failed, skipped, cancelled. */
    private static final class Progress {
        final FreshSetup.Step step;
        volatile String status = "waiting";
        volatile String detail = "";
        final Deque<String> output = new ArrayDeque<>();

        Progress(FreshSetup.Step step) {
            this.step = step;
        }
    }

    private final EngineSession session;
    private final Map<String, String> environment;

    // ---- guarded by "this" ----
    private List<Progress> steps = List.of();
    private Path folder;
    private boolean running;
    private Optional<Process> current = Optional.empty();
    private boolean cancelled;

    FreshSetupRunner(EngineSession session, Map<String, String> environment) {
        this.session = session;
        this.environment = environment;
    }

    /** Starts the run, unless one is already going. {@code answers} fills the env files' questions. */
    synchronized Optional<String> start(Path root, List<FreshSetup.Step> chosen, Map<String, String> answers) {
        if (running) {
            return Optional.of("a setup is already running");
        }
        folder = root;
        steps = chosen.stream().map(Progress::new).toList();
        running = true;
        cancelled = false;
        List<Progress> run = steps;
        Thread.ofVirtual().name("fresh-setup").start(() -> runAll(run, answers));
        return Optional.empty();
    }

    /** Stops the step that runs (and everything it started), and skips the rest. */
    synchronized void cancel() {
        cancelled = true;
        current.ifPresent(p -> {
            // npm and pip start child processes: stop them too, children first.
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        });
    }

    synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", folder == null ? "" : folder.toString());
        m.put("running", running);
        m.put(
                "steps",
                steps.stream()
                        .map(p -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("id", p.step.id());
                            s.put("title", p.step.title());
                            s.put("status", p.status);
                            s.put("detail", p.detail);
                            synchronized (p.output) {
                                s.put("output", List.copyOf(p.output));
                            }
                            return s;
                        })
                        .toList());
        return m;
    }

    private void runAll(List<Progress> run, Map<String, String> answers) {
        try {
            for (Progress p : run) {
                if (isCancelled()) {
                    p.status = "cancelled";
                    continue;
                }
                p.status = "running";
                boolean ok;
                try {
                    ok = switch (p.step) {
                        case FreshSetup.Step.CreateEnv e -> createEnv(e, answers, p);
                        case FreshSetup.Step.Run r -> command(r, p);
                        case FreshSetup.Step.Job j -> job(j, p);
                    };
                } catch (IOException e) {
                    p.detail = e.getMessage();
                    ok = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.detail = "interrupted";
                    ok = false;
                }
                p.status = isCancelled() ? "cancelled" : ok ? "ok" : "failed";
                if (!ok) {
                    run.stream().filter(x -> x.status.equals("waiting")).forEach(x -> x.status = "skipped");
                    break;
                }
            }
        } finally {
            synchronized (this) {
                running = false;
                current = Optional.empty();
            }
        }
    }

    private boolean createEnv(FreshSetup.Step.CreateEnv e, Map<String, String> answers, Progress p) throws IOException {
        Path file = folder.resolve(e.file());
        if (Files.exists(file)) {
            p.detail = e.file() + " exists already: left as it is";
            return true;
        }
        Map<String, String> values = new LinkedHashMap<>();
        e.questions().forEach(q -> values.put(q.key(), answers.getOrDefault(q.key(), "")));
        // Created only readable by you (rw-------): it holds passwords.
        Files.writeString(
                file,
                FreshSetup.envContent(e.example(), values),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        long empty = values.values().stream().filter(String::isBlank).count();
        p.detail = "wrote " + e.file()
                + (empty > 0
                        ? " (" + empty + " value" + (empty == 1 ? "" : "s") + " left empty: fill them in later)"
                        : "");
        return true;
    }

    private boolean command(FreshSetup.Step.Run r, Progress p) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", r.command())
                .directory(folder.resolve(r.directory()).toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().put("CI", "true"); // installers then print plain lines instead of animated bars
        Process process = builder.start();
        synchronized (this) {
            current = Optional.of(process);
        }
        process.getOutputStream().close(); // nobody answers prompts: a tool asking one fails instead of hanging
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    add(p, line);
                }
            } catch (IOException e) {
                // the process ended or was stopped: its output simply ends
            }
        });
        boolean finished = process.waitFor(STEP_LIMIT.toMinutes(), TimeUnit.MINUTES);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            p.detail = "stopped after " + STEP_LIMIT.toMinutes() + " minutes without finishing";
            return false;
        }
        reader.join(2000);
        int code = process.exitValue();
        p.detail = code == 0 ? "done" : "exited with code " + code + lastLine(p);
        return code == 0;
    }

    /** Opens the project, starts the job (and what it needs), waits until it ended, then stops everything again. */
    private boolean job(FreshSetup.Step.Job j, Progress p) throws InterruptedException {
        if (session.running()) {
            p.detail = "another project is running: stop it, then run the setup again";
            return false;
        }
        EngineSession.Answer opened = session.open(folder);
        if (opened.status() != 200) {
            p.detail = "the project could not be opened: " + ApiJson.write(opened.body());
            return false;
        }
        session.trust(); // the user saw this job in the setup's list of commands before clicking "Run setup"
        EngineSession.Answer started = session.serviceAction(j.service(), "start");
        if (started.status() >= 300) {
            p.detail = ApiJson.write(started.body());
            return false;
        }
        long deadline = System.nanoTime() + JOB_LIMIT.toNanos();
        String outcome = "";
        while (System.nanoTime() < deadline && !isCancelled()) {
            ServiceState state = session.current()
                    .map(c -> c.states().getOrDefault(j.service(), new ServiceState.Stopped()))
                    .orElse(new ServiceState.Stopped());
            String label = ApiJson.state(state).get("label").toString();
            if (List.of("completed", "failed", "crashed", "blocked").contains(label)) {
                outcome = label;
                break;
            }
            Thread.sleep(500);
        }
        session.recentOutput(j.service(), OUTPUT_LINES).forEach(line -> add(p, line));
        session.down();
        for (int i = 0; i < 120 && session.running(); i++) {
            Thread.sleep(250);
        }
        p.detail = switch (outcome) {
            case "completed" -> "finished OK";
            case "" -> isCancelled() ? "cancelled" : "still running after " + JOB_LIMIT.toMinutes() + " minutes";
            default -> j.service() + " " + outcome + lastLine(p) + " (see Diagnosis for why)";
        };
        return outcome.equals("completed");
    }

    private void add(Progress p, String line) {
        synchronized (p.output) {
            p.output.addLast(line);
            while (p.output.size() > OUTPUT_LINES) {
                p.output.removeFirst();
            }
        }
    }

    private static String lastLine(Progress p) {
        synchronized (p.output) {
            return p.output.isEmpty() ? "" : ": " + p.output.peekLast();
        }
    }

    private synchronized boolean isCancelled() {
        return cancelled;
    }
}
