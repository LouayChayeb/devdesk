package io.github.louch.devdesk.core.testing;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.TaskSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Each service's test command: its {@code test:} line in devdesk.yaml, or else the usual one for what's in its folder
 * ({@code package.json} → {@code npm test}, {@code go.mod} → {@code go test ./...}). Core reads no files itself.
 */
public final class TestCommands {

    /** What {@code npm init} writes: a placeholder that always fails, not a real test script. */
    private static final String NPM_PLACEHOLDER = "no test specified";

    private static final Pattern NPM_TEST_SCRIPT = Pattern.compile(
            "\"test\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""); // a JSON string; \" inside it is an escaped quote

    private TestCommands() {}

    /** A detected command and the file it was detected from. */
    public record Detected(String command, String because) {}

    /**
     * Every service that has tests, as a task named after the service. It runs in the service's folder, with the
     * service's own variables, and starts nothing first: a test that needs a database says so with a task instead.
     *
     * @param readFile reads a file by absolute path; empty if it doesn't exist
     */
    public static Map<String, TaskSpec> of(ProjectSpec project, Function<Path, Optional<String>> readFile) {
        Map<String, TaskSpec> tests = new LinkedHashMap<>();
        for (ServiceSpec s : project.services().values()) {
            if (s.test().isPresent()) {
                tests.put(s.name(), task(s, s.test().get(), "from devdesk.yaml"));
            } else if (!(s.workload() instanceof Workload.Container)) {
                // A container's folder is the project's: its package.json belongs to some other service.
                detect(name -> readFile.apply(s.directory().resolve(name)))
                        .ifPresent(d -> tests.put(
                                s.name(),
                                task(
                                        s,
                                        d.command(),
                                        "detected from " + d.because() + " (set test: in devdesk.yaml to change it)")));
            }
        }
        return tests;
    }

    private static TaskSpec task(ServiceSpec s, String command, String description) {
        // CI=true: Jest (create-react-app) otherwise starts its watch mode and never ends; most tools also print
        // plain lines instead of animations. The service's own block can still override it.
        Map<String, String> environment = new LinkedHashMap<>(Map.of("CI", "true"));
        environment.putAll(s.environment());
        return new TaskSpec(s.name(), command, s.directory(), environment, List.of(), description, Optional.empty());
    }

    /** @param file reads a file of the service's folder by name */
    public static Optional<Detected> detect(Function<String, Optional<String>> file) {
        Optional<String> packageJson = file.apply("package.json");
        if (packageJson.isPresent()) {
            var script = NPM_TEST_SCRIPT.matcher(packageJson.get());
            if (script.find() && !script.group(1).contains(NPM_PLACEHOLDER)) {
                // The lock file says which package manager the project uses; mixing them breaks node_modules.
                String npm = file.apply("pnpm-lock.yaml").isPresent()
                        ? "pnpm"
                        : file.apply("yarn.lock").isPresent() ? "yarn" : "npm";
                return Optional.of(new Detected(npm + " test", "package.json"));
            }
        }
        if (file.apply("pytest.ini").isPresent()
                || file.apply("conftest.py").isPresent()
                || file.apply("pyproject.toml")
                        .filter(t -> t.contains("[tool.pytest"))
                        .isPresent()
                || file.apply("setup.cfg")
                        .filter(t -> t.contains("[tool:pytest]"))
                        .isPresent()) {
            return Optional.of(new Detected("python3 -m pytest", "the pytest settings"));
        }
        if (file.apply("go.mod").isPresent()) {
            return Optional.of(new Detected("go test ./...", "go.mod"));
        }
        if (file.apply("Cargo.toml").isPresent()) {
            return Optional.of(new Detected("cargo test", "Cargo.toml"));
        }
        if (file.apply("gradlew").isPresent()
                && (file.apply("build.gradle.kts").isPresent()
                        || file.apply("build.gradle").isPresent())) {
            return Optional.of(new Detected("./gradlew test", "the Gradle wrapper"));
        }
        if (file.apply("pom.xml").isPresent()) {
            return Optional.of(new Detected(file.apply("mvnw").isPresent() ? "./mvnw test" : "mvn test", "pom.xml"));
        }
        return Optional.empty();
    }
}
