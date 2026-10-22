package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SqlBrowser.Structure;
import io.github.louch.devdesk.core.data.SqlDialect;
import io.github.louch.devdesk.core.data.SqlEditor;
import io.github.louch.devdesk.core.data.SqlScript;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

/** Row edits and imports on a SQLite file, each in one transaction. */
public final class SqliteEditor implements SqlEditor {

    @Override
    public Applied apply(DatabaseTarget target, TableChanges changes) {
        try (Connection c = SqliteBrowser.connect(target, false)) {
            Structure s = SqliteBrowser.structure(c, changes.table())
                    .orElseThrow(() -> new DataException("there is no table " + changes.table()));
            return RowWriter.apply(c, SqlDialect.SQLITE, s, changes, col -> "?", e -> SqliteBrowser.explain(target, e));
        } catch (SQLException e) {
            throw SqliteBrowser.explain(target, e);
        }
    }

    @Override
    public Applied runScript(DatabaseTarget target, String sql) {
        long started = System.nanoTime();
        List<String> statements = SqlScript.split(sql, false);
        try (Connection c = SqliteBrowser.connect(target, false)) {
            c.setAutoCommit(false); // SQLite undoes CREATE and DROP too when the transaction is rolled back
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
                throw new DataException(
                        SqliteBrowser.explain(target, e).getMessage() + " (statement " + (done + 1) + " of "
                                + statements.size() + ". Nothing was imported: the whole script is undone.)",
                        e);
            }
        } catch (SQLException e) {
            throw SqliteBrowser.explain(target, e);
        }
    }

    /** SQLite has no server and no other sessions to stop. */
    @Override
    public boolean stop(DatabaseTarget target, int pid, boolean terminate) {
        return false;
    }
}
