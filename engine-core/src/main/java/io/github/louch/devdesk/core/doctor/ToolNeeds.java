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
            String program = cmd.commandLine().strip().split("\\s+")[0];
            switch (program) {
                case "npm", "npx", "node", "pnpm", "yarn" -> {
                    needs.add(node(s.name(), here, where));
                    if (program.equals("pnpm") || program.equals("yarn")) {
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
}
