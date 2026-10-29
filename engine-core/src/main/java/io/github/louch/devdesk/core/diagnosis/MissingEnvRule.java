package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code .env.example} lists variables the app expects, but some aren't set. Only names are ever shown, never values
 * (secrets never leak).
 */
public final class MissingEnvRule implements DiagnosisRule {

    private static final Pattern KEY =
            Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=", Pattern.MULTILINE);

    @Override
    public String id() {
        return "missing-env";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceSpec spec = ctx.project().services().get(service);
        if (!(spec.workload() instanceof Workload.Command)) {
            return List.of();
        }
        Path example = spec.directory().resolve(".env.example");
        Optional<String> content = ctx.system().readFile(example);
        if (content.isEmpty() && !spec.directory().equals(ctx.project().root())) {
            example = ctx.project().root().resolve(".env.example");
            content = ctx.system().readFile(example);
        }
        if (content.isEmpty()) {
            return List.of();
        }
        // Most frameworks (dotenv, Vite, Next.js, Laravel, Django-environ) load the .env next to .env.example
        // themselves: a key set there is set, even if devdesk.yaml doesn't list the file.
        String own = ctx.system().readFile(example.resolveSibling(".env")).orElse("");
        List<String> ownKeys = new ArrayList<>();
        Matcher o = KEY.matcher(own);
        while (o.find()) {
            ownKeys.add(o.group(1));
        }
        List<String> missing = new ArrayList<>();
        Matcher m = KEY.matcher(content.get());
        while (m.find()) {
            String key = m.group(1);
            boolean set = spec.environment().containsKey(key)
                    || ctx.project().environment().containsKey(key)
                    || ctx.environment().containsKey(key)
                    || ownKeys.contains(key);
            if (!set && !missing.contains(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of(new Finding(
                id(),
                Finding.Severity.WARNING,
                service,
                missing.size() == 1
                        ? "Setting " + missing.getFirst() + " is not set"
                        : missing.size() + " settings are not set",
                example.getFileName() + " says " + service + " expects " + String.join(", ", missing)
                        + ", but no environment, .env file or devdesk.yaml sets "
                        + (missing.size() == 1 ? "it" : "them") + ".",
                List.of(example + " lists: " + String.join(", ", missing)),
                List.of(
                        "add them to a .env file and list it under env_files: in devdesk.yaml",
                        "or set them under the service's environment: block")));
    }
}
