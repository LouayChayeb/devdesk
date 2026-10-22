package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseBackups;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Backups of a SQLite file with SQLite's own online backup: a consistent copy even while the app writes to the
 * file (a plain file copy could catch it halfway through a write).
 */
public final class SqliteBackups implements DatabaseBackups {

    @Override
    public void save(String project, DatabaseTarget target, Path file) {
        try (Connection c = SqliteBrowser.connect(target, true);
                Statement st = c.createStatement()) {
            // sqlite-jdbc's "backup to": SQLite's backup API, page by page, into a new database file.
            st.executeUpdate("backup to " + file.toAbsolutePath());
        } catch (SQLException e) {
            throw SqliteBrowser.explain(target, e);
        }
        if (!Files.isRegularFile(file)) {
            throw new DataException("SQLite reported success but wrote no file at " + file);
        }
    }

    @Override
    public void restore(String project, DatabaseTarget target, Path file) {
        try (Connection c = SqliteBrowser.connect(target, false);
                Statement st = c.createStatement()) {
            // "restore from" copies every page of the backup over the open database, under SQLite's write lock.
            st.executeUpdate("restore from " + file.toAbsolutePath());
        } catch (SQLException e) {
            throw SqliteBrowser.explain(target, e);
        }
    }
}
