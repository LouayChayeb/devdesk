package io.github.louch.devdesk.core.data;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads a SQL database for the Workbench. Implemented with JDBC in engine-data; faked in tests. Every method opens a
 * read-only session: nothing here can change data (edits come through a separate, explicit path).
 */
public interface SqlBrowser {

    /** Tables and views the user can see, without the database's own system schemas. */
    List<Table> tables(DatabaseTarget target);

    /** Columns, primary key, indexes and links to other tables. Empty when the table doesn't exist. */
    Optional<Structure> structure(DatabaseTarget target, String schema, String table);

    /** One page of a table's rows, sorted and filtered. */
    ResultTable rows(DatabaseTarget target, RowQuery query);

    /** Any SQL the user typed, in a read-only transaction with a time limit. At most {@code maxRows} are returned. */
    ResultTable run(DatabaseTarget target, String sql, int maxRows);

    /**
     * How Postgres runs a query: EXPLAIN, or EXPLAIN ANALYZE when {@code analyze} is true. ANALYZE really runs the
     * query (still in a read-only session, so a write fails instead of happening).
     */
    QueryPlan explain(DatabaseTarget target, String sql, boolean analyze);

    /** Size of the database and its tables, how the tables are used, and the users with their rights. */
    default DatabaseStats stats(DatabaseTarget target) {
        throw new DataException("this database doesn't report statistics to DevDesk");
    }

    /** The database's own list of its slowest queries, or which step is missing to have it. */
    default SlowQueries slowQueries(DatabaseTarget target) {
        throw new DataException("only Postgres keeps a list of its slowest queries (pg_stat_statements)");
    }

    /** Other sessions connected to the database right now, and what each one is doing. */
    List<Session> activity(DatabaseTarget target);

    /**
     * Every row a table query matches ({@code offset} and {@code limit} are ignored), with whole cell values, handed
     * to {@code sink} one at a time so a big export never sits in memory at once.
     */
    void each(DatabaseTarget target, RowQuery query, RowSink sink);

    /** Every row of a typed query (read-only session, like {@link #run}), for exporting its result. */
    void each(DatabaseTarget target, String sql, RowSink sink);

    /** Receives an export: the columns once, then each row. Cells are text; null is SQL NULL. */
    interface RowSink {
        /** Called once, before the first row. */
        void columns(List<String> names, List<String> types);

        /** Called once per row, with one text per column (null for SQL NULL). */
        void row(List<String> values);
    }

    /**
     * One connection to the database (from pg_stat_activity).
     *
     * @param state "active" (running a query), "idle", "idle in transaction" (holding locks, often a bug)...
     * @param running how long the current query (or the open transaction, when idle in one) has taken so far
     * @param waitingFor what it waits for when blocked, e.g. "Lock: transactionid"
     */
    record Session(
            int pid,
            String user,
            String application,
            String client,
            String state,
            Optional<Duration> running,
            Optional<String> waitingFor,
            String query) {}

    /** @param estimatedRows the planner's estimate (fast, may be a little off); -1 when unknown */
    record Table(String schema, String name, boolean view, long estimatedRows) {}

    /** Everything about one table's shape. */
    record Structure(List<Column> columns, List<Index> indexes, List<ForeignKey> foreignKeys) {}

    /** One column, as the database describes it ("numeric", "timestamp with time zone"). */
    record Column(String name, String type, boolean nullable, Optional<String> defaultValue, boolean primaryKey) {}

    /** An index and the columns it covers, in order. */
    record Index(String name, List<String> columns, boolean unique) {}

    /** {@code columns} of this table point at {@code toColumns} of {@code toSchema.toTable}. */
    record ForeignKey(String name, List<String> columns, String toSchema, String toTable, List<String> toColumns) {}

    /**
     * @param orderBy a column name, checked against the table before use
     * @param filters all must match
     */
    record RowQuery(
            String schema,
            String table,
            Optional<String> orderBy,
            boolean descending,
            List<Filter> filters,
            long offset,
            int limit) {}

    /** {@code column op value}; {@code value} is sent as a query parameter, never pasted into SQL. */
    record Filter(String column, Op op, String value) {}

    /** How a filter compares; the value is always a query parameter. */
    enum Op {
        EQUALS,
        NOT_EQUALS,
        LESS,
        GREATER,
        CONTAINS,
        IS_NULL,
        IS_NOT_NULL
    }

    /**
     * Rows as text, ready to show. {@code null} cells stay null so "NULL" and an empty string look different.
     *
     * @param more true when there were more rows than returned
     * @param affected for statements without rows (e.g. EXPLAIN without ANALYZE), the update count, else -1
     */
    record ResultTable(
            List<String> columns,
            List<String> types,
            List<List<String>> rows,
            boolean more,
            long affected,
            Duration took) {}
}
