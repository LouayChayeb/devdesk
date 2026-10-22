package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.SqlBrowser.Column;
import io.github.louch.devdesk.core.data.SqlBrowser.Filter;
import io.github.louch.devdesk.core.data.SqlBrowser.ResultTable;
import io.github.louch.devdesk.core.data.SqlBrowser.RowQuery;
import io.github.louch.devdesk.core.data.SqlBrowser.Structure;
import io.github.louch.devdesk.core.data.SqlDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The JDBC steps every SQL database shares: building a table query, reading results as text. */
final class Jdbc {

    /** Longer cell values are cut when browsing, so one huge JSON column can't freeze the app (exports never cut). */
    static final int MAX_CELL = 10_000;

    private Jdbc() {}

    /**
     * "select * from t where ... order by ..." with its parameters. Every name was checked against the table's real
     * columns first ({@code s}); values only ever travel as parameters.
     *
     * @param placeholder the parameter for one column's value: "?" or e.g. "cast(? as integer)"
     */
    static String select(
            SqlDialect dialect, RowQuery q, Structure s, List<String> params, Function<Column, String> placeholder) {
        Map<String, Column> columns = new LinkedHashMap<>();
        s.columns().forEach(col -> columns.put(col.name(), col));
        StringBuilder sql = new StringBuilder("select * from " + dialect.table(q.schema(), q.table()));
        List<String> where = new ArrayList<>();
        for (Filter f : q.filters()) {
            Column col = columns.get(f.column());
            if (col == null) {
                throw new DataException(q.table() + " has no column " + f.column());
            }
            where.add(condition(dialect, col, f, params, placeholder));
        }
        if (!where.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", where));
        }
        if (q.orderBy().isPresent()) {
            if (!columns.containsKey(q.orderBy().get())) {
                throw new DataException(
                        q.table() + " has no column " + q.orderBy().get());
            }
            sql.append(" order by ").append(dialect.name(q.orderBy().get())).append(q.descending() ? " desc" : " asc");
        } else {
            // Without "order by" the database may return rows in any order: an updated row moves (Postgres writes a
            // new row version elsewhere), and offset paging can show a row twice or skip it. The key keeps it stable.
            List<String> key = s.columns().stream()
                    .filter(Column::primaryKey)
                    .map(col -> dialect.name(col.name()))
                    .toList();
            if (!key.isEmpty()) {
                sql.append(" order by ").append(String.join(", ", key));
            }
        }
        return sql.toString();
    }

    /** One filter as SQL; "contains" ignores case and treats % and _ as plain characters. */
    private static String condition(
            SqlDialect dialect, Column col, Filter f, List<String> params, Function<Column, String> placeholder) {
        String name = dialect.name(col.name());
        return switch (f.op()) {
            case IS_NULL -> name + " is null";
            case IS_NOT_NULL -> name + " is not null";
            case CONTAINS -> {
                params.add("%"
                        + f.value().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                yield switch (dialect) {
                    case POSTGRES -> name + "::text ilike ?";
                    // MySQL's default collations already ignore case; backslash is its default LIKE escape.
                    case MYSQL -> name + " like ?";
                    case SQLITE -> name + " like ? escape '\\'";
                };
            }
            case EQUALS, NOT_EQUALS, LESS, GREATER -> {
                params.add(f.value());
                String op = switch (f.op()) {
                    case EQUALS -> " = ";
                    case NOT_EQUALS -> " <> ";
                    case LESS -> " < ";
                    default -> " > ";
                };
                yield name + op + placeholder.apply(col);
            }
        };
    }

    static PreparedStatement prepare(Connection c, String sql, List<String> params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) {
            ps.setString(i + 1, params.get(i));
        }
        return ps;
    }

    /** Runs typed SQL: rows if it returns some, else the update count. */
    static ResultTable run(Connection c, String sql, int maxRows) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setMaxRows(maxRows + 1);
            long started = System.nanoTime();
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    return read(rs, maxRows, started);
                }
            }
            return new ResultTable(
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    st.getUpdateCount(),
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /** At most {@code limit} rows as text, as the database's own client shows them; null stays null. */
    static ResultTable read(ResultSet rs, int limit, long started) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            names.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
        }
        List<List<String>> rows = new ArrayList<>();
        boolean more = false;
        while (rs.next()) {
            if (rows.size() == limit) {
                more = true;
                break;
            }
            List<String> row = new ArrayList<>(names.size());
            for (int i = 1; i <= names.size(); i++) {
                String v = rs.getString(i);
                row.add(v != null && v.length() > MAX_CELL ? v.substring(0, MAX_CELL) + "…" : v);
            }
            rows.add(row);
        }
        return new ResultTable(names, types, rows, more, -1, Duration.ofNanos(System.nanoTime() - started));
    }
}
