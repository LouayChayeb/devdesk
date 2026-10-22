package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.branch.BranchStacks;
import io.github.louch.devdesk.core.changes.KnownGood;
import io.github.louch.devdesk.core.config.EnvFile;
import io.github.louch.devdesk.core.doctor.ToolNeeds;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.doctor.ToolVersions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * "It worked yesterday": each time the whole project is up and healthy, saves what it took to get there (see
 * {@link KnownGood}); when something breaks, compares today with that. One file per project in DevDesk's state folder.
 */
final class ChangeTracker {

    /** Files whose change means dependencies changed (looked for in the project folder and each service's folder). */
    private static final List<String> LOCK_FILES = List.of(
            "package-lock.json",
            "pnpm-lock.yaml",
            "yarn.lock",
            "poetry.lock",
            "Pipfile.lock",
            "uv.lock",
            "requirements.txt",
            "go.sum",
            "Cargo.lock",
            "Gemfile.lock",
            "composer.lock",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "gradle.lockfile",
            "Dockerfile");

    private static final Pattern ENV_FILE =
            Pattern.compile("\\.env(?![A-Za-z0-9._-]*\\.(example|sample|template|dist)$)[A-Za-z0-9._-]*");
    private static final Set<String> GOOD = Set.of("healthy", "running", "completed");

    private final EngineSession session;
    private final Supplier<DockerConnection.Result> docker;
    private final ToolVersions tools;
    private final Path folder;
    private final Clock clock;
    /** True once this clean start was saved: the next save waits until the project goes down or breaks first. */
    private volatile boolean savedThisRun;

    ChangeTracker(
            EngineSession session,
            Supplier<DockerConnection.Result> docker,
            ToolVersions tools,
            Path folder,
            Clock clock) {
        this.session = session;
        this.docker = docker;
        this.tools = tools;
        this.folder = folder;
        this.clock = clock;
    }

    void start() {
        Thread.ofVirtual().name("known-good").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                    look();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // a failed look (Docker gone for a moment) is tried again next time
                }
            }
        });
    }

    /** Saves the state when every service is up (healthy, running, or a finished one-off job). */
    private void look() throws InterruptedException {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty() || current.get().states().isEmpty()) {
            savedThisRun = false;
            return;
        }
        boolean allGood = current.get().states().values().stream()
                .map(ServiceState::label)
                .allMatch(GOOD::contains);
        if (!allGood) {
            savedThisRun = false;
            return;
        }
        if (!savedThisRun) {
            ProjectSpec p = current.get().project();
            save(p, take(p));
            savedThisRun = true;
        }
    }

    /** What changed since the project last started cleanly. */
    EngineSession.Answer changes() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        ProjectSpec p = current.get().project();
        Optional<KnownGood> then = load(p);
        if (then.isEmpty()) {
            return new EngineSession.Answer(
                    200,
                    Map.of(
                            "known",
                            false,
                            "message",
                            "DevDesk hasn't seen this project start cleanly yet. Once every service is up, it"
                                    + " remembers that state, and this compares with it."));
        }
        List<KnownGood.Change> changes = KnownGood.compare(then.get(), take(p));
        return new EngineSession.Answer(
                200,
                Map.of(
                        "known", true,
                        "since", then.get().at().toString(),
                        "changes",
                                changes.stream()
                                        .map(c -> Map.of("area", c.area(), "what", c.what(), "detail", c.detail()))
                                        .toList()));
    }

    // ---- taking the state ----

    private KnownGood take(ProjectSpec p) {
        Path root = p.root();
        Map<String, Map<String, String>> env = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path f : files.filter(
                            f -> ENV_FILE.matcher(f.getFileName().toString()).matches())
                    .sorted()
                    .toList()) {
                Map<String, String> keys = new LinkedHashMap<>();
                // Salted with the project and the key: a short secret can't be found by hashing guesses elsewhere.
                EnvFile.parse(read(f)).forEach((k, v) -> keys.put(k, hash(p.key() + "\u0000" + k + "\u0000" + v)));
                env.put(f.getFileName().toString(), keys);
            }
        } catch (IOException e) {
            // no env files to compare
        }
        Set<Path> dirs = new LinkedHashSet<>(List.of(root));
        p.services().values().stream().map(ServiceSpec::directory).forEach(dirs::add);
        Map<String, String> files = new LinkedHashMap<>();
        for (Path dir : dirs) {
            for (String name : LOCK_FILES) {
                Path f = dir.resolve(name);
                if (Files.isRegularFile(f)) {
                    files.put(root.relativize(f).toString(), hash(read(f)));
                }
            }
        }
        Set<String> images = new LinkedHashSet<>();
        p.services().values().forEach(s -> {
            if (s.workload() instanceof Workload.Container c) {
                images.add(c.image());
            }
        });
        Map<String, String> imageIds =
                images.isEmpty() || !(docker.get() instanceof DockerConnection.Result.Connected c)
                        ? Map.of()
                        : c.runtime().imageIds(images);
        Map<String, String> versions = new LinkedHashMap<>();
        ToolNeeds.of(p, f -> Files.isRegularFile(f) ? Optional.of(read(f)) : Optional.empty()).stream()
                .map(ToolNeeds.Need::tool)
                .distinct()
                .forEach(tool -> tools.of(tool).ifPresent(v -> versions.put(tool, v)));
        Optional<String> branch = git(root, "HEAD").flatMap(BranchStacks::branch);
        return new KnownGood(
                clock.instant(),
                branch,
                commit(root),
                read(root.resolve("devdesk.yaml")),
                env,
                files,
                imageIds,
                versions);
    }

    /** The commit checked out: HEAD names a branch whose file (or packed-refs line) holds it, or is the commit. */
    private static Optional<String> commit(Path root) {
        Optional<String> head = git(root, "HEAD").map(String::strip);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        if (!head.get().startsWith("ref: ")) {
            return head; // detached: HEAD is the commit itself
        }
        String ref = head.get().substring(5).strip();
        return git(root, ref)
                .map(String::strip)
                .or(() -> git(root, "packed-refs")
                        .flatMap(packed -> packed.lines()
                                .filter(l -> l.endsWith(" " + ref))
                                .map(l -> l.substring(0, l.indexOf(' ')))
                                .findFirst()));
    }

    /** A file of the git folder, also for a worktree (whose .git is a file naming the real folder). */
    private static Optional<String> git(Path root, String name) {
        Path dotGit = root.resolve(".git");
        Path dir = dotGit;
        if (Files.isRegularFile(dotGit)) {
            Optional<String> real = BranchStacks.gitDirOf(read(dotGit));
            if (real.isEmpty()) {
                return Optional.empty();
            }
            dir = root.resolve(real.get());
        }
        Path f = dir.resolve(name);
        return Files.isRegularFile(f) ? Optional.of(read(f)) : Optional.empty();
    }

    // ---- saving ----

    private Path file(ProjectSpec p) {
        return folder.resolve(p.key() + ".json");
    }

    private void save(ProjectSpec p, KnownGood k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", k.at().toString());
        m.put("branch", k.branch().orElse(null));
        m.put("commit", k.commit().orElse(null));
        m.put("devdeskYaml", k.devdeskYaml());
        m.put("envFiles", k.envFiles());
        m.put("files", k.files());
        m.put("images", k.images());
        m.put("tools", k.tools());
        try {
            Files.createDirectories(folder);
            // Written next to it, then moved: a crash mid-write never leaves half a file.
            Path tmp = folder.resolve(p.key() + ".json.tmp");
            Files.writeString(tmp, ApiJson.write(m), StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    file(p),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // not saved: the comparison then uses the previous clean start
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<KnownGood> load(ProjectSpec p) {
        try {
            Map<String, Object> m = ApiJson.MAPPER.readValue(Files.readString(file(p)), Map.class);
            return Optional.of(new KnownGood(
                    Instant.parse((String) m.get("at")),
                    Optional.ofNullable((String) m.get("branch")),
                    Optional.ofNullable((String) m.get("commit")),
                    String.valueOf(m.get("devdeskYaml")),
                    (Map<String, Map<String, String>>) m.get("envFiles"),
                    (Map<String, String>) m.get("files"),
                    (Map<String, String>) m.get("images"),
                    (Map<String, String>) m.get("tools")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // never saved, or unreadable
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** A value's fingerprint: equal values give equal hashes, and the value can't be read back from it. */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }
}
