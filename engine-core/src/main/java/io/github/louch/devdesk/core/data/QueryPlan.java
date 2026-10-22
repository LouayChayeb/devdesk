package io.github.louch.devdesk.core.data;

import java.util.List;
import java.util.Optional;

/**
 * A query plan as a tree of steps ("nodes"): Postgres reads from the leaves up to the root. With ANALYZE, each node
 * also has what really happened (rows, time), next to the planner's guess.
 *
 * @param planningMs only with ANALYZE
 * @param executionMs only with ANALYZE
 * @param raw the plan as Postgres returned it (JSON), for copying into other tools
 */
public record QueryPlan(Node root, Optional<Double> planningMs, Optional<Double> executionMs, String raw) {

    /**
     * One step of the plan.
     *
     * @param type "Seq Scan", "Index Scan", "Hash Join", "Sort"...
     * @param relation the table it reads, if it reads one
     * @param detail the condition or key it uses ("Filter: (status = 'failed')", "Sort Key: created_at")
     * @param planRows rows the planner expected
     * @param actualRows rows it really produced (ANALYZE), for all loops together
     * @param removedByFilter rows read and thrown away by the filter (ANALYZE): high means an index is missing
     * @param totalMs time spent in this step and its children (ANALYZE), for all loops together
     * @param sortSpill the sort didn't fit in memory and used the disk ("external merge")
     */
    public record Node(
            String type,
            Optional<String> relation,
            List<String> detail,
            double totalCost,
            long planRows,
            Optional<Long> actualRows,
            Optional<Long> removedByFilter,
            Optional<Double> totalMs,
            boolean sortSpill,
            List<Node> children) {

        public Node {
            detail = List.copyOf(detail);
            children = List.copyOf(children);
        }
    }
}
