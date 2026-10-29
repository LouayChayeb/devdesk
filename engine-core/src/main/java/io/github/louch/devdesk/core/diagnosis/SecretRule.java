package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A service that fails while one of its secret settings is empty, or missing although its example file lists it:
 * the setting is named, the one its error mentions first. In the 5 hard apps this was the most common real cause,
 * and DevDesk named it 0 times. Only names are shown, never values: secrets never leak.
 */
public final class SecretRule implements DiagnosisRule {

    private static final Pattern KEY =
            Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=", Pattern.MULTILINE);

    private static final List<String> EXAMPLES = List.of(".example", ".sample", ".template", ".dist");

    @Override
    public String id() {
        return "empty-secret";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceState state = ctx.stateOf(service);
        boolean failing = state instanceof ServiceState.Crashed
                || state instanceof ServiceState.CrashLoop
                || state instanceof ServiceState.Unhealthy
                || state instanceof ServiceState.FailedToStart;
        if (!failing) {
            return List.of();
        }
        ServiceSpec spec = ctx.project().services().get(service);
        var env = ServiceEnvironment.values(ctx.project(), spec, ctx.environment());
        Set<String> empty = new LinkedHashSet<>();
        // Sorted: env files are read into maps without an order, and the message must read the same every time.
        new java.util.TreeMap<>(env).forEach((k, v) -> {
            if (v.isBlank() && ServiceEnvironment.looksSecret(k)) {
                empty.add(k);
            }
        });
        Set<String> missing = new LinkedHashSet<>();
        for (String example : examples(ctx, spec)) {
            Matcher m = KEY.matcher(example);
            while (m.find()) {
                String k = m.group(1);
                if (ServiceEnvironment.looksSecret(k) && !env.containsKey(k)) {
                    missing.add(k);
                }
            }
        }
        if (empty.isEmpty() && missing.isEmpty()) {
            return List.of();
        }
        Optional<String> cause = ErrorLines.cause(ctx.historyOf(service).recentOutput());
        String said = cause.orElse("").toLowerCase(Locale.ROOT);
        // The one the error names first ("Missing `secret_key_base`" → SECRET_KEY_BASE), then the others.
        List<String> named = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String k : concat(empty, missing)) {
            (said.contains(k.toLowerCase(Locale.ROOT)) ? named : rest).add(k);
        }
        List<String> all = new ArrayList<>(named);
        all.addAll(rest);
        String first = all.getFirst();
        String how = empty.contains(first) ? "is empty" : "isn't set (its example file lists it)";
        List<String> evidence = new ArrayList<>();
        cause.ifPresent(c -> evidence.add("its error: " + c));
        if (!empty.isEmpty()) {
            evidence.add("empty: " + String.join(", ", empty));
        }
        if (!missing.isEmpty()) {
            evidence.add("not set, but in its example: " + String.join(", ", missing));
        }
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                service + "'s setting " + first + " " + how,
                (named.isEmpty() ? service + " fails, and " : service + "'s error names it: ") + first + " " + how
                        + ". Apps refuse to start, or fail on every request, without their secrets."
                        + (all.size() > 1 ? " Also: " + String.join(", ", all.subList(1, all.size())) + "." : ""),
                evidence,
                List.of(
                        "give " + first + " a value in the service's env file (or environment:), then start it again",
                        "if the project's docs say how to make it (rails secret, openssl rand -hex 32), use that")));
    }

    /** The example files of the env files this service reads ({@code .env.production.sample}), and .env.example. */
    private static List<String> examples(DiagnosisContext ctx, ServiceSpec spec) {
        List<String> found = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path dir : List.of(spec.directory(), ctx.project().root())) {
            for (String base : List.of(".env", ".env.production", ".env.local")) {
                for (String ext : EXAMPLES) {
                    Path p = dir.resolve(base + ext);
                    if (seen.add(p)) {
                        ctx.system().readFile(p).ifPresent(found::add);
                    }
                }
            }
        }
        return found;
    }

    private static List<String> concat(Set<String> a, Set<String> b) {
        List<String> all = new ArrayList<>(a);
        b.stream().filter(k -> !all.contains(k)).forEach(all::add);
        return all;
    }
}
