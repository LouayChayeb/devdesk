package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a query plan into plain-words hints: "orders is read row by row (Seq Scan) and 29,700 of 30,000 rows are
 * thrown away by the filter on status: an index on orders(status) would let Postgres jump to the right rows."
 */
public final class PlanAdvice {

    /** Below this many rows a full read is cheap, and an index wouldn't help much. */
    static final long BIG_TABLE = 10_000;

    private PlanAdvice() {}

    /** Hints for the slow parts of {@code plan} (full scans, filters that throw most rows away...); empty when none. */
    public static List<String> hints(QueryPlan plan) {
        List<String> out = new ArrayList<>();
        walk(plan.root(), out);
        return out;
    }

    /** Postgres says "Seq Scan", MySQL and SQLite (as DevDesk names them) "Table scan". */
    private static boolean isFullScan(String type) {
        return type.equals("Seq Scan") || type.equals("Table scan");
    }

    private static void walk(QueryPlan.Node n, List<String> out) {
        String table = n.relation().orElse("a table");
        long actual = n.actualRows().orElse(-1L);
        long removed = n.removedByFilter().orElse(0L);
        if (isFullScan(n.type()) && removed > BIG_TABLE && removed > 9 * Math.max(actual, 1)) {
            String filter = n.detail().stream()
                    .filter(d -> d.startsWith("Filter: "))
                    .findFirst()
                    .map(d -> d.substring("Filter: ".length()))
                    .orElse("the filter");
            out.add(String.format(
                    Locale.ROOT,
                    "%s is read row by row (%s) and %,d of %,d rows are thrown away by %s. An index on the"
                            + " column(s) in that filter would let the database jump to the right rows.",
                    table,
                    n.type(),
                    removed,
                    removed + Math.max(actual, 0),
                    filter));
        }
        if (actual >= 0 && n.planRows() > 0) {
            double ratio = (double) Math.max(actual, 1) / Math.max(n.planRows(), 1);
            if ((ratio > 10 || ratio < 0.1) && Math.max(actual, n.planRows()) > 1000) {
                out.add(String.format(
                        Locale.ROOT,
                        "%s on %s: the database expected %,d rows but got %,d. Its statistics are out of date, so it"
                                + " may pick a slow plan. Run: ANALYZE %s;",
                        n.type(),
                        table,
                        n.planRows(),
                        actual,
                        n.relation().orElse("")));
            }
        }
        if (n.sortSpill()) {
            out.add("The sort didn't fit in memory and used the disk (\"external merge\"). Sorting fewer rows (a"
                    + " filter or LIMIT first), an index matching the ORDER BY, or a bigger work_mem helps.");
        }
        n.children().forEach(c -> walk(c, out));
    }
}
