package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.config.EnvFile;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wizard's settings step: what the .env files hold, which variables the setup needs but nobody defines, and which
 * keys .env.example promises but .env lacks. Secret-looking values never leave the engine.
 */
public final class EnvReport {

    /**
     * One variable from an env file.
     *
     * @param value the value, or empty when it looks secret (the app shows ●●●●)
     * @param usedBy services whose settings mention ${KEY}
     */
    public record Variable(String key, String file, Optional<String> value, boolean secret, List<String> usedBy) {}

    /**
     * One variable that has no value anywhere.
     *
     * @param reason why it's needed, in plain words
     */
    public record Missing(String key, String reason) {}

    /** The whole report: every variable, what is missing, and warnings like an .env file outside .gitignore. */
    public record Result(List<Variable> variables, List<Missing> missing, List<String> warnings) {}

    /** ${KEY} or ${KEY:-default}: the same syntax devdesk.yaml and docker compose resolve. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(:-[^}]*)?}");

    private EnvReport() {}

    /** The keys of one .env file, in order. */
    public static List<String> keys(String content) {
        return List.copyOf(EnvFile.parse(content).keySet());
    }

    /**
     * @param read reads a file relative to the project folder
     * @param shell the variables DevDesk itself was started with: ${VAR} may come from there too
     */
    public static Result of(SetupDraft draft, Function<String, Optional<String>> read, Set<String> shell) {
        Map<String, List<String>> references = references(draft);
        List<Variable> variables = new ArrayList<>();
        Set<String> defined = new LinkedHashSet<>();
        for (String file : draft.envFiles()) {
            read.apply(file)
                    .ifPresent(content -> EnvFile.parse(content).forEach((key, value) -> {
                        defined.add(key);
                        boolean secret = ServiceEnvironment.looksSecret(key);
                        variables.add(new Variable(
                                key,
                                file,
                                secret ? Optional.empty() : Optional.of(value),
                                secret,
                                references.getOrDefault(key, List.of())));
                    }));
        }
        List<Missing> missing = new ArrayList<>();
        references.forEach((key, services) -> {
            if (!defined.contains(key) && !shell.contains(key) && !hasDefault(draft, key)) {
                missing.add(
                        new Missing(key, String.join(", ", services) + " use ${" + key + "}, but no env file sets it"));
            }
        });
        // A card variable left empty (the scan saw the code read it but can't guess the value).
        Map<String, List<String>> empty = new LinkedHashMap<>();
        draft.services()
                .forEach(s -> s.environment().forEach((key, value) -> {
                    if (value.isBlank() && !defined.contains(key)) {
                        empty.computeIfAbsent(key, k -> new ArrayList<>()).add(s.name());
                    }
                }));
        empty.forEach((key, services) -> {
            if (missing.stream().noneMatch(m -> m.key().equals(key))) {
                missing.add(new Missing(
                        key,
                        String.join(", ", services) + " need" + (services.size() == 1 ? "s" : "")
                                + " a value for it: fill it on the card (step 2), or add it to an env file here"));
            }
        });
        read.apply(".env.example")
                .ifPresent(example -> keys(example).forEach(key -> {
                    if (!defined.contains(key)
                            && missing.stream().noneMatch(m -> m.key().equals(key))) {
                        missing.add(new Missing(key, ".env.example lists it, but your env files don't set it"));
                    }
                }));
        List<String> warnings = new ArrayList<>();
        if (read.apply(".env").isPresent()
                && read.apply(".gitignore")
                        .map(g -> !g.lines().anyMatch(l -> l.strip().matches("/?\\.env\\*?")))
                        .orElse(true)) {
            warnings.add(
                    ".env is not in .gitignore: its secrets could end up in git. Add a line \".env\" to .gitignore.");
        }
        return new Result(variables, missing, warnings);
    }

    /** KEY → the services whose settings mention ${KEY}, in the order they appear. */
    private static Map<String, List<String>> references(SetupDraft draft) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SetupDraft.Service s : draft.services()) {
            List<String> texts = new ArrayList<>(s.environment().values());
            texts.addAll(List.of(s.command(), s.image(), s.health().target()));
            texts.addAll(s.ports());
            texts.addAll(s.volumes());
            for (String text : texts) {
                Matcher m = REFERENCE.matcher(text);
                while (m.find()) {
                    List<String> users = out.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                    if (!users.contains(s.name())) {
                        users.add(s.name());
                    }
                }
            }
        }
        return out;
    }

    /** ${KEY:-default} everywhere it's used: the default counts as a value. */
    private static boolean hasDefault(SetupDraft draft, String key) {
        Pattern bare = Pattern.compile("\\$\\{" + Pattern.quote(key) + "}");
        return draft.services().stream()
                        .flatMap(s -> s.environment().values().stream())
                        .noneMatch(v -> bare.matcher(v).find())
                && draft.services().stream()
                        .flatMap(s -> s.environment().values().stream())
                        .anyMatch(v -> v.contains("${" + key + ":-"));
    }
}
