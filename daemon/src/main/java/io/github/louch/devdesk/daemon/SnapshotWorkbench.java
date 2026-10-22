package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.KeyDumpFile;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.Snapshot;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Snapshots of the open project's data ("save game"): every database, every Redis and the env files, saved together
 * under a name and put back together. One at a time: two restores at once would mix their data.
 *
 * <p>On disk: {@code $XDG_STATE_HOME/devdesk/snapshots/<project-key>/<time>_<label>/}, with a {@code snapshot.json}
 * that lists the parts, and one file per part.
 */
final class SnapshotWorkbench {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String MANIFEST = "snapshot.json";
    /** Env files of the project folder, but not the examples meant to be copied (.env.example). */
    private static final Pattern ENV_FILE =
            Pattern.compile("\\.env(?![A-Za-z0-9._-]*\\.(example|sample|template|dist)$)[A-Za-z0-9._-]*");
    /** More keys than this is no longer a dev cache: the snapshot then says it holds only part of it. */
    private static final int MAX_REDIS_KEYS = 200_000;

    private final EngineSession session;
    private final Path root;
    private final Clock clock;

    SnapshotWorkbench(EngineSession session, Path root, Clock clock) {
        this.session = session;
        this.root = root;
        this.clock = clock;
    }

    synchronized EngineSession.Answer list() {
        return withProject(p ->
                Map.of("snapshots", all(p).stream().map(SnapshotWorkbench::json).toList()));
    }

    /** Saves everything now. Parts that can't be saved (a database that isn't running) are listed with the reason. */
    synchronized EngineSession.Answer save(String label) {
        return withProject(p -> json(take(p, label)));
    }

    /**
     * Puts every saved part back: databases replaced, Redis emptied then refilled, env files rewritten.
     *
     * @param saveFirst snapshot the current state first, so the restore itself can be undone
     */
    synchronized EngineSession.Answer restore(String id, boolean saveFirst) {
        return withProject(p -> {
            Snapshot s = find(p, id);
            long started = System.nanoTime();
            Map<String, Object> out = new LinkedHashMap<>();
            if (saveFirst) {
                out.put("savedFirst", json(take(p, "before restoring " + s.label())));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Snapshot.Item item : s.items()) {
                results.add(Map.of(
                        "kind", item.kind().name().toLowerCase(), "name", item.name(), "result", put(p, s, item)));
            }
            out.put("results", results);
            out.put("ms", (System.nanoTime() - started) / 1_000_000);
            return out;
        });
    }

    synchronized EngineSession.Answer delete(String id) {
        return withProject(p -> {
            Path dir = folder(p).resolve(find(p, id).id());
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(f); // children first, then the folder itself
                }
            } catch (IOException e) {
                throw new IllegalStateException("couldn't delete " + dir + ": " + e.getMessage(), e);
            }
            return Map.of(
                    "snapshots", all(p).stream().map(SnapshotWorkbench::json).toList());
        });
    }

    // ---- saving ----

    private Snapshot take(ProjectSpec p, String label) {
        Instant now = clock.instant();
        String slug = Snapshot.slug(label);
        String id = STAMP.format(now.atZone(ZoneId.systemDefault())) + (slug.isEmpty() ? "" : "_" + slug);
        Path dir = folder(p).resolve(id);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("couldn't create the snapshot folder " + dir + ": " + e.getMessage(), e);
        }
        Map<String, ServiceState> states =
                session.current().map(EngineSession.Current::states).orElse(Map.of());
        List<Snapshot.Item> items = new ArrayList<>();
        for (DatabaseTarget t : session.databaseTargets()) {
            items.add(saveDatabase(p, t, states, dir));
        }
        for (Path env : envFiles(p.root())) {
            items.add(saveFile(env, dir));
        }
        Snapshot s = new Snapshot(id, label.isBlank() ? "snapshot" : label.strip(), now, items);
        try {
            Files.writeString(dir.resolve(MANIFEST), ApiJson.write(json(s)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("couldn't write " + dir.resolve(MANIFEST) + ": " + e.getMessage(), e);
        }
        return s;
    }

    private Snapshot.Item saveDatabase(ProjectSpec p, DatabaseTarget t, Map<String, ServiceState> states, Path dir) {
        boolean redis = t.kind() == DatabaseTarget.Kind.REDIS;
        Snapshot.Kind kind = redis ? Snapshot.Kind.REDIS : Snapshot.Kind.DATABASE;
        if (t.kind() == DatabaseTarget.Kind.OBJECTS || t.kind() == DatabaseTarget.Kind.SEARCH) {
            return new Snapshot.Item(
                    kind,
                    t.service(),
                    Optional.empty(),
                    0,
                    Optional.of("DevDesk doesn't include " + t.kind().label() + " in snapshots yet"));
        }
        Optional<String> notReady = notRunning(t, states);
        if (notReady.isPresent()) {
            return new Snapshot.Item(kind, t.service(), Optional.empty(), 0, notReady);
        }
        String file = safe(t.service())
                + switch (t.kind()) {
                    case SQLITE -> ".db";
                    case MONGO -> ".archive";
                    case REDIS -> ".keys";
                    default -> ".sql";
                };
        try {
            Optional<String> warning = Optional.empty();
            if (redis) {
                List<KeyValueBrowser.KeyDump> keys = session.redis().dump(t, "*", MAX_REDIS_KEYS);
                Files.writeString(dir.resolve(file), KeyDumpFile.write(keys), StandardCharsets.UTF_8);
                if (keys.size() >= MAX_REDIS_KEYS) {
                    warning = Optional.of("only the first " + MAX_REDIS_KEYS + " keys were saved");
                }
            } else {
                session.backups(t).save(p.name(), t, dir.resolve(file));
            }
            return new Snapshot.Item(kind, t.service(), Optional.of(file), Files.size(dir.resolve(file)), warning);
        } catch (IOException | RuntimeException e) {
            return new Snapshot.Item(
                    kind, t.service(), Optional.empty(), 0, Optional.of("not saved: " + e.getMessage()));
        }
    }

    private static Snapshot.Item saveFile(Path env, Path dir) {
        String name = env.getFileName().toString();
        try {
            Files.copy(env, dir.resolve("settings" + name), StandardCopyOption.REPLACE_EXISTING);
            return new Snapshot.Item(
                    Snapshot.Kind.SETTINGS, name, Optional.of("settings" + name), Files.size(env), Optional.empty());
        } catch (IOException e) {
            return new Snapshot.Item(
                    Snapshot.Kind.SETTINGS, name, Optional.empty(), 0, Optional.of("not saved: " + e.getMessage()));
        }
    }

    // ---- restoring ----

    /** Puts one part back. Returns what happened, in words: "restored", "unchanged", or why not. */
    private String put(ProjectSpec p, Snapshot s, Snapshot.Item item) {
        if (item.file().isEmpty()) {
            return "skipped: it wasn't in the snapshot (" + item.problem().orElse("not saved") + ")";
        }
        Path file = folder(p).resolve(s.id()).resolve(item.file().get());
        try {
            if (item.kind() == Snapshot.Kind.SETTINGS) {
                Path target = p.root().resolve(item.name());
                if (!ENV_FILE.matcher(item.name()).matches()) {
                    return "skipped: not an env file"; // a hand-edited snapshot.json never writes elsewhere
                }
                if (Files.exists(target) && Files.mismatch(file, target) == -1) {
                    return "unchanged";
                }
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return "restored: restart the services that use it to pick it up";
            }
            Optional<DatabaseTarget> t = session.databaseTargets().stream()
                    .filter(x -> x.service().equals(item.name()))
                    .findFirst();
            if (t.isEmpty()) {
                return "skipped: " + item.name() + " is no longer a database of this project";
            }
            Map<String, ServiceState> states =
                    session.current().map(EngineSession.Current::states).orElse(Map.of());
            Optional<String> notReady = notRunning(t.get(), states);
            if (notReady.isPresent()) {
                return "skipped: " + notReady.get();
            }
            if (item.kind() == Snapshot.Kind.REDIS) {
                // Exactly as it was: keys added since then go too, not only the saved ones come back.
                session.redis().deleteMatching(t.get(), "*");
                var keys = KeyDumpFile.read(Files.readString(file, StandardCharsets.UTF_8));
                session.redis().restore(t.get(), keys, true);
                return "restored (" + keys.size() + " keys)";
            }
            session.backups(t.get()).restoreExactly(p.name(), t.get(), file);
            return "restored";
        } catch (IOException | RuntimeException e) {
            return "failed: " + e.getMessage();
        }
    }

    // ---- helpers ----

    /** A server database must be running to be saved or restored by its own tools; a SQLite file needs nothing. */
    private static Optional<String> notRunning(DatabaseTarget t, Map<String, ServiceState> states) {
        if (t.kind() == DatabaseTarget.Kind.SQLITE) {
            return Optional.empty();
        }
        ServiceState s = states.getOrDefault(t.service(), new ServiceState.Stopped());
        boolean up = s instanceof ServiceState.Running
                || s instanceof ServiceState.Healthy
                || s instanceof ServiceState.Unhealthy;
        return up ? Optional.empty() : Optional.of(t.service() + " isn't running (" + s.label() + "): start it first");
    }

    private static List<Path> envFiles(Path projectRoot) {
        try (Stream<Path> files = Files.list(projectRoot)) {
            return files.filter(f -> Files.isRegularFile(f)
                            && ENV_FILE.matcher(f.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<Snapshot> all(ProjectSpec p) {
        List<Snapshot> out = new ArrayList<>();
        Path dir = folder(p);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(dir)) {
            for (Path f : folders) {
                read(f).ifPresent(out::add);
            }
        } catch (IOException e) {
            throw new IllegalStateException("couldn't read the snapshots folder " + dir + ": " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(Snapshot::created).reversed());
        return out;
    }

    /** An id from the app is only looked up among the saved snapshots, never used as a path by itself. */
    private Snapshot find(ProjectSpec p, String id) {
        return all(p).stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("there's no snapshot called " + id));
    }

    private static Optional<Snapshot> read(Path dir) {
        try {
            Map<?, ?> m = ApiJson.MAPPER.readValue(Files.readString(dir.resolve(MANIFEST)), Map.class);
            List<Snapshot.Item> items = new ArrayList<>();
            for (Object o : (List<?>) m.get("items")) {
                Map<?, ?> i = (Map<?, ?>) o;
                items.add(new Snapshot.Item(
                        Snapshot.Kind.valueOf(String.valueOf(i.get("kind")).toUpperCase()),
                        String.valueOf(i.get("name")),
                        Optional.ofNullable((String) i.get("file")),
                        ((Number) i.get("bytes")).longValue(),
                        Optional.ofNullable((String) i.get("problem"))));
            }
            return Optional.of(new Snapshot(
                    dir.getFileName().toString(),
                    String.valueOf(m.get("label")),
                    Instant.parse(String.valueOf(m.get("created"))),
                    items));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // not a snapshot folder, or a half-written one: not listed
        }
    }

    private static Map<String, Object> json(Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("label", s.label());
        m.put("created", s.created().toString());
        m.put("complete", s.complete());
        m.put("bytes", s.items().stream().mapToLong(Snapshot.Item::bytes).sum());
        m.put(
                "items",
                s.items().stream()
                        .map(i -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("kind", i.kind().name().toLowerCase());
                            item.put("name", i.name());
                            item.put("file", i.file().orElse(null));
                            item.put("bytes", i.bytes());
                            item.put("problem", i.problem().orElse(null));
                            return item;
                        })
                        .toList());
        return m;
    }

    private Path folder(ProjectSpec p) {
        return root.resolve(p.key());
    }

    /** SQLite targets are named like "data:app.db": made safe as a file name. */
    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private EngineSession.Answer withProject(java.util.function.Function<ProjectSpec, Object> call) {
        Optional<ProjectSpec> p = session.current().map(EngineSession.Current::project);
        if (p.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        try {
            return new EngineSession.Answer(200, call.apply(p.get()));
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(404, ApiJson.error(e.getMessage()));
        } catch (RuntimeException e) {
            return new EngineSession.Answer(500, ApiJson.error(e.getMessage()));
        }
    }
}
