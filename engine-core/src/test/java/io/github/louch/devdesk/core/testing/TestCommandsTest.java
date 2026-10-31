package io.github.louch.devdesk.core.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TestCommandsTest {

    private static Optional<TestCommands.Detected> detect(Map<String, String> files) {
        return TestCommands.detect(name -> Optional.ofNullable(files.get(name)));
    }

    @Test
    void npmUsesTheProjectsPackageManager() {
        String pkg = "{\"scripts\": {\"test\": \"jest\"}}";
        assertThat(detect(Map.of("package.json", pkg)).orElseThrow().command()).isEqualTo("npm test");
        assertThat(detect(Map.of("package.json", pkg, "pnpm-lock.yaml", ""))
                        .orElseThrow()
                        .command())
                .isEqualTo("pnpm test");
        assertThat(detect(Map.of("package.json", pkg, "yarn.lock", ""))
                        .orElseThrow()
                        .command())
                .isEqualTo("yarn test");
        // Pinned in package.json (like outline: "yarn@4.18.0"): through corepack, whatever yarn is installed.
        String pinned = "{\"packageManager\": \"yarn@4.18.0\", \"scripts\": {\"test\": \"jest\"}}";
        assertThat(detect(Map.of("package.json", pinned, "yarn.lock", ""))
                        .orElseThrow()
                        .command())
                .isEqualTo("corepack yarn test");
    }

    @Test
    void npmsPlaceholderScriptIsNotATest() {
        String placeholder = "{\"scripts\": {\"test\": \"echo \\\"Error: no test specified\\\" && exit 1\"}}";
        assertThat(detect(Map.of("package.json", placeholder))).isEmpty();
    }

    @Test
    void otherLanguages() {
        assertThat(detect(Map.of("pyproject.toml", "[tool.pytest.ini_options]\n"))
                        .orElseThrow()
                        .command())
                .isEqualTo("python3 -m pytest");
        assertThat(detect(Map.of("pyproject.toml", "[project]\n"))).isEmpty();
        assertThat(detect(Map.of("go.mod", "")).orElseThrow().command()).isEqualTo("go test ./...");
        assertThat(detect(Map.of("Cargo.toml", "")).orElseThrow().command()).isEqualTo("cargo test");
        assertThat(detect(Map.of("gradlew", "", "build.gradle.kts", ""))
                        .orElseThrow()
                        .command())
                .isEqualTo("./gradlew test");
        assertThat(detect(Map.of("pom.xml", "")).orElseThrow().command()).isEqualTo("mvn test");
        assertThat(detect(Map.of("pom.xml", "", "mvnw", "")).orElseThrow().command())
                .isEqualTo("./mvnw test");
    }

    @Test
    void devdeskYamlWinsAndContainersAreNeverGuessed() throws ConfigException {
        ProjectSpec project = ConfigParser.parse("""
                version: 1
                name: shop
                services:
                  api:
                    command: npm run dev
                    directory: api
                    test: npm run test:unit
                    environment:
                      CI: "false"
                  worker:
                    command: go run .
                    directory: worker
                  db:
                    container:
                      image: postgres:16
                """, Path.of("/work/shop"), Map.of(), file -> Optional.empty());
        // Every folder "has" a go.mod and a package.json: only the services that should get a test do.
        var tests = TestCommands.of(
                project,
                path -> path.getFileName().toString().equals("go.mod")
                                || path.getFileName().toString().equals("package.json")
                        ? Optional.of("{\"scripts\": {\"test\": \"jest\"}}")
                        : Optional.empty());
        assertThat(tests).containsOnlyKeys("api", "worker");
        assertThat(tests.get("api").command()).isEqualTo("npm run test:unit");
        assertThat(tests.get("api").description()).isEqualTo("from devdesk.yaml");
        assertThat(tests.get("api").environment()).containsEntry("CI", "false"); // the service's own value wins
        assertThat(tests.get("api").directory()).isEqualTo(Path.of("/work/shop/api"));
        assertThat(tests.get("worker").command()).isEqualTo("npm test"); // package.json is checked before go.mod
        assertThat(tests.get("worker").environment()).containsEntry("CI", "true");
        assertThat(tests.get("worker").description()).startsWith("detected from package.json");
    }
}
