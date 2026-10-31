package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Which migrations haven't run yet?": the migration files in the project, compared with what the database's own
 * history table says ran. Knows Flyway, Prisma and Alembic. Core reads no files and no database: callers pass both.
 */
public final class Migrations {

    /** A migration tool, with its history table and the command that applies what's missing. */
    public enum Tool {
        FLYWAY("flyway_schema_history", "flyway migrate (or start the app, if it runs Flyway on startup)"),
        PRISMA("_prisma_migrations", "npx prisma migrate deploy"),
        ALEMBIC("alembic_version", "alembic upgrade head");

        private final String table;
        private final String fix;

        Tool(String table, String fix) {
            this.table = table;
            this.fix = fix;
        }

        /** The table the tool keeps its history in. */
        public String table() {
            return table;
        }

        /** What the user runs to apply the pending ones. */
        public String fix() {
            return fix;
        }
    }

    /**
     * One migration file.
     *
     * @param id what the history table stores: Flyway's version ("1.2"), Prisma's folder name, Alembic's revision
     * @param parents Alembic only: the revisions it comes after (its down_revision)
     */
    public record Migration(String id, String name, List<String> parents) {}

    /** What ran, as the history table says. {@code failed}: started but not finished (Flyway, Prisma). */
    public record Applied(List<String> ids, List<String> failed) {}

    /**
     * The comparison for one folder of migrations and one database.
     *
     * @param pending in the folder, never ran: in the order they will run
     * @param failed ran and failed: the tool usually refuses to go on until it's fixed
     * @param unknown ran on the database, but there's no such file here (another branch, or a deleted file)
     */
    public record Status(Tool tool, int applied, List<Migration> pending, List<String> failed, List<String> unknown) {

        /** True when no migration is pending or failed. */
        public boolean upToDate() {
            return pending.isEmpty() && failed.isEmpty();
        }
    }

    private static final Pattern FLYWAY_FILE = Pattern.compile("V(\\d+(?:[._]\\d+)*)__(.+)\\.sql");
    private static final Pattern PRISMA_FOLDER = Pattern.compile("(\\d{14})_(.+)");
    private static final Pattern REVISION =
            Pattern.compile("(?m)^revision\\s*(?::\\s*[\\w\\[\\], ]+)?=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DOWN_REVISION =
            Pattern.compile("(?m)^down_revision\\s*(?::\\s*[\\w\\[\\], .|]+)?=\\s*(.+)$");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final Pattern DOCSTRING = Pattern.compile("^\\s*(?:\"\"\"|''')\\s*(.+)");

    private Migrations() {}

    /** {@code V3__add_status.sql} → version 3, "add status". Repeatable (R__) and undo (U) files aren't versions. */
    public static Optional<Migration> flywayFile(String fileName) {
        Matcher m = FLYWAY_FILE.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        // Flyway stores "1_2" as "1.2" in its history table.
        return Optional.of(
                new Migration(m.group(1).replace('_', '.'), m.group(2).replace('_', ' '), List.of()));
    }

    /** {@code 20241003120000_add_status} (a folder of prisma/migrations) → itself, named "add status". */
    public static Optional<Migration> prismaFolder(String folderName) {
        Matcher m = PRISMA_FOLDER.matcher(folderName);
        return m.matches()
                ? Optional.of(new Migration(folderName, m.group(2).replace('_', ' '), List.of()))
                : Optional.empty();
    }

    /** An Alembic revision file: its {@code revision}, {@code down_revision} (none, one, or a merge of several). */
    public static Optional<Migration> alembicFile(String content) {
        Matcher r = REVISION.matcher(content);
        if (!r.find()) {
            return Optional.empty();
        }
        List<String> parents = new ArrayList<>();
        Matcher d = DOWN_REVISION.matcher(content);
        if (d.find()) {
            Matcher q = QUOTED.matcher(d.group(1));
            while (q.find()) {
                parents.add(q.group(1));
            }
        }
        Matcher doc = DOCSTRING.matcher(content);
        String name = doc.find() ? doc.group(1).replaceAll("(\"\"\"|''')$", "").strip() : r.group(1);
        return Optional.of(new Migration(r.group(1), name, parents));
    }

    /** Compares the files with the history. */
    public static Status compare(Tool tool, List<Migration> files, Applied applied) {
        Set<String> onDisk = new HashSet<>();
        files.forEach(f -> onDisk.add(f.id()));
        List<Migration> pending = new ArrayList<>();
        if (tool == Tool.ALEMBIC) {
            // alembic_version holds only the current head(s): everything they come after has run too.
            Map<String, Migration> byId = new HashMap<>();
            files.forEach(f -> byId.put(f.id(), f));
            Set<String> done = new HashSet<>();
            List<String> todo = new ArrayList<>(applied.ids());
            while (!todo.isEmpty()) {
                String id = todo.removeLast();
                if (done.add(id) && byId.containsKey(id)) {
                    todo.addAll(byId.get(id).parents());
                }
            }
            pending.addAll(alembicOrder(files).stream()
                    .filter(f -> !done.contains(f.id()))
                    .toList());
        } else {
            Set<String> ran = new HashSet<>(applied.ids());
            for (Migration f : files) {
                if (!ran.contains(f.id())) {
                    pending.add(f);
                }
            }
            pending.sort(
                    tool == Tool.FLYWAY
                            ? Comparator.comparing(Migration::id, Migrations::compareVersions)
                            : Comparator.comparing(Migration::id));
        }
        List<String> unknown = applied.ids().stream()
                .filter(id -> !onDisk.contains(id))
                .distinct()
                .toList();
        int count = (int) files.stream().filter(f -> !pending.contains(f)).count();
        return new Status(tool, count, pending, List.copyOf(applied.failed()), unknown);
    }

    /** Oldest first: a revision comes after the ones it names as down_revision. */
    private static List<Migration> alembicOrder(List<Migration> files) {
        Map<String, Migration> byId = new HashMap<>();
        files.forEach(f -> byId.put(f.id(), f));
        Set<String> placed = new LinkedHashSet<>();
        for (Migration f :
                files.stream().sorted(Comparator.comparing(Migration::id)).toList()) {
            place(f, byId, placed, new HashSet<>());
        }
        return placed.stream().map(byId::get).toList();
    }

    private static void place(Migration f, Map<String, Migration> byId, Set<String> placed, Set<String> visiting) {
        if (placed.contains(f.id()) || !visiting.add(f.id())) {
            return; // done, or a loop in a broken set of files
        }
        for (String p : f.parents()) {
            if (byId.containsKey(p)) {
                place(byId.get(p), byId, placed, visiting);
            }
        }
        placed.add(f.id());
    }

    /** "1.10" comes after "1.9": versions compare part by part, as numbers. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long xi = i < x.length ? Long.parseLong(x[i]) : 0;
            long yi = i < y.length ? Long.parseLong(y[i]) : 0;
            if (xi != yi) {
                return Long.compare(xi, yi);
            }
        }
        return 0;
    }
}
