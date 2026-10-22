package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.SqlBrowser.Column;
import io.github.louch.devdesk.core.data.SqlBrowser.Structure;
import io.github.louch.devdesk.core.data.SqlDialect;
import io.github.louch.devdesk.core.data.SqlEditor.Applied;
import io.github.louch.devdesk.core.data.SqlEditor.RowChange;
import io.github.louch.devdesk.core.data.SqlEditor.TableChanges;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies the grid's pending changes in one transaction, for every SQL database: names are checked against the
 * table's real columns, values are always parameters, and each update or delete must hit exactly one row.
 */
final class RowWriter {

    private RowWriter() {}

    /**
     * @param placeholder the parameter for one column's value: "?" or e.g. "cast(? as integer)"
     * @param explain turns the driver's error into the database's message plus DevDesk's hint
     */
    static Applied apply(
            Connection c,
            SqlDialect dialect,
            Structure s,
            TableChanges changes,
            Function<Column, String> placeholder,
            Function<SQLException, DataException> explain)
            throws SQLException {
        long started = System.nanoTime();
        // One transaction: if change 3 of 5 fails, changes 1 and 2 are undone too (rollback below).
        c.setAutoCommit(false);
        try {
            Map<String, Column> columns = new LinkedHashMap<>();
            s.columns().forEach(col -> columns.put(col.name(), col));
            Set<String> primaryKey = s.columns().stream()
                    .filter(Column::primaryKey)
                    .map(Column::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            String table = dialect.table(changes.schema(), changes.table());
            int rows = 0;
            for (int i = 0; i < changes.changes().size(); i++) {
                RowChange change = changes.changes().get(i);
                try {
                    rows += apply(c, dialect, table, columns, primaryKey, change, placeholder);
                } catch (SQLException e) {
                    throw new DataException(
                            explain.apply(e).getMessage() + " (" + where(i, changes, change) + ". Nothing was saved.)",
                            e);
                } catch (DataException e) {
                    throw new DataException(
                            e.getMessage() + " (" + where(i, changes, change) + ". Nothing was saved.)", e);
                }
            }
            c.commit();
            return new Applied(rows, Duration.ofNanos(System.nanoTime() - started));
        } catch (RuntimeException | SQLException e) {
            c.rollback();
            throw e;
        }
    }

    /** Runs one change and returns how many rows it touched. */
    private static int apply(
            Connection c,
            SqlDialect dialect,
            String table,
            Map<String, Column> columns,
            Set<String> primaryKey,
            RowChange change,
            Function<Column, String> placeholder)
            throws SQLException {
        List<Optional<String>> params = new ArrayList<>();
        String sql = switch (change) {
            case RowChange.Insert ins -> {
                if (ins.values().isEmpty()) {
                    // "default values" is standard SQL; MySQL spells it "() values ()".
                    yield dialect == SqlDialect.MYSQL
                            ? "insert into " + table + " () values ()"
                            : "insert into " + table + " default values";
                }
                List<String> names = new ArrayList<>();
                List<String> marks = new ArrayList<>();
                ins.values().forEach((name, value) -> {
                    Column col = column(columns, name);
                    names.add(dialect.name(col.name()));
                    marks.add(placeholder.apply(col));
                    params.add(value);
                });
                yield "insert into " + table + " (" + String.join(", ", names) + ") values (" + String.join(", ", marks)
                        + ")";
            }
            case RowChange.Update up -> {
                if (up.values().isEmpty()) {
                    yield null;
                }
                List<String> sets = new ArrayList<>();
                up.values().forEach((name, value) -> {
                    Column col = column(columns, name);
                    sets.add(dialect.name(col.name()) + " = " + placeholder.apply(col));
                    params.add(value);
                });
                yield "update " + table + " set " + String.join(", ", sets) + " where "
                        + key(dialect, columns, primaryKey, up.key(), params, placeholder);
            }
            case RowChange.Delete del ->
                "delete from " + table + " where " + key(dialect, columns, primaryKey, del.key(), params, placeholder);
        };
        if (sql == null) {
            return 0;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i).orElse(null)); // null here is SQL NULL
            }
            int count = ps.executeUpdate();
            if (!(change instanceof RowChange.Insert) && count != 1) {
                // The page the user edited is older than the table: another program changed or deleted the row.
                throw new DataException("that row is no longer in the table (found " + count
                        + " rows with this key). Something else changed or deleted it after you loaded the page:"
                        + " reload and try again");
            }
            return count;
        }
    }

    /** "id = ? and ..." for the full primary key, which must be exactly what was sent. */
    private static String key(
            SqlDialect dialect,
            Map<String, Column> columns,
            Set<String> primaryKey,
            Map<String, String> key,
            List<Optional<String>> params,
            Function<Column, String> placeholder) {
        if (primaryKey.isEmpty()) {
            throw new DataException("this table has no primary key, so DevDesk can't tell its rows apart to change one."
                    + " Use the SQL editor's tools, or add a primary key");
        }
        if (!new TreeSet<>(key.keySet()).equals(primaryKey)) {
            throw new DataException("a row must be named by its whole primary key " + primaryKey + ", got "
                    + new TreeSet<>(key.keySet()));
        }
        List<String> parts = new ArrayList<>();
        for (String name : primaryKey) {
            parts.add(dialect.name(name) + " = " + placeholder.apply(columns.get(name)));
            params.add(Optional.of(key.get(name)));
        }
        return String.join(" and ", parts);
    }

    /** Names from the app are checked against the catalog before they get anywhere near SQL. */
    private static Column column(Map<String, Column> columns, String name) {
        Column col = columns.get(name);
        if (col == null) {
            throw new DataException("the table has no column " + name);
        }
        return col;
    }

    /** "change 2 of 3: update of id = 7", so the user knows which edit to fix. */
    private static String where(int index, TableChanges all, RowChange change) {
        String what = switch (change) {
            case RowChange.Insert ins -> "new row";
            case RowChange.Update up -> "update of " + describe(up.key());
            case RowChange.Delete del -> "delete of " + describe(del.key());
        };
        return "change " + (index + 1) + " of " + all.changes().size() + ": " + what;
    }

    private static String describe(Map<String, String> key) {
        return key.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
