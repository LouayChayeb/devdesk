package io.github.louch.devdesk.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment.Source;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment.Variable;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceEnvironmentTest {

    private static final String YAML = """
            version: 1
            env_files: [.env]
            services:
              api: { command: node server.js, environment: { PORT: "3001", LOG: debug } }
              db: { container: { image: "postgres:16", environment: { POSTGRES_PASSWORD: dev } } }
            """;

    private final Map<String, String> shell = Map.of("HOME", "/home/me", "LOG", "info", "AWS_SECRET_ACCESS_KEY", "x");

    private ServiceEnvironment.Variable find(String service, String key) throws Exception {
        var project = ConfigParser.parse(YAML, Path.of("/p"), Map.of(), f -> Optional.of("PORT=8080\nAPI_TOKEN=abc\n"));
        return ServiceEnvironment.of(project, project.services().get(service), shell).stream()
                .filter(v -> v.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    @Test
    void laterSourcesWinAndEachVariableSaysWhereItCameFrom() throws Exception {
        assertThat(find("api", "PORT")).isEqualTo(new Variable("PORT", "3001", Source.SERVICE)); // beats .env's 8080
        assertThat(find("api", "LOG")).isEqualTo(new Variable("LOG", "debug", Source.SERVICE)); // beats the shell
        assertThat(find("api", "API_TOKEN").source()).isEqualTo(Source.ENV_FILE);
        assertThat(find("api", "HOME").source()).isEqualTo(Source.SHELL);
        assertThat(find("api", "PYTHONUNBUFFERED").source()).isEqualTo(Source.DEVDESK);
    }

    @Test
    void containersNeverGetTheShellEnvironment() throws Exception {
        assertThat(find("db", "HOME")).isNull();
        assertThat(find("db", "AWS_SECRET_ACCESS_KEY")).isNull();
        assertThat(find("db", "POSTGRES_PASSWORD").source()).isEqualTo(Source.SERVICE);
    }

    @Test
    void secretLookingNamesAreFlagged() throws Exception {
        assertThat(find("api", "API_TOKEN").looksSecret()).isTrue();
        assertThat(find("db", "POSTGRES_PASSWORD").looksSecret()).isTrue();
        assertThat(find("api", "PORT").looksSecret()).isFalse();
    }

    @Test
    void aTaskGetsTheShellThenTheEnvFilesThenItsOwnBlock() throws Exception {
        var project = ConfigParser.parse("""
                version: 1
                env_files: [.env]
                services:
                  api: { command: x }
                tasks:
                  seed: { command: ./seed.sh, environment: { LOG: trace } }
                """, Path.of("/p"), Map.of(), f -> Optional.of("PORT=8080\nLOG=warn\n"));

        var values = ServiceEnvironment.forTask(project, project.tasks().get("seed"), shell);

        assertThat(values)
                .containsEntry("PYTHONUNBUFFERED", "1")
                .containsEntry("HOME", "/home/me")
                .containsEntry("PORT", "8080")
                .containsEntry("LOG", "trace"); // the task's own block beats .env's warn and the shell's info
        assertThat(ServiceEnvironment.forTask(project, project.tasks().get("seed"), Map.of("FORCE_COLOR", "0")))
                .containsEntry("FORCE_COLOR", "0"); // your shell beats DevDesk's defaults
    }
}
