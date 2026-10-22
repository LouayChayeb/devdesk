package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.remote.RemoteTarget;
import io.github.louch.devdesk.core.remote.SshCommands;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project on another Linux machine over SSH. "Deploy": copy it there and start it under DevDesk's engine in
 * server mode, then watch it from here through an SSH tunnel. "Dev box": the same, plus each service's port forwarded
 * to this machine and the files copied again on every save, so it feels local while a stronger machine runs it.
 * Every step is a plain ssh or rsync command, shown with its output.
 */
final class RemoteWorkbench {

    /** One step of a run, as the app shows it. {@code status}: waiting, running, ok, failed. */
    private static final class Step {
        final String name;
        volatile String status = "waiting";
        final List<String> output = new ArrayList<>();
        volatile String command = "";

        Step(String name) {
            this.name = name;
        }
    }

    private static final Pattern SERVER_JSON =
            Pattern.compile("\\{\"port\":(\\d+),\"token\":\"([0-9a-f]+)\",\"pid\":(\\d+)}");

    private final EngineSession session;
    private final Path targetsFolder;

    private volatile RemoteTarget target;
    private volatile String mode = "";
    private final List<Step> steps = new ArrayList<>();
    private volatile Process tunnel;
    private volatile int localPort;
    private volatile String remoteToken;
    private final List<Map<String, Object>> forwards = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean syncing;

    RemoteWorkbench(EngineSession session, Path targetsFolder) {
        this.session = session;
        this.targetsFolder = targetsFolder;
    }

    synchronized EngineSession.Answer status() {
        Optional<ProjectSpec> p = project();
        if (p.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        if (target == null) {
            target = load(p.get()).orElse(null);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(
                "target",
                target == null
                        ? null
                        : Map.of(
                                "user",
                                target.user(),
                                "host",
                                target.host(),
                                "port",
                                target.port(),
                                "folder",
                                target.folder(),
                                "identityFile",
                                target.identityFile()));
        m.put("mode", mode);
        m.put("running", running);
        m.put("connected", tunnel != null && tunnel.isAlive());
        m.put("syncing", syncing);
        m.put("engine", tunnel != null && tunnel.isAlive() ? Map.of("port", localPort, "token", remoteToken) : null);
        m.put("forwards", List.copyOf(forwards));
        m.put(
                "steps",
                steps.stream()
                        .map(s -> {
                            Map<String, Object> x = new LinkedHashMap<>();
                            x.put("name", s.name);
                            x.put("status", s.status);
                            x.put("command", s.command);
                            synchronized (s.output) {
                                x.put(
                                        "output",
                                        List.copyOf(
                                                s.output.subList(Math.max(0, s.output.size() - 40), s.output.size())));
                            }
                            return x;
                        })
                        .toList());
        return new EngineSession.Answer(200, m);
    }

    /** {user, host, port, folder, identityFile?}: where the project goes. Kept per project. */
    synchronized EngineSession.Answer setTarget(Map<?, ?> body) {
        Optional<ProjectSpec> p = project();
        if (p.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        RemoteTarget t = new RemoteTarget(
                String.valueOf(body.get("user")).strip(),
                String.valueOf(body.get("host")).strip(),
                body.get("port") instanceof Number n ? n.intValue() : 22,
                body.get("folder") instanceof String f && !f.isBlank()
                        ? f.strip()
                        : "~/apps/" + p.get().name(),
                body.get("identityFile") instanceof String k ? k.strip() : "",
                knownHosts());
        Optional<String> problem = t.problem();
        if (problem.isPresent()) {
            return new EngineSession.Answer(400, ApiJson.error(problem.get()));
        }
        target = t;
        save(p.get(), t);
        return status();
    }

    /** {mode: deploy | devbox}: runs every step in the background; the app follows with {@link #status}. */
    synchronized EngineSession.Answer run(String requested) {
        Optional<ProjectSpec> p = project();
        if (p.isEmpty() || target == null) {
            return new EngineSession.Answer(400, ApiJson.error("set the remote machine first"));
        }
        if (running) {
            return new EngineSession.Answer(409, ApiJson.error("it's already running: wait for it, or stop it"));
        }
        if (!session.trusted()) {
            return new EngineSession.Answer(
                    403,
                    ApiJson.error("trust the project here first: the other machine will" + " run its commands too"));
        }
        closeTunnel();
        mode = requested.equals("devbox") ? "devbox" : "deploy";
        steps.clear();
        for (String name : List.of(
                "Check the machine",
                "Copy the project",
                "Copy DevDesk's engine",
                "Stop an older engine there",
                "Start the project there",
                "Connect")) {
            steps.add(new Step(name));
        }
        running = true;
        RemoteTarget t = target;
        ProjectSpec spec = p.get();
        Thread.ofVirtual().name("remote").start(() -> {
            try {
                steps(t, spec);
            } finally {
                running = false;
            }
        });
        return status();
    }

    /** {stopRemote}: closes the tunnel and the syncing; with stopRemote, also stops the project over there. */
    synchronized EngineSession.Answer stop(boolean stopRemote) {
        syncing = false;
        closeTunnel();
        if (stopRemote && target != null) {
            Step s = new Step("Stop the project there");
            steps.add(s);
            run(s, SshCommands.ssh(target, SshCommands.stopEngine()));
        }
        return status();
    }

    /** The app closes: the tunnel and the syncing end; what runs over there keeps running. */
    void close() {
        syncing = false;
        closeTunnel();
    }

    // ---- the steps ----

    private void steps(RemoteTarget t, ProjectSpec p) {
        Step check = steps.get(0);
        if (!run(check, SshCommands.ssh(t, SshCommands.check()))) {
            check.output.add("SSH didn't work. DevDesk logs in with your SSH keys only (no password prompts): try"
                    + " ssh -p " + t.port() + " " + t.login() + " in a terminal first.");
            return;
        }
        String found = String.join("\n", check.output);
        if (found.contains("java: missing")) {
            fail(
                    check,
                    "Java is missing there. DevDesk's engine needs Java 25: sudo apt install openjdk-25-jre-headless");
            return;
        }
        boolean containers = p.services().values().stream().anyMatch(s -> s.workload() instanceof Workload.Container);
        if (containers && found.contains("docker: missing")) {
            fail(
                    check,
                    "The project has containers, and Docker is missing there: https://docs.docker.com/engine/install/");
            return;
        }
        if (found.contains("rsync: missing")) {
            fail(check, "rsync is missing there (it copies only what changed): sudo apt install rsync");
            return;
        }
        if (!run(steps.get(1), SshCommands.ssh(t, "mkdir -p " + t.folder()))
                || !run(steps.get(1), SshCommands.syncProject(t, p.root().toString()))) {
            return;
        }
        Optional<Path> engine = engineFolder();
        if (engine.isEmpty()) {
            fail(
                    steps.get(2),
                    "DevDesk's engine runs from a build folder here, not an installed one: build it with"
                            + " ./gradlew :daemon:installDist and start the app from that");
            return;
        }
        // rsync makes the last folder of a path, not its parents: ~/.devdesk may not exist yet.
        if (!run(steps.get(2), SshCommands.ssh(t, "mkdir -p " + SshCommands.HOME + "/engine"))
                || !run(steps.get(2), SshCommands.syncEngine(t, engine.get().toString()))) {
            return;
        }
        run(steps.get(3), SshCommands.ssh(t, SshCommands.stopEngine()));
        Step start = steps.get(4);
        if (!run(start, SshCommands.ssh(t, SshCommands.startEngine(t, true)))) {
            return;
        }
        Matcher m;
        synchronized (start.output) {
            m = SERVER_JSON.matcher(String.join("", start.output));
        }
        if (!m.find()) {
            fail(
                    start,
                    "The engine there didn't say where it listens. Its log: ssh " + t.login() + " cat "
                            + SshCommands.HOME + "/server.log");
            return;
        }
        connect(t, p, Integer.parseInt(m.group(1)), m.group(2));
    }

    private void connect(RemoteTarget t, ProjectSpec p, int remoteEnginePort, String token) {
        Step step = steps.get(5);
        step.status = "running";
        List<int[]> pairs = new ArrayList<>();
        localPort = freePort();
        remoteToken = token;
        pairs.add(new int[] {localPort, remoteEnginePort});
        forwards.clear();
        if (mode.equals("devbox")) {
            // Each service's port on this machine reaches the service over there: localhost:3000 just works.
            for (ServiceSpec s : p.services().values()) {
                for (int port : s.ports()) {
                    if (portFree(port)) {
                        pairs.add(new int[] {port, port});
                        forwards.add(Map.of("service", s.name(), "port", port));
                    } else {
                        step.output.add(s.name() + ": port " + port + " is taken on this machine, not forwarded");
                    }
                }
            }
        }
        List<String> argv = SshCommands.tunnel(t, pairs);
        step.command = String.join(" ", argv);
        try {
            tunnel = new ProcessBuilder(argv).redirectErrorStream(true).start();
            if (tunnel.waitFor(2, TimeUnit.SECONDS)) { // it ended at once: a forward failed
                fail(step, new String(tunnel.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
                return;
            }
        } catch (IOException e) {
            fail(step, "couldn't start ssh: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        step.output.add("The engine there is reachable here on port " + localPort + ".");
        step.status = "ok";
        if (mode.equals("devbox")) {
            syncing = true;
            Thread.ofVirtual().name("devbox-sync").start(() -> syncLoop(t, p));
        }
    }

    /** Dev box: copies what changed every 2 s (rsync sends only the differences), so saving here updates there. */
    private void syncLoop(RemoteTarget t, ProjectSpec p) {
        while (syncing && tunnel != null && tunnel.isAlive()) {
            try {
                Process r = new ProcessBuilder(
                                SshCommands.syncProject(t, p.root().toString()))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                r.waitFor(60, TimeUnit.SECONDS);
                Thread.sleep(2000);
            } catch (IOException e) {
                syncing = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        syncing = false;
    }

    /** Runs one command for a step, keeping its output. Returns whether it worked (exit code 0). */
    private boolean run(Step step, List<String> argv) {
        step.status = "running";
        step.command = String.join(" ", argv);
        try {
            Process proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
            proc.getOutputStream().close(); // nothing types into it: a prompt fails instead of hanging
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (step.output) {
                        step.output.add(line);
                    }
                }
            }
            if (!proc.waitFor(10, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                fail(step, "it took longer than 10 minutes");
                return false;
            }
            if (proc.exitValue() != 0) {
                fail(step, "exited with code " + proc.exitValue());
                return false;
            }
            step.status = "ok";
            return true;
        } catch (IOException e) {
            fail(step, "couldn't run " + argv.getFirst() + " (is it installed here?): " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void fail(Step step, String why) {
        synchronized (step.output) {
            step.output.add(why);
        }
        step.status = "failed";
    }

    private void closeTunnel() {
        Process t = tunnel;
        tunnel = null;
        forwards.clear();
        if (t != null) {
            t.destroy(); // ssh closes its forwards and the connection
        }
    }

    /** The installed engine folder (…/devdeskd, with bin/ and lib/), found from where this code runs. */
    private static Optional<Path> engineFolder() {
        try {
            Path jar = Path.of(Main.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path home = jar.getParent() == null ? null : jar.getParent().getParent();
            return home != null && Files.isRegularFile(home.resolve("bin/devdeskd"))
                    ? Optional.of(home)
                    : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port on this machine", e);
        }
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return s.isBound();
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<ProjectSpec> project() {
        return session.current().map(EngineSession.Current::project);
    }

    /** DevDesk's own list of SSH host keys it has accepted (never the user's ~/.ssh files). */
    private String knownHosts() {
        try {
            Files.createDirectories(targetsFolder);
        } catch (IOException e) {
            // ssh then says it couldn't write the file, in the step's output
        }
        return targetsFolder.resolve("known_hosts").toString();
    }

    private Path file(ProjectSpec p) {
        return targetsFolder.resolve(p.key() + ".json");
    }

    private void save(ProjectSpec p, RemoteTarget t) {
        try {
            Files.createDirectories(targetsFolder);
            Files.writeString(
                    file(p),
                    ApiJson.write(Map.of("user", t.user(), "host", t.host(), "port", t.port(), "folder", t.folder())));
        } catch (IOException e) {
            // kept for this session only
        }
    }

    private Optional<RemoteTarget> load(ProjectSpec p) {
        try {
            Map<?, ?> m = ApiJson.MAPPER.readValue(Files.readString(file(p)), Map.class);
            return Optional.of(new RemoteTarget(
                    String.valueOf(m.get("user")),
                    String.valueOf(m.get("host")),
                    ((Number) m.get("port")).intValue(),
                    String.valueOf(m.get("folder")),
                    m.get("identityFile") instanceof String k ? k : "",
                    knownHosts()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
