package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Each mistake in devdesk.yaml gets its own message, at its own place ("path: message"). Table-driven: one row per
 * mistake, written as the person would write it.
 */
class ConfigErrorsTest {

    private static final Path ROOT = Path.of("/work/shop");

    private static ProjectSpec parse(String yaml) throws ConfigException {
        return ConfigParser.parse(
                yaml,
                ROOT,
                Map.of("HOME", "/home/me"),
                f -> f.equals(".env") ? Optional.of("A=1\n") : Optional.empty());
    }

    /** "path: message" of each problem, in order. */
    private static List<String> problems(String yaml) {
        return catchThrowableOfType(ConfigException.class, () -> parse(yaml)).problems().stream()
                .map(p -> p.path() + ": " + p.message())
                .toList();
    }

    /** A valid file with {@code service} as the api service's settings. */
    private static String api(String service) {
        return "version: 1\nservices:\n  api: " + service + "\n";
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            { command: x, container: { image: a } } | services.api: use either command: (runs on this machine) or container:
            { directory: x }                        | services.api: missing "command:", the thing to run
            [x]                                     | services.api: expected settings like command:, found a list
            { container: postgres }                 | services.api.container: expected settings like image: postgres:18, found "postgres"
            { container: { ports: [] } }            | services.api.container: missing "image:"
            { container: { image: a, ports: [x] } } | services.api.container.ports: expected "host:container" like "5432:5432", found "x"
            { container: { image: a, ports: ["0:80"] } }     | services.api.container.ports: ports go from 1 to 65535, found "0:80"
            { container: { image: a, ports: ["80:65536"] } } | services.api.container.ports: ports go from 1 to 65535, found "80:65536"
            { container: { image: a, ports: 80 } }  | services.api.container.ports: expected a list like [a, b], found "80"
            { container: { image: a, tag: 1 } }     | services.api.container: unknown key "tag"
            { container: { build: { context: ., file: x } } } | services.api.container.build: unknown key "file"
            { command: x, environment: [A] }        | services.api.environment: expected KEY: value pairs, found a list
            { command: x, environment: { A: } }     | services.api.environment.A: expected a value, found empty
            { command: "${NOPE:?Variable not set}" } | services.api.command: variable ${NOPE} is required and not set ("Variable not set")
            { command: x, ports: [0] }              | services.api.ports: expected a whole number from 1 to 65535, found "0"
            { command: x, ports: [65536] }          | services.api.ports: expected a whole number from 1 to 65535, found "65536"
            { command: x, health: http }            | services.api.health: expected settings like type: http, found "http"
            { command: x, health: { type: htp } }   | services.api.health.type: unknown type "htp". Use process, tcp, http, command or docker
            { command: x, health: { interval: 1s } }        | services.api.health.type: missing type. Use process, tcp, http, command or docker
            { command: x, health: { type: tcp } }           | services.api.health: missing "port:"
            { command: x, health: { type: tcp, port: 0 } }  | services.api.health.port: expected a whole number from 1 to 65535, found "0"
            { command: x, health: { type: http, url: "ftp://x/" } } | services.api.health.url: "ftp://x/" is not an http(s) URL
            { command: x, health: { type: http, url: "http:///up" } } | services.api.health.url: "http:///up" is not an http(s) URL
            { command: x, health: { type: http, url: "http://a/", expect_status: ok } } | services.api.health.expect_status: expected a status like 200 or a range like 200-399
            { command: x, health: { type: command } }       | services.api.health: missing "command:"
            { command: x, health: { type: docker } }        | services.api.health: type: docker reads a container's own HEALTHCHECK; this service isn't a container
            { command: x, health: { type: process, retries: 0 } }   | services.api.health.retries: expected a whole number from 1 to 100, found "0"
            { command: x, health: { type: process, retries: 101 } } | services.api.health.retries: expected a whole number from 1 to 100, found "101"
            { command: x, health: { type: process, timeout: soon } } | services.api.health.timeout: expected a duration like 500ms, 10s or 2m, found "soon"
            { command: x, restart: sometimes }      | services.api.restart: unknown restart policy "sometimes". Use no, on-failure or always
            { command: x, stop_grace_period: x }    | services.api.stop_grace_period: expected a duration like 500ms, 10s or 2m, found "x"
            { command: x, sleep_after: 15m }        | services.api.sleep_after: a sleeping service is woken by a connection to its port: give it ports: first
            { command: x, watch: [../other] }       | services.api.watch: "../other" is outside the project folder
            { command: x, depends_on: [api] }       | services.api.depends_on: "api" can't depend on itself
            { command: x, depends_on: { db: { condition: up } } }   | services.api.depends_on.db.condition: unknown condition "up". Use started, healthy or completed_successfully
            { command: x, depends_on: { db: { when: healthy } } }   | services.api.depends_on.db: unknown key "when"
            """)
    void eachMistakeIsNamedWhereItIs(String service, String expected) {
        assertThat(problems(api(service))).anySatisfy(p -> assertThat(p).startsWith(expected));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            'services: { api: { command: x } }'                | : missing "version: 1" at the top
            'version: 2\nservices: { api: { command: x } }'    | version: only version 1 exists, found "2"
            'version: 1'                                        | : missing "services:": nothing to run
            'version: 1\nservices: {}'                          | services: expected at least one service (name: then its settings)
            'version: 1\nservices: { My_Api: { command: x } }' | services.My_Api: service names use lowercase letters, digits, - and _
            'version: 1\nenv_files: [.missing]\nservices: { api: { command: x } }' | env_files: file ".missing" not found in /work/shop
            'version: 1\nservices: { api: { command: x } }\ntasks: [seed]'         | tasks: expected task names, like seed: then its command:
            'version: 1\nservices: { api: { command: x } }\ntasks: { Seed: x }'    | tasks.Seed: task names use lowercase letters, digits, - and _
            'version: 1\nservices: { api: { command: x } }\ntasks: { seed: [x] }'  | tasks.seed: expected command: and its settings, found a list
            'version: 1\nservices: { api: { command: x } }\ntasks: { seed: { needs: [db] } }' | tasks.seed: missing "command:"
            'version: 1\nservices: { api: { command: x } }\ntasks: { seed: { command: x, needs: [apii] } }' | tasks.seed.needs: no service "apii" in this project
            'version: 1\nservices: { api: { command: x } }\ntasks: { seed: { command: x, when: now } }'    | tasks.seed: unknown key "when"
            'version: 1\nservices: { api: { command: x } }\nprofiles: [demo]'      | profiles: expected profile names, like demo: then its settings
            'version: 1\nservices: { api: { command: x } }\nprofiles: { Demo: {} }' | profiles.Demo: profile names use lowercase letters, digits, - and _
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: x }'  | profiles.demo: expected settings like environment:, found "x"
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: { colour: red } }' | profiles.demo: unknown key "colour"
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: { env_files: [.nope] } }' | profiles.demo.env_files: file ".nope" not found in /work/shop
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: { services: [api] } }' | profiles.demo.services: expected service names, like api: then environment:
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: { services: { web: {} } } }' | profiles.demo.services.web: no service "web" in this project
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: { services: { api: x } } }' | profiles.demo.services.api: expected environment: under the service
            'version: 1\nservices: { api: { command: x } }\nprofiles: { demo: { services: { api: { ports: [1] } } } }' | profiles.demo.services.api: unknown key "ports"
            """)
    void projectLevelMistakes(String yaml, String expected) {
        assertThat(problems(yaml.replace("\\n", "\n")))
                .anySatisfy(p -> assertThat(p).startsWith(expected.strip()));
    }

    @Test
    void theEdgesThatAreAllowed() throws Exception {
        var p = parse("""
                version: 1
                services:
                  api:
                    command: x
                    ports: [1, 65535]
                    watch: true
                    health: { type: http, url: "http://127.0.0.1/up", expect_status: 204 }
                    restart: "false"
                  db:
                    container:
                      image: postgres
                      ports: ["0.0.0.0:5432:5432", "6379"]
                      volumes: ["./data:/var/lib/data", "named:/x"]
                  worker: { command: y, watch: false, health: { type: process, retries: 100, timeout: 500ms } }
                tasks:
                  seed: python3 seed.py
                """);

        var api = p.services().get("api");
        assertThat(api.ports()).containsExactly(1, 65535);
        assertThat(api.watch()).containsExactly(ROOT);
        assertThat(api.health()).isInstanceOfSatisfying(HealthCheckSpec.Http.class, h -> {
            assertThat(h.minStatus()).isEqualTo(204);
            assertThat(h.maxStatus()).isEqualTo(204);
        });
        var db = (Workload.Container) p.services().get("db").workload();
        assertThat(db.ports())
                .containsExactly(
                        new Workload.PortMapping("0.0.0.0", 5432, 5432),
                        new Workload.PortMapping("127.0.0.1", 6379, 6379));
        assertThat(db.volumes()).containsExactly("/work/shop/data:/var/lib/data", "named:/x");
        assertThat(p.services().get("db").ports()).containsExactly(5432, 6379); // checked before starting too
        var worker = p.services().get("worker");
        assertThat(worker.watch()).isEmpty();
        assertThat(worker.health().timing().retries()).isEqualTo(100);
        assertThat(worker.health().timing().timeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.tasks().get("seed").command()).isEqualTo("python3 seed.py"); // a task can be just its command
        assertThat(p.name()).isEqualTo("shop"); // no name: the folder's
        // Today YAML's ~ ("no value") stays the text "~" here, unlike docker compose. Pinned so a change is deliberate.
        assertThat(parse(api("{ command: x, environment: { A: ~ } }"))
                        .services()
                        .get("api")
                        .environment())
                .containsEntry("A", "~");
    }

    @Test
    void aProfilesEnvFilesCountOnlyInsideThatProfile() throws Exception {
        var p = ConfigParser.parse(
                """
                version: 1
                services:
                  api: { command: x }
                profiles:
                  demo: { env_files: [.env.demo], environment: { URL: "${HOST}" } }
                  other: { environment: { URL: "${HOST:-none}" } }
                """, ROOT, Map.of(), f -> f.equals(".env.demo") ? Optional.of("HOST=demo.local\n") : Optional.empty());

        assertThat(p.profiles().get("demo").environment()).containsEntry("URL", "demo.local");
        assertThat(p.profiles().get("other").environment()).containsEntry("URL", "none"); // demo's HOST didn't leak
    }
}
