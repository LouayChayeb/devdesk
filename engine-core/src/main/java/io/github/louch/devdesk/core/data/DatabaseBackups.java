package io.github.louch.devdesk.core.data;

import java.nio.file.Path;

/**
 * Saves a whole database to a file and puts it back: a snapshot before trying something risky. For server databases
 * the database's own tools do it (pg_dump, mysqldump) inside its container; for SQLite, a copy of the file.
 */
public interface DatabaseBackups {

    /**
     * Writes everything (tables, data, indexes) to {@code file}.
     *
     * @param project the project's name: containers are named after it
     * @throws DataException with the tool's own error when it fails (the file is then removed)
     */
    void save(String project, DatabaseTarget target, Path file);

    /**
     * Replaces the database's tables with the ones in {@code file}, as one transaction where the database allows it.
     *
     * @throws DataException with the tool's error; for Postgres and SQLite nothing changed then
     */
    void restore(String project, DatabaseTarget target, Path file);

    /**
     * Like {@link #restore}, but the database ends up exactly as in the file: tables (collections) created after the
     * save are removed too. What a snapshot's "jump back" needs. A SQLite restore already copies the whole file.
     */
    default void restoreExactly(String project, DatabaseTarget target, Path file) {
        restore(project, target, file);
    }
}
