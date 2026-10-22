package io.github.louch.devdesk.core.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * How big a database is, how its tables are used, and who may do what in it. Numbers the database doesn't keep are
 * -1 (MySQL has no "dead rows", SQLite no scan counts).
 *
 * @param roles users and roles with their rights; empty for SQLite, which has none
 */
public record DatabaseStats(long databaseBytes, List<TableStats> tables, List<Role> roles) {

    public DatabaseStats {
        tables = List.copyOf(tables);
        roles = List.copyOf(roles);
    }

    /**
     * One table's size and use since the statistics were last reset (usually: since the database started).
     *
     * @param seqScans times the whole table was read from start to end
     * @param indexScans times rows were found through an index
     * @param deadRows old row versions left by updates and deletes, waiting for VACUUM (Postgres)
     */
    public record TableStats(
            String schema,
            String name,
            long rows,
            long totalBytes,
            long indexBytes,
            long seqScans,
            long indexScans,
            long deadRows,
            Optional<Instant> lastVacuum,
            Optional<Instant> lastAnalyze) {}

    /**
     * A user or role.
     *
     * @param attributes "superuser", "can log in", "creates databases"...
     * @param memberOf roles it inherits rights from
     * @param privileges "public.orders: SELECT, INSERT", or MySQL's GRANT lines
     */
    public record Role(String name, List<String> attributes, List<String> memberOf, List<String> privileges) {
        public Role {
            attributes = List.copyOf(attributes);
            memberOf = List.copyOf(memberOf);
            privileges = List.copyOf(privileges);
        }
    }
}
