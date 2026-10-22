package io.github.louch.devdesk.core.setup;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recognises how to start the app in one folder from its build files: package.json (Node), pom.xml or build.gradle
 * (Spring Boot, Quarkus) and manage.py (Django). Pure: it only reads file contents it is given.
 */
final class AppDetector {

    /** The dev script people run, most specific first: "dev" beats "start", which is often the production one. */
    private static final List<String> NODE_SCRIPTS = List.of("dev", "start:dev", "develop", "serve", "start");

    private static final Pattern PORT_FLAG = Pattern.compile("(?:--port[= ]|-p[= ]|PORT=)(\\d{2,5})\\b");
    private static final Pattern SPRING_PORT = Pattern.compile("(?m)^\\s*server\\.port\\s*[=:]\\s*(\\d{2,5})");
    private static final Pattern YAML_PORT = Pattern.compile("(?ms)^server:\\s*\\n\\s+port:\\s*(\\d{2,5})");
    private static final Pattern ENV_PORT = Pattern.compile("(?m)^\\s*PORT\\s*=\\s*[\"']?(\\d{2,5})");
    private static final JsonMapper JSON = JsonMapper.shared();

    private AppDetector() {}

    /**
     * @param read reads a file relative to the project folder; empty when it doesn't exist
     * @param dir the folder to look at, relative to the project ("" = the project folder itself)
     */
    static Optional<ComposeImport.DetectedApp> detect(Function<String, Optional<String>> read, String dir) {
        Function<String, Optional<String>> here = file -> read.apply(dir.isEmpty() ? file : dir + "/" + file);
        return node(here, dir).or(() -> java(here, dir)).or(() -> django(here, dir));
    }

    /** Whether package.json says it's a monorepo root: the real apps are in its workspaces, not here. */
    static boolean isWorkspaceRoot(Function<String, Optional<String>> read) {
        return read.apply("package.json")
                .flatMap(AppDetector::json)
                .map(j -> j.has("workspaces"))
                .orElse(false);
    }

    private static Optional<ComposeImport.DetectedApp> node(Function<String, Optional<String>> read, String dir) {
        Optional<JsonNode> pkg = read.apply("package.json").flatMap(AppDetector::json);
        if (pkg.isEmpty()) {
            return Optional.empty();
        }
        JsonNode scripts = pkg.get().path("scripts");
        Optional<String> script = NODE_SCRIPTS.stream().filter(scripts::has).findFirst();
        if (script.isEmpty()) {
            return Optional.empty();
        }
        String body = scripts.path(script.get()).asString("");
        // Each package manager keeps its own lock file; running another one would install different versions.
        String runner = read.apply("pnpm-lock.yaml").isPresent()
                ? "pnpm run "
                : read.apply("yarn.lock").isPresent()
                        ? "yarn "
                        : read.apply("bun.lock")
                                        .or(() -> read.apply("bun.lockb"))
                                        .isPresent()
                                ? "bun run "
                                : "npm run ";
        Optional<Integer> port = first(PORT_FLAG, body)
                .or(() -> read.apply(".env").flatMap(env -> first(ENV_PORT, env)))
                .or(() -> defaultNodePort(body));
        // Dev servers for web pages answer "/" with 200; an API's "/" may well be a 404, so only check its port.
        boolean webPage = body.matches(".*\\b(vite|next|nuxt|ng serve|astro|react-scripts|webpack serve).*");
        // Each package manager leaves its own marker in node_modules after an install.
        boolean hasPackages = pkg.get().path("dependencies").size()
                        + pkg.get().path("devDependencies").size()
                > 0;
        boolean installed = read.apply("node_modules/.package-lock.json")
                .or(() -> read.apply("node_modules/.modules.yaml"))
                .or(() -> read.apply("node_modules/.yarn-state.yml"))
                .or(() -> read.apply("node_modules/.yarn-integrity"))
                .isPresent();
        List<String> notes = hasPackages && !installed
                ? List.of("Its packages aren't installed yet: run \""
                        + runner.replace(" run ", " ").strip().replaceAll("^(npm|pnpm|bun)$", "$1 install") + "\" in ./"
                        + dir
                        + " once, or the start command will fail.")
                : List.of();
        return Optional.of(new ComposeImport.DetectedApp(
                runner + script.get(),
                port,
                webPage ? port.map(p -> "http://127.0.0.1:" + p + "/") : Optional.empty(),
                where(dir, "package.json") + " script \"" + script.get() + "\"",
                notes));
    }

    private static Optional<Integer> defaultNodePort(String script) {
        if (script.contains("vite")) {
            return Optional.of(5173);
        }
        if (script.contains("ng serve")) {
            return Optional.of(4200);
        }
        if (script.contains("astro")) {
            return Optional.of(4321);
        }
        if (script.matches(".*\\b(next|nuxt|react-scripts|nest start)\\b.*")) {
            return Optional.of(3000);
        }
        return Optional.empty();
    }

    private static Optional<ComposeImport.DetectedApp> java(Function<String, Optional<String>> read, String dir) {
        Optional<String> pom = read.apply("pom.xml");
        Optional<String> gradle = read.apply("build.gradle.kts").or(() -> read.apply("build.gradle"));
        String build = pom.or(() -> gradle).orElse(null);
        if (build == null) {
            return Optional.empty();
        }
        boolean maven = pom.isPresent();
        // The wrapper pins the build tool version the project was made with; use it when it's there.
        String tool = maven
                ? (read.apply("mvnw").isPresent() ? "./mvnw " : "mvn ")
                : (read.apply("gradlew").isPresent() ? "./gradlew " : "gradle ");
        String task;
        if (build.contains("spring-boot")) {
            task = maven ? "spring-boot:run" : "bootRun";
        } else if (build.contains("quarkus")) {
            task = maven ? "quarkus:dev" : "quarkusDev";
        } else if (!maven && build.matches("(?s).*\\bapplication\\b.*")) {
            task = "run";
        } else {
            return Optional.empty();
        }
        Optional<Integer> port = read.apply("src/main/resources/application.properties")
                .flatMap(p -> first(SPRING_PORT, p))
                .or(() -> read.apply("src/main/resources/application.yml").flatMap(y -> first(YAML_PORT, y)))
                .or(() -> Optional.of(8080));
        Optional<String> health = build.contains("actuator")
                ? port.map(p -> "http://127.0.0.1:" + p + "/actuator/health")
                : Optional.empty();
        return Optional.of(new ComposeImport.DetectedApp(
                tool + task,
                task.equals("run") ? Optional.empty() : port,
                health,
                where(dir, maven ? "pom.xml" : "build.gradle"),
                List.of()));
    }

    private static Optional<ComposeImport.DetectedApp> django(Function<String, Optional<String>> read, String dir) {
        return read.apply("manage.py")
                .filter(m -> m.contains("django"))
                .map(m -> new ComposeImport.DetectedApp(
                        "python3 manage.py runserver 127.0.0.1:8000",
                        Optional.of(8000),
                        Optional.empty(),
                        where(dir, "manage.py"),
                        List.of()));
    }

    private static String where(String dir, String file) {
        return dir.isEmpty() ? file : dir + "/" + file;
    }

    private static Optional<Integer> first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private static Optional<JsonNode> json(String text) {
        try {
            return Optional.of(JSON.readTree(text));
        } catch (JacksonException e) {
            return Optional.empty(); // a broken package.json: nothing to detect, the scan notes the folder anyway
        }
    }
}
