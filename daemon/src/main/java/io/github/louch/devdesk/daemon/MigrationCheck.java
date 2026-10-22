package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.Migrations;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.TaskSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the project's migration folders (Flyway, Prisma, Alembic) and asks its databases which of them ran, so
 * "relation does not exist" can be answered with "V7__add_status.sql hasn't run on db".
 */
final class MigrationCheck {

    /** Folders never searched: dependencies and build output are big and hold other projects' migrations. */
    private static final Set<String> SKIP = Set.of(
            "node_modules",
            ".git",
            "build",
            "target",
            "dist",
            ".venv",
            "venv",
            "__pycache__",
            ".gradle",
            ".idea",
            ".next",
            "vendor");

    private static final int MAX_DEPTH = 7;
    private static final Pattern SCRIPT_LOCATION = Pattern.compile("(?m)^script_location\\s*=\\s*(.+)$");

    /** One folder of migrations found on disk. */
    record Source(Migrations.Tool tool, Path folder, List<Migrations.Migration> files) {}

    private final EngineSession session;

    MigrationCheck(EngineSession session) {
        this.session = session;
    }

    EngineSession.Answer check() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        ProjectSpec p = current.get().project();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Source source : find(p.root())) {
            out.add(words(status(p, source, current.get().states())));
        }
        return new EngineSession.Answer(200, Map.of("sources", out));
    }

    // ---- the database side ----

    private Map<String, Object> status(ProjectSpec p, Source source, Map<String, ServiceState> states) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", source.tool().name().toLowerCase());
        m.put("folder", p.root().relativize(source.folder()).toString());
        m.put("files", source.files().size());
        m.put("fix", source.tool().fix());
        m.put("task", task(p, source.tool()).orElse(null));
        List<String> notRunning = new ArrayList<>();
        for (DatabaseTarget t : session.databaseTargets()) {
            if (!EnumSet.of(DatabaseTarget.Kind.POSTGRES, DatabaseTarget.Kind.MYSQL, DatabaseTarget.Kind.SQLITE)
                    .contains(t.kind())) {
                continue;
            }
            if (t.kind() != DatabaseTarget.Kind.SQLITE && !running(states.get(t.service()))) {
                notRunning.add(t.service());
                continue;
            }
            Optional<Migrations.Applied> applied = applied(t, source.tool());
            if (applied.isPresent()) {
                Migrations.Status s = Migrations.compare(source.tool(), source.files(), applied.get());
                m.put("database", t.service());
                m.put("applied", s.applied());
                m.put(
                        "pending",
                        s.pending().stream()
                                .map(x -> Map.of("id", x.id(), "name", x.name()))
                                .toList());
                m.put("failed", s.failed());
                m.put("unknown", s.unknown());
                m.put("upToDate", s.upToDate());
                return m;
            }
        }
        // No database has the tool's history table: nothing ran yet, or the database that would have it is down.
        m.put("database", null);
        m.put("applied", 0);
        m.put(
                "pending",
                source.files().stream()
                        .map(x -> Map.of("id", x.id(), "name", x.name()))
                        .toList());
        m.put("failed", List.of());
        m.put("unknown", List.of());
        m.put("upToDate", source.files().isEmpty());
        m.put(
                "note",
                notRunning.isEmpty()
                        ? "no database of the project has " + source.tool().table() + " yet: none of these ran"
                        : String.join(", ", notRunning) + (notRunning.size() == 1 ? " isn't" : " aren't")
                                + " running, so DevDesk can't tell which ran. Start "
                                + (notRunning.size() == 1 ? "it" : "them") + " to check");
        return m;
    }

    /**
     * The finding's words, written here so the app only shows them: what's wrong, why it matters, how to fix it.
     * An up-to-date folder gets a one-line "fine" instead.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> words(Map<String, Object> m) {
        String tool = switch (String.valueOf(m.get("tool"))) {
            case "flyway" -> "Flyway";
            case "prisma" -> "Prisma";
            default -> "Alembic";
        };
        List<Map<String, String>> pending = (List<Map<String, String>>) m.get("pending");
        List<String> failed = (List<String>) m.get("failed");
        List<String> unknown = (List<String>) m.get("unknown");
        String db = m.get("database") == null ? "the database" : String.valueOf(m.get("database"));
        if (Boolean.TRUE.equals(m.get("upToDate")) && unknown.isEmpty()) {
            m.put("fine", tool + ": all " + m.get("applied") + " migrations in " + m.get("folder") + " ran on " + db);
            return m;
        }
        List<String> parts = new ArrayList<>();
        if (!failed.isEmpty()) {
            parts.add(failed.size() + " failed");
        }
        if (!pending.isEmpty()) {
            parts.add(pending.size() + (pending.size() == 1 ? " hasn't" : " haven't") + " run");
        }
        if (!unknown.isEmpty()) {
            parts.add(unknown.size() + " ran but " + (unknown.size() == 1 ? "has" : "have") + " no file here");
        }
        m.put("title", tool + " migrations: " + String.join(", ", parts));
        String explanation;
        if (m.get("note") != null) {
            explanation = String.valueOf(m.get("note")) + ".";
        } else if (!failed.isEmpty()) {
            explanation = tool + " recorded a failed migration on " + db + ". It usually refuses to run more until"
                    + " that one is fixed (repaired) by hand.";
        } else if (!pending.isEmpty()) {
            explanation = "The code expects tables or columns these migrations create. Until they run on " + db
                    + ", queries that use them fail with errors like \"relation does not exist\" or"
                    + " \"no such column\".";
        } else {
            explanation = db + " has migrations that aren't in " + m.get("folder")
                    + ": maybe from another git branch. Its tables may not match this branch's code.";
        }
        m.put("explanation", explanation);
        List<String> evidence = new ArrayList<>();
        failed.forEach(f -> evidence.add("failed:  " + f));
        pending.forEach(x -> evidence.add("not run: " + x.get("id") + "  " + x.get("name")));
        unknown.forEach(u -> evidence.add("no file: " + u));
        m.put("evidence", evidence);
        List<String> actions = new ArrayList<>();
        boolean dbDown = String.valueOf(m.get("note")).contains("running");
        if (!dbDown && !failed.isEmpty()) {
            // Fix the broken migration first; then tell the tool it may go on.
            failed.forEach(f -> actions.add(
                    switch (String.valueOf(m.get("tool"))) {
                        case "flyway" -> "flyway repair   (after fixing V" + f + "; then: flyway migrate)";
                        case "prisma" -> "npx prisma migrate resolve --rolled-back " + f;
                        default -> "alembic upgrade head";
                    }));
        }
        if (!dbDown && !pending.isEmpty()) {
            actions.add(from(m) + m.get("fix"));
        }
        if (!dbDown && m.get("task") != null && !pending.isEmpty()) {
            actions.add("or run the task \"" + m.get("task") + "\" (Tasks bar)");
        }
        m.put("actions", actions);
        return m;
    }

    /** "cd worker && ": the folder holding the tool's settings (alembic.ini, the prisma folder's parent). */
    private static String from(Map<String, Object> m) {
        Path folder = Path.of(String.valueOf(m.get("folder")));
        Path base = switch (String.valueOf(m.get("tool"))) {
            case "prisma", "alembic" ->
                folder.getParent() == null ? null : folder.getParent().getParent();
            default -> null; // Flyway runs from the build (mvn/gradle) or the app: the project folder is fine
        };
        return base == null || base.toString().isEmpty() ? "" : "cd " + base + " && ";
    }

    /** What the tool's own table says ran; empty when this database has no such table. */
    private Optional<Migrations.Applied> applied(DatabaseTarget t, Migrations.Tool tool) {
        String sql = switch (tool) {
            case FLYWAY -> "select version, success from flyway_schema_history where version is not null";
            case PRISMA -> "select migration_name, finished_at, rolled_back_at from _prisma_migrations";
            case ALEMBIC -> "select version_num from alembic_version";
        };
        SqlBrowser.ResultTable rows;
        try {
            rows = session.sql(t).run(t, sql, 100_000);
        } catch (RuntimeException e) {
            return Optional.empty(); // no such table in this database (the usual case), or not readable
        }
        List<String> ids = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (List<String> r : rows.rows()) {
            switch (tool) {
                case FLYWAY -> {
                    boolean ok = Set.of("t", "true", "1")
                            .contains(String.valueOf(r.get(1)).toLowerCase());
                    (ok ? ids : failed).add(r.get(0));
                    if (!ok) {
                        ids.add(r.get(0)); // it was tried: not "pending", but failed
                    }
                }
                case PRISMA -> {
                    boolean finished = r.get(1) != null;
                    boolean rolledBack = r.get(2) != null;
                    if (finished && !rolledBack) {
                        ids.add(r.get(0));
                    } else if (!finished && !rolledBack) {
                        failed.add(r.get(0));
                        ids.add(r.get(0));
                    }
                }
                case ALEMBIC -> ids.add(r.get(0));
            }
        }
        return Optional.of(new Migrations.Applied(ids, failed));
    }

    private static boolean running(ServiceState s) {
        return s instanceof ServiceState.Running
                || s instanceof ServiceState.Healthy
                || s instanceof ServiceState.Unhealthy;
    }

    /** A task of the project that applies migrations, to offer as a one-click fix. */
    private static Optional<String> task(ProjectSpec p, Migrations.Tool tool) {
        String key = switch (tool) {
            case FLYWAY -> "flyway";
            case PRISMA -> "prisma migrate";
            case ALEMBIC -> "alembic upgrade";
        };
        return p.tasks().values().stream()
                .filter(t -> t.command().contains(key) || t.name().contains("migrat"))
                .map(TaskSpec::name)
                .findFirst();
    }

    // ---- the files side ----

    /** Every migration folder under the project, nearest first. */
    static List<Source> find(Path root) {
        List<Source> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    flyway(dir).ifPresent(found::add);
                    if (dir.getFileName().toString().equals("migrations")
                            && dir.getParent() != null
                            && dir.getParent().getFileName().toString().equals("prisma")) {
                        prisma(dir).ifPresent(found::add);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals("alembic.ini")) {
                        alembic(file).ifPresent(found::add);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE; // an unreadable folder: skip it
                }
            });
        } catch (IOException e) {
            // the project folder itself can't be read: nothing found
        }
        return found;
    }

    private static Optional<Source> flyway(Path dir) {
        List<Migrations.Migration> files = list(dir).stream()
                .filter(Files::isRegularFile)
                .flatMap(f -> Migrations.flywayFile(f.getFileName().toString()).stream())
                .toList();
        return files.isEmpty() ? Optional.empty() : Optional.of(new Source(Migrations.Tool.FLYWAY, dir, files));
    }

    private static Optional<Source> prisma(Path dir) {
        List<Migrations.Migration> files = list(dir).stream()
                .filter(f -> Files.isRegularFile(f.resolve("migration.sql")))
                .flatMap(f -> Migrations.prismaFolder(f.getFileName().toString()).stream())
                .toList();
        return files.isEmpty() ? Optional.empty() : Optional.of(new Source(Migrations.Tool.PRISMA, dir, files));
    }

    /** alembic.ini says where the scripts are ({@code script_location}); their {@code versions} folder holds them. */
    private static Optional<Source> alembic(Path ini) {
        Path base = ini.getParent();
        Path versions = base.resolve("versions"); // Flask-Migrate keeps alembic.ini next to versions/
        try {
            Matcher m = SCRIPT_LOCATION.matcher(Files.readString(ini));
            if (m.find()) {
                Path location = base.resolve(m.group(1).strip().replace("%(here)s", "."));
                if (Files.isDirectory(location.resolve("versions"))) {
                    versions = location.resolve("versions");
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable or odd alembic.ini: try the folder next to it
        }
        List<Migrations.Migration> files = new ArrayList<>();
        for (Path f : list(versions)) {
            if (f.getFileName().toString().endsWith(".py")) {
                try {
                    Migrations.alembicFile(Files.readString(f)).ifPresent(files::add);
                } catch (IOException e) {
                    // skip a file that can't be read
                }
            }
        }
        return files.isEmpty()
                ? Optional.empty()
                : Optional.of(new Source(Migrations.Tool.ALEMBIC, versions.normalize(), files));
    }

    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
