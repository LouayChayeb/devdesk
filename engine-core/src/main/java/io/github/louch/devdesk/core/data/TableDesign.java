package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Creating and changing a table with a form. The form says what the table should look like; this compares it with
 * what the table is now and writes the SQL (CREATE TABLE, ALTER TABLE...) for the database's dialect. The user sees
 * that SQL before anything runs.
 */
public final class TableDesign {

    /** Type names: letters, digits, spaces and the brackets of "numeric(10, 2)" or "int[]". Nothing that ends a statement. */
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_ ]*(\\([0-9 ,]+\\))?( ?\\[\\])?");

    private TableDesign() {}

    /**
     * What the form says.
     *
     * @param name the table's name after the change (a different name renames it)
     */
    public record Design(String schema, String name, List<Column> columns, List<Index> indexes, List<Link> links) {
        public Design {
            columns = List.copyOf(columns);
            indexes = List.copyOf(indexes);
            links = List.copyOf(links);
        }
    }

    /**
     * @param was the column's current name; empty for a new column
     * @param defaultValue a SQL expression as typed: {@code now()}, {@code 'new'}, {@code 0}; empty = no default
     */
    public record Column(
            Optional<String> was,
            String name,
            String type,
            boolean nullable,
            Optional<String> defaultValue,
            boolean primaryKey) {}

    /** @param was the index's current name; empty for a new one */
    public record Index(Optional<String> was, String name, List<String> columns, boolean unique) {
        public Index {
            columns = List.copyOf(columns);
        }
    }

    /** A foreign key: {@code columns} point at {@code toColumns} of {@code toSchema.toTable}. */
    public record Link(
            Optional<String> was,
            String name,
            List<String> columns,
            String toSchema,
            String toTable,
            List<String> toColumns) {
        public Link {
            columns = List.copyOf(columns);
            toColumns = List.copyOf(toColumns);
        }
    }

    /**
     * The SQL to get from {@code now} (empty: the table doesn't exist yet) to {@code design}.
     *
     * @param table the table's current name (for a new table, the same as the design's)
     * @throws DataException when the form isn't valid, or the database can't make that change (SQLite can't change a
     *     column's type, for example)
     */
    public static List<String> sql(SqlDialect d, String table, Optional<SqlBrowser.Structure> now, Design design) {
        check(d, design);
        return now.isEmpty() ? create(d, design) : alter(d, table, now.get(), design);
    }

    // ---- new table ----

    private static List<String> create(SqlDialect d, Design t) {
        List<String> lines = new ArrayList<>();
        for (Column c : t.columns()) {
            lines.add("  " + columnDefinition(d, c));
        }
        List<String> key = t.columns().stream()
                .filter(Column::primaryKey)
                .map(Column::name)
                .toList();
        if (!key.isEmpty()) {
            lines.add("  primary key (" + names(d, key) + ")");
        }
        for (Link l : t.links()) {
            lines.add("  " + linkDefinition(d, l));
        }
        List<String> out = new ArrayList<>();
        out.add("create table " + d.table(t.schema(), t.name()) + " (\n" + String.join(",\n", lines) + "\n)");
        for (Index i : t.indexes()) {
            out.add(createIndex(d, t.schema(), t.name(), i));
        }
        return out;
    }

    // ---- changed table ----

    private static List<String> alter(SqlDialect d, String table, SqlBrowser.Structure now, Design t) {
        List<String> out = new ArrayList<>();
        Map<String, SqlBrowser.Column> current =
                now.columns().stream().collect(Collectors.toMap(SqlBrowser.Column::name, Function.identity()));
        String at = d.table(t.schema(), table);
        String alter = "alter table " + at + " ";

        // Links first: a dropped column may be part of one, and a new link may use a new column (added below).
        Set<String> keptLinks = names(t.links(), Link::was);
        for (SqlBrowser.ForeignKey fk : now.foreignKeys()) {
            if (!keptLinks.contains(fk.name())) {
                unsupported(d == SqlDialect.SQLITE, "SQLite can't remove a link from an existing table");
                out.add(alter + (d == SqlDialect.MYSQL ? "drop foreign key " : "drop constraint ") + d.name(fk.name()));
            }
        }
        Set<String> keptIndexes = names(t.indexes(), Index::was);
        for (SqlBrowser.Index i : now.indexes()) {
            if (!keptIndexes.contains(i.name()) && !isKeyIndex(d, i, now)) {
                out.add(
                        d == SqlDialect.MYSQL
                                ? alter + "drop index " + d.name(i.name())
                                : "drop index " + d.table(t.schema(), i.name()));
            }
        }
        Set<String> keptColumns = names(t.columns(), Column::was);
        for (SqlBrowser.Column c : now.columns()) {
            if (!keptColumns.contains(c.name())) {
                out.add(alter + "drop column " + d.name(c.name()));
            }
        }
        for (Column c : t.columns()) {
            if (c.was().isEmpty()) {
                unsupported(
                        d == SqlDialect.SQLITE && c.primaryKey(), "SQLite can't add a key column to an existing table");
                out.add(alter + "add column " + columnDefinition(d, c));
                continue;
            }
            SqlBrowser.Column old = current.get(c.was().get());
            if (old == null) {
                throw new DataException("the column " + c.was().get() + " isn't in " + table + " any more: reload it");
            }
            String name = c.name();
            if (!old.name().equals(name)) {
                out.add(alter + "rename column " + d.name(old.name()) + " to " + d.name(name));
            }
            boolean typeChanged = !sameType(old.type(), c.type());
            boolean nullChanged = old.nullable() != c.nullable();
            boolean defaultChanged = !old.defaultValue()
                    .map(String::strip)
                    .equals(c.defaultValue().map(String::strip));
            if (!typeChanged && !nullChanged && !defaultChanged) {
                continue;
            }
            switch (d) {
                case POSTGRES -> {
                    String col = alter + "alter column " + d.name(name) + " ";
                    if (typeChanged) {
                        // USING converts the values already there; without it Postgres refuses e.g. text → integer.
                        out.add(col + "type " + c.type() + " using " + d.name(name) + "::" + c.type());
                    }
                    if (nullChanged) {
                        out.add(col + (c.nullable() ? "drop not null" : "set not null"));
                    }
                    if (defaultChanged) {
                        out.add(col
                                + c.defaultValue().map(v -> "set default " + v).orElse("drop default"));
                    }
                }
                // MySQL changes type, null and default together, by restating the whole column.
                case MYSQL -> out.add(alter + "modify column " + columnDefinition(d, c));
                case SQLITE ->
                    unsupported(
                            true,
                            "SQLite can't change a column's type, required flag or default in place (only"
                                    + " add, rename and drop columns). Make a new table and copy the rows over in a SQL tab");
            }
        }
        List<String> oldKey = now.columns().stream()
                .filter(SqlBrowser.Column::primaryKey)
                .map(SqlBrowser.Column::name)
                .toList();
        List<String> newKey = t.columns().stream()
                .filter(Column::primaryKey)
                .map(c -> c.was().orElse(c.name()))
                .toList();
        if (!oldKey.equals(newKey)) {
            unsupported(d == SqlDialect.SQLITE, "SQLite can't change the primary key of an existing table");
            List<String> keyNames = t.columns().stream()
                    .filter(Column::primaryKey)
                    .map(Column::name)
                    .toList();
            // Dropping a key column already took the key with it.
            boolean keyGone = oldKey.stream().anyMatch(k -> !keptColumns.contains(k));
            if (!oldKey.isEmpty() && !keyGone) {
                out.add(
                        d == SqlDialect.MYSQL
                                ? alter + "drop primary key"
                                : alter + "drop constraint " + d.name(primaryKeyName(now, table)));
            }
            if (!keyNames.isEmpty()) {
                out.add(alter + "add primary key (" + names(d, keyNames) + ")");
            }
        }
        for (Index i : t.indexes()) {
            if (i.was().isEmpty()) {
                out.add(createIndex(d, t.schema(), table, i));
            }
        }
        for (Link l : t.links()) {
            if (l.was().isEmpty()) {
                unsupported(d == SqlDialect.SQLITE, "SQLite can't add a link to an existing table");
                out.add(alter + "add " + linkDefinition(d, l));
            }
        }
        if (!t.name().equals(table)) {
            // Last, so every statement above can name the table as it was.
            out.add(alter + "rename to " + (d == SqlDialect.MYSQL ? d.table(t.schema(), t.name()) : d.name(t.name())));
        }
        return out;
    }

    /** DROP TABLE, for the separate "delete this table" action. */
    public static String drop(SqlDialect d, String schema, String table) {
        return "drop table " + d.table(schema, table);
    }

    // ---- pieces ----

    private static String columnDefinition(SqlDialect d, Column c) {
        return d.name(c.name()) + " " + c.type() + (c.nullable() ? "" : " not null")
                + c.defaultValue().map(v -> " default " + v).orElse("");
    }

    private static String linkDefinition(SqlDialect d, Link l) {
        return "constraint " + d.name(l.name()) + " foreign key (" + names(d, l.columns()) + ") references "
                + d.table(l.toSchema(), l.toTable()) + " (" + names(d, l.toColumns()) + ")";
    }

    private static String createIndex(SqlDialect d, String schema, String table, Index i) {
        // Postgres and SQLite put an index in the table's schema by themselves; only the bare name may be given.
        return "create " + (i.unique() ? "unique " : "") + "index " + d.name(i.name()) + " on " + d.table(schema, table)
                + " (" + names(d, i.columns()) + ")";
    }

    private static String names(SqlDialect d, List<String> names) {
        return names.stream().map(d::name).collect(Collectors.joining(", "));
    }

    private static <T> Set<String> names(List<T> items, Function<T, Optional<String>> was) {
        Set<String> out = new HashSet<>();
        items.forEach(i -> was.apply(i).ifPresent(out::add));
        return out;
    }

    /** The index behind the primary key isn't the user's to drop: it goes with the key. */
    private static boolean isKeyIndex(SqlDialect d, SqlBrowser.Index i, SqlBrowser.Structure now) {
        List<String> key = now.columns().stream()
                .filter(SqlBrowser.Column::primaryKey)
                .map(SqlBrowser.Column::name)
                .toList();
        return i.unique()
                && i.columns().equals(key)
                && (i.name().equals("PRIMARY")
                        || i.name().endsWith("_pkey")
                        || i.name().startsWith("sqlite_autoindex"));
    }

    /** Postgres names it "orders_pkey" unless told otherwise; the structure's unique index on the key has the name. */
    private static String primaryKeyName(SqlBrowser.Structure now, String table) {
        List<String> key = now.columns().stream()
                .filter(SqlBrowser.Column::primaryKey)
                .map(SqlBrowser.Column::name)
                .toList();
        return now.indexes().stream()
                .filter(i -> i.unique() && i.columns().equals(key))
                .map(SqlBrowser.Index::name)
                .findFirst()
                .orElse(table + "_pkey");
    }

    /** "integer" and "INTEGER", "varchar(20)" and "character varying(20)" are the same type. */
    static boolean sameType(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(String type) {
        return type.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll(" ?, ?", ",")
                .replace("character varying", "varchar")
                .replace("timestamp with time zone", "timestamptz")
                .replace("timestamp without time zone", "timestamp")
                .replace("double precision", "float8")
                .replaceAll("^(int|int4)$", "integer")
                .replaceAll("^int8$", "bigint")
                .replaceAll("^int2$", "smallint")
                .replaceAll("^bool$", "boolean")
                .replaceAll("^decimal", "numeric");
    }

    private static void unsupported(boolean when, String why) {
        if (when) {
            throw new DataException(why);
        }
    }

    private static void check(SqlDialect d, Design t) {
        if (t.name().isBlank()) {
            throw new DataException("the table needs a name");
        }
        if (t.columns().isEmpty()) {
            throw new DataException("a table needs at least one column");
        }
        Set<String> seen = new HashSet<>();
        for (Column c : t.columns()) {
            if (c.name().isBlank()) {
                throw new DataException("every column needs a name");
            }
            if (!seen.add(c.name().toLowerCase(Locale.ROOT))) {
                throw new DataException("two columns are called " + c.name());
            }
            if (!TYPE.matcher(c.type().strip()).matches()) {
                throw new DataException("\"" + c.type() + "\" isn't a type DevDesk can write for " + c.name()
                        + " (examples: integer, text, varchar(100), numeric(10,2), timestamptz)");
            }
            if (c.defaultValue().map(v -> v.contains(";") || v.contains("--")).orElse(false)) {
                throw new DataException(
                        "the default of " + c.name() + " must be one value or expression, like now()" + " or 'new'");
            }
            if (c.primaryKey() && c.nullable()) {
                throw new DataException(
                        c.name() + " is part of the primary key, so it can't be empty (untick \"empty" + " allowed\")");
            }
        }
        for (Index i : t.indexes()) {
            if (i.name().isBlank() || i.columns().isEmpty()) {
                throw new DataException("every index needs a name and at least one column");
            }
            for (String c : i.columns()) {
                if (!seen.contains(c.toLowerCase(Locale.ROOT))) {
                    throw new DataException(
                            "the index " + i.name() + " uses " + c + ", which isn't a column of the table");
                }
            }
        }
        for (Link l : t.links()) {
            if (l.name().isBlank()
                    || l.columns().isEmpty()
                    || l.columns().size() != l.toColumns().size()
                    || l.toTable().isBlank()) {
                throw new DataException(
                        "every link needs a name, its columns, and as many columns of the table it" + " points at");
            }
        }
    }
}
