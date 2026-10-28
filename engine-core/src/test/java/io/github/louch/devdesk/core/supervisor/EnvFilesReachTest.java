package io.github.louch.devdesk.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Who gets which env file's values: the rule docker compose follows, which Twenty's validation depends on. */
class EnvFilesReachTest {

    private static final Map<String, String> FILES = Map.of(
            ".env", "TAG=v2.52.0\nAUTH_GOOGLE_ENABLED=\n",
            "backend.env", "SECRET_KEY=s3cret\n");

    private static io.github.louch.devdesk.core.project.ProjectSpec project() throws Exception {
        return ConfigParser.parse("""
                version: 1
                env_files: [.env]
                services:
                  server:
                    container: { image: "twentycrm/twenty:${TAG}" }
                    env_files: [backend.env]
                    environment: { SECRET_KEY: "${TAG}-wins" }
                  db: { container: { image: postgres:16 } }
                  tool: { command: node tool.js }
                """, Path.of("/p"), Map.of(), f -> Optional.ofNullable(FILES.get(f)));
    }

    @Test
    void theProjectsEnvFileFillsVariablesButNeverReachesAContainer() throws Exception {
        var p = project();

        // ${TAG} came from .env, but .env's own values aren't the containers' settings.
        assertThat(((io.github.louch.devdesk.core.project.Workload.Container)
                                p.services().get("server").workload())
                        .image())
                .isEqualTo("twentycrm/twenty:v2.52.0");
        assertThat(ServiceEnvironment.values(p, p.services().get("db"), Map.of()))
                .doesNotContainKeys("TAG", "AUTH_GOOGLE_ENABLED");
        // A command on this machine still gets them: apps that read .env themselves expect it.
        assertThat(ServiceEnvironment.values(p, p.services().get("tool"), Map.of()))
                .containsEntry("TAG", "v2.52.0");
    }

    @Test
    void aServicesOwnEnvFileReachesThatServiceOnlyAndItsEnvironmentWins() throws Exception {
        var p = project();

        assertThat(ServiceEnvironment.values(p, p.services().get("server"), Map.of()))
                .containsEntry("SECRET_KEY", "v2.52.0-wins")
                .doesNotContainKey("AUTH_GOOGLE_ENABLED");
        assertThat(ServiceEnvironment.values(p, p.services().get("db"), Map.of()))
                .doesNotContainKey("SECRET_KEY");
    }
}
