package io.github.louch.devdesk.core.config;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${VAR}} and {@code ${VAR:-default}} in config values with environment values. {@code $$} is a
 * literal {@code $}, like in Docker Compose: {@code $${PGUSER}} reaches the command as {@code ${PGUSER}}, for the
 * shell or container to expand.
 */
final class Interpolation {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\$|\\$\\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}");

    private Interpolation() {}

    /** The text with every variable replaced, or empty plus the first unknown variable name in {@code missing}. */
    static Optional<String> resolve(String text, Map<String, String> env, StringBuilder missing) {
        Matcher m = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (m.group(1) == null) {
                m.appendReplacement(out, "\\$"); // "$$": an escaped dollar sign
                continue;
            }
            String value = env.get(m.group(1));
            if (value == null && m.group(2) != null) {
                value = m.group(3); // the default after :-
            }
            if (value == null) {
                missing.append(m.group(1));
                return Optional.empty();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }
}
