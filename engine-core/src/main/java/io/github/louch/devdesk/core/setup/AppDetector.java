package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.doctor.Install;
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
 * (Spring Boot, Quarkus), manage.py (Django), a FastAPI or Flask app, and, for a compose build folder, the
 * Dockerfile's CMD. Pure: it only reads file contents it is given.
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
        return detect(read, dir, List.of());
    }

    /** @param files the folder's file names when known (the project folder): Go's entry file may have any name */
    static Optional<ComposeImport.DetectedApp> detect(
            Function<String, Optional<String>> read, String dir, List<String> files) {
        Function<String, Optional<String>> here = file -> read.apply(dir.isEmpty() ? file : dir + "/" + file);
        return node(here, dir)
                .or(() -> java(here, dir))
                .or(() -> django(here, dir))
                .or(() -> python(here, dir))
                .or(() -> go(here, dir, files));
    }

    private static final Pattern GO_PORT = Pattern.compile("\"(?::)?(\\d{4,5})\"");

    /** A Go module whose folder holds func main (package main): "go run ." builds and starts it. */
    private static Optional<ComposeImport.DetectedApp> go(
            Function<String, Optional<String>> read, String dir, List<String> files) {
        if (read.apply("go.mod").isEmpty()) {
            return Optional.empty();
        }
        List<String> candidates = new java.util.ArrayList<>(List.of("main.go"));
        files.stream().filter(f -> f.endsWith(".go") && !f.endsWith("_test.go")).forEach(candidates::add);
        for (String file : candidates) {
            Optional<String> code =
                    read.apply(file).filter(c -> c.contains("package main") && c.contains("func main("));
            if (code.isPresent()) {
                return Optional.of(new ComposeImport.DetectedApp(
                        "go run .",
                        first(GO_PORT, code.get()),
                        Optional.empty(),
                        where(dir, "go.mod") + " (" + file + ")",
                        code.get().contains("Getenv(\"PORT\")")
                                ? List.of("It reads PORT: set it under environment: if the default doesn't suit.")
                                : List.of()));
            }
        }
        return Optional.empty();
    }

    /**
     * For a folder compose builds an image from: what {@link #detect} finds, else the Dockerfile's own CMD, which says
     * how the app starts (it then assumes the packages the image installs are installed here too).
     */
    static Optional<ComposeImport.DetectedApp> detectBuild(Function<String, Optional<String>> read, String dir) {
        Function<String, Optional<String>> here = file -> read.apply(dir.isEmpty() ? file : dir + "/" + file);
        return detect(read, dir).or(() -> dockerfile(here, dir));
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
            return nodeMain(read, dir, pkg.get());
        }
        String body = scripts.path(script.get()).asString("");
        // Each package manager keeps its own lock file; running another one would install different versions.
        // A version pinned in package.json wins, through corepack (see Install.pinnedManager).
        Optional<String> pinned = Install.pinnedManager(read);
        String runner = pinned.isPresent()
                ? pinned.get() + (pinned.get().endsWith("pnpm") ? " run " : " ")
                : read.apply("pnpm-lock.yaml").isPresent()
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
                ? List.of("Its packages aren't installed yet: run \"" + Install.nodeInstall(read) + "\" in ./"
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

    private static final Pattern LISTEN = Pattern.compile("(?:listen\\(|PORT\\s*\\|\\|\\s*)(\\d{2,5})");

    /** No dev script: "main" in package.json (or server.js / index.js / app.js) run with node. */
    private static Optional<ComposeImport.DetectedApp> nodeMain(
            Function<String, Optional<String>> read, String dir, JsonNode pkg) {
        String main = pkg.path("main").asString("");
        Optional<String> file = (main.isBlank() ? List.of("server.js", "index.js", "app.js") : List.of(main))
                .stream().filter(f -> read.apply(f).isPresent()).findFirst();
        if (file.isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> port = read.apply(".env")
                .flatMap(env -> first(ENV_PORT, env))
                .or(() -> read.apply(file.get()).flatMap(code -> first(LISTEN, code)));
        boolean installed = read.apply("node_modules/.package-lock.json").isPresent();
        return Optional.of(new ComposeImport.DetectedApp(
                "node " + file.get(),
                port,
                Optional.empty(),
                where(dir, "package.json") + (main.isBlank() ? " (" + file.get() + ")" : " \"main\""),
                installed || pkg.path("dependencies").isEmpty()
                        ? List.of()
                        : List.of("Its packages aren't installed yet: run \"npm install\" in ./" + dir
                                + " once, or the start command will fail.")));
    }

    private static final Pattern FASTAPI = Pattern.compile("(?m)^(\\w+)\\s*=\\s*FastAPI\\(");
    private static final Pattern FLASK_APP = Pattern.compile("(?m)^\\s*FLASK_APP\\s*=\\s*[\"']?([\\w./:-]+)");
    private static final Pattern FLASK = Pattern.compile("(?m)^(\\w+)\\s*=\\s*Flask\\(");

    /** A FastAPI or Flask app in main.py or app.py, whose packages requirements.txt or pyproject.toml list. */
    private static Optional<ComposeImport.DetectedApp> python(Function<String, Optional<String>> read, String dir) {
        String deps = read.apply("requirements.txt").orElse("")
                + read.apply("pyproject.toml").orElse("");
        String install =
                read.apply("requirements.txt").isPresent() ? "pip install -r requirements.txt" : "pip install -e .";
        // FLASK_APP in .flaskenv (or .env) names the app, also one made by a factory (app = create_app()).
        Optional<String> flaskApp = read.apply(".flaskenv")
                .or(() -> read.apply(".env"))
                .flatMap(env -> {
                    Matcher m = FLASK_APP.matcher(env);
                    return m.find() ? Optional.of(m.group(1).replaceFirst("\\.py$", "")) : Optional.empty();
                });
        if (flaskApp.isPresent() && deps.toLowerCase().contains("flask")) {
            return Optional.of(new ComposeImport.DetectedApp(
                    inVenv(read, "flask --app " + flaskApp.get() + " run --port 5000"),
                    Optional.of(5000),
                    Optional.empty(),
                    where(dir, ".flaskenv") + " FLASK_APP",
                    List.of("It needs its packages: run \"" + install + "\" in ./" + dir
                            + " once (in a virtualenv).")));
        }
        for (String file : List.of("main.py", "app.py")) {
            Optional<String> code = read.apply(file);
            if (code.isEmpty()) {
                continue;
            }
            String module = file.replace(".py", "");
            Matcher fast = FASTAPI.matcher(code.get());
            if (fast.find() && deps.toLowerCase().contains("fastapi")) {
                return Optional.of(new ComposeImport.DetectedApp(
                        inVenv(
                                read,
                                "uvicorn " + module + ":" + fast.group(1) + " --reload --host 127.0.0.1 --port 8000"),
                        Optional.of(8000),
                        Optional.empty(),
                        where(dir, file) + " (FastAPI)",
                        List.of("It needs its packages: run \"" + install + "\" in ./" + dir
                                + " once (in a virtualenv).")));
            }
            Matcher flask = FLASK.matcher(code.get());
            if (flask.find() && deps.toLowerCase().contains("flask")) {
                return Optional.of(new ComposeImport.DetectedApp(
                        inVenv(read, "flask --app " + module + " run --port 5000"),
                        Optional.of(5000),
                        Optional.empty(),
                        where(dir, file) + " (Flask)",
                        List.of("It needs its packages: run \"" + install + "\" in ./" + dir
                                + " once (in a virtualenv).")));
            }
        }
        return Optional.empty();
    }

    private static final Pattern CMD = Pattern.compile("(?im)^\\s*CMD\\s+(.+)$");
    private static final Pattern ENTRYPOINT = Pattern.compile("(?im)^\\s*ENTRYPOINT\\s+(.+)$");
    private static final Pattern EXPOSE = Pattern.compile("(?im)^\\s*EXPOSE\\s+(\\d{2,5})");

    /**
     * What the Dockerfile runs: its last ENTRYPOINT followed by its last CMD (as Docker does), each in exec form
     * ["node", "server.js"] or shell form, and its first EXPOSE as the port.
     */
    private static Optional<ComposeImport.DetectedApp> dockerfile(Function<String, Optional<String>> read, String dir) {
        Optional<String> file = read.apply("Dockerfile");
        if (file.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> entrypoint = last(ENTRYPOINT, file.get()).flatMap(AppDetector::words);
        Optional<String> cmd = last(CMD, file.get()).flatMap(AppDetector::words);
        if (entrypoint.isEmpty() && cmd.isEmpty()) {
            return Optional.empty();
        }
        String line = String.join(
                " ",
                java.util.stream.Stream.of(entrypoint, cmd)
                        .flatMap(Optional::stream)
                        .toList());
        return Optional.of(new ComposeImport.DetectedApp(
                line,
                first(EXPOSE, file.get()),
                Optional.empty(),
                where(dir, "Dockerfile") + (entrypoint.isPresent() ? " ENTRYPOINT" : " CMD"),
                List.of("Taken from the Dockerfile's CMD: it assumes what the image installs (packages, tools) is"
                        + " installed here too. Check it before starting.")));
    }

    /** The last match: a later instruction replaces an earlier one, as in Docker. */
    private static Optional<String> last(Pattern p, String text) {
        Matcher m = p.matcher(text);
        String found = null;
        while (m.find()) {
            found = m.group(1).strip();
        }
        return Optional.ofNullable(found);
    }

    /** Exec form ["dotnet", "Worker.dll"] as a shell line; shell form as it is. */
    private static Optional<String> words(String instruction) {
        if (!instruction.startsWith("[")) {
            return Optional.of(instruction);
        }
        Optional<JsonNode> words = json(instruction);
        if (words.isEmpty() || !words.get().isArray()) {
            return Optional.empty();
        }
        List<String> parts = new java.util.ArrayList<>();
        words.get().forEach(w -> parts.add(quoteIfNeeded(w.asString(""))));
        return Optional.of(String.join(" ", parts));
    }

    private static String quoteIfNeeded(String word) {
        return word.matches("[\\w@%+=:,./-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
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
        if (script.matches(".*\\b(next|nuxt|react-scripts|nest start|docusaurus)\\b.*")) {
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
                        inVenv(read, "python3 manage.py runserver 127.0.0.1:8000"),
                        Optional.of(8000),
                        Optional.empty(),
                        where(dir, "manage.py"),
                        List.of()));
    }

    /**
     * The command with the program from the folder's {@code .venv} when it has a requirements.txt: that's where Set up
     * installs the packages (Ubuntu forbids pip in the system Python, PEP 668), so a plain {@code python3} or
     * {@code uvicorn} wouldn't find them. Without a requirements.txt the command stays as it is.
     */
    static String inVenv(Function<String, Optional<String>> read, String command) {
        return read.apply("requirements.txt").isPresent() ? ".venv/bin/" + command : command;
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
