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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads MySQL and MariaDB over JDBC (MariaDB's driver speaks to both). Like {@link PostgresBrowser}: one short session
 * per call, which the server itself keeps read-only ({@code SET SESSION TRANSACTION READ ONLY}).
 */
public final class MysqlBrowser implements SqlBrowser {

    /** The server's own schemas, hidden from the table list. */
    static final String SYSTEM_SCHEMAS = "('mysql', 'information_schema', 'performance_schema', 'sys')";

    @Override
    public List<Table> tables(DatabaseTarget target) {
        String sql = "select table_schema, table_name, table_type, table_rows from information_schema.tables"
                + " where table_schema not in " + SYSTEM_SCHEMAS + " order by table_schema, table_name";
        try (Connection c = connect(target, true);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<Table> tables = new ArrayList<>();
            while (rs.next()) {
                boolean view = rs.getString(3).equals("VIEW");
                tables.add(new Table(rs.getString(1), rs.getString(2), view, view ? -1 : rs.getLong(4)));
            }
            // InnoDB's table_rows is only an estimate (often off by 40%); small tables get an exact count.
            List<Table> counted = new ArrayList<>();
            for (Table t : tables) {
                counted.add(!t.view() && t.estimatedRows() < PostgresBrowser.EXACT_COUNT_BELOW ? withCount(c, t) : t);
            }
            return counted;
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    private static Table withCount(Connection c, Table t) {
        try (Statement st = c.createStatement();
                ResultSet rs =
                        st.executeQuery("select count(*) from " + SqlDialect.MYSQL.table(t.schema(), t.name()))) {
            rs.next();
            return new Table(t.schema(), t.name(), false, rs.getLong(1));
        } catch (SQLException e) {
            return t;
        }
    }

    @Override
    public Optional<Structure> structure(DatabaseTarget target, String schema, String table) {
        try (Connection c = connect(target, true)) {
            return structure(c, schema, table);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    static Optional<Structure> structure(Connection c, String schema, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select column_name, column_type, is_nullable, column_default, column_key, extra
                from information_schema.columns where table_schema = ? and table_name = ? order by ordinal_position
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // auto_increment has no "default" in MySQL's catalog, but for the grid it is one: leave it empty.
                    String extra = Optional.ofNullable(rs.getString(6)).orElse("");
                    Optional<String> dflt = Optional.ofNullable(rs.getString(4))
                            .or(() -> extra.contains("auto_increment")
                                    ? Optional.of("auto_increment")
                                    : Optional.empty());
                    columns.add(new Column(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3).equals("YES"),
                            dflt,
                            rs.getString(5).equals("PRI")));
                }
            }
        }
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Index> indexes = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select index_name, non_unique, column_name from information_schema.statistics
                where table_schema = ? and table_name = ? order by index_name, seq_in_index
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Index before = indexes.get(name);
                    List<String> cols = new ArrayList<>(before == null ? List.of() : before.columns());
                    cols.add(rs.getString(3));
                    indexes.put(name, new Index(name, cols, rs.getInt(2) == 0));
                }
            }
        }
        Map<String, ForeignKey> keys = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select constraint_name, column_name, referenced_table_schema, referenced_table_name,
                       referenced_column_name
                from information_schema.key_column_usage
                where table_schema = ? and table_name = ? and referenced_table_name is not null
                order by constraint_name, ordinal_position
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    ForeignKey before = keys.get(name);
                    List<String> from = new ArrayList<>(before == null ? List.of() : before.columns());
                    List<String> to = new ArrayList<>(before == null ? List.of() : before.toColumns());
                    from.add(rs.getString(2));
                    to.add(rs.getString(5));
                    keys.put(name, new ForeignKey(name, from, rs.getString(3), rs.getString(4), to));
                }
            }
        }
        return Optional.of(new Structure(columns, List.copyOf(indexes.values()), List.copyOf(keys.values())));
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
            try (PreparedStatement ps = Jdbc.prepare(c, select(c, q, params), params)) {
                ps.setFetchSize(1000); // the MariaDB driver streams rows with a fetch size, instead of loading all
                try (ResultSet rs = ps.executeQuery()) {
                    PostgresBrowser.stream(rs, sink);
                }
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public void each(DatabaseTarget target, String sql, RowSink sink) {
        try (Connection c = connect(target, true);
                Statement st = c.createStatement()) {
            st.setFetchSize(1000);
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
        Structure s = structure(c, q.schema(), q.table())
                .orElseThrow(() -> new DataException("there is no table " + q.schema() + "." + q.table()));
        // MySQL converts the text parameter to the column's type itself, so no cast is needed.
        return Jdbc.select(SqlDialect.MYSQL, q, s, params, col -> "?");
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
        String body = sql.strip().replaceFirst(";+$", "");
        if (body.regionMatches(true, 0, "explain", 0, 7) || body.regionMatches(true, 0, "analyze", 0, 7)) {
            throw new DataException("leave out EXPLAIN: the Plan button adds it (and ANALYZE when you ask for it)");
        }
        try (Connection c = connect(target, true);
                Statement st = c.createStatement()) {
            if (isMariaDb(c)) {
                // MariaDB has no tree format: its table-shaped plan, with real row counts when analyzing.
                try (ResultSet rs = st.executeQuery((analyze ? "analyze " : "explain ") + body)) {
                    return MysqlPlans.fromTable(rs);
                }
            }
            // MySQL 8.0.18+: the plan as an indented tree; with ANALYZE it also runs the query and adds real numbers.
            try (ResultSet rs = st.executeQuery((analyze ? "explain analyze " : "explain format=tree ") + body)) {
                rs.next();
                return MysqlPlans.fromTree(rs.getString(1));
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    static boolean isMariaDb(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductVersion().contains("MariaDB");
    }

    @Override
    public DatabaseStats stats(DatabaseTarget target) {
        // Sizes from information_schema; full-table reads ("no index used") from performance_schema, when it's on.
        String sql = "select table_schema, table_name, coalesce(table_rows, 0), data_length + index_length,"
                + " index_length from information_schema.tables"
                + " where table_type = 'BASE TABLE' and table_schema not in " + SYSTEM_SCHEMAS
                + " order by data_length + index_length desc, table_schema, table_name";
        try (Connection c = connect(target, true)) {
            Map<String, long[]> scans = scans(c);
            List<DatabaseStats.TableStats> tables = new ArrayList<>();
            long total = 0;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    long[] s = scans.getOrDefault(rs.getString(1) + "." + rs.getString(2), new long[] {-1, -1});
                    total += rs.getLong(4);
                    tables.add(new DatabaseStats.TableStats(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getLong(3),
                            rs.getLong(4),
                            rs.getLong(5),
                            s[0],
                            s[1],
                            -1, // InnoDB purges old row versions itself: there's no "dead rows" count
                            Optional.empty(),
                            Optional.empty()));
                }
            }
            return new DatabaseStats(total, tables, users(c));
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** "schema.table" → {reads without an index, reads through one}; empty when performance_schema is off. */
    private static Map<String, long[]> scans(Connection c) {
        Map<String, long[]> out = new HashMap<>();
        String sql = "select object_schema, object_name, sum(if(index_name is null, count_read, 0)),"
                + " sum(if(index_name is null, 0, count_read)) from performance_schema.table_io_waits_summary_by_index_usage"
                + " where object_schema not in " + SYSTEM_SCHEMAS + " group by object_schema, object_name";
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1) + "." + rs.getString(2), new long[] {rs.getLong(3), rs.getLong(4)});
            }
        } catch (SQLException e) {
            // performance_schema off, or no right to read it: the numbers stay unknown (-1)
        }
        return out;
    }

    /** Accounts and their GRANT lines; needs a user allowed to read mysql.user (DevDesk connects as root if it can). */
    private static List<DatabaseStats.Role> users(Connection c) {
        List<DatabaseStats.Role> out = new ArrayList<>();
        List<String[]> accounts = new ArrayList<>();
        // MySQL has account_locked; MariaDB's mysql.user (a view since 10.4) may not: fall back to fewer columns.
        for (String sql : List.of(
                "select user, host, super_priv, account_locked from mysql.user where user not like 'mysql.%'"
                        + " order by user, host",
                "select user, host, super_priv, 'N' from mysql.user where user not like 'mysql.%' order by user, host")) {
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    accounts.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                }
                break;
            } catch (SQLException e) {
                accounts.clear(); // try the next form; none works = not allowed to read the accounts
            }
        }
        for (String[] a : accounts) {
            List<String> grants = new ArrayList<>();
            // SHOW GRANTS can't take parameters; the names come from mysql.user and are quoted here.
            String who = "'" + a[0].replace("'", "''") + "'@'" + a[1].replace("'", "''") + "'";
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("show grants for " + who)) {
                while (rs.next()) {
                    grants.add(rs.getString(1));
                }
            } catch (SQLException e) {
                grants.add("(couldn't read: " + e.getMessage() + ")");
            }
            List<String> attributes = new ArrayList<>();
            if ("Y".equals(a[2])) {
                attributes.add("superuser");
            }
            if ("Y".equals(a[3])) {
                attributes.add("locked");
            }
            attributes.add("from host " + a[1]);
            out.add(new DatabaseStats.Role(a[0] + "@" + a[1], attributes, List.of(), grants));
        }
        return out;
    }

    @Override
    public List<Session> activity(DatabaseTarget target) {
        // The process list, joined to InnoDB's open transactions: a "Sleep" connection holding a transaction is the
        // MySQL version of Postgres's "idle in transaction" (it keeps its locks while doing nothing).
        String sql = """
                select p.id, p.user, coalesce(p.db, ''), p.host, p.command, p.time, p.state, coalesce(p.info, ''),
                       timestampdiff(microsecond, t.trx_started, now(6)) div 1000
                from information_schema.processlist p
                left join information_schema.innodb_trx t on t.trx_mysql_thread_id = p.id
                where p.id <> connection_id() and p.command not in ('Daemon', 'Binlog Dump')
                order by p.command = 'Sleep', p.time desc
                """;
        try (Connection c = connect(target, true);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<Session> out = new ArrayList<>();
            while (rs.next()) {
                String command = rs.getString(5);
                long trxMs = rs.getLong(9);
                boolean inTransaction = !rs.wasNull();
                String state = command.equals("Query")
                        ? "active"
                        : command.equals("Sleep") ? (inTransaction ? "idle in transaction" : "idle") : command;
                Optional<Duration> running = command.equals("Query")
                        ? Optional.of(Duration.ofSeconds(rs.getLong(6)))
                        : inTransaction ? Optional.of(Duration.ofMillis(trxMs)) : Optional.empty();
                String waiting = Optional.ofNullable(rs.getString(7)).orElse("");
                out.add(new Session(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        state,
                        running,
                        waiting.startsWith("Waiting") ? Optional.of(waiting) : Optional.empty(),
                        rs.getString(8)));
            }
            return out;
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** A query typed in the editor may run this long; then the server stops it. */
    static final Duration STATEMENT_TIMEOUT = PostgresBrowser.STATEMENT_TIMEOUT;

    /** {@code readOnly} is false only for {@link MysqlEditor}, the explicit write path. */
    static Connection connect(DatabaseTarget t, boolean readOnly) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", t.user());
        p.setProperty("password", t.password());
        p.setProperty("connectTimeout", "3000");
        // MySQL 8+'s default login (caching_sha2_password) needs the server's key when the connection isn't TLS.
        p.setProperty("allowPublicKeyRetrieval", "true");
        p.setProperty("connectionAttributes", "program_name:DevDesk"); // shows in performance_schema
        String url = "jdbc:mariadb://" + t.host() + ":" + t.port() + "/" + t.database();
        Connection c = DriverManager.getConnection(url, p);
        try (Statement st = c.createStatement()) {
            if (readOnly) {
                // Enforced by the server: every statement in this session runs in a read-only transaction.
                st.execute("set session transaction read only");
            }
            // The time limit is spelled differently: MySQL (selects only, ms) and MariaDB (any statement, seconds).
            st.execute(
                    isMariaDb(c)
                            ? "set session max_statement_time = " + STATEMENT_TIMEOUT.toSeconds()
                            : "set session max_execution_time = " + STATEMENT_TIMEOUT.toMillis());
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /** The server's message, plus what DevDesk knows about the likely cause (principle 9). */
    static DataException explain(DatabaseTarget t, SQLException e) {
        String message = Optional.ofNullable(e.getMessage()).orElse(e.toString());
        String hint = switch (e.getErrorCode()) {
            case 1045 ->
                " DevDesk logged in as " + t.user() + " with the password from " + t.service() + "'s environment in"
                        + " devdesk.yaml (MYSQL_ROOT_PASSWORD, or MYSQL_USER/MYSQL_PASSWORD). If the data volume was"
                        + " created with another password, the variable no longer applies: it is only read on the very"
                        + " first start.";
            case 1049 -> " The database " + t.database() + " doesn't exist (MYSQL_DATABASE in devdesk.yaml).";
            case 1792 ->
                " The SQL editor is read-only. To change rows, open the table and edit them in the grid, then Save.";
            case 3024, 1969 -> " The query ran longer than " + STATEMENT_TIMEOUT.toSeconds() + " s and was stopped.";
            default -> "";
        };
        if (hint.isEmpty() && "08000".equals(e.getSQLState()) || message.contains("Could not connect")) {
            hint = " Can't reach " + t.service() + " at " + t.host() + ":" + t.port()
                    + ". Is it running? Start it in Services, or see Diagnosis.";
        }
        return new DataException(message + hint, e);
    }
}
