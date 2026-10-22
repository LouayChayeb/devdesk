package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The "N+1" problem in one request: one query loads a list, then the same query runs once per item ("select * from
 * users where id = ?" 50 times). Each is fast; together they make the page slow. Found from the request's own SQL.
 */
public final class RepeatedQueries {

    /** From this many runs of one shape in one request, it's worth a warning. */
    public static final int THRESHOLD = 5;

    /**
     * One repeated query.
     *
     * @param shape the query without its values
     * @param example one real run of it
     * @param times how often it ran in the request
     * @param distinctValues how many different values it ran with: equal to {@code times} is the classic N+1 (one per
     *     item); 1 means the very same query again and again
     * @param totalMs all of its runs together
     * @param before the query that ran just before the first one: usually the one that loaded the list
     */
    public record Repeat(
            String shape, String example, int times, int distinctValues, double totalMs, Optional<String> before) {

        /** Every run had the same values: nothing differs, so the answer could be reused. */
        public boolean identical() {
            return distinctValues == 1;
        }

        /** What to do about it, in plain words. */
        public String advice() {
            if (identical()) {
                return "The very same query ran " + times + " times: run it once and reuse the answer (keep it in a"
                        + " variable, or cache it for the request).";
            }
            return "Load them in one query instead of one per item: \"where id in (…)\" or a join. In an ORM that's"
                    + " eager loading: include (Prisma, Sequelize), select_related / prefetch_related (Django),"
                    + " joinedload (SQLAlchemy), JOIN FETCH / @EntityGraph (JPA), with (Laravel), includes (Rails).";
        }
    }

    private RepeatedQueries() {}

    /** Repeats of one request, most costly first. Transaction steps (begin, commit) don't count. */
    public static List<Repeat> find(List<QueryLog.Query> queries) {
        Map<String, List<Integer>> byShape = new LinkedHashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            String shape = SlowQueries.shape(queries.get(i).sql());
            if (shape.matches("(begin|commit|rollback|start transaction|set|show|savepoint|release)\\b.*")) {
                continue;
            }
            byShape.computeIfAbsent(shape, s -> new ArrayList<>()).add(i);
        }
        List<Repeat> out = new ArrayList<>();
        byShape.forEach((shape, at) -> {
            if (at.size() < THRESHOLD) {
                return;
            }
            Set<String> values = new HashSet<>();
            double ms = 0;
            for (int i : at) {
                QueryLog.Query q = queries.get(i);
                // The values are in the SQL (literals) or in the parameters Postgres logged ($1 = '7').
                values.add(q.sql() + "|" + q.params());
                ms += q.ms();
            }
            int first = at.getFirst();
            Optional<String> before =
                    first > 0 ? Optional.of(queries.get(first - 1).sql()) : Optional.empty();
            out.add(new Repeat(shape, queries.get(first).sql(), at.size(), values.size(), ms, before));
        });
        out.sort((a, b) -> Double.compare(b.totalMs(), a.totalMs()) != 0
                ? Double.compare(b.totalMs(), a.totalMs())
                : Integer.compare(b.times(), a.times()));
        return out;
    }
}
