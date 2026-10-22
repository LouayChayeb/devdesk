package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.QueryPlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads SQLite's {@code EXPLAIN QUERY PLAN} rows (id, parent, detail) into a {@link QueryPlan} tree. */
final class SqlitePlans {

    /** "SCAN orders", "SEARCH o USING INDEX status_idx (status=?)", "SCAN users USING COVERING INDEX ...". */
    private static final Pattern STEP = Pattern.compile("^(SCAN|SEARCH) (\\S+)(?: (.*))?$");

    private SqlitePlans() {}

    static QueryPlan parse(ResultSet rs) throws SQLException {
        Map<Integer, Integer> parents = new LinkedHashMap<>();
        Map<Integer, String> details = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();
        while (rs.next()) {
            parents.put(rs.getInt(1), rs.getInt(2));
            details.put(rs.getInt(1), rs.getString(4));
            raw.append(rs.getInt(1))
                    .append('\t')
                    .append(rs.getInt(2))
                    .append('\t')
                    .append(rs.getString(4))
                    .append('\n');
        }
        List<QueryPlan.Node> top = children(0, parents, details);
        QueryPlan.Node root = top.size() == 1
                ? top.getFirst()
                : new QueryPlan.Node(
                        "Query",
                        Optional.empty(),
                        List.of(),
                        0,
                        0,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        top);
        return new QueryPlan(root, Optional.empty(), Optional.empty(), raw.toString());
    }

    private static List<QueryPlan.Node> children(
            int parent, Map<Integer, Integer> parents, Map<Integer, String> details) {
        List<QueryPlan.Node> out = new ArrayList<>();
        parents.forEach((id, p) -> {
            if (p == parent) {
                out.add(node(details.get(id), children(id, parents, details)));
            }
        });
        return out;
    }

    private static QueryPlan.Node node(String detail, List<QueryPlan.Node> children) {
        Matcher m = STEP.matcher(detail);
        String type = detail;
        Optional<String> relation = Optional.empty();
        List<String> extra = new ArrayList<>();
        if (m.matches()) {
            // SCAN without an index reads the whole table (like Postgres's Seq Scan); SEARCH jumps to rows.
            boolean index = m.group(3) != null && m.group(3).contains("INDEX");
            type = m.group(1).equals("SCAN") ? (index ? "Full index scan" : "Table scan") : "Index lookup";
            relation = Optional.of(m.group(2));
            if (m.group(3) != null) {
                extra.add(m.group(3));
            }
        }
        return new QueryPlan.Node(
                type,
                relation,
                extra,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                detail.contains("TEMP B-TREE"),
                children);
    }
}
