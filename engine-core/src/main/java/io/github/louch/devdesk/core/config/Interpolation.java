package io.github.louch.devdesk.core.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces variables in config values with environment values, with Docker Compose's rules: {@code ${VAR}},
 * {@code ${VAR:-default}} / {@code ${VAR-default}} (a fallback) and {@code ${VAR:?message}} / {@code ${VAR?message}}
 * (required). With the colon, an empty value counts as unset; without it, only a missing one does. {@code $$} is a
 * literal {@code $}: {@code $${PGUSER}} reaches the command as {@code ${PGUSER}}, for the shell or container to expand.
 */
final class Interpolation {

    private static final Pattern VARIABLE =
            Pattern.compile("\\$\\$|\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?:(:?)([-?])([^}]*))?}");

    /** The text with every variable replaced, or the first variable that had no value. */
    sealed interface Result {
        record Resolved(String text) implements Result {}

        /** {@code message} is the text after {@code ?} in a required variable, empty otherwise. */
        record Unset(String variable, String message) implements Result {}
    }

    private Interpolation() {}

    static Result resolve(String text, Map<String, String> env) {
        Matcher m = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (name == null) {
                m.appendReplacement(out, "\\$"); // "$$": an escaped dollar sign
                continue;
            }
            String value = env.get(name);
            String operator = m.group(3);
            boolean unset = value == null || (":".equals(m.group(2)) && value.isEmpty());
            if (unset && "-".equals(operator)) {
                value = m.group(4); // the fallback
            } else if (unset && "?".equals(operator)) {
                return new Result.Unset(name, m.group(4).strip()); // required, and the project says why
            } else if (value == null) {
                return new Result.Unset(name, "");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return new Result.Resolved(out.toString());
    }
}
