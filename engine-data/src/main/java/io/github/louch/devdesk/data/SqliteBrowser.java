package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseStats;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.QueryPlan;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.data.SqlDialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sqlite.SQLiteConfig;

/**
 * Reads a SQLite file. SQLite runs inside DevDesk (no server): the file is opened read-only by SQLite itself, so
 * browsing can't change it even while the app that owns it is writing.
 */
public final class SqliteBrowser implements SqlBrowser {

    /** SQLite has one schema per file, called "main". */
    static final String MAIN = "main";

    @Override
    public List<Table> tables(DatabaseTarget target) {
        String sql = "select name, type from sqlite_master where type in ('table', 'view') and name not like 'sqlite_%'"
                + " order by name";
        try (Connection c = connect(target, true);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<Table> tables = new ArrayList<>();
            while (rs.next()) {
                boolean view = rs.getString(2).equals("view");
                tables.add(new Table(MAIN, rs.getString(1), view, view ? -1 : count(c, rs.getString(1))));
            }
            return tables;
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** SQLite keeps no row estimate; counting a local file is fast enough. */
    private static long count(Connection c, String table) {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select count(*) from " + SqlDialect.SQLITE.name(table))) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    @Override
    public Optional<Structure> structure(DatabaseTarget target, String schema, String table) {
        try (Connection c = connect(target, true)) {
            return structure(c, table);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** From the pragma_* table functions, which (unlike PRAGMA statements) take the table name as a parameter. */
    static Optional<Structure> structure(Connection c, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement("select name, type, \"notnull\", dflt_value, pk from pragma_table_info(?)")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(new Column(
                            rs.getString(1),
                            rs.getString(2).isEmpty() ? "any" : rs.getString(2).toLowerCase(),
                            rs.getInt(3) == 0,
                            Optional.ofNullable(rs.getString(4)),
                            rs.getInt(5) > 0));
                }
            }
        }
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        List<Index> indexes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select l.name, l."unique", group_concat(i.name, char(31))
                from pragma_index_list(?) l join pragma_index_info(l.name) i
                group by l.name, l."unique" order by l.name
                """)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cols = Optional.ofNullable(rs.getString(3)).orElse("");
                    indexes.add(new Index(rs.getString(1), List.of(cols.split("\u001f")), rs.getInt(2) == 1));
                }
            }
        }
        Map<Integer, ForeignKey> keys = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "select id, \"table\", \"from\", \"to\" from pragma_foreign_key_list(?) order by id, seq")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    ForeignKey before = keys.get(id);
                    List<String> from = new ArrayList<>(before == null ? List.of() : before.columns());
                    List<String> to = new ArrayList<>(before == null ? List.of() : before.toColumns());
                    from.add(rs.getString(3));
                    // "references users" without a column means the other table's primary key: SQLite leaves it null.
                    to.add(Optional.ofNullable(rs.getString(4)).orElse("rowid"));
                    keys.put(id, new ForeignKey("fk_" + table + "_" + id, from, MAIN, rs.getString(2), to));
                }
            }
        }
        return Optional.of(new Structure(columns, indexes, List.copyOf(keys.values())));
    }

    @Override
    public ResultTable rows(DatabaseTarget target, RowQuery q) {
        try (Connection c = connect(target, true)) {
            List<String> params = new ArrayList<>();
            String sql = select(c, q, params) + " limit " + (q.limit() + 1) + " offset " + Math.max(0, q.offset());
            long started = System.nanoTime();
            try (PreparedStatement ps = Jdbc.prepare(c, sql, params);
                    ResultSet rs = ps.executeQuery()) {
                return Jdbc.read(rs, q.limit(), started);
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public void each(DatabaseTarget target, RowQuery q, RowSink sink) {
        try (Connection c = connect(target, true)) {
            List<String> params = new ArrayList<>();
            try (PreparedStatement ps = Jdbc.prepare(c, select(c, q, params), params);
                    ResultSet rs = ps.executeQuery()) {
                PostgresBrowser.stream(rs, sink); // SQLite steps through rows one by one anyway
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public void each(DatabaseTarget target, String sql, RowSink sink) {
        try (Connection c = connect(target, true);
                Statement st = c.createStatement()) {
            if (!st.execute(sql)) {
                throw new DataException("this statement returns no rows, so there is nothing to export");
            }
            try (ResultSet rs = st.getResultSet()) {
                PostgresBrowser.stream(rs, sink);
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    private static String select(Connection c, RowQuery q, List<String> params) throws SQLException {
        Structure s = structure(c, q.table()).orElseThrow(() -> new DataException("there is no table " + q.table()));
        return Jdbc.select(SqlDialect.SQLITE, q, s, params, col -> "?");
    }

    @Override
    public ResultTable run(DatabaseTarget target, String sql, int maxRows) {
        try (Connection c = connect(target, true)) {
            return Jdbc.run(c, sql, maxRows);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public QueryPlan explain(DatabaseTarget target, String sql, boolean analyze) {
        if (analyze) {
            throw new DataException("SQLite can't run a query with measurements (it has no EXPLAIN ANALYZE): use Plan");
        }
        String body = sql.strip().replaceFirst(";+$", "");
        if (body.regionMatches(true, 0, "explain", 0, 7)) {
            throw new DataException("leave out EXPLAIN: the Plan button adds it");
        }
        try (Connection c = connect(target, true);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("explain query plan " + body)) {
            return SqlitePlans.parse(rs);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** A file has no other sessions to list: SQLite has no server. */
    @Override
    public DatabaseStats stats(DatabaseTarget target) {
        try (Connection c = connect(target, true)) {
            Map<String, Long> bytes = new HashMap<>();
            // dbstat (a virtual table compiled into most SQLite builds) gives each table's and index's bytes.
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("select name, sum(pgsize) from dbstat group by name")) {
                while (rs.next()) {
                    bytes.put(rs.getString(1), rs.getLong(2));
                }
            } catch (SQLException e) {
                // no dbstat in this build: sizes stay unknown
            }
            Map<String, List<String>> indexes = new HashMap<>();
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("select tbl_name, name from sqlite_master where type = 'index'")) {
                while (rs.next()) {
                    indexes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
            List<DatabaseStats.TableStats> tables = new ArrayList<>();
            for (Table t : tables(target)) {
                if (t.view()) {
                    continue;
                }
                long index = indexes.getOrDefault(t.name(), List.of()).stream()
                        .mapToLong(i -> bytes.getOrDefault(i, 0L))
                        .sum();
                long data = bytes.getOrDefault(t.name(), -1L);
                tables.add(new DatabaseStats.TableStats(
                        MAIN,
                        t.name(),
                        t.estimatedRows(),
                        data < 0 ? -1 : data + index,
                        bytes.isEmpty() ? -1 : index,
                        -1,
                        -1,
                        -1,
                        Optional.empty(),
                        Optional.empty()));
            }
            tables.sort((a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));
            long size;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "select page_count * page_size from pragma_page_count(), pragma_page_size()")) {
                size = rs.next() ? rs.getLong(1) : -1;
            }
            return new DatabaseStats(size, tables, List.of()); // SQLite has no users: the file's permissions decide
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public List<Session> activity(DatabaseTarget target) {
        return List.of();
    }

    /** {@code readOnly} is false only for {@link SqliteEditor}. */
    static Connection connect(DatabaseTarget t, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        // Opened read-only by SQLite itself (no write can happen); never created when the file is missing.
        config.setReadOnly(readOnly);
        config.setOpenMode(org.sqlite.SQLiteOpenMode.OPEN_URI);
        // The app that owns the file may be writing: wait up to 3 s for its lock instead of failing at once.
        config.setBusyTimeout(3000);
        config.enforceForeignKeys(true);
        if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(t.database()))) {
            throw new DataException("the file " + t.database() + " is gone (moved or deleted?)");
        }
        return DriverManager.getConnection("jdbc:sqlite:" + t.database(), config.toProperties());
    }

    static DataException explain(DatabaseTarget t, SQLException e) {
        String message = Optional.ofNullable(e.getMessage()).orElse(e.toString());
        String hint = "";
        if (message.contains("SQLITE_READONLY") || message.contains("readonly")) {
            hint = " The SQL editor is read-only. To change rows, open the table and edit them in the grid, then Save.";
        } else if (message.contains("SQLITE_BUSY") || message.contains("locked")) {
            hint = " Another program (probably your app) holds a write lock on " + t.database()
                    + " right now. Try again in a moment.";
        }
        return new DataException(message + hint, e);
    }
}
