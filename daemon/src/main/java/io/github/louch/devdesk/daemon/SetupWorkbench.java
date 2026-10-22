package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.setup.CloudNeeds;
import io.github.louch.devdesk.core.setup.DatabaseTemplates;
import io.github.louch.devdesk.core.setup.EnvReport;
import io.github.louch.devdesk.core.setup.ExistingSetup;
import io.github.louch.devdesk.core.setup.FolderScan;
import io.github.louch.devdesk.core.setup.FreshSetup;
import io.github.louch.devdesk.core.setup.SetupDraft;
import io.github.louch.devdesk.core.setup.SetupYaml;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.project.ProjectLoader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The "Add project" wizard's engine side: scans a folder, adds databases, checks the draft with DevDesk's own parser,
 * test-runs it without saving, and finally writes devdesk.yaml. The app only shows what this class answers.
 */
final class SetupWorkbench {

    /** Bigger files aren't build files: a 5 MB "package.json" is someone's data. */
    private static final long MAX_FILE = 1024 * 1024;

    private static final String DRAFT_SHAPE =
            "send {\"path\": \"/folder\", \"draft\": {\"name\", \"services\": [...]}}";

    private final EngineSession session;
    private final Supplier<DockerConnection.Result> docker;
    private final Map<String, String> environment;
    private final RandomGenerator random;
    private final FreshSetupRunner runner;

    SetupWorkbench(
            EngineSession session,
            Supplier<DockerConnection.Result> docker,
            Map<String, String> environment,
            RandomGenerator random) {
        this.session = session;
        this.docker = docker;
        this.environment = environment;
        this.random = random;
        this.runner = new FreshSetupRunner(session, environment);
    }

    /** Step 1: an existing devdesk.yaml as cards ("Edit setup"), or a scan of the folder. */
    EngineSession.Answer scan(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        if (folder.isEmpty()) {
            return new EngineSession.Answer(
                    404, ApiJson.error("There is no folder at " + body.get("path") + ". Check the path."));
        }
        Path root = folder.get();
        List<FolderScan.RunningContainer> running = new ArrayList<>();
        List<String> found = new ArrayList<>();
        switch (docker.get()) {
            case DockerConnection.Result.Connected c ->
                running.addAll(c.runtime().running());
            case DockerConnection.Result.Unavailable u -> found.add("Docker: not reachable (" + u.reason() + ")");
        }
        Path existing = root.resolve(ConfigParser.FILE_NAME);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("path", root.toString());
        if (Files.isRegularFile(existing)) {
            try {
                SetupDraft draft = ExistingSetup.read(
                        Files.readString(existing), root.getFileName().toString());
                found.addFirst(ConfigParser.FILE_NAME + ": " + draft.services().size() + " services (editing it)");
                answer.put("mode", "edit");
                answer.put("draft", SetupJson.draft(draft));
                answer.put("found", found);
                answer.put("suggestions", List.of());
                answer.put("databaseKinds", DatabaseTemplates.KINDS);
                answer.put("cloud", cloud(root, found));
                return new EngineSession.Answer(200, answer);
            } catch (IOException | ConfigException e) {
                found.add(ConfigParser.FILE_NAME + " could not be read (" + e.getMessage()
                        + "), so the folder was scanned");
            }
        }
        FolderScan.Result scan = FolderScan.scan(folder(root), root.toString(), running);
        found.addAll(0, scan.found());
        answer.put("mode", Files.isRegularFile(existing) ? "edit" : "new");
        answer.put("draft", SetupJson.draft(scan.draft()));
        answer.put("found", found);
        answer.put(
                "suggestions",
                scan.suggestions().stream().map(SetupJson::service).toList());
        answer.put("databaseKinds", DatabaseTemplates.KINDS);
        answer.put("cloud", cloud(root, found));
        return new EngineSession.Answer(200, answer);
    }

    /**
     * Whether the code uses AWS (S3, SQS...): then the wizard suggests a local stand-in, like Mailpit for e-mail. Looks
     * at the project folder and its first-level folders (where apps usually are).
     */
    private static Map<String, Object> cloud(Path root, List<String> found) {
        List<String> folders = new ArrayList<>(List.of(""));
        try (var list = Files.list(root)) {
            list.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(n -> !n.startsWith(".")
                            && !Set.of("node_modules", "build", "target", "dist", "venv")
                                    .contains(n))
                    .sorted()
                    .forEach(folders::add);
        } catch (IOException e) {
            // the project folder alone, then
        }
        CloudNeeds.Result r = CloudNeeds.scan(folders, name -> {
            try {
                Path f = root.resolve(name);
                return Files.isRegularFile(f) && Files.size(f) < 2_000_000
                        ? Optional.of(Files.readString(f))
                        : Optional.empty();
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        });
        r.suggestion()
                .ifPresent(s -> found.add(
                        "Uses AWS (" + String.join(", ", r.services()) + ") in " + String.join(", ", r.foundIn())
                                + ": add a local stand-in so it works offline: Add database → " + s));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("services", List.copyOf(r.services()));
        m.put("foundIn", r.foundIn());
        m.put("suggestion", r.suggestion().orElse(null));
        return m;
    }

    /** Step 2's "Add database": a ready card with a free port and a fresh password. */
    EngineSession.Answer addDatabase(Map<?, ?> body) {
        Optional<SetupDraft> draft = SetupJson.readDraft(body.get("draft"));
        if (draft.isEmpty() || !(body.get("kind") instanceof String kind)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"kind\": \"postgres\", \"draft\": {...}}"));
        }
        Set<String> names = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        draft.get().services().forEach(s -> {
            names.add(s.name());
            ports.addAll(s.hostPorts());
        });
        return DatabaseTemplates.create(kind, draft.get().name(), names, ports, SetupWorkbench::portFree, random)
                .map(card -> new EngineSession.Answer(200, SetupJson.service(card)))
                .orElse(new EngineSession.Answer(
                        400, ApiJson.error("unknown kind \"" + kind + "\": use one of " + DatabaseTemplates.KINDS)));
    }

    /**
     * Steps 4 and 6: the devdesk.yaml the draft becomes, checked by the same parser that opens projects, plus the
     * settings report and the commands it would run (shown before the test run, like the trust prompt).
     */
    EngineSession.Answer preview(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        Optional<SetupDraft> draft = SetupJson.readDraft(body.get("draft"));
        if (folder.isEmpty() || draft.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(DRAFT_SHAPE));
        }
        Path root = folder.get();
        String yaml = SetupYaml.write(draft.get());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("yaml", yaml);
        switch (ProjectLoader.loadText(root, yaml, environment)) {
            case ProjectLoader.Result.Loaded l -> {
                answer.put("problems", List.of());
                answer.put("commands", ProjectLoader.commands(l.project()));
            }
            case ProjectLoader.Result.Invalid i -> {
                answer.put(
                        "problems", i.problems().stream().map(ApiJson::problem).toList());
                answer.put("commands", List.of());
            }
            case ProjectLoader.Result.Missing m -> {
                answer.put("problems", List.of(ApiJson.error(m.message())));
                answer.put("commands", List.of());
            }
        }
        answer.put("env", SetupJson.envReport(EnvReport.of(draft.get(), f -> read(root, f), environment.keySet())));
        answer.put("exists", Files.isRegularFile(root.resolve(ConfigParser.FILE_NAME)));
        return new EngineSession.Answer(200, answer);
    }

    /** The doctor on the draft, before anything is saved: the scan and the test run show what to fix first. */
    EngineSession.Answer doctor(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        Optional<SetupDraft> draft = SetupJson.readDraft(body.get("draft"));
        if (folder.isEmpty() || draft.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(DRAFT_SHAPE));
        }
        return switch (loadForDoctor(folder.get(), draft.get())) {
            case ProjectLoader.Result.Loaded l -> new EngineSession.Answer(200, session.doctor(l.project(), Map.of()));
            // Mistakes in the draft are shown by the preview; the doctor needs a valid setup to look at.
            case ProjectLoader.Result.Invalid i ->
                new EngineSession.Answer(
                        200,
                        Map.of(
                                "problems",
                                List.of(),
                                "passed",
                                List.of(),
                                "skipped",
                                "fix the setup's mistakes first"));
            case ProjectLoader.Result.Missing m -> new EngineSession.Answer(404, ApiJson.error(m.message()));
        };
    }

    /**
     * The draft as a project, for the doctor. On a fresh clone .env doesn't exist yet, so devdesk.yaml can't load:
     * then it's read without the missing env files and with placeholders for their variables. The doctor looks at
     * tools and folders, not at those values.
     */
    private ProjectLoader.Result loadForDoctor(Path root, SetupDraft draft) {
        ProjectLoader.Result result = ProjectLoader.loadText(root, SetupYaml.write(draft), environment);
        if (!(result instanceof ProjectLoader.Result.Invalid)) {
            return result;
        }
        SetupDraft present = new SetupDraft(
                draft.name(),
                draft.envFiles().stream()
                        .filter(f -> Files.isRegularFile(root.resolve(f)))
                        .toList(),
                draft.services(),
                draft.notes());
        Map<String, String> placeholders = new HashMap<>(environment);
        Matcher m = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)").matcher(SetupYaml.write(draft));
        while (m.find()) {
            placeholders.putIfAbsent(m.group(1), "placeholder");
        }
        return ProjectLoader.loadText(root, SetupYaml.write(present), placeholders);
    }

    // ---- "Set up" for a fresh clone that has a devdesk.yaml ----

    /** The plan: every step and command, shown before anything runs. Rebuilt here for every run, never sent back. */
    EngineSession.Answer freshPlan(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        if (folder.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("There is no folder at " + body.get("path") + "."));
        }
        Optional<SetupDraft> draft = existing(folder.get());
        if (draft.isEmpty()) {
            return new EngineSession.Answer(
                    404,
                    ApiJson.error("no readable devdesk.yaml in " + folder.get()
                            + ": use \"Add project folder\" to create one"));
        }
        FreshSetup.Plan plan = plan(folder.get(), draft.get());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("path", folder.get().toString());
        answer.put("name", draft.get().name());
        answer.put("steps", plan.steps().stream().map(SetupJson::step).toList());
        answer.put("notes", plan.notes());
        answer.put("draft", SetupJson.draft(draft.get())); // for the doctor, which runs first
        answer.put("running", runner.status().get("running"));
        return new EngineSession.Answer(200, answer);
    }

    /** Runs the chosen steps of a fresh plan. The app only picks which ones; their commands come from the plan. */
    EngineSession.Answer freshRun(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        Optional<SetupDraft> draft = folder.flatMap(SetupWorkbench::existing);
        if (folder.isEmpty() || draft.isEmpty() || !(body.get("steps") instanceof List<?> ids)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"path\", \"steps\": [ids], \"answers\": {}}"));
        }
        List<FreshSetup.Step> chosen = plan(folder.get(), draft.get()).steps().stream()
                .filter(s -> ids.contains(s.id()))
                .toList();
        Map<String, String> answers = new LinkedHashMap<>();
        if (body.get("answers") instanceof Map<?, ?> a) {
            a.forEach((k, v) -> {
                if (k instanceof String key && v instanceof String value && !value.contains("\n")) {
                    answers.put(key, value);
                }
            });
        }
        return runner.start(folder.get(), chosen, answers)
                .map(problem -> new EngineSession.Answer(409, ApiJson.error(problem)))
                .orElse(new EngineSession.Answer(202, runner.status()));
    }

    EngineSession.Answer freshStatus() {
        return new EngineSession.Answer(200, runner.status());
    }

    EngineSession.Answer freshCancel() {
        runner.cancel();
        return new EngineSession.Answer(200, runner.status());
    }

    private static Optional<SetupDraft> existing(Path root) {
        try {
            Path file = root.resolve(ConfigParser.FILE_NAME);
            return Files.isRegularFile(file)
                    ? Optional.of(ExistingSetup.read(
                            Files.readString(file), root.getFileName().toString()))
                    : Optional.empty();
        } catch (IOException | ConfigException e) {
            return Optional.empty();
        }
    }

    private static FreshSetup.Plan plan(Path root, SetupDraft draft) {
        return FreshSetup.of(
                draft, f -> read(root, f), f -> Files.exists(root.resolve(f).normalize()));
    }

    /** Step 5: runs the draft without saving it. The app follows the services through the usual events. */
    EngineSession.Answer test(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        Optional<SetupDraft> draft = SetupJson.readDraft(body.get("draft"));
        if (folder.isEmpty() || draft.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(DRAFT_SHAPE));
        }
        EngineSession.Answer opened = session.openUnsaved(folder.get(), SetupYaml.write(draft.get()));
        return opened.status() == 200 ? session.up() : opened;
    }

    /** Step 6: writes devdesk.yaml (shared through git) and opens the project. */
    EngineSession.Answer write(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        Optional<SetupDraft> draft = SetupJson.readDraft(body.get("draft"));
        if (folder.isEmpty() || draft.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(DRAFT_SHAPE));
        }
        Path root = folder.get();
        Path file = root.resolve(ConfigParser.FILE_NAME);
        if (Files.isRegularFile(file) && !Boolean.TRUE.equals(body.get("replace"))) {
            return new EngineSession.Answer(
                    409,
                    ApiJson.error(file + " already exists. Use \"Edit setup\" to change it, or confirm replacing it"));
        }
        if (session.running()) {
            return new EngineSession.Answer(409, ApiJson.error("stop the test run first: services are still running"));
        }
        String yaml = SetupYaml.write(draft.get());
        if (ProjectLoader.loadText(root, yaml, environment) instanceof ProjectLoader.Result.Invalid i) {
            return new EngineSession.Answer(
                    400,
                    Map.of(
                            "error",
                            "the setup has mistakes",
                            "problems",
                            i.problems().stream().map(ApiJson::problem).toList()));
        }
        try {
            // Written next to it, then moved: a crash halfway never leaves a half-written devdesk.yaml.
            Path temp = Files.createTempFile(root, ".devdesk-", ".yaml");
            Files.writeString(temp, yaml);
            // Temp files are private (rw-------); this one is shared through git: keep the old file's permissions,
            // or the usual rw-r--r-- for a new one.
            Files.setPosixFilePermissions(
                    temp,
                    Files.isRegularFile(file)
                            ? Files.getPosixFilePermissions(file)
                            : PosixFilePermissions.fromString("rw-r--r--"));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return new EngineSession.Answer(500, ApiJson.error("could not write " + file + ": " + e.getMessage()));
        }
        return session.open(root);
    }

    /** Cancel after a test run: the unsaved project closes (a saved one stays open). */
    EngineSession.Answer discard() {
        return session.closeUnsaved();
    }

    /** Step 4's "Add": appends KEY=value to an env file (created if needed). Values never go into devdesk.yaml. */
    EngineSession.Answer setVariable(Map<?, ?> body) {
        Optional<Path> folder = folder(body.get("path"));
        if (folder.isEmpty()
                || !(body.get("file") instanceof String file)
                || !file.matches("\\.env[A-Za-z0-9._-]*")
                || !(body.get("key") instanceof String key)
                || !key.matches("[A-Za-z_][A-Za-z0-9_]*")
                || !(body.get("value") instanceof String value)
                || value.contains("\n")) {
            return new EngineSession.Answer(
                    400,
                    ApiJson.error("send {\"path\", \"file\": \".env\", \"key\": \"API_KEY\", \"value\": \"...\"}"));
        }
        Path target = folder.get().resolve(file);
        try {
            String before = Files.exists(target) ? Files.readString(target) : "";
            String line = (before.isEmpty() || before.endsWith("\n") ? "" : "\n") + key + "="
                    + (value.matches("[^\\s\"'#]*") ? value : "\"" + value.replace("\"", "\\\"") + "\"") + "\n";
            Files.writeString(
                    target, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            return new EngineSession.Answer(500, ApiJson.error("could not write " + target + ": " + e.getMessage()));
        }
        return new EngineSession.Answer(200, Map.of("file", file, "key", key));
    }

    /** The folder the app sent, if it exists. "~" is the user's home, like in a terminal. */
    private static Optional<Path> folder(Object path) {
        if (!(path instanceof String p) || p.isBlank()) {
            return Optional.empty();
        }
        Path folder = Path.of(p.strip().replaceFirst("^~", System.getProperty("user.home")))
                .toAbsolutePath()
                .normalize();
        return Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
    }

    /** The project folder for the scan. Reads stay inside it, and only small files are read. */
    private static FolderScan.Folder folder(Path root) {
        return new FolderScan.Folder() {
            @Override
            public String name() {
                return root.getFileName() == null
                        ? "project"
                        : root.getFileName().toString();
            }

            @Override
            public List<String> subfolders() {
                try (Stream<Path> list = Files.list(root)) {
                    return list.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .filter(n -> !n.startsWith("."))
                            .toList();
                } catch (IOException e) {
                    return List.of();
                }
            }

            @Override
            public List<String> files() {
                try (Stream<Path> list = Files.list(root)) {
                    return list.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .toList();
                } catch (IOException e) {
                    return List.of();
                }
            }

            @Override
            public Optional<String> read(String relativePath) {
                return SetupWorkbench.read(root, relativePath);
            }
        };
    }

    private static Optional<String> read(Path root, String relativePath) {
        Path file = root.resolve(relativePath).normalize();
        try {
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty(); // binary or unreadable: not a build file
        }
    }

    /**
     * Whether nothing listens on {@code port} yet. Binding on all addresses fails both for a program on 127.0.0.1 and
     * for Docker's proxy on 0.0.0.0.
     */
    static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
