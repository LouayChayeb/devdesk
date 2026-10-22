package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.QueryPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the JSON that {@code EXPLAIN (FORMAT JSON)} returns into a {@link QueryPlan} tree. */
final class PostgresPlans {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Plan fields that say how a step finds or joins its rows, shown under the step's name. */
    private static final List<String> DETAILS = List.of(
            "Index Name",
            "Index Cond",
            "Recheck Cond",
            "Hash Cond",
            "Merge Cond",
            "Join Filter",
            "Filter",
            "Sort Key",
            "Group Key",
            "Sort Method");

    private PostgresPlans() {}

    /** {@code [{"Plan": {...}, "Planning Time": 0.1, "Execution Time": 2.3}]} */
    static QueryPlan parse(String json) {
        JsonNode top = JSON.readTree(json).get(0);
        return new QueryPlan(node(top.get("Plan")), number(top, "Planning Time"), number(top, "Execution Time"), json);
    }

    private static QueryPlan.Node node(JsonNode n) {
        List<String> detail = new ArrayList<>();
        for (String field : DETAILS) {
            JsonNode v = n.get(field);
            if (v != null) {
                // "Sort Key" and "Group Key" are lists; the rest are plain text.
                String text = v.isArray()
                        ? String.join(
                                ", ", v.valueStream().map(JsonNode::asString).toList())
                        : v.asString();
                detail.add(field + ": " + text);
            }
        }
        // With ANALYZE, rows and time are per loop: a step run 1,000 times inside a nested loop shows the
        // per-run numbers. Multiplying by "Actual Loops" gives the total, which is what the user cares about.
        long loops = n.has("Actual Loops") ? Math.max(1, n.get("Actual Loops").asLong()) : 1;
        Optional<Long> actualRows = number(n, "Actual Rows").map(r -> Math.round(r * loops));
        Optional<Long> removed = number(n, "Rows Removed by Filter").map(r -> Math.round(r * loops));
        Optional<Double> ms = number(n, "Actual Total Time").map(t -> t * loops);
        boolean spill =
                n.has("Sort Space Type") && n.get("Sort Space Type").asString().equals("Disk");
        String type = n.get("Node Type").asString();
        if (n.has("Join Type") && type.endsWith("Join")) {
            type = type + " (" + n.get("Join Type").asString().toLowerCase() + ")";
        }
        List<QueryPlan.Node> children = new ArrayList<>();
        if (n.has("Plans")) {
            n.get("Plans").forEach(child -> children.add(node(child)));
        }
        return new QueryPlan.Node(
                type,
                Optional.ofNullable(n.get("Relation Name")).map(JsonNode::asString),
                detail,
                n.path("Total Cost").asDouble(),
                n.path("Plan Rows").asLong(),
                actualRows,
                removed,
                ms,
                spill,
                children);
    }

    private static Optional<Double> number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isNumber() ? Optional.empty() : Optional.of(v.asDouble());
    }
}
