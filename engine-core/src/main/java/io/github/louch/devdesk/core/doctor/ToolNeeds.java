package io.github.louch.devdesk.core.doctor;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which tools each service needs, and which version its own files ask for: {@code .nvmrc}, package.json
 * {@code engines}, {@code .python-version}, pom.xml's {@code java.version}, Gradle's toolchain…
 */
public final class ToolNeeds {

    /**
     * One tool a service needs.
     *
     * @param tool "node", "python", "java", "docker", "pnpm"…
     * @param version what the project asks for (".nvmrc says 22"), empty when nothing says
     * @param source the file the version comes from, relative to the project ("api/.nvmrc")
     */
    public record Need(String tool, String service, Optional<String> version, String source) {}

    private static final Pattern POM_JAVA = Pattern.compile(
            "<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source|release)>\\s*(\\d+)\\s*<");
    private static final Pattern GRADLE_JAVA = Pattern.compile(
            "JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)|sourceCompatibility\\s*=\\s*(?:JavaVersion\\.VERSION_)?['\"]?(\\d+)");
    private static final Pattern REQUIRES_PYTHON = Pattern.compile("requires-python\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern RUNTIME_TXT = Pattern.compile("python-(\\d+(?:\\.\\d+){0,2})");
    private static final Pattern SDKMAN_JAVA = Pattern.compile("(?m)^java=(\\d+)");
    private static final Pattern GO_TOOLCHAIN = Pattern.compile("(?m)^toolchain\\s+go(\\d+(?:\\.\\d+){0,2})");
    private static final Pattern GO_LINE = Pattern.compile("(?m)^go\\s+(\\d+(?:\\.\\d+){0,2})");
    private static final Pattern TARGET_FRAMEWORK =
            Pattern.compile("<TargetFramework>net(\\d+\\.\\d+)</TargetFramework>");
    private static final JsonMapper JSON = JsonMapper.shared();

    private ToolNeeds() {}

    /** @param read reads a file (absolute path); empty when it doesn't exist */
    public static List<Need> of(ProjectSpec project, Function<Path, Optional<String>> read) {
        List<Need> needs = new ArrayList<>();
        for (ServiceSpec s : project.services().values()) {
            String rel = project.root().relativize(s.directory()).toString();
            Function<String, Optional<String>> here =
                    f -> read.apply(s.directory().resolve(f));
            Function<String, String> where = f -> rel.isEmpty() ? f : rel + "/" + f;
            if (s.workload() instanceof Workload.Container
                    || s.health() instanceof HealthCheckSpec.Command c
                            && c.command().startsWith("docker ")) {
                needs.add(new Need("docker", s.name(), Optional.empty(), "devdesk.yaml"));
            }
            if (!(s.workload() instanceof Workload.Command cmd)) {
                continue;
            }
            String program = program(cmd.commandLine());
            switch (program) {
                case "npm", "npx", "node", "pnpm", "yarn" -> {
                    needs.add(node(s.name(), here, where));
                    // Through corepack the pinned yarn/pnpm comes with Node: nothing else to install.
                    if ((program.equals("pnpm") || program.equals("yarn"))
                            && !cmd.commandLine().strip().startsWith("corepack ")) {
                        needs.add(new Need(program, s.name(), Optional.empty(), "devdesk.yaml"));
                    }
                }
                case "python", "python3", "uvicorn", "gunicorn", "flask", "pip", "pip3" ->
                    needs.add(python(s.name(), here, where));
                case "java", "mvn", "./mvnw", "gradle", "./gradlew" -> {
                    needs.add(java(s.name(), here, where));
                    if (program.equals("mvn") || program.equals("gradle")) {
                        needs.add(new Need(program, s.name(), Optional.empty(), "devdesk.yaml"));
                    }
                }
                case "go" -> needs.add(go(s.name(), here, where));
                case "dotnet" -> needs.add(dotnet(s.name(), s.directory(), here, where));
                default -> {
                    // other programs: "program-missing" already checks they exist
                }
            }
        }
        return needs;
    }

    private static Need node(String service, Function<String, Optional<String>> here, Function<String, String> where) {
        Optional<String> nvmrc =
                here.apply(".nvmrc").or(() -> here.apply(".node-version")).map(String::strip);
        if (nvmrc.isPresent()) {
            return new Need(
                    "node", service, nvmrc, where.apply(here.apply(".nvmrc").isPresent() ? ".nvmrc" : ".node-version"));
        }
        Optional<String> engines = here.apply("package.json").flatMap(p -> {
            try {
                String v = JSON.readTree(p).path("engines").path("node").asString("");
                return v.isBlank() ? Optional.empty() : Optional.of(v);
            } catch (JacksonException e) {
                return Optional.empty();
            }
        });
        return new Need("node", service, engines, engines.isPresent() ? where.apply("package.json") : "devdesk.yaml");
    }

    private static Need python(
            String service, Function<String, Optional<String>> here, Function<String, String> where) {
        Optional<String> pv = here.apply(".python-version")
                .map(String::strip)
                .map(v -> v.lines().findFirst().orElse(v));
        if (pv.isPresent()) {
            return new Need("python", service, pv, where.apply(".python-version"));
        }
        Optional<String> requires = here.apply("pyproject.toml").flatMap(t -> first(REQUIRES_PYTHON, t));
        if (requires.isPresent()) {
            return new Need("python", service, requires, where.apply("pyproject.toml"));
        }
        Optional<String> runtime = here.apply("runtime.txt").flatMap(t -> first(RUNTIME_TXT, t));
        return new Need("python", service, runtime, runtime.isPresent() ? where.apply("runtime.txt") : "devdesk.yaml");
    }

    private static Need java(String service, Function<String, Optional<String>> here, Function<String, String> where) {
        Map<String, Optional<String>> sources = new LinkedHashMap<>();
        sources.put(
                ".java-version",
                here.apply(".java-version").map(String::strip).flatMap(v -> first(Pattern.compile("^(\\d+)"), v)));
        sources.put(".sdkmanrc", here.apply(".sdkmanrc").flatMap(t -> first(SDKMAN_JAVA, t)));
        sources.put("pom.xml", here.apply("pom.xml").flatMap(t -> first(POM_JAVA, t)));
        sources.put("build.gradle.kts", here.apply("build.gradle.kts").flatMap(t -> first(GRADLE_JAVA, t)));
        sources.put("build.gradle", here.apply("build.gradle").flatMap(t -> first(GRADLE_JAVA, t)));
        for (var e : sources.entrySet()) {
            if (e.getValue().isPresent()) {
                // A build's Java version is a minimum: a newer JDK compiles for an older release.
                return new Need("java", service, e.getValue().map(v -> ">=" + v), where.apply(e.getKey()));
            }
        }
        return new Need("java", service, Optional.empty(), "devdesk.yaml");
    }

    /** go.mod's "toolchain go1.22.3" if it has one (the exact Go it wants), else its "go 1.21" line (a minimum). */
    private static Need go(String service, Function<String, Optional<String>> here, Function<String, String> where) {
        Optional<String> mod = here.apply("go.mod");
        Optional<String> version = mod.flatMap(t -> first(GO_TOOLCHAIN, t))
                .or(() -> mod.flatMap(t -> first(GO_LINE, t)))
                .map(v -> ">=" + v);
        return new Need("go", service, version, version.isPresent() ? where.apply("go.mod") : "devdesk.yaml");
    }

    /** global.json's sdk.version, else the TargetFramework (net8.0) of the project file named like its folder. */
    private static Need dotnet(
            String service, Path directory, Function<String, Optional<String>> here, Function<String, String> where) {
        Optional<String> global = here.apply("global.json").flatMap(g -> {
            try {
                String v = JSON.readTree(g).path("sdk").path("version").asString("");
                return v.isBlank() ? Optional.empty() : Optional.of(v);
            } catch (JacksonException e) {
                return Optional.empty();
            }
        });
        if (global.isPresent()) {
            return new Need("dotnet", service, global.map(v -> ">=" + v), where.apply("global.json"));
        }
        String csproj =
                (directory.getFileName() == null ? "" : directory.getFileName().toString()) + ".csproj";
        Optional<String> framework = here.apply(csproj).flatMap(t -> first(TARGET_FRAMEWORK, t));
        return new Need(
                "dotnet",
                service,
                framework.map(v -> ">=" + v),
                framework.isPresent() ? where.apply(csproj) : "devdesk.yaml");
    }

    /** The first group that matched, of a pattern that may have several alternatives. */
    private static Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) {
                return Optional.of(m.group(g).strip());
            }
        }
        return Optional.empty();
    }

    /**
     * The program a command line runs, seen through what the setup wizard puts in front of it: "corepack yarn dev"
     * runs yarn (corepack only picks its version), ".venv/bin/python3 manage.py" runs python3. Without this the
     * version checks miss exactly the services the wizard set up.
     */
    static String program(String commandLine) {
        String[] words = commandLine.strip().split("\\s+");
        String program = words[0].equals("corepack") && words.length > 1 ? words[1] : words[0];
        return program.startsWith(".venv/bin/") ? program.substring(".venv/bin/".length()) : program;
    }
}
