package io.github.louch.devdesk.core.config;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads durations the way people write them: {@code 500ms}, {@code 10s}, {@code 2m}, {@code 1h}. */
final class Durations {

    private static final Pattern FORMAT = Pattern.compile("(\\d+)(ms|s|m|h)");

    private Durations() {}

    static Optional<Duration> parse(String text) {
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        long n = Long.parseLong(m.group(1));
        return Optional.of(
                switch (m.group(2)) {
                    case "ms" -> Duration.ofMillis(n);
                    case "s" -> Duration.ofSeconds(n);
                    case "m" -> Duration.ofMinutes(n);
                    default -> Duration.ofHours(n);
                });
    }
}
