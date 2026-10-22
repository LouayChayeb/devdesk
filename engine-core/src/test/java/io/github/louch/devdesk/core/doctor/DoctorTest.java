package io.github.louch.devdesk.core.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DoctorTest {

    private static final String YAML = """
            version: 1
            services:
              db: { container: { image: postgres:18, ports: ["5432:5432"] } }
              api: { command: npm run dev, directory: api }
              jobs: { command: python3 jobs.py }
              backend: { command: ./mvnw spring-boot:run, directory: backend }
            """;

    private static ProjectSpec project() throws Exception {
        return ConfigParser.parse(YAML, Path.of("/p"), Map.of(), f -> Optional.empty());
    }

    private static Doctor.Facts facts(Map<String, String> files, Map<String, String> versions, long free) {
        return new Doctor.Facts(
                p -> Optional.ofNullable(files.get(p.toString())),
                p -> files.containsKey(p.toString()),
                t -> Optional.ofNullable(versions.get(t)),
                Optional.empty(),
                Optional.of(Set.of("redis:8-alpine")),
                free);
    }

    @Test
    void theWrongVersionsAreNamedWithWhereTheRequirementComesFrom() throws Exception {
        var report = Doctor.check(
                project(),
                facts(
                        Map.of(
                                "/p/api/.nvmrc", "22\n",
                                "/p/api/package.json", "{\"dependencies\": {\"express\": \"^5\"}}",
                                "/p/.python-version", "3.12\n",
                                "/p/backend/pom.xml", "<properties><java.version>21</java.version></properties>"),
                        Map.of("node", "v18.19.0", "python", "Python 3.12.3", "java", "openjdk 25 2025-09-16"),
                        50 * Doctor.GIB),
                List.of());

        assertThat(report.problems())
                .extracting(Finding::title)
                .contains(
                        "Node 18.19.0 found, api needs 22",
                        "api's packages aren't installed",
                        "postgres:18 isn't downloaded yet");
        var node = report.problems().stream()
                .filter(f -> f.ruleId().equals("tool-version"))
                .findFirst()
                .orElseThrow();
        assertThat(node.explanation()).startsWith("api/.nvmrc asks for Node 22");
        assertThat(node.actions()).containsExactly("install Node 22 with nvm: nvm install 22 && nvm use 22");
        assertThat(report.problems().stream()
                        .filter(f -> f.ruleId().equals("packages-missing"))
                        .findFirst()
                        .orElseThrow()
                        .actions())
                .containsExactly("cd api && npm install");
        assertThat(report.passed())
                .contains("Python 3.12.3 installed: jobs needs 3.12 (.python-version)")
                .contains("Java 25 installed: backend needs >=21 (backend/pom.xml)")
                .contains("Docker is running")
                .anySatisfy(p -> assertThat(p).endsWith("of disk space free"));
        // errors first, then warnings
        assertThat(report.problems().getLast().severity()).isEqualTo(Finding.Severity.WARNING);
    }

    @Test
    void aMissingToolOrDockerOrDiskIsAnErrorWithAFix() throws Exception {
        var noDocker = new Doctor.Facts(
                p -> Optional.empty(),
                p -> false,
                t -> Optional.empty(),
                Optional.of("Docker isn't running"),
                Optional.empty(),
                Doctor.GIB / 2);

        var report = Doctor.check(project(), noDocker, List.of());

        assertThat(report.problems())
                .extracting(Finding::ruleId)
                .contains("tool-missing", "docker-unavailable", "disk-full");
        assertThat(report.problems().stream()
                        .filter(f -> f.title().equals("Python isn't installed"))
                        .findFirst())
                .hasValueSatisfying(f -> assertThat(f.actions().getFirst()).contains("pyenv install"));
    }

    @Test
    void aProgramAlreadyReportedMissingIsNotReportedTwice() throws Exception {
        var preflight = new Finding(
                "program-missing", Finding.Severity.ERROR, "api", "`npm` was not found", "", List.of(), List.of());

        var report = Doctor.check(
                project(),
                facts(Map.of(), Map.of("python", "3.12", "java", "21"), 50 * Doctor.GIB),
                List.of(preflight));

        assertThat(report.problems())
                .extracting(Finding::title)
                .contains("`npm` was not found")
                .doesNotContain("Node isn't installed");
        assertThat(report.passed()).doesNotContain("Every program the services start was found");
    }

    @Test
    void servicesSharingOneVersionFileGetOneProblem() throws Exception {
        var project = ConfigParser.parse(
                "version: 1\nservices:\n  a: { command: python3 a.py }\n  b: { command: python3 b.py }\n"
                        + "  c: { command: python3 c.py }\n",
                Path.of("/p"),
                Map.of(),
                f -> Optional.empty());

        var report = Doctor.check(
                project,
                facts(Map.of("/p/.python-version", "3.99\n"), Map.of("python", "Python 3.12.3"), 50 * Doctor.GIB),
                List.of());

        assertThat(report.problems())
                .filteredOn(f -> f.ruleId().equals("tool-version"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.title()).isEqualTo("Python 3.12.3 found, 3 services need 3.99");
                    assertThat(f.explanation()).startsWith(".python-version asks for Python 3.99 (for a, b, c)");
                });
    }

    @Test
    void imageNamesAreComparedTheWayDockerListsThem() {
        assertThat(Doctor.normalize("postgres")).isEqualTo("postgres:latest");
        assertThat(Doctor.normalize("docker.io/library/postgres:18")).isEqualTo("postgres:18");
        assertThat(Doctor.normalize("localhost:5000/team/api")).isEqualTo("localhost:5000/team/api:latest");
    }
}
