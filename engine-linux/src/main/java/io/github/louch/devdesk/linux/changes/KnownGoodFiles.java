package io.github.louch.devdesk.linux.changes;

import io.github.louch.devdesk.core.branch.BranchStacks;
import io.github.louch.devdesk.core.changes.KnownGood;
import io.github.louch.devdesk.core.config.EnvFile;
import io.github.louch.devdesk.core.doctor.ToolNeeds;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.linux.doctor.ToolVersions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes a project's {@link KnownGood} picture, and keeps the last clean one per project in DevDesk's state folder
 * ({@code known-good/<project key>.json}). Shared by the engine (which saves it) and {@code devdesk why} (which reads
 * it).
 */
public final class KnownGoodFiles {

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
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path folder;
    private final ToolVersions tools;
    private final Clock clock;

    public KnownGoodFiles(Path folder, ToolVersions tools, Clock clock) {
        this.folder = folder;
        this.tools = tools;
        this.clock = clock;
    }

    /** {@code $XDG_STATE_HOME/devdesk/known-good}, like the other state files. */
    public static Path defaultFolder(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return base.resolve("devdesk").resolve("known-good");
    }

    /**
     * The picture right now.
     *
     * @param imageIds image reference → the id it points to on this machine (Docker's job; empty without Docker)
     */
    public KnownGood take(ProjectSpec p, Function<Collection<String>, Map<String, String>> imageIds) {
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
        Map<String, String> ids = images.isEmpty() ? Map.of() : imageIds.apply(images);
        Map<String, String> versions = new LinkedHashMap<>();
        ToolNeeds.of(p, f -> Files.isRegularFile(f) ? Optional.of(read(f)) : Optional.empty()).stream()
                .map(ToolNeeds.Need::tool)
                .distinct()
                .forEach(tool -> tools.of(tool).ifPresent(v -> versions.put(tool, v)));
        Optional<String> branch = git(root, "HEAD").flatMap(BranchStacks::branch);
        return new KnownGood(
                clock.instant(), branch, commit(root), read(root.resolve("devdesk.yaml")), env, files, ids, versions);
    }

    /** Replaces the saved picture. A failed write keeps the previous one. */
    public void save(ProjectSpec p, KnownGood k) {
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
            // Written next to it, then renamed: rename(2) is atomic, so a crash never leaves half a file.
            Path tmp = folder.resolve(p.key() + ".json.tmp");
            Files.writeString(tmp, JSON.writeValueAsString(m), StandardCharsets.UTF_8);
            Files.move(tmp, file(p), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // not saved: the comparison then uses the previous clean start
        }
    }

    /** The last clean start, if DevDesk ever saw one. */
    @SuppressWarnings("unchecked")
    public Optional<KnownGood> load(ProjectSpec p) {
        try {
            Map<String, Object> m = JSON.readValue(Files.readString(file(p)), Map.class);
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

    private Path file(ProjectSpec p) {
        return folder.resolve(p.key() + ".json");
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
