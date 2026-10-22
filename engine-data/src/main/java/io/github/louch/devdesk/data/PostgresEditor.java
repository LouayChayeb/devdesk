package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SqlBrowser.Structure;
import io.github.louch.devdesk.core.data.SqlDialect;
import io.github.louch.devdesk.core.data.SqlEditor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;

/**
 * Saves the Workbench's row edits to PostgreSQL, runs imports, and stops other sessions: the only writable sessions
 * DevDesk opens on Postgres, each for one transaction (all changes are applied, or none).
 */
public final class PostgresEditor implements SqlEditor {

    @Override
    public Applied apply(DatabaseTarget target, TableChanges changes) {
        try (Connection c = PostgresBrowser.connect(target, false)) {
            Structure s = PostgresBrowser.structure(c, changes.schema(), changes.table())
                    .orElseThrow(
                            () -> new DataException("there is no table " + changes.schema() + "." + changes.table()));
            // Postgres converts the text to the column's type (the type name comes from the catalog, not the user).
            return RowWriter.apply(
                    c,
                    SqlDialect.POSTGRES,
                    s,
                    changes,
                    col -> "cast(? as " + col.type() + ")",
                    e -> PostgresBrowser.explain(target, e));
        } catch (SQLException e) {
            throw PostgresBrowser.explain(target, e);
        }
    }

    @Override
    public Optional<Boolean> queryLogging(DatabaseTarget target) {
        try (Connection c = PostgresBrowser.connect(target, true);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select current_setting('log_min_duration_statement')")) {
            rs.next();
            return Optional.of(rs.getString(1).equals("0"));
        } catch (SQLException e) {
            throw PostgresBrowser.explain(target, e);
        }
    }

    @Override
    public void slowQueries(DatabaseTarget target, SlowQueriesStep step) {
        try (Connection c = PostgresBrowser.connect(target, false);
                Statement st = c.createStatement()) {
            c.setAutoCommit(true); // ALTER SYSTEM can't run inside a transaction
            switch (step) {
                case LOAD -> {
                    // Keep what's already preloaded (pg_cron, timescaledb...); add ours. Read at the next start.
                    String current;
                    try (ResultSet rs = st.executeQuery("select current_setting('shared_preload_libraries')")) {
                        rs.next();
                        current = rs.getString(1).strip();
                    }
                    if (!current.contains("pg_stat_statements")) {
                        String value = current.isEmpty() ? "pg_stat_statements" : current + ", pg_stat_statements";
                        st.execute("alter system set shared_preload_libraries = " + SqlDialect.POSTGRES.literal(value));
                    }
                }
                case CREATE -> st.execute("create extension if not exists pg_stat_statements");
                case RESET -> st.execute("select pg_stat_statements_reset()");
            }
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                throw new DataException(target.user() + " may not change the server's settings (that needs a"
                        + " superuser). Add pg_stat_statements to shared_preload_libraries in the database's own"
                        + " configuration instead.");
            }
            throw PostgresBrowser.explain(target, e);
        }
    }

    @Override
    public void setQueryLogging(DatabaseTarget target, boolean on) {
        // ALTER SYSTEM writes postgresql.auto.conf; pg_reload_conf() makes the running server read it. It can't run
        // inside a transaction, so this session stays in autocommit. Off = back to the server's own default.
        try (Connection c = PostgresBrowser.connect(target, false);
                Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute(
                    on
                            ? "alter system set log_min_duration_statement = 0"
                            : "alter system reset log_min_duration_statement");
            st.execute("select pg_reload_conf()");
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                throw new DataException(
                        target.user() + " may not change the server's settings (that needs a"
                                + " superuser). Set log_min_duration_statement = 0 in the database's own configuration instead.");
            }
            throw PostgresBrowser.explain(target, e);
        }
    }

    @Override
    public Applied runScript(DatabaseTarget target, String sql) {
        long started = System.nanoTime();
        try (Connection c = PostgresBrowser.connect(target, false)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // An import may be big: the 15 s limit of the browsing sessions is lifted for this transaction only.
                st.execute("set local statement_timeout = '10min'");
                int rows = 0;
                // pgjdbc sends the whole script and returns one result per statement; walk them to count rows.
                boolean isQuery = st.execute(sql);
                while (true) {
                    int count = st.getUpdateCount();
                    if (!isQuery && count == -1) {
                        break;
                    }
                    rows += Math.max(0, count);
                    isQuery = st.getMoreResults();
                }
                c.commit();
                return new Applied(rows, Duration.ofNanos(System.nanoTime() - started));
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            DataException explained = PostgresBrowser.explain(target, e);
            throw new DataException(explained.getMessage() + " (Nothing was imported: the whole script is undone.)", e);
        }
    }

    @Override
    public boolean stop(DatabaseTarget target, int pid, boolean terminate) {
        // Only sessions on this database: the user (often the superuser "postgres") could otherwise stop anything.
        String function = terminate ? "pg_terminate_backend" : "pg_cancel_backend";
        String sql =
                "select " + function + "(pid) from pg_stat_activity where pid = ? and datname = current_database()";
        try (Connection c = PostgresBrowser.connect(target, false);
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw PostgresBrowser.explain(target, e);
        }
    }
}
