package io.github.louch.devdesk.core.data;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The database's own list of its slowest queries (Postgres: the pg_stat_statements extension): every query shape
 * with how often it ran and how long it took, in total and on average, since the list was last reset.
 *
 * @param status whether the list can be read, or which step is missing
 * @param statements slowest first (by total time)
 * @param since when the counting started (the last reset), if the database says
 */
public record SlowQueries(Status status, List<Statement> statements, Optional<Instant> since) {

    public SlowQueries {
        statements = List.copyOf(statements);
    }

    /** What's missing before the list exists. */
    public enum Status {
        /** The list is there. */
        READY,
        /** The extension's library isn't loaded: a setting plus a restart of the database. */
        NOT_LOADED,
        /** Loaded, but the extension wasn't created in this database ({@code create extension}). */
        NOT_CREATED
    }

    /**
     * One query shape: constants are {@code $1, $2} in the text, so "where id = 7" and "where id = 8" count as one.
     *
     * @param shape {@link #shape} of the query, to find it again from a request's own SQL
     */
    public record Statement(
            String query, long calls, double totalMs, double meanMs, double maxMs, long rows, String shape) {}

    /**
     * A list that can't be read yet; {@code status} names the missing step (extension not installed, not loaded...).
     */
    public static SlowQueries notReady(Status status) {
        return new SlowQueries(status, List.of(), Optional.empty());
    }

    private static final Pattern COMMENTS = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRINGS = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern PARAMETERS = Pattern.compile("\\$\\d+");
    private static final Pattern NUMBERS = Pattern.compile("(?<![\\w$.])-?\\d+(?:\\.\\d+)?(?![\\w])");
    private static final Pattern LISTS = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * A query without its values, to compare two queries by what they do: "select * from orders where id = 7" and
     * "SELECT * FROM orders WHERE id = $1" both become "select * from orders where id = ?". A list of values
     * ("in (1, 2, 3)") becomes one "(?)", like Postgres groups them.
     */
    public static String shape(String sql) {
        String s = COMMENTS.matcher(sql).replaceAll(" ");
        s = STRINGS.matcher(s).replaceAll("?");
        s = PARAMETERS.matcher(s).replaceAll("?");
        s = NUMBERS.matcher(s).replaceAll("?");
        s = LISTS.matcher(s).replaceAll("(?)");
        s = SPACES.matcher(s).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
        return s.endsWith(";") ? s.substring(0, s.length() - 1).strip() : s;
    }

    /** Where a query of a request stands in the list (0 = slowest), if it's there. */
    public Optional<Integer> rankOf(String sql) {
        String wanted = shape(sql);
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).shape().equals(wanted)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
