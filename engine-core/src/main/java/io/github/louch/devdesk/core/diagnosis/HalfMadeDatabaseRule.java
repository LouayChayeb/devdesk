package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.process.OutputLine;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The app asks its database for tables that aren't there: its migrations never ran, or a first start stopped while
 * it was creating them. Twenty's home page said "Unable to load Twenty" while its own health check said OK; its log
 * said {@code relation "core.workspace" does not exist}. Some apps only create their tables on an empty database, so
 * after a half-made first start, retrying never helps.
 */
public final class HalfMadeDatabaseRule implements DiagnosisRule {

    /** Postgres, MySQL/MariaDB and SQLite saying a table is missing. */
    private static final Pattern MISSING_TABLE =
            Pattern.compile("(?i)(relation \"[^\"]+\" does not exist|table '[^']+' doesn't exist|no such table: \\S+)");

    @Override
    public String id() {
        return "missing-tables";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        Optional<String> line = ctx.historyOf(service).recentOutput().stream()
                .map(OutputLine::text)
                .filter(t -> MISSING_TABLE.matcher(t).find())
                .reduce((a, b) -> b);
        if (line.isEmpty()) {
            return List.of();
        }
        var m = MISSING_TABLE.matcher(line.get());
        String what = m.find() ? m.group(1) : line.get();
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                service + "'s database is missing tables",
                service + " asks its database for a table that isn't there (" + what + "). Its migrations didn't"
                        + " run, or a first start stopped halfway through creating them; some apps then skip the"
                        + " setup on every later start.",
                List.of("its log: " + line.get().strip()),
                List.of(
                        "run the project's migrations (its docs say how)",
                        "if a first start failed halfway: Remove the project (its database data goes) and start it"
                                + " again, so the app sets up an empty database")));
    }
}
