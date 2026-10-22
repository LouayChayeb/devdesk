package io.github.louch.devdesk.core.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL a request made the database run, read from Postgres's own log while the request ran. Postgres writes one
 * "duration: 0.512 ms  statement: ..." line per query when {@code log_min_duration_statement} is 0 (DevDesk can
 * switch that on). From those queries, the rows a request created or changed can be linked: "open orders row 8912".
 */
public final class QueryLog {

    /** "duration: 0.512 ms  statement: select 1" or "duration: 0.05 ms  execute <unnamed>: select ...". */
    private static final Pattern QUERY =
            Pattern.compile("duration: ([0-9.]+) ms\\s+(?:statement|execute [^:]*): (.*)$");
    /** Parse and bind steps of a prepared query come with their own duration: only "execute" is the query running. */
    private static final Pattern STEP = Pattern.compile("duration: [0-9.]+ ms\\s+(?:parse|bind) ");

    private static final Pattern PARAMS = Pattern.compile("DETAIL:\\s+[Pp]arameters: (.*)$");
    /** A new log entry starts with Postgres's line prefix: a timestamp ("2026-10-08 11:00:16.036 UTC [32] LOG: ..."). */
    private static final Pattern ENTRY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    private QueryLog() {}

    /** One log line of a database service, as the engine kept it. */
    public record Line(Instant at, String text) {}

    /** @param params the values of $1, $2... as Postgres logged them ("$1 = '7'"), or "" */
    public record Query(Instant at, double ms, String sql, String params) {}

    /** A row the request touched, to open in the Data page. */
    public record RowLink(String schema, String table, String column, String value, String action) {}

    public static List<Query> parse(List<Line> lines) {
        List<Query> out = new ArrayList<>();
        StringBuilder sql = null;
        Instant at = null;
        double ms = 0;
        String params = "";
        for (Line l : lines) {
            Matcher q = QUERY.matcher(l.text());
            Matcher p = PARAMS.matcher(l.text());
            boolean continuation = !ENTRY.matcher(l.text()).find() && sql != null && !p.find(0);
            if (q.find() && !STEP.matcher(l.text()).find()) {
                if (sql != null) {
                    out.add(new Query(at, ms, sql.toString().strip(), params));
                }
                sql = new StringBuilder(q.group(2));
                at = l.at();
                ms = Double.parseDouble(q.group(1));
                params = "";
            } else if (p.find(0) && sql != null) {
                params = p.group(1).strip();
            } else if (continuation) {
                sql.append('\n').append(l.text().strip()); // a statement over several lines
            } else if (sql != null && ENTRY.matcher(l.text()).find()) {
                out.add(new Query(at, ms, sql.toString().strip(), params));
                sql = null;
            }
        }
        if (sql != null) {
            out.add(new Query(at, ms, sql.toString().strip(), params));
        }
        return out;
    }

    private static final Pattern INSERT =
            Pattern.compile("^\\s*insert\\s+into\\s+([\\w.\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANGE = Pattern.compile(
            "^\\s*(update|delete\\s+from)\\s+([\\w.\"]+).*\\bwhere\\s+\"?id\"?\\s*=\\s*('?[\\w-]+'?|\\$\\d+)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ID_IN_JSON =
            Pattern.compile("^\\s*[\\[{].*?\"id\"\\s*:\\s*\"?([\\w-]+)\"?", Pattern.DOTALL);

    /**
     * Rows to open: the first table a request inserted into, with the "id" its answer gave back (the usual
     * "POST /orders answers {id: 8912}"); and rows changed or deleted by id.
     */
    public static List<RowLink> rows(List<Query> queries, String answer) {
        List<RowLink> out = new ArrayList<>();
        Optional<String> id = Optional.empty();
        Matcher j = ID_IN_JSON.matcher(answer);
        if (j.find()) {
            id = Optional.of(j.group(1));
        }
        boolean inserted = false;
        for (Query q : queries) {
            Matcher i = INSERT.matcher(q.sql());
            if (i.find() && !inserted && id.isPresent()) {
                String[] t = table(i.group(1));
                out.add(new RowLink(t[0], t[1], "id", id.get(), "created"));
                inserted = true;
                continue;
            }
            Matcher c = CHANGE.matcher(q.sql());
            if (c.find()) {
                String value = c.group(3);
                if (value.startsWith("$")) {
                    value = param(q.params(), value).orElse("");
                }
                value = value.replace("'", "");
                if (!value.isEmpty()) {
                    String[] t = table(c.group(2));
                    boolean delete = c.group(1).toLowerCase(Locale.ROOT).startsWith("delete");
                    out.add(new RowLink(t[0], t[1], "id", value, delete ? "deleted" : "changed"));
                }
            }
        }
        return out;
    }

    /** "$1 = '7', $2 = 'paid'" → the value of $n. */
    private static Optional<String> param(String params, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + " = '([^']*)'").matcher(params);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** {@code public.orders}, {@code "Orders"} → schema and name; no schema means "public". */
    private static String[] table(String name) {
        String n = name.replace("\"", "");
        int dot = n.indexOf('.');
        return dot < 0 ? new String[] {"public", n} : new String[] {n.substring(0, dot), n.substring(dot + 1)};
    }
}
