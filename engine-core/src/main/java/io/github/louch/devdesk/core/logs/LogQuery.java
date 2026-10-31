package io.github.louch.devdesk.core.logs;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What to look for in past logs. Plain text matches anywhere, ignoring case; {@code /regex/} is a regular expression.
 * "Errors only" keeps stderr lines and lines that look like errors, the way the logs panel's button does.
 *
 * @param services empty = all services
 * @param since only lines printed after this
 */
public record LogQuery(String text, Set<String> services, boolean errorsOnly, Instant since) {

    private static final Pattern ERROR_WORDS =
            Pattern.compile("(?i)\\b(error|exception|traceback|fatal|panic|failed|refused|denied)\\b");

    public LogQuery {
        services = Set.copyOf(services);
    }

    /** The compiled search, or a plain-words reason when the regular expression is invalid. */
    public Matcher matcher() throws InvalidQuery {
        String t = text.strip();
        if (t.length() > 2 && t.startsWith("/") && t.endsWith("/")) {
            try {
                Pattern p = Pattern.compile(t.substring(1, t.length() - 1));
                return line -> matches(line) && p.matcher(line.text()).find();
            } catch (PatternSyntaxException e) {
                throw new InvalidQuery("not a valid regular expression: " + e.getDescription()
                        + ". Leave out the / / to search for the plain text");
            }
        }
        String lower = t.toLowerCase();
        return line -> matches(line) && line.text().toLowerCase().contains(lower);
    }

    private boolean matches(LogLine line) {
        return !line.at().isBefore(since)
                && (services.isEmpty() || services.contains(line.service()))
                && (!errorsOnly
                        || line.stream().equals("stderr")
                        || ERROR_WORDS.matcher(line.text()).find());
    }

    /** Whether one line matches. */
    @FunctionalInterface
    public interface Matcher {
        /** True when {@code line} matches. */
        boolean test(LogLine line);
    }

    /** The search text can't be used; the message says why. */
    public static final class InvalidQuery extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidQuery(String message) {
            super(message);
        }
    }
}
