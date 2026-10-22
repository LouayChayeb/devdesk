package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComposeImportTest {

    private static final String COMPOSE = """
            services:
              db:
                image: postgres:16
                environment:
                  - POSTGRES_PASSWORD=secret
                  - POSTGRES_DB=shop
                  - FROM_SHELL
                ports: ["5432:5432"]
                volumes: [pgdata:/var/lib/postgresql/data, ./init.sql:/docker-entrypoint-initdb.d/init.sql:ro]
                healthcheck:
                  test: ["CMD-SHELL", "pg_isready -U postgres"]
                  start_period: 10s
              cache:
                image: redis:7
                ports: ["127.0.0.1:6380:6379"]
              api:
                build: ./api
                env_file: .env
                environment:
                  DATABASE_URL: postgres://postgres:secret@db:5432/shop
                  REDIS_HOST: cache
                ports: ["3000:3000"]
                depends_on:
                  db: { condition: service_healthy }
                  cache: { condition: service_started }
                restart: unless-stopped
              web:
                image: nginx
                ports: ["80", "8000-8001:8000-8001"]
                command: [nginx, -g, "daemon off;"]
            volumes:
              pgdata:
            """;

    private final ComposeImport.Result result = ComposeImport.read(
            COMPOSE,
            "shop",
            "My Shop",
            dir -> dir.equals("api")
                    ? Optional.of(new ComposeImport.DetectedApp(
                            "npm run dev",
                            Optional.of(3000),
                            Optional.empty(),
                            "api/package.json script \"dev\"",
                            List.of()))
                    : Optional.empty());

    private SetupDraft.Service card(String name) {
        return result.services().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void imagesBecomeContainersWithTheirPortsEnvAndVolumes() {
        var db = card("db");

        assertThat(db.kind()).isEqualTo("container");
        assertThat(db.image()).isEqualTo("postgres:16");
        assertThat(db.ports()).containsExactly("5432:5432");
        assertThat(db.environment())
                .containsExactly(Map.entry("POSTGRES_PASSWORD", "secret"), Map.entry("POSTGRES_DB", "shop"));
        // compose names a volume {project}_{name}: keeping it keeps the data the user already has
        assertThat(db.volumes())
                .containsExactly(
                        "myshop_pgdata:/var/lib/postgresql/data", "./init.sql:/docker-entrypoint-initdb.d/init.sql:ro");
        assertThat(db.notes()).anySatisfy(n -> assertThat(n).startsWith("FROM_SHELL takes its value from your shell"));
    }

    @Test
    void healthchecksRunInsideTheContainerAndKnownImagesGetOne() {
        assertThat(card("db").health())
                .isEqualTo(
                        SetupDraft.Health.command("docker exec devdesk-shop-db sh -c 'pg_isready -U postgres'", "10s"));
        assertThat(card("cache").health().target()).isEqualTo("docker exec devdesk-shop-cache redis-cli ping");
        assertThat(card("cache").ports()).containsExactly("127.0.0.1:6380:6379");
    }

    @Test
    void aBuiltServiceRunsOnThisMachineAndReachesTheOthersThroughTheHost() {
        var api = card("api");

        assertThat(api.kind()).isEqualTo("command");
        assertThat(api.command()).isEqualTo("npm run dev");
        assertThat(api.directory()).isEqualTo("api");
        assertThat(api.ports()).containsExactly("3000");
        assertThat(api.environment())
                .containsEntry("DATABASE_URL", "postgres://postgres:secret@127.0.0.1:5432/shop")
                .containsEntry("REDIS_HOST", "127.0.0.1");
        assertThat(api.dependsOn()).containsExactly(Map.entry("db", "healthy"), Map.entry("cache", "started"));
        assertThat(api.restart()).isEqualTo("always");
        assertThat(api.health()).isEqualTo(SetupDraft.Health.tcp(3000));
        assertThat(result.envFiles()).containsExactly(".env");
    }

    @Test
    void whatCantBeMappedIsSaidNotDropped() {
        var web = card("web");

        assertThat(web.ports()).isEmpty();
        assertThat(web.notes()).hasSize(2).allSatisfy(n -> assertThat(n).contains("was skipped"));
        assertThat(web.command()).isEqualTo("nginx -g 'daemon off;'");
    }

    @Test
    void aBrokenFileIsANoteNotACrash() {
        var broken = ComposeImport.read("services: [", "x", "x", d -> Optional.empty());

        assertThat(broken.services()).isEmpty();
        assertThat(broken.notes()).singleElement().asString().startsWith("docker-compose.yml could not be read");
    }
}
