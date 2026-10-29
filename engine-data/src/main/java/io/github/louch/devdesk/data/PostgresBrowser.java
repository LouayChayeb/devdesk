package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseStats;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.QueryPlan;
import io.github.louch.devdesk.core.data.SlowQueries;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.data.SqlDialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads PostgreSQL over JDBC. Every call opens a short read-only session (Postgres itself refuses writes in it), with
 * a time limit, and closes it again: simple, and local databases answer in milliseconds.
 */
public final class PostgresBrowser implements SqlBrowser {

    /** A query typed in the editor may run this long before Postgres cancels it. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(15);
    /** Tables up to this estimated size get an exact count(*); bigger ones keep the planner's estimate. */
    static final long EXACT_COUNT_BELOW = 200_000;

    @Override
    public List<Table> tables(DatabaseTarget target) {
        String sql = """
                select n.nspname, c.relname, c.relkind, c.reltuples
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r', 'p', 'v', 'm')
                  and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg_toast%'
                order by n.nspname, c.relname
                """;
        try (Connection c = connect(target);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<Table> tables = new ArrayList<>();
            while (rs.next()) {
                boolean view = rs.getString(3).equals("v") || rs.getString(3).equals("m");
                tables.add(new Table(rs.getString(1), rs.getString(2), view, Math.round(rs.getDouble(4))));
            }
            // reltuples is -1 until a table was analyzed; small tables get an exact count instead (cheap locally).
            List<Table> counted = new ArrayList<>();
            for (Table t : tables) {
                counted.add(!t.view() && t.estimatedRows() < EXACT_COUNT_BELOW ? withCount(c, t) : t);
            }
            return counted;
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    private static Table withCount(Connection c, Table t) {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select count(*) from " + quote(t.schema()) + "." + quote(t.name()))) {
            rs.next();
            return new Table(t.schema(), t.name(), false, rs.getLong(1));
        } catch (SQLException e) {
            return t; // no permission on this table: keep the estimate
        }
    }

    @Override
    public Optional<Structure> structure(DatabaseTarget target, String schema, String table) {
        try (Connection c = connect(target)) {
            return structure(c, schema, table);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    static Optional<Structure> structure(Connection c, String schema, String table) throws SQLException {
        // to_regclass returns null for a missing table; a ::regclass cast would fail the whole statement instead.
        if (list(c, "select to_regclass(quote_ident(?) || '.' || quote_ident(?))::text", schema, table)
                        .getFirst()
                == null) {
            return Optional.empty();
        }
        List<String> primary = list(c, """
                select a.attname from pg_index i
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
                where i.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass and i.indisprimary
                """, schema, table);
        List<Column> columns = new ArrayList<>();
        // format_type writes the type as it would be declared: "numeric(10,2)", "character varying(20)", "integer[]".
        // An identity column ("id int generated always as identity") has no default in pg_attrdef, but the database
        // fills it like one: reported as one, so new rows leave it alone.
        try (PreparedStatement ps = c.prepareStatement("""
                select a.attname, format_type(a.atttypid, a.atttypmod), not a.attnotnull,
                       coalesce(pg_get_expr(d.adbin, d.adrelid),
                                case when a.attidentity <> '' then 'generated as identity' end)
                from pg_attribute a left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where a.attrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass and a.attnum > 0
                  and not a.attisdropped
                order by a.attnum
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(new Column(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getBoolean(3),
                            Optional.ofNullable(rs.getString(4)),
                            primary.contains(rs.getString(1))));
                }
            }
        }
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        List<Index> indexes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select ic.relname, i.indisunique,
                       array(select a.attname from unnest(i.indkey) with ordinality k(n, o)
                             join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.n order by k.o)
                from pg_index i join pg_class ic on ic.oid = i.indexrelid
                where i.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass order by ic.relname
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(new Index(rs.getString(1), strings(rs, 3), rs.getBoolean(2)));
                }
            }
        }
        List<ForeignKey> keys = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select con.conname, tn.nspname, tc.relname,
                       array(select a.attname from unnest(con.conkey) with ordinality k(n, o)
                             join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.n order by k.o),
                       array(select a.attname from unnest(con.confkey) with ordinality k(n, o)
                             join pg_attribute a on a.attrelid = con.confrelid and a.attnum = k.n order by k.o)
                from pg_constraint con
                join pg_class tc on tc.oid = con.confrelid join pg_namespace tn on tn.oid = tc.relnamespace
                where con.contype = 'f' and con.conrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass
                order by con.conname
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(new ForeignKey(
                            rs.getString(1), strings(rs, 4), rs.getString(2), rs.getString(3), strings(rs, 5)));
                }
            }
        }
        return Optional.of(new Structure(columns, indexes, keys));
    }

    @Override
    public ResultTable rows(DatabaseTarget target, RowQuery q) {
        try (Connection c = connect(target)) {
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
        try (Connection c = connect(target)) {
            List<String> params = new ArrayList<>();
            String sql = select(c, q, params);
            // pgjdbc only streams rows (instead of loading all of them) inside a transaction with a fetch size.
            c.setAutoCommit(false);
            try (PreparedStatement ps = Jdbc.prepare(c, sql, params)) {
                ps.setFetchSize(1000);
                try (ResultSet rs = ps.executeQuery()) {
                    stream(rs, sink);
                }
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public void each(DatabaseTarget target, String sql, RowSink sink) {
        try (Connection c = connect(target)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.setFetchSize(1000);
                if (!st.execute(sql)) {
                    throw new DataException("this statement returns no rows, so there is nothing to export");
                }
                try (ResultSet rs = st.getResultSet()) {
                    stream(rs, sink);
                }
            }
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** Whole values, no cutting: an export must be exact. */
    static void stream(ResultSet rs, RowSink sink) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            names.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
        }
        sink.columns(names, types);
        while (rs.next()) {
            List<String> row = new ArrayList<>(names.size());
            for (int i = 1; i <= names.size(); i++) {
                row.add(rs.getString(i));
            }
            sink.row(row);
        }
    }

    /** "select * from t where ... order by ..." with its parameters, the names checked against the catalog first. */
    private static String select(Connection c, RowQuery q, List<String> params) throws SQLException {
        Structure s = structure(c, q.schema(), q.table())
                .orElseThrow(() -> new DataException("there is no table " + q.schema() + "." + q.table()));
        // Values are cast to the column's own type so "id > 9" compares numbers (the type name is the catalog's).
        return Jdbc.select(SqlDialect.POSTGRES, q, s, params, col -> "cast(? as " + col.type() + ")");
    }

    @Override
    public ResultTable run(DatabaseTarget target, String sql, int maxRows) {
        try (Connection c = connect(target)) {
            return Jdbc.run(c, sql, maxRows);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public QueryPlan explain(DatabaseTarget target, String sql, boolean analyze) {
        String body = sql.strip().replaceFirst(";+$", "");
        if (body.regionMatches(true, 0, "explain", 0, 7)) {
            throw new DataException("leave out EXPLAIN: the Plan button adds it (and ANALYZE when you ask for it)");
        }
        // ANALYZE really runs the query, but the session is read-only, so an insert or delete fails instead.
        String options = analyze ? "(format json, analyze, buffers)" : "(format json)";
        try (Connection c = connect(target);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("explain " + options + " " + body)) {
            rs.next();
            return PostgresPlans.parse(rs.getString(1));
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    @Override
    public DatabaseStats stats(DatabaseTarget target) {
        try (Connection c = connect(target)) {
            long size;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("select pg_database_size(current_database())")) {
                rs.next();
                size = rs.getLong(1);
            }
            return new DatabaseStats(size, tableStats(c), roles(c));
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** At most this many query shapes: the slow ones are at the top, the rest is noise. */
    static final int SLOW_QUERIES_SHOWN = 100;

    /** Queries about Postgres itself (catalogs, settings, sessions), not about the app's data. */
    static final String SYSTEM_QUERIES = "\\m(pg_catalog|information_schema|pg_class|pg_namespace|pg_attribute"
            + "|pg_attrdef|pg_index|pg_constraint|pg_extension|pg_database|pg_roles|pg_settings|pg_stat_[a-z_]+"
            + "|pg_statio_[a-z_]+|pg_locks|pg_type|pg_proc)\\M|current_setting\\(|pg_reload_conf|^\\s*(alter\\s+system|set|show|begin"
            + "|commit|rollback|reset|discard|deallocate)\\M";

    @Override
    public SlowQueries slowQueries(DatabaseTarget target) {
        try (Connection c = connect(target)) {
            // The library must be loaded when the server starts (shared_preload_libraries), then the extension
            // created in the database: two separate steps, so DevDesk says which one is missing.
            if (!String.join(",", list(c, "select current_setting('shared_preload_libraries')"))
                    .contains("pg_stat_statements")) {
                return SlowQueries.notReady(SlowQueries.Status.NOT_LOADED);
            }
            if (list(c, "select 1 from pg_extension where extname = 'pg_stat_statements'")
                    .isEmpty()) {
                return SlowQueries.notReady(SlowQueries.Status.NOT_CREATED);
            }
            List<SlowQueries.Statement> out = new ArrayList<>();
            // Only this database's queries. pg_stat_statements doesn't keep who ran a query, so DevDesk's own reads
            // (and any tool's) on Postgres's system catalogs and settings are left out by their text: an app's slow
            // query is about its own tables.
            try (PreparedStatement ps = c.prepareStatement("""
                    select query, calls, total_exec_time, mean_exec_time, max_exec_time, rows
                    from pg_stat_statements
                    where dbid = (select oid from pg_database where datname = current_database())
                      and query !~* ?
                    order by total_exec_time desc
                    limit ?
                    """)) {
                ps.setString(1, SYSTEM_QUERIES);
                ps.setInt(2, SLOW_QUERIES_SHOWN);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String q = rs.getString(1);
                        out.add(new SlowQueries.Statement(
                                q,
                                rs.getLong(2),
                                rs.getDouble(3),
                                rs.getDouble(4),
                                rs.getDouble(5),
                                rs.getLong(6),
                                SlowQueries.shape(q)));
                    }
                }
            }
            Optional<Instant> since = Optional.empty();
            try {
                since = list(c, "select stats_reset::text from pg_stat_statements_info").stream()
                        .findFirst()
                        .map(t -> java.time.OffsetDateTime.parse(
                                        t.replace(' ', 'T').replaceFirst("([+-]\\d\\d)$", "$1:00"))
                                .toInstant());
            } catch (SQLException | RuntimeException e) {
                // pg_stat_statements_info is Postgres 14+: older ones just don't say since when
            }
            return new SlowQueries(SlowQueries.Status.READY, out, since);
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    /** pg_stat_user_tables counts since the statistics were last reset; n_live_tup is better than reltuples. */
    private static List<DatabaseStats.TableStats> tableStats(Connection c) throws SQLException {
        String sql = """
                select n.nspname, c.relname, coalesce(s.n_live_tup, greatest(c.reltuples, 0)::bigint),
                       pg_total_relation_size(c.oid), pg_indexes_size(c.oid),
                       coalesce(s.seq_scan, -1), coalesce(s.idx_scan, -1), coalesce(s.n_dead_tup, -1),
                       greatest(s.last_vacuum, s.last_autovacuum), greatest(s.last_analyze, s.last_autoanalyze)
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                     left join pg_stat_user_tables s on s.relid = c.oid
                where c.relkind in ('r', 'p')
                  and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg_toast%'
                order by pg_total_relation_size(c.oid) desc, n.nspname, c.relname
                """;
        List<DatabaseStats.TableStats> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new DatabaseStats.TableStats(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getLong(6),
                        rs.getLong(7),
                        rs.getLong(8),
                        Optional.ofNullable(rs.getTimestamp(9)).map(java.sql.Timestamp::toInstant),
                        Optional.ofNullable(rs.getTimestamp(10)).map(java.sql.Timestamp::toInstant)));
            }
        }
        return out;
    }

    /** Roles, without Postgres's own pg_* ones, with their table rights (the owner's implicit rights aren't listed). */
    private static List<DatabaseStats.Role> roles(Connection c) throws SQLException {
        String sql = """
                select r.rolname, r.rolsuper, r.rolcanlogin, r.rolcreatedb, r.rolcreaterole, r.rolreplication,
                       r.rolbypassrls,
                       coalesce((select string_agg(b.rolname, ',' order by b.rolname) from pg_auth_members m
                                 join pg_roles b on b.oid = m.roleid where m.member = r.oid), ''),
                       (select count(*) from pg_class t join pg_namespace n on n.oid = t.relnamespace
                        where t.relowner = r.oid and t.relkind in ('r', 'p', 'v', 'm')
                          and n.nspname not in ('pg_catalog', 'information_schema'))
                from pg_roles r where r.rolname not like 'pg\\_%' order by r.rolname
                """;
        Map<String, List<String>> grants = new HashMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("""
                        select grantee, table_schema || '.' || table_name,
                               string_agg(privilege_type, ', ' order by privilege_type)
                        from information_schema.role_table_grants
                        where table_schema not in ('pg_catalog', 'information_schema')
                        group by 1, 2 order by 1, 2
                        """)) {
            while (rs.next()) {
                grants.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(rs.getString(2) + ": " + rs.getString(3));
            }
        }
        List<DatabaseStats.Role> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<String> attributes = new ArrayList<>();
                String[] names = {
                    "superuser",
                    "can log in",
                    "creates databases",
                    "creates roles",
                    "replication",
                    "bypasses row security"
                };
                for (int i = 0; i < names.length; i++) {
                    if (rs.getBoolean(2 + i)) {
                        attributes.add(names[i]);
                    }
                }
                long owned = rs.getLong(9);
                if (owned > 0) {
                    attributes.add("owns " + owned + " table" + (owned == 1 ? "" : "s") + " (all rights on them)");
                }
                String member = rs.getString(8);
                out.add(new DatabaseStats.Role(
                        rs.getString(1),
                        attributes,
                        member.isEmpty() ? List.of() : List.of(member.split(",")),
                        grants.getOrDefault(rs.getString(1), List.of())));
            }
        }
        return out;
    }

    @Override
    public List<Session> activity(DatabaseTarget target) {
        // "client backend": real connections, not Postgres's own background workers. Our own session is left out.
        String sql = """
                select pid, usename, application_name, coalesce(client_addr::text, 'local socket'), state,
                       (extract(epoch from now() - case when state = 'active' then query_start
                                                        when state like 'idle in transaction%' then xact_start end)
                        * 1000)::bigint,
                       wait_event_type || ': ' || wait_event, query
                from pg_stat_activity
                where datname = current_database() and pid <> pg_backend_pid() and backend_type = 'client backend'
                order by state = 'idle', query_start
                """;
        try (Connection c = connect(target);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<Session> out = new ArrayList<>();
            while (rs.next()) {
                long ms = rs.getLong(6);
                Optional<Duration> running = rs.wasNull() ? Optional.empty() : Optional.of(Duration.ofMillis(ms));
                out.add(new Session(
                        rs.getInt(1),
                        Optional.ofNullable(rs.getString(2)).orElse(""),
                        Optional.ofNullable(rs.getString(3)).orElse(""),
                        rs.getString(4),
                        Optional.ofNullable(rs.getString(5)).orElse("unknown"),
                        running,
                        Optional.ofNullable(rs.getString(7)),
                        Optional.ofNullable(rs.getString(8)).orElse("")));
            }
            return out;
        } catch (SQLException e) {
            throw explain(target, e);
        }
    }

    private static Connection connect(DatabaseTarget t) throws SQLException {
        return connect(t, true);
    }

    /** {@code readOnly} is false only for {@link PostgresEditor}, the explicit write path. */
    static Connection connect(DatabaseTarget t, boolean readOnly) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", t.user());
        p.setProperty("password", t.password());
        p.setProperty("ApplicationName", "DevDesk"); // shows in pg_stat_activity, so you can see who's connected
        p.setProperty("connectTimeout", "3");
        // "always": Postgres refuses writes even outside an explicit transaction. Enforced by the server, not by us.
        if (readOnly) {
            p.setProperty("readOnly", "true");
            p.setProperty("readOnlyMode", "always");
        }
        p.setProperty("options", "-c statement_timeout=" + STATEMENT_TIMEOUT.toMillis());
        String url = "jdbc:postgresql://" + t.host() + ":" + t.port() + "/" + t.database();
        return DriverManager.getConnection(url, p);
    }

    /** Postgres's own message, plus what DevDesk knows about the likely cause. */
    static DataException explain(DatabaseTarget t, SQLException e) {
        String state = Optional.ofNullable(e.getSQLState()).orElse("");
        String message = Optional.ofNullable(e.getMessage()).orElse(e.toString());
        String hint = switch (state) {
            case "08001", "08004", "08006" ->
                " Can't reach " + t.service() + " at " + t.host() + ":" + t.port()
                        + ". Is it running? Start it in Services, or see Diagnosis.";
            case "28P01", "28000" ->
                " DevDesk used the user and password from " + t.service()
                        + "'s environment in devdesk.yaml (POSTGRES_USER, POSTGRES_PASSWORD). If the data volume was "
                        + "created with another password, the variable no longer applies: Postgres only reads it on "
                        + "the very first start.";
            case "3D000" -> " The database " + t.database() + " doesn't exist (POSTGRES_DB in devdesk.yaml).";
            case "25006" ->
                " The SQL editor is read-only. To change rows, open the table and edit them in the grid, then Save.";
            case "57014" -> " The query ran longer than " + STATEMENT_TIMEOUT.toSeconds() + " s and was cancelled.";
            default -> "";
        };
        return new DataException(message + hint, e);
    }

    private static List<String> list(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static List<String> strings(ResultSet rs, int column) throws SQLException {
        return Arrays.asList((String[]) rs.getArray(column).getArray());
    }

    /** A SQL identifier in double quotes, with inner quotes doubled: {@code my"table} → {@code "my""table"}. */
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
