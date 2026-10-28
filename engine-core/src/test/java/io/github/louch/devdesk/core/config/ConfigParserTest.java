package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.RestartPolicy;
import io.github.louch.devdesk.core.project.Workload;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigParserTest {

    private static final Path ROOT = Path.of("/work/shop");

    private static ProjectSpec parse(String yaml) throws ConfigException {
        return ConfigParser.parse(
                yaml,
                ROOT,
                Map.of("PG_PORT", "5432"),
                file -> file.equals(".env")
                        ? Optional.of("# comment\nAPI_PORT=3001\nexport GREETING=\"hi there\"\n")
                        : Optional.empty());
    }

    /** The formatted error output, exactly as the user will see it. */
    private static String errors(String yaml) {
        return catchThrowableOfType(ConfigException.class, () -> parse(yaml)).format("devdesk.yaml");
    }

    @Test
    void fullExampleIsReadCompletely() throws Exception {
        ProjectSpec project = parse("""
                version: 1
                name: shop
                env_files: [.env]
                services:
                  db:
                    command: postgres -p ${PG_PORT}
                    ports: [5432]
                    health: { type: tcp, port: 5432 }
                  api:
                    command: npm run start:dev
                    directory: backend
                    environment:
                      PORT: ${API_PORT}
                      MODE: ${MODE:-dev}
                    depends_on:
                      db: { condition: healthy }
                    health:
                      type: http
                      url: http://127.0.0.1:${API_PORT}/health
                      expect_status: 200-299
                      interval: 500ms
                    restart: on-failure
                    stop_grace_period: 5s
                  web:
                    command: npm start
                    depends_on: [api]
                    restart: no
                """);

        assertThat(project.name()).isEqualTo("shop");
        assertThat(project.environment()).containsEntry("API_PORT", "3001").containsEntry("GREETING", "hi there");
        assertThat(project.services()).containsOnlyKeys("db", "api", "web");
        var db = project.services().get("db");
        assertThat(db.workload()).isEqualTo(new Workload.Command("postgres -p 5432"));
        assertThat(db.health()).isEqualTo(new HealthCheckSpec.Tcp("127.0.0.1", 5432, HealthCheckSpec.Timing.DEFAULT));
        var api = project.services().get("api");
        assertThat(api.directory()).isEqualTo(Path.of("/work/shop/backend"));
        assertThat(api.environment()).containsEntry("PORT", "3001").containsEntry("MODE", "dev");
        assertThat(api.dependsOn()).containsExactly(new Dependency("db", Dependency.Condition.HEALTHY));
        assertThat(api.health()).isInstanceOfSatisfying(HealthCheckSpec.Http.class, h -> {
            assertThat(h.url()).isEqualTo(URI.create("http://127.0.0.1:3001/health"));
            assertThat(h.maxStatus()).isEqualTo(299);
            assertThat(h.timing().interval()).isEqualTo(Duration.ofMillis(500));
        });
        assertThat(api.restart()).isEqualTo(RestartPolicy.ON_FAILURE);
        assertThat(api.stopGracePeriod()).isEqualTo(Duration.ofSeconds(5));
        var web = project.services().get("web");
        assertThat(web.dependsOn()).containsExactly(new Dependency("api", Dependency.Condition.STARTED));
        assertThat(web.restart()).isEqualTo(RestartPolicy.NO);
        assertThat(web.health()).isInstanceOf(HealthCheckSpec.ProcessAlive.class);
    }

    @Test
    void typoInDependencyGetsLineAndSuggestion() {
        assertThat(errors("""
                version: 1
                services:
                  postgres:
                    command: postgres
                  api:
                    command: npm start
                    depends_on: [postgress]
                """))
                .isEqualTo(
                        "devdesk.yaml:7:17: services.api.depends_on: unknown service \"postgress\". Did you mean \"postgres\"?");
    }

    @Test
    void everyProblemIsReportedAtOnceInFileOrder() {
        String out = errors("""
                version: 2
                services:
                  api:
                    comand: npm start
                    ports: [3000, 99999]
                    restart: sometimes
                """);

        assertThat(out.lines())
                .containsExactly(
                        "devdesk.yaml:1:10: version: only version 1 exists, found \"2\"",
                        "devdesk.yaml:3:3: services.api: missing \"command:\", the thing to run (e.g. command: npm run dev), "
                                + "or \"container:\" (e.g. container: { image: postgres:18 })",
                        "devdesk.yaml:4:5: services.api: unknown key \"comand\". Did you mean \"command\"?",
                        "devdesk.yaml:5:19: services.api.ports: expected a whole number from 1 to 65535, found \"99999\"",
                        "devdesk.yaml:6:14: services.api.restart: unknown restart policy \"sometimes\". Use no, on-failure or always");
    }

    @Test
    void anUnsetVariableIsEmptyAndSaidLikeComposeDoesButARequiredOneStops() throws Exception {
        // Like compose: ${API_KEY} unset is "", with a warning. Plausible's ${BASE_URL} used to block the wizard.
        ProjectSpec project = parse("""
                version: 1
                services:
                  api: { command: "node server.js --key ${API_KEY}" }
                """);

        assertThat(project.services().get("api").workload()).isEqualTo(new Workload.Command("node server.js --key "));
        assertThat(project.warnings()).singleElement().asString().contains("${API_KEY} isn't set, so it's empty");
        assertThat(errors("""
                version: 1
                services:
                  api: { command: "node server.js --key ${API_KEY:?the API key}" }
                """)).contains("variable ${API_KEY} is required and not set (\"the API key\")");
    }

    @Test
    void duplicateServiceIsAnErrorNotSilentlyDropped() {
        assertThat(errors("""
                version: 1
                services:
                  api: { command: a }
                  api: { command: b }
                """)).isEqualTo("devdesk.yaml:4:3: api: \"api\" appears twice; the first is at line 3");
    }

    @Test
    void brokenYamlPointsAtTheLine() {
        assertThat(errors("""
                version: 1
                services:
                  api: { command: [unclosed
                """))
                .isEqualTo(
                        "devdesk.yaml:3:28: not valid YAML: while parsing a flow sequence: expected ',' or ']', but got <stream end>");
    }

    @Test
    void containerServiceIsReadWithSafeDefaults() throws Exception {
        ProjectSpec project = parse("""
                version: 1
                services:
                  db:
                    container:
                      image: postgres:${PG_VERSION:-18.1}
                      ports: ["5432:5432", "0.0.0.0:8080:80", "6379"]
                      volumes: ["pgdata:/var/lib/postgresql/data", "./init:/docker-entrypoint-initdb.d"]
                      environment: { POSTGRES_PASSWORD: dev }
                    health: { type: docker }
                """);

        var db = project.services().get("db");
        assertThat(db.workload())
                .isEqualTo(new Workload.Container(
                        "postgres:18.1",
                        List.of(
                                new Workload.PortMapping(
                                        "127.0.0.1", 5432, 5432), // localhost only unless asked otherwise
                                new Workload.PortMapping("0.0.0.0", 8080, 80),
                                new Workload.PortMapping("127.0.0.1", 6379, 6379)),
                        List.of("pgdata:/var/lib/postgresql/data", "/work/shop/init:/docker-entrypoint-initdb.d"),
                        Optional.empty()));
        assertThat(db.environment()).containsEntry("POSTGRES_PASSWORD", "dev");
        assertThat(db.ports()).containsExactly(5432, 8080, 6379); // checked for conflicts before starting
        assertThat(db.health()).isInstanceOf(HealthCheckSpec.Docker.class);
    }

    @Test
    void aContainerCanBeBuiltFromTheProjectsDockerfile() throws Exception {
        ProjectSpec project = parse("""
                version: 1
                name: My Shop
                services:
                  api:
                    container:
                      build: ./backend
                      ports: ["8000:8000"]
                  worker:
                    container:
                      image: shop/worker:dev
                      build: { context: ., dockerfile: worker/Dockerfile.dev }
                """);

        // Without image:, the built image is named like compose names it: "<project>-<service>".
        assertThat(project.services().get("api").workload())
                .isEqualTo(new Workload.Container(
                        "my-shop-api",
                        List.of(new Workload.PortMapping("127.0.0.1", 8000, 8000)),
                        List.of(),
                        Optional.empty(),
                        Optional.of(new Workload.Build(Path.of("/work/shop/backend"), "Dockerfile"))));
        assertThat(((Workload.Container) project.services().get("worker").workload()).build())
                .contains(new Workload.Build(ROOT, "worker/Dockerfile.dev"));
        assertThat(project.services().get("api").workload().describe())
                .isEqualTo("container my-shop-api (built from /work/shop/backend) ports [8000:8000]");
    }

    @Test
    void aUdpPortIsPublishedAsUdpAndIsntCheckedLikeATcpOne() throws Exception {
        ProjectSpec project = parse("""
                version: 1
                services:
                  jaeger: { container: { image: jaegertracing/all-in-one, ports: ["16686:16686", "6831:6831/udp"] } }
                """);

        var jaeger = project.services().get("jaeger");
        assertThat(((Workload.Container) jaeger.workload()).ports())
                .containsExactly(
                        new Workload.PortMapping("127.0.0.1", 16686, 16686),
                        new Workload.PortMapping("127.0.0.1", 6831, 6831, true));
        assertThat(jaeger.ports()).containsExactly(16686); // the busy-port check is about TCP listeners
    }

    @Test
    void containerMistakesAreExplained() {
        String out = errors("""
                version: 1
                services:
                  db:
                    command: postgres
                    container: { ports: ["54:32:10"] }
                  api:
                    command: node server.js
                    health: { type: docker }
                """);

        assertThat(out)
                .contains("use either command: (runs on this machine) or container: (runs in Docker), not both")
                .contains("services.db.container: missing \"image:\"")
                .contains("expected \"host:container\" like \"5432:5432\"")
                .contains("type: docker reads a container's own HEALTHCHECK; this service isn't a container");
    }

    @Test
    void selfDependencyAndMissingEnvFileAreCaught() {
        String out = errors("""
                version: 1
                env_files: [.env.local]
                services:
                  api: { command: x, depends_on: [api] }
                """);

        assertThat(out).contains("file \".env.local\" not found").contains("\"api\" can't depend on itself");
    }

    @Test
    void dependencyLoopIsAConfigError() {
        assertThat(errors("""
                version: 1
                services:
                  api: { command: a, depends_on: [worker] }
                  worker: { command: b, depends_on: [api] }
                """))
                .isEqualTo("devdesk.yaml:3:34: services.api.depends_on: dependency cycle: api → worker → api. "
                        + "Remove one of these depends_on entries");
    }

    @Test
    void waitingForHealthOfAServiceWithoutHealthCheckIsCaught() {
        assertThat(errors("""
                version: 1
                services:
                  db: { command: postgres }
                  api: { command: x, depends_on: { db: { condition: healthy } } }
                """)).contains("\"api\" waits for \"db\" to be healthy, but \"db\" has no health check");
    }

    @Test
    void profilesOverrideSettingsForEveryServiceOrOne() throws Exception {
        var project = ConfigParser.parse(
                """
                version: 1
                services:
                  api: { command: node api.js, environment: { MAIL_MODE: smtp, SEED: none } }
                  web: { command: node web.js }
                profiles:
                  demo:
                    env_files: [.env.demo]
                    environment: { MAIL_MODE: inbox }
                    services:
                      api: { environment: { SEED: "${DEMO_SEED}" } }
                """,
                Path.of("/p"),
                Map.of(),
                f -> f.equals(".env.demo") ? Optional.of("DEMO_SEED=shop\n") : Optional.empty());

        var demo = project.profiles().get("demo").applyTo(project);

        assertThat(project.services().get("api").environment()).containsEntry("MAIL_MODE", "smtp");
        assertThat(demo.services().get("api").environment())
                .containsEntry("MAIL_MODE", "inbox") // the profile beats the service's own value
                .containsEntry("SEED", "shop");
        assertThat(demo.environment()).containsEntry("MAIL_MODE", "inbox").containsEntry("DEMO_SEED", "shop");
        assertThat(demo.services().get("web").environment()).isEmpty();
    }

    @Test
    void aProfileForAServiceThatDoesntExistIsAMistake() {
        ConfigException e = catchThrowableOfType(
                ConfigException.class,
                () -> ConfigParser.parse(
                        "version: 1\nservices:\n  api: { command: x }\nprofiles:\n  demo:\n    services:\n"
                                + "      apx: { environment: { A: b } }\n",
                        Path.of("/p"),
                        Map.of(),
                        f -> Optional.empty()));

        assertThat(e.problems()).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("profiles.demo.services.apx");
            assertThat(p.message()).startsWith("no service \"apx\" in this project");
        });
    }

    @Test
    void tasksAreOneOffCommandsThatMayNeedServices() throws Exception {
        var project = ConfigParser.parse("""
                version: 1
                services:
                  db: { container: { image: postgres:18 } }
                  api: { command: node api.js, directory: api }
                tasks:
                  seed:
                    command: python3 seed.py
                    directory: api
                    needs: [db]
                    environment: { ROWS: "50" }
                    description: Add demo orders
                  lint: npm run lint
                  reset:
                    command: ./reset.sh
                    confirm: This deletes all data.
                """, Path.of("/p"), Map.of(), f -> Optional.empty());

        assertThat(project.tasks()).containsOnlyKeys("seed", "lint", "reset");
        var seed = project.tasks().get("seed");
        assertThat(seed.command()).isEqualTo("python3 seed.py");
        assertThat(seed.directory()).isEqualTo(Path.of("/p/api"));
        assertThat(seed.needs()).containsExactly("db");
        assertThat(seed.environment()).containsEntry("ROWS", "50");
        assertThat(project.tasks().get("lint").directory()).isEqualTo(Path.of("/p"));
        assertThat(project.tasks().get("reset").confirm()).contains("This deletes all data.");
    }

    @Test
    void aTaskNeedingAServiceThatDoesntExistIsAMistake() {
        ConfigException e = catchThrowableOfType(
                ConfigException.class,
                () -> ConfigParser.parse(
                        "version: 1\nservices:\n  db: { command: x }\ntasks:\n  seed: { command: y, needs: [dv] }\n",
                        Path.of("/p"),
                        Map.of(),
                        f -> Optional.empty()));

        assertThat(e.problems()).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("tasks.seed.needs");
            assertThat(p.message()).contains("no service \"dv\"").contains("db");
        });
    }
}
