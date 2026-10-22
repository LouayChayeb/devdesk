package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SqlBrowser.Structure;
import io.github.louch.devdesk.core.data.SqlDialect;
import io.github.louch.devdesk.core.data.SqlEditor;
import io.github.louch.devdesk.core.data.SqlScript;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

/** Row edits, imports and stopping sessions on MySQL and MariaDB: the only writable sessions DevDesk opens there. */
public final class MysqlEditor implements SqlEditor {

    @Override
    public Applied apply(DatabaseTarget target, TableChanges changes) {
        try (Connection c = MysqlBrowser.connect(target, false)) {
            Structure s = MysqlBrowser.structure(c, changes.schema(), changes.table())
                    .orElseThrow(
                            () -> new DataException("there is no table " + changes.schema() + "." + changes.table()));
            return RowWriter.apply(c, SqlDialect.MYSQL, s, changes, col -> "?", e -> MysqlBrowser.explain(target, e));
        } catch (SQLException e) {
            throw MysqlBrowser.explain(target, e);
        }
    }

    @Override
    public Applied runScript(DatabaseTarget target, String sql) {
        long started = System.nanoTime();
        List<String> statements = SqlScript.split(sql, true);
        try (Connection c = MysqlBrowser.connect(target, false)) {
            c.setAutoCommit(false);
            int rows = 0;
            int done = 0;
            try (Statement st = c.createStatement()) {
                for (String statement : statements) {
                    st.execute(statement);
                    rows += Math.max(0, st.getUpdateCount());
                    done++;
                }
                c.commit();
                return new Applied(rows, Duration.ofNanos(System.nanoTime() - started));
            } catch (SQLException e) {
                c.rollback();
                // Unlike Postgres, MySQL commits by itself after CREATE, ALTER or DROP: those can't be undone.
                throw new DataException(
                        MysqlBrowser.explain(target, e).getMessage() + " (statement " + (done + 1)
                                + " of " + statements.size()
                                + ". Rows changed before it were undone; but a CREATE, ALTER or"
                                + " DROP before it stays: MySQL commits those at once.)",
                        e);
            }
        } catch (SQLException e) {
            throw MysqlBrowser.explain(target, e);
        }
    }

    @Override
    public boolean stop(DatabaseTarget target, int pid, boolean terminate) {
        try (Connection c = MysqlBrowser.connect(target, false)) {
            // Only a connection the user can see in the process list; KILL QUERY stops the statement, KILL the
            // whole connection (and rolls back its open transaction).
            try (PreparedStatement ps =
                    c.prepareStatement("select count(*) from information_schema.processlist where id = ?")) {
                ps.setInt(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) == 0) {
                        return false;
                    }
                }
            }
            try (Statement st = c.createStatement()) {
                st.execute((terminate ? "kill " : "kill query ") + pid); // pid is an int: nothing to inject
                return true;
            }
        } catch (SQLException e) {
            throw MysqlBrowser.explain(target, e);
        }
    }
}
