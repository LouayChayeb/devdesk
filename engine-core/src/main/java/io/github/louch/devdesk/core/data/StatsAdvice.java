package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-words hints from a database's statistics: a big table read whole over and over (a missing index), many dead
 * rows (VACUUM is behind), indexes bigger than the data. Thresholds are deliberately high: a hint should be worth
 * reading on a local development database.
 */
public final class StatsAdvice {

    /** Tables smaller than this are fast to read whole; scanning them is fine. */
    static final long BIG_TABLE_ROWS = 10_000;

    static final long MANY_SCANS = 500;

    private StatsAdvice() {}

    /** @param table the table it's about ("public.orders"), or empty for the whole database */
    public record Hint(String table, String text) {}

    public static List<Hint> of(DatabaseStats stats) {
        List<Hint> out = new ArrayList<>();
        for (DatabaseStats.TableStats t : stats.tables()) {
            String name = t.schema() + "." + t.name();
            if (t.rows() >= BIG_TABLE_ROWS
                    && t.seqScans() >= MANY_SCANS
                    && t.seqScans() > 10 * Math.max(t.indexScans(), 0)) {
                out.add(
                        new Hint(
                                name,
                                name + " (" + t.rows() + " rows) was read from start to end " + t.seqScans()
                                        + " times, and through an index only " + Math.max(t.indexScans(), 0)
                                        + " times: some"
                                        + " query on it probably misses an index. Run it with Plan in a SQL tab to see which."));
            }
            if (t.deadRows() > 1_000 && t.deadRows() > t.rows() / 5) {
                out.add(new Hint(
                        name,
                        name + " has " + t.deadRows() + " dead rows (old versions left by updates and deletes) for "
                                + t.rows() + " live ones. It wastes space and slows reads: VACUUM " + name
                                + " reclaims them (autovacuum may be off or behind)."));
            }
            long data = t.totalBytes() - t.indexBytes();
            if (t.indexBytes() > 50L * 1024 * 1024 && data > 0 && t.indexBytes() > 3 * data) {
                out.add(new Hint(
                        name,
                        "The indexes of " + name + " are " + (t.indexBytes() / data) + "× the size of its data. Some"
                                + " may be unused or duplicates: check the Structure tab."));
            }
        }
        return out;
    }
}
