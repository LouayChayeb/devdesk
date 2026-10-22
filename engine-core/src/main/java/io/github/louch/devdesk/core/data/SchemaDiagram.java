package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tables of a database and the links between them (foreign keys), arranged in levels for drawing: a table sits
 * one level above every table it points at, so "orders → users" puts users under orders, like the service graph.
 */
public final class SchemaDiagram {

    /** More tables than this make an unreadable picture: the diagram asks to pick a schema instead. */
    public static final int MAX_TABLES = 150;

    private SchemaDiagram() {}

    /** A table's name as the diagram knows it: "public.orders". */
    public record TableName(String schema, String name) {
        @Override
        public String toString() {
            return schema + "." + name;
        }
    }

    /** One arrow: {@code from}'s {@code columns} point at {@code to}'s {@code toColumns}. */
    public record Link(TableName from, List<String> columns, TableName to, List<String> toColumns, String name) {}

    /**
     * @param levels top to bottom; each table is in exactly one level
     * @param outside tables a link points at that aren't in the picture (another schema): drawn as a faded box
     */
    public record Diagram(List<List<TableName>> levels, List<Link> links, Set<TableName> outside) {}

    /**
     * Levels by the longest chain of links under each table. Links that close a loop (a self-reference, or
     * a → b → a) are kept as arrows but ignored for the levels, so every table still gets one.
     */
    public static Diagram of(Map<TableName, SqlBrowser.Structure> tables) {
        List<Link> links = new ArrayList<>();
        Set<TableName> outside = new HashSet<>();
        for (Map.Entry<TableName, SqlBrowser.Structure> t : tables.entrySet()) {
            for (SqlBrowser.ForeignKey fk : t.getValue().foreignKeys()) {
                TableName to = new TableName(fk.toSchema(), fk.toTable());
                links.add(new Link(t.getKey(), fk.columns(), to, fk.toColumns(), fk.name()));
                if (!tables.containsKey(to)) {
                    outside.add(to);
                }
            }
        }
        Map<TableName, Integer> depth = new HashMap<>();
        for (TableName t : tables.keySet()) {
            depth(t, links, tables.keySet(), depth, new HashSet<>());
        }
        int deepest = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<TableName>> levels = new ArrayList<>();
        for (int d = deepest; d >= 0; d--) {
            final int level = d;
            levels.add(tables.keySet().stream()
                    .filter(t -> depth.get(t) == level)
                    .sorted((a, b) -> a.toString().compareTo(b.toString()))
                    .toList());
        }
        return new Diagram(levels, links, outside);
    }

    /** 0 for a table that points at nothing (in the picture), else 1 + the deepest table it points at. */
    private static int depth(
            TableName t, List<Link> links, Set<TableName> shown, Map<TableName, Integer> done, Set<TableName> path) {
        Integer known = done.get(t);
        if (known != null) {
            return known;
        }
        path.add(t);
        int d = 0;
        for (Link l : links) {
            // path.contains: following this link would come back to a table we're inside of (a loop).
            if (l.from().equals(t) && shown.contains(l.to()) && !path.contains(l.to())) {
                d = Math.max(d, 1 + depth(l.to(), links, shown, done, path));
            }
        }
        path.remove(t);
        done.put(t, d);
        return d;
    }
}
