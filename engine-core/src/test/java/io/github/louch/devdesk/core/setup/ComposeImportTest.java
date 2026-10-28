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
    void aBuiltServiceIsBuiltFromItsDockerfileAndReachesTheOthersByName() {
        var api = card("api");

        // A container by default: the project's Dockerfile installs what it needs, nothing lands on this machine.
        assertThat(api.kind()).isEqualTo("container");
        assertThat(api.image()).isEqualTo("myshop-api"); // compose's own name, so "docker compose build" results count
        assertThat(api.extras()).containsEntry("build", "api");
        assertThat(api.ports()).containsExactly("3000:3000");
        assertThat(api.environment())
                .containsEntry("DATABASE_URL", "postgres://postgres:secret@db:5432/shop")
                .containsEntry("REDIS_HOST", "cache");
        assertThat(api.dependsOn()).containsExactly(Map.entry("db", "healthy"), Map.entry("cache", "started"));
        assertThat(api.restart()).isEqualTo("always");
        assertThat(api.notes()).anyMatch(n -> n.contains("DevDesk builds its image from ./api/Dockerfile"));
        // compose's env_file is this service's own settings, not the whole project's (like compose).
        assertThat(api.extras()).containsEntry("env_files", ".env");
        assertThat(result.envFiles()).isEmpty();
    }

    @Test
    void howToRunABuiltServiceOnThisMachineInsteadIsKeptOnTheCard() {
        var api = card("api");

        // One click in the wizard: the command, its folder and port, and the settings that differ on this machine.
        assertThat(api.extras())
                .containsEntry("native.command", "npm run dev")
                .containsEntry("native.directory", "api")
                .containsEntry("native.ports", "3000")
                .containsEntry("native.health.type", "tcp")
                .containsEntry("native.health.target", "3000")
                .containsEntry("native.env.DATABASE_URL", "postgres://postgres:secret@127.0.0.1:5432/shop")
                .containsEntry("native.env.REDIS_HOST", "127.0.0.1");
    }

    @Test
    void switchingABuiltServiceToACommandAndBackLosesNothing() {
        var api = card("api");

        var local = api.switchTo("command");

        assertThat(local.kind()).isEqualTo("command");
        assertThat(local.command()).isEqualTo("npm run dev");
        assertThat(local.directory()).isEqualTo("api");
        assertThat(local.ports()).containsExactly("3000");
        assertThat(local.health()).isEqualTo(SetupDraft.Health.tcp(3000));
        assertThat(local.environment()).containsEntry("REDIS_HOST", "127.0.0.1");

        var back = local.switchTo("container");

        assertThat(back.kind()).isEqualTo("container");
        assertThat(back.command()).isEmpty();
        assertThat(back.ports()).containsExactly("3000:3000");
        assertThat(back.environment()).isEqualTo(api.environment());
        assertThat(back.extras()).containsEntry("build", "api");
        assertThat(back.switchTo("command").command()).isEqualTo("npm run dev");
    }

    @Test
    void composesHealthcheckTimingIsKeptAndSeveralPortsAreNotGuessed() {
        var r = ComposeImport.read("""
                services:
                  server:
                    image: twentycrm/twenty:v2.52.0
                    ports: ["3000:3000"]
                    healthcheck:
                      test: curl --fail http://localhost:3000/healthz
                      interval: 5s
                      timeout: 5s
                      retries: 20
                  jaeger:
                    image: jaegertracing/all-in-one
                    ports: ["5778:5778", "16686:16686", "6831:6831/udp"]
                """, "twenty", "twenty", dir -> Optional.empty());
        var server = r.services().get(0);
        var jaeger = r.services().get(1);

        // Twenty's server boots for ~100 s: 20 retries 5 s apart, as compose gives it.
        assertThat(server.extras())
                .containsEntry("health.interval", "5s")
                .containsEntry("health.timeout", "5s")
                .containsEntry("health.retries", "20");
        // jaeger answers on 16686, not on 5778 (its first port): no guess, and the card says why.
        assertThat(jaeger.health()).isEqualTo(SetupDraft.Health.PROCESS);
        assertThat(jaeger.notes()).anyMatch(n -> n.contains("only checks that it runs"));
        assertThat(jaeger.ports()).containsExactly("5778:5778", "16686:16686", "6831:6831/udp");
    }

    @Test
    void whatCantBeMappedIsSaidNotDropped() {
        var web = card("web");

        // "80" alone (random host port in compose) becomes a fixed 80:80, said in a note; a range can't be mapped.
        assertThat(web.ports()).containsExactly("80:80");
        assertThat(web.notes()).hasSize(2);
        assertThat(web.notes().get(0)).contains("random host port").contains("80:80");
        assertThat(web.notes().get(1)).contains("8000-8001:8000-8001 was skipped");
        assertThat(web.command()).isEqualTo("nginx -g 'daemon off;'");
    }

    @Test
    void aBrokenFileIsANoteNotACrash() {
        var broken = ComposeImport.read("services: [", "x", "x", d -> Optional.empty());

        assertThat(broken.services()).isEmpty();
        assertThat(broken.notes()).singleElement().asString().startsWith(ComposeImport.UNREADABLE);
    }

    /** Umami's real compose file (trimmed): a database named like the app, and a healthcheck using $$. */
    private static final String UMAMI = """
            services:
              umami:
                image: ghcr.io/umami-software/umami:latest
                ports: ["3000:3000"]
                environment:
                  DATABASE_URL: postgresql://umami:umami@db:5432/umami
                depends_on:
                  db:
                    condition: service_healthy
              db:
                image: postgres:15-alpine
                environment:
                  POSTGRES_DB: umami
                  POSTGRES_USER: umami
                  POSTGRES_PASSWORD: umami
                healthcheck:
                  test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
            """;

    @Test
    void aValueThatMerelyEqualsAServiceNameIsNotTreatedAsAnAddress() {
        var umami = ComposeImport.read(UMAMI, "umami", "umami", dir -> Optional.empty());
        var db = umami.services().stream()
                .filter(s -> s.name().equals("db"))
                .findFirst()
                .orElseThrow();

        assertThat(db.environment()).containsEntry("POSTGRES_USER", "umami").containsEntry("POSTGRES_DB", "umami");
        assertThat(db.notes()).noneMatch(n -> n.contains("compose name"));
        // A container reaching another by its compose name keeps it: they share the project's network.
        var app = umami.services().stream()
                .filter(s -> s.name().equals("umami"))
                .findFirst()
                .orElseThrow();
        assertThat(app.environment().get("DATABASE_URL")).contains("@db:5432");
        assertThat(app.notes()).noneMatch(n -> n.contains("compose name"));
    }

    @Test
    void composesDoubleDollarSurvivesTheImportAndTheWrittenFileIsValid() throws Exception {
        var umami = ComposeImport.read(UMAMI, "umami", "umami", dir -> Optional.empty());
        var db = umami.services().stream()
                .filter(s -> s.name().equals("db"))
                .findFirst()
                .orElseThrow();
        assertThat(db.health().target()).contains("$${POSTGRES_USER}");

        String yaml = SetupYaml.write(new SetupDraft("umami", List.of(), umami.services(), List.of()));
        var project = io.github.louch.devdesk.core.config.ConfigParser.parse(
                yaml, java.nio.file.Path.of("/work/umami"), Map.of(), f -> Optional.empty());
        var check = (io.github.louch.devdesk.core.project.HealthCheckSpec.Command)
                project.services().get("db").health();
        // DevDesk turns $$ into $: the container's shell expands ${POSTGRES_USER}, as with compose.
        assertThat(check.command()).contains("pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}");
    }

    @Test
    void aDatabaseOnComposesOwnNetworkGetsItsUsualPortOnThisMachine() {
        var r = ComposeImport.read("""
                services:
                  db:
                    image: postgres:15-alpine
                  vote:
                    build: ./vote
                    environment:
                      DB_HOST: db
                """, "voting", "voting", dir -> Optional.empty());
        var db = r.services().stream()
                .filter(s -> s.name().equals("db"))
                .findFirst()
                .orElseThrow();
        var vote = r.services().stream()
                .filter(s -> s.name().equals("vote"))
                .findFirst()
                .orElseThrow();

        assertThat(db.ports()).containsExactly("5432:5432");
        assertThat(db.notes()).anyMatch(n -> n.contains("publishes its usual port 5432 on 127.0.0.1"));
        assertThat(vote.environment()).containsEntry("DB_HOST", "db"); // the project's network knows "db"
        assertThat(vote.extras()).containsEntry("native.env.DB_HOST", "127.0.0.1");
    }

    @Test
    void aBuiltServiceUsesComposesCommandOverTheDockerfilesCmd() {
        var r = ComposeImport.read(
                """
                services:
                  worker:
                    build: ./worker
                    command: celery -A shop worker -l info
                  web:
                    build: ./web
                """,
                "shop",
                "shop",
                dir -> Optional.of(new ComposeImport.DetectedApp(
                        "node server.js", Optional.of(80), Optional.empty(), dir + "/Dockerfile CMD", List.of())));
        var worker = r.services().stream()
                .filter(s -> s.name().equals("worker"))
                .findFirst()
                .orElseThrow();
        var web = r.services().stream()
                .filter(s -> s.name().equals("web"))
                .findFirst()
                .orElseThrow();

        // In the container, compose's command replaces the image's CMD; on this machine it is the command too.
        assertThat(worker.command()).isEqualTo("celery -A shop worker -l info");
        assertThat(worker.extras()).containsEntry("native.command", "celery -A shop worker -l info");
        assertThat(web.command()).isEmpty();
        assertThat(web.extras()).containsEntry("native.command", "node server.js");
    }

    @Test
    void secretsAreMountedWhereComposePutsThem() {
        // Like docker/awesome-compose react-express-mysql: MariaDB reads its root password from a secret file.
        var r = ComposeImport.read("""
                services:
                  db:
                    image: mariadb:10.6
                    environment:
                      - MYSQL_ROOT_PASSWORD_FILE=/run/secrets/db-password
                    secrets:
                      - db-password
                      - source: api-key
                secrets:
                  db-password:
                    file: db/password.txt
                  api-key:
                    environment: API_KEY
                """, "shop", "shop", dir -> Optional.empty());
        var db = r.services().getFirst();

        assertThat(db.volumes()).contains("./db/password.txt:/run/secrets/db-password:ro");
        assertThat(db.notes()).anyMatch(n -> n.contains("\"api-key\" isn't a file"));
    }

    @Test
    void aSecretPathPointsAtTheSecretsFileWhenItRunsOnThisMachine() {
        // awesome-compose react-express-mysql: the backend, run on this machine, reads DATABASE_PASSWORD as a file.
        var r = ComposeImport.read("""
                services:
                  backend:
                    build: ./backend
                    environment:
                      - DATABASE_PASSWORD=/run/secrets/db-password
                      - OTHER=/run/secrets/db-password-old
                    secrets:
                      - db-password
                secrets:
                  db-password:
                    file: db/password.txt
                """, "shop", "shop", dir -> Optional.empty());
        var backend = r.services().getFirst();

        // In its container the secret is mounted where compose puts it; on this machine it's the file itself.
        assertThat(backend.environment()).containsEntry("DATABASE_PASSWORD", "/run/secrets/db-password");
        assertThat(backend.volumes()).contains("./db/password.txt:/run/secrets/db-password:ro");
        assertThat(backend.extras())
                .containsEntry("native.env.DATABASE_PASSWORD", "../db/password.txt")
                .doesNotContainKey("native.env.OTHER");
    }

    @Test
    void secretFilesAreSeenFromTheServicesDirectory() {
        assertThat(ComposeImport.fromServiceDir("", "./db/password.txt")).isEqualTo("./db/password.txt");
        assertThat(ComposeImport.fromServiceDir("backend", "./db/password.txt")).isEqualTo("../db/password.txt");
        assertThat(ComposeImport.fromServiceDir("apps/api", "db/p.txt")).isEqualTo("../../db/p.txt");
        assertThat(ComposeImport.fromServiceDir("backend", "/etc/p.txt")).isEqualTo("/etc/p.txt");
    }
}
