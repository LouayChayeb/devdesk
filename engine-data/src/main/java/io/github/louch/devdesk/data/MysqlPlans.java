package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.QueryPlan;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads MySQL's and MariaDB's query plans into a {@link QueryPlan} tree. */
final class MysqlPlans {

    /** "-> Filter: (o.status = 'x')  (cost=3.25 rows=3) (actual time=0.04..0.06 rows=8 loops=1)" */
    private static final Pattern LINE =
            Pattern.compile("^(\\s*)-> (.*?)(?:\\s+\\(cost=([\\d.e+]+)(?:\\.\\.([\\d.e+]+))? rows=([\\d.e+]+)\\))?"
                    + "(?:\\s+\\(actual time=[\\d.e+]+\\.\\.([\\d.e+]+) rows=([\\d.e+]+) loops=(\\d+)\\))?\\s*$");
    /** "Table scan on orders", "Index lookup on o using status_idx (status='x')", "Covering index scan on u". */
    private static final Pattern TABLE = Pattern.compile("^(.*?) on (\\S+)(?: using (\\S+))?(?: \\((.*)\\))?$");

    private MysqlPlans() {}

    /** MySQL's {@code EXPLAIN FORMAT=TREE} / {@code EXPLAIN ANALYZE}: one step per line, children indented deeper. */
    static QueryPlan fromTree(String tree) {
        Deque<Builder> stack = new ArrayDeque<>();
        Builder root = null;
        for (String line : tree.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            Builder b = new Builder(m.group(1).length(), m);
            while (!stack.isEmpty() && stack.peek().indent >= b.indent) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                root = b;
            } else {
                stack.peek().children.add(b);
            }
            stack.push(b);
        }
        if (root == null) {
            root = new Builder(0, LINE.matcher("-> (no plan)"));
            root.text = tree.strip();
        }
        Optional<Double> total = root.ms;
        return new QueryPlan(root.build(), Optional.empty(), total, tree);
    }

    /** One line of the tree, before its children are known. */
    private static final class Builder {
        final int indent;
        String text;
        final double cost;
        final long planRows;
        final Optional<Long> actualRows;
        final Optional<Double> ms;
        final List<Builder> children = new ArrayList<>();

        Builder(int indent, Matcher m) {
            this.indent = indent;
            if (!m.matches()) {
                text = "";
                cost = 0;
                planRows = 0;
                actualRows = Optional.empty();
                ms = Optional.empty();
                return;
            }
            text = m.group(2);
            cost = m.group(4) != null ? parse(m.group(4)) : m.group(3) != null ? parse(m.group(3)) : 0;
            planRows = m.group(5) == null ? 0 : Math.round(parse(m.group(5)));
            long loops = m.group(8) == null ? 1 : Long.parseLong(m.group(8));
            // Like Postgres, the actual numbers are per loop: times loops gives the total.
            actualRows = Optional.ofNullable(m.group(7)).map(r -> Math.round(parse(r) * loops));
            ms = Optional.ofNullable(m.group(6)).map(t -> parse(t) * loops);
        }

        QueryPlan.Node build() {
            String type = text;
            Optional<String> relation = Optional.empty();
            List<String> detail = new ArrayList<>();
            if (text.startsWith("Filter: ")) {
                type = "Filter";
                detail.add(text);
            } else {
                Matcher t = TABLE.matcher(text);
                if (t.matches() && !text.contains(":")) {
                    type = t.group(1);
                    relation = Optional.of(t.group(2));
                    if (t.group(3) != null) {
                        detail.add("Index Name: " + t.group(3));
                    }
                    if (t.group(4) != null) {
                        detail.add("Index Cond: " + t.group(4));
                    }
                } else if (text.contains(": ")) {
                    type = text.substring(0, text.indexOf(": "));
                    detail.add(text);
                }
            }
            List<QueryPlan.Node> kids = new ArrayList<>();
            for (Builder c : children) {
                QueryPlan.Node child = c.build();
                // MySQL shows a filter as its own step above the scan. Rows the filter threw away are what tells
                // that an index is missing, so they go on the scan, like Postgres reports them.
                if (type.equals("Filter")
                        && children.size() == 1
                        && child.relation().isPresent()) {
                    Optional<Long> removed = child.actualRows().flatMap(in -> actualRows.map(out -> in - out));
                    List<String> d = new ArrayList<>(child.detail());
                    d.addAll(detail);
                    child = new QueryPlan.Node(
                            child.type(),
                            child.relation(),
                            d,
                            child.totalCost(),
                            child.planRows(),
                            child.actualRows(),
                            removed,
                            child.totalMs(),
                            child.sortSpill(),
                            child.children());
                }
                kids.add(child);
            }
            return new QueryPlan.Node(
                    type, relation, detail, cost, planRows, actualRows, Optional.empty(), ms, false, kids);
        }
    }

    /**
     * MariaDB's table-shaped plan ({@code EXPLAIN}, or {@code ANALYZE} with real row counts in r_rows): one step per
     * table read, in join order, under one "Query" step.
     */
    static QueryPlan fromTable(ResultSet rs) throws SQLException {
        Set<String> columns = new HashSet<>();
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columns.add(md.getColumnLabel(i).toLowerCase());
        }
        List<QueryPlan.Node> steps = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        while (rs.next()) {
            String access = Optional.ofNullable(rs.getString("type")).orElse("");
            long rows = rs.getLong("rows");
            List<String> detail = new ArrayList<>();
            Optional.ofNullable(rs.getString("key")).ifPresent(k -> detail.add("Index Name: " + k));
            Optional.ofNullable(rs.getString("Extra"))
                    .filter(x -> !x.isBlank())
                    .ifPresent(x -> detail.add("Extra: " + x));
            Optional<Long> actual = Optional.empty();
            Optional<Long> removed = Optional.empty();
            if (columns.contains("r_rows") && rs.getString("r_rows") != null) {
                long read = Math.round(rs.getDouble("r_rows"));
                double kept = columns.contains("r_filtered") ? rs.getDouble("r_filtered") : 100;
                actual = Optional.of(Math.round(read * kept / 100));
                removed = Optional.of(read - actual.get());
            }
            String table = Optional.ofNullable(rs.getString("table")).orElse("");
            raw.append(rs.getString("id"))
                    .append('\t')
                    .append(table)
                    .append('\t')
                    .append(access)
                    .append('\t')
                    .append(rows)
                    .append('\t')
                    .append(String.join("; ", detail))
                    .append('\n');
            steps.add(new QueryPlan.Node(
                    accessName(access),
                    table.isEmpty() ? Optional.empty() : Optional.of(table),
                    detail,
                    0,
                    rows,
                    actual,
                    removed,
                    Optional.empty(),
                    false,
                    List.of()));
        }
        QueryPlan.Node root = new QueryPlan.Node(
                "Query",
                Optional.empty(),
                List.of(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                steps);
        return new QueryPlan(root, Optional.empty(), Optional.empty(), raw.toString());
    }

    /** MySQL's access types in words: "ALL" is the one to worry about on a big table. */
    static String accessName(String type) {
        return switch (type) {
            case "ALL" -> "Table scan";
            case "index" -> "Full index scan";
            case "range" -> "Index range scan";
            case "ref", "eq_ref", "ref_or_null" -> "Index lookup";
            case "const", "system" -> "Single row lookup";
            default -> type.isEmpty() ? "Step" : type;
        };
    }

    private static double parse(String number) {
        return Double.parseDouble(number);
    }
}
