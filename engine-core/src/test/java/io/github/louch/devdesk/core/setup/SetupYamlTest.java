package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SetupYamlTest {

    private static SetupDraft draft() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DATABASE_URL", "${DATABASE_URL}");
        env.put("GREETING", "hello: world # not a comment");
        env.put("DEBUG", "true");
        env.put("WORKERS", "4");
        var api = new SetupDraft.Service(
                "api",
                "command",
                "npm run dev -- --port 3000",
                "api",
                "",
                List.of("3000"),
                List.of(),
                env,
                Map.of("postgres", "healthy"),
                SetupDraft.Health.http("http://127.0.0.1:3000/health"),
                "on-failure",
                "api/package.json",
                List.of());
        var db = DatabaseTemplates.create(
                        "postgres",
                        "shop",
                        java.util.Set.of(),
                        java.util.Set.of(),
                        p -> true,
                        RandomGenerator.of("L64X128MixRandom"))
                .orElseThrow();
        return new SetupDraft("shop", List.of(".env"), List.of(db, api), List.of());
    }

    @Test
    void theWrittenFilePassesDevDesksOwnChecks() throws Exception {
        String yaml = SetupYaml.write(draft());

        var project =
                ConfigParser.parse(yaml, Path.of("/shop"), Map.of(), f -> Optional.of("DATABASE_URL=postgres://x\n"));

        var api = project.services().get("api");
        assertThat(api.workload()).isEqualTo(new Workload.Command("npm run dev -- --port 3000"));
        assertThat(api.directory()).isEqualTo(Path.of("/shop/api"));
        assertThat(api.environment())
                .containsEntry("GREETING", "hello: world # not a comment")
                .containsEntry("DEBUG", "true")
                .containsEntry("DATABASE_URL", "postgres://x");
        assertThat(api.health()).isInstanceOf(HealthCheckSpec.Http.class);
        var db = project.services().get("postgres");
        assertThat(((Workload.Container) db.workload()).ports())
                .containsExactly(new Workload.PortMapping("127.0.0.1", 5432, 5432));
        assertThat(db.health()).isInstanceOf(HealthCheckSpec.Command.class);
    }

    @Test
    void editSetupReadsBackWhatWasWrittenWithVariablesUnresolved() throws Exception {
        String yaml = SetupYaml.write(draft());

        var again = ExistingSetup.read(yaml, "shop");

        assertThat(again.services()).extracting(SetupDraft.Service::name).containsExactly("postgres", "api");
        var api = again.services().get(1);
        assertThat(api.environment()).containsEntry("DATABASE_URL", "${DATABASE_URL}"); // never the secret itself
        assertThat(api.dependsOn()).containsExactly(Map.entry("postgres", "healthy"));
        assertThat(SetupYaml.write(again)).isEqualTo(yaml); // even the "# from" lines come back
        assertThat(again.notes()).isEmpty(); // the wizard's own header comment doesn't count
    }

    @Test
    void settingsTheCardsDontShowSurviveAnEdit() throws Exception {
        String yaml = """
                version: 1
                services:
                  api:
                    command: node server.js
                    health: { type: tcp, port: 3000, interval: 5s, retries: 10 }
                    stop_grace_period: 30s
                """;

        String rewritten = SetupYaml.write(ExistingSetup.read(yaml, "x"));

        var api = ConfigParser.parse(rewritten, Path.of("/x"), Map.of(), f -> Optional.empty())
                .services()
                .get("api");
        assertThat(api.stopGracePeriod()).hasSeconds(30);
        assertThat(api.health().timing().retries()).isEqualTo(10);
    }

    @Test
    void editSetupKeepsTheProfilesSectionAsWritten() throws Exception {
        String yaml = """
                version: 1
                services:
                  api: { command: node api.js }
                # demo data for presentations
                profiles:
                  demo:
                    environment: { SEED: demo } # the demo database
                """;

        String rewritten = SetupYaml.write(ExistingSetup.read(yaml, "x"));

        assertThat(rewritten).endsWith("profiles:\n  demo:\n    environment: { SEED: demo } # the demo database\n");
        var project = ConfigParser.parse(rewritten, Path.of("/x"), Map.of(), f -> Optional.empty());
        assertThat(project.profiles()).containsKey("demo");
    }
}
