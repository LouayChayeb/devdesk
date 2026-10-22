package io.github.louch.devdesk.core.data;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Changes rows of one table for the Workbench: the only way DevDesk writes to a database. Kept apart from
 * {@link SqlBrowser}, whose sessions stay read-only, so a write can only ever come from this explicit "Save".
 */
public interface SqlEditor {

    /**
     * Applies every change in one transaction: all of them or none. Rows are found by their full primary key, and
     * each update or delete must hit exactly one row, otherwise nothing is saved.
     *
     * @throws DataException with the database's message (e.g. a broken constraint) and which change failed
     */
    Applied apply(DatabaseTarget target, TableChanges changes);

    /**
     * Runs a SQL script (an import) in one transaction: all of it, or none if a statement fails.
     *
     * @return the rows changed, as far as the statements report it
     */
    Applied runScript(DatabaseTarget target, String sql);

    /**
     * Stops another session's query (pg_cancel_backend), or closes the whole session ({@code terminate}, for one
     * stuck "idle in transaction"). Only the user's own explicit click reaches this.
     *
     * @return false when there was no such session any more
     */
    boolean stop(DatabaseTarget target, int pid, boolean terminate);

    /**
     * Whether the database logs every query with its duration (Postgres: {@code log_min_duration_statement = 0}), so
     * DevDesk can show the queries each API request ran. Empty when this database can't do it.
     */
    default Optional<Boolean> queryLogging(DatabaseTarget target) {
        return Optional.empty();
    }

    /** Switches that logging on or off on the running server (no restart); only on the user's explicit click. */
    default void setQueryLogging(DatabaseTarget target, boolean on) {
        throw new DataException("DevDesk can only switch query logging on for Postgres");
    }

    /**
     * The steps behind the slowest-queries list, each on the user's explicit click. {@code LOAD}: add the extension's
     * library to the server's settings (takes effect after a restart). {@code CREATE}: create the extension in this
     * database. {@code RESET}: start counting from zero.
     */
    enum SlowQueriesStep {
        LOAD,
        CREATE,
        RESET
    }

    default void slowQueries(DatabaseTarget target, SlowQueriesStep step) {
        throw new DataException("only Postgres keeps a list of its slowest queries (pg_stat_statements)");
    }

    /** Pending edits of one table, in the order the user made them. */
    record TableChanges(String schema, String table, List<RowChange> changes) {
        public TableChanges {
            changes = List.copyOf(changes);
        }
    }

    /**
     * One edit. Values are text, as shown in the grid, and the database converts them to the column's type; an empty
     * {@link Optional} means SQL NULL (different from an empty string).
     */
    sealed interface RowChange {
        /** Columns left out get their default (e.g. a serial id). */
        record Insert(Map<String, Optional<String>> values) implements RowChange {}

        /** {@code key} holds every primary key column with its current value. */
        record Update(Map<String, String> key, Map<String, Optional<String>> values) implements RowChange {}

        /** Removes the row named by {@code key}. */
        record Delete(Map<String, String> key) implements RowChange {}
    }

    /** @param rows rows inserted, updated or deleted in total */
    record Applied(int rows, Duration took) {}
}
