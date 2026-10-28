package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.config.EnvFile;
import io.github.louch.devdesk.core.doctor.Install;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Set up" for a fresh clone that already has a devdesk.yaml: the steps a new teammate would otherwise copy from the
 * README. Settings from .env.example first, then each service's packages, the images, and finally one-time jobs like
 * migrate. This class only plans; every command is shown before anything runs.
 */
public final class FreshSetup {

    /** One step of the plan. {@code id} is stable, so the app can say which steps to run. */
    public sealed interface Step {
        String id();

        String title();

        /**
         * Creates {@code file} from {@code example}, filling in the answers to {@code questions}.
         *
         * @param example the example's text, or "" when there is none (then only the questions are written)
         */
        record CreateEnv(String id, String title, String file, String example, List<Question> questions)
                implements Step {}

        /**
         * Replaces placeholder values ("change-me") of secret-looking keys in an env file the project ships. An answer
         * left empty keeps the value that is there.
         */
        record FillEnv(String id, String title, String file, List<Question> questions) implements Step {}

        /** Runs a command in a folder of the project ("" = the project folder): installs and image downloads. */
        record Run(String id, String title, String directory, String command) implements Step {}

        /** Starts a one-time service (with what it needs), waits until it finished, then stops everything again. */
        record Job(String id, String title, String service) implements Step {}
    }

    /**
     * A value the user must give.
     *
     * @param example what the example file has, if it's a hint rather than a real value ("changeme")
     */
    public record Question(String key, String example, boolean secret) {}

    /** The steps in the order they run, and things to tell the user that no step can fix. */
    public record Plan(List<Step> steps, List<String> notes) {}

    private static final List<String> EXAMPLES = List.of(".example", ".sample", ".template", ".dist");
    /** Example values that are obviously not real: they become questions too. */
    // Values that say "put a real one here": changeme, replace_with_lengthy_secure_hex (Chatwoot),
    // replace_me_with_a_random_string (Twenty), <your key>, your-secret…
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(|change[-_ ]?(me|this|it)([-_ ].*)?|replace[-_ ]?(me|with|this)([-_ ].*)?|todo|tbd|fixme|xxx+|<.*>|your[-_ ].*"
                    + "|\\.\\.\\.)$");

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private FreshSetup() {}

    /**
     * @param read reads a small file relative to the project folder; empty when it doesn't exist
     * @param exists whether a file exists, however big (node_modules' markers can be megabytes)
     */
    public static Plan of(SetupDraft draft, Function<String, Optional<String>> read, Predicate<String> exists) {
        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        envFiles(draft, read, steps);
        installs(draft, read, exists, steps, notes);
        Set<String> images = new LinkedHashSet<>();
        // Images DevDesk builds from the project's Dockerfile aren't downloaded: they're built when they start.
        draft.services().stream()
                .filter(s -> s.isContainer() && !s.extras().containsKey("build"))
                .forEach(s -> images.add(s.image()));
        // ${TAG:-latest} in an image name: what the project's env files say, as for the containers themselves.
        Map<String, String> values = new LinkedHashMap<>();
        draft.envFiles().forEach(f -> read.apply(f).ifPresent(c -> values.putAll(EnvFile.parse(c))));
        images.stream()
                .map(image -> resolve(image, values))
                .distinct()
                .forEach(image ->
                        steps.add(new Step.Run("pull:" + image, "Download " + image, "", "docker pull " + image)));
        jobs(draft, steps);
        if (steps.isEmpty()) {
            notes.add("Nothing to prepare: settings exist, packages are installed and there are no one-time jobs.");
        }
        return new Plan(steps, notes);
    }

    /** The example a missing env file can be made from: {@code .env.production.sample} for .env.production. */
    public static Optional<String> exampleOf(String file, Function<String, Optional<String>> read) {
        return EXAMPLES.stream()
                .map(e -> file + e)
                .filter(f -> read.apply(f).isPresent())
                .findFirst();
    }

    /** {@code ${VAR}}s the services use without a default: if no env file sets them, someone has to. */
    public static Set<String> unsetVariables(List<SetupDraft.Service> services) {
        return referencedVariables(new SetupDraft("x", List.of(), services, List.of()));
    }

    /** Each env file the project reads that doesn't exist yet, from its example when there is one. */
    private static void envFiles(SetupDraft draft, Function<String, Optional<String>> read, List<Step> steps) {
        Set<String> files = new LinkedHashSet<>(draft.envFiles());
        // A service's own env files (compose's env_file) too: Mastodon's .env.production.
        draft.services().forEach(s -> {
            String own = s.extras().get("env_files");
            if (own != null) {
                files.addAll(List.of(own.split(",")));
            }
        });
        // Many apps read .env themselves (dotenv), even when devdesk.yaml doesn't list it.
        if (EXAMPLES.stream().anyMatch(e -> read.apply(".env" + e).isPresent())) {
            files.add(".env");
        }
        for (String file : files) {
            Optional<String> existing = read.apply(file);
            if (existing.isPresent()) {
                // paperless-ngx ships PAPERLESS_SECRET_KEY=change-me, which its current image refuses to start with.
                List<Question> placeholders = new ArrayList<>();
                EnvFile.parse(existing.get()).forEach((key, value) -> {
                    if (!value.isBlank()
                            && PLACEHOLDER.matcher(value.strip()).matches()
                            && ServiceEnvironment.looksSecret(key)) {
                        placeholders.add(new Question(key, value.strip(), true));
                    }
                });
                if (!placeholders.isEmpty()) {
                    steps.add(new Step.FillEnv(
                            "fill:" + file,
                            "Replace " + placeholders.size() + " placeholder value"
                                    + (placeholders.size() == 1 ? "" : "s") + " in " + file,
                            file,
                            placeholders));
                }
                continue;
            }
            Optional<String> example = EXAMPLES.stream()
                    .map(e -> file + e)
                    .filter(f -> read.apply(f).isPresent())
                    .findFirst();
            List<Question> questions = new ArrayList<>();
            if (example.isPresent()) {
                EnvFile.parse(read.apply(example.get()).orElseThrow()).forEach((key, value) -> {
                    if (PLACEHOLDER.matcher(value.strip()).matches()) {
                        questions.add(new Question(key, value.strip(), ServiceEnvironment.looksSecret(key)));
                    }
                });
            } else {
                // No example: ask for what devdesk.yaml itself uses as ${VAR}.
                referencedVariables(draft)
                        .forEach(key -> questions.add(new Question(key, "", ServiceEnvironment.looksSecret(key))));
            }
            steps.add(new Step.CreateEnv(
                    "env:" + file,
                    "Create " + file + example.map(e -> " from " + e).orElse(""),
                    file,
                    example.flatMap(read).orElse(""),
                    questions));
        }
    }

    /** One install per folder, in the tool the folder uses. Already installed folders are skipped. */
    private static void installs(
            SetupDraft draft,
            Function<String, Optional<String>> read,
            Predicate<String> exists,
            List<Step> steps,
            List<String> notes) {
        Map<String, List<String>> byFolder = new LinkedHashMap<>();
        draft.services().stream()
                .filter(s -> !s.isContainer())
                .forEach(s -> byFolder.computeIfAbsent(s.directory(), d -> new ArrayList<>())
                        .add(s.name()));
        byFolder.forEach((dir, services) -> {
            Function<String, Optional<String>> here = f -> read.apply(dir.isEmpty() ? f : dir + "/" + f);
            String where = dir.isEmpty() ? "the project folder" : "./" + dir;
            // "api's packages", or "the packages of migrate and api" when several services share the folder.
            String who = services.size() == 1 ? services.getFirst() + "'s" : "the";
            String whose = services.size() == 1 ? "" : " of " + String.join(" and ", services);
            if (here.apply("package.json").isPresent()
                    && Install.MARKERS.stream()
                            .noneMatch(m -> exists.test((dir.isEmpty() ? "" : dir + "/") + "node_modules/" + m))) {
                String install = Install.nodeInstall(here);
                steps.add(new Step.Run(
                        "install:" + dir,
                        "Install " + who + " packages" + whose + " (" + install + " in " + where + ")",
                        dir,
                        install));
            } else if (here.apply("pom.xml").isPresent()) {
                String mvn = here.apply("mvnw").isPresent() ? "./mvnw" : "mvn";
                steps.add(new Step.Run(
                        "install:" + dir,
                        "Download " + who + " Java libraries" + whose + " (Maven, in " + where + ")",
                        dir,
                        mvn + " -B -q dependency:go-offline"));
            } else if (here.apply("build.gradle.kts")
                    .or(() -> here.apply("build.gradle"))
                    .isPresent()) {
                String gradle = here.apply("gradlew").isPresent() ? "./gradlew" : "gradle";
                steps.add(new Step.Run(
                        "install:" + dir,
                        "Build " + String.join(" and ", services) + " once (Gradle downloads the libraries, in " + where
                                + ")",
                        dir,
                        gradle + " --quiet classes"));
            } else if (here.apply("go.mod").isPresent()) {
                steps.add(new Step.Run(
                        "install:" + dir,
                        "Download " + who + " Go modules" + whose + " (in " + where + ")",
                        dir,
                        "go mod download"));
            } else if (draft.services().stream()
                    .anyMatch(s -> s.directory().equals(dir) && s.command().matches("dotnet\\b.*"))) {
                steps.add(new Step.Run(
                        "install:" + dir,
                        "Download " + who + " .NET packages" + whose + " (dotnet restore, in " + where + ")",
                        dir,
                        "dotnet restore"));
            } else if (here.apply("requirements.txt").isPresent()) {
                boolean venv = here.apply(".venv/pyvenv.cfg").isPresent();
                // Ubuntu forbids pip installs into the system Python (PEP 668): a virtual environment is the way.
                steps.add(new Step.Run(
                        "install:" + dir,
                        "Install " + who + " Python packages" + whose + " (into " + (dir.isEmpty() ? "" : dir + "/")
                                + ".venv)",
                        dir,
                        (venv ? "" : "python3 -m venv .venv && ") + ".venv/bin/pip install -r requirements.txt"));
                draft.services().stream()
                        .filter(s -> s.directory().equals(dir) && s.command().matches("python3?\\b.*"))
                        .forEach(s -> notes.add(
                                s.name() + " starts with \"" + s.command().split("\\s+")[0]
                                        + "\", not .venv/bin/python: it won't see the packages installed into .venv. Change its "
                                        + "command to \".venv/bin/" + s.command() + "\" in Edit setup."));
            }
        });
    }

    /** Services others wait for with "finished OK": migrations, seeds. They run once, then everything stops. */
    private static void jobs(SetupDraft draft, List<Step> steps) {
        Set<String> jobs = new LinkedHashSet<>();
        draft.services()
                .forEach(s -> s.dependsOn().forEach((dep, condition) -> {
                    if (condition.equals("completed_successfully")) {
                        jobs.add(dep);
                    }
                }));
        jobs.forEach(job -> steps.add(new Step.Job("job:" + job, "Run " + job + " once", job)));
    }

    private static final Pattern WITH_DEFAULT = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::?[-?]([^}]*))?}");

    /** {@code ${VAR}}, {@code ${VAR:-default}}: the env files' value, else the default, else nothing. */
    static String resolve(String text, Map<String, String> values) {
        Matcher m = WITH_DEFAULT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null || v.isEmpty()) {
                v = m.group(2) == null ? "" : m.group(2);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Set<String> referencedVariables(SetupDraft draft) {
        Set<String> keys = new LinkedHashSet<>();
        for (SetupDraft.Service s : draft.services()) {
            List<String> texts = new ArrayList<>(s.environment().values());
            texts.add(s.command());
            texts.add(s.image());
            for (String t : texts) {
                Matcher m = REFERENCE.matcher(t);
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }

    /**
     * The new env file: the example's lines in order (comments kept), each asked-for key with the user's answer.
     * Answers for keys the example doesn't have are added at the end.
     */
    public static String envContent(String example, Map<String, String> answers) {
        StringBuilder out = new StringBuilder();
        Set<String> written = new LinkedHashSet<>();
        for (String line : example.lines().toList()) {
            String stripped = line.strip();
            int eq = stripped.indexOf('=');
            String key = eq > 0
                    ? stripped.substring(0, eq).replaceFirst("^export\\s+", "").strip()
                    : "";
            if (!key.isEmpty() && answers.containsKey(key)) {
                out.append(key).append('=').append(quote(answers.get(key))).append('\n');
                written.add(key);
            } else {
                out.append(line).append('\n');
            }
        }
        answers.forEach((key, value) -> {
            if (!written.contains(key)) {
                out.append(key).append('=').append(quote(value)).append('\n');
            }
        });
        return out.toString();
    }

    /** Quotes only when needed: spaces, # or quotes would change how .env readers see the value. */
    private static String quote(String value) {
        return value.matches("[^\\s\"'#$\\\\]*")
                ? value
                : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
