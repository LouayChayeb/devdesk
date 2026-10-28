package io.github.louch.devdesk.core.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The failures the 5 hard apps (Mastodon, Twenty, Saleor, Chatwoot, Plausible) showed, each named by a rule. */
class HardAppRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final Path ROOT = Path.of("/work/app");

    private final FakeIntrospection machine = new FakeIntrospection();
    private final Map<String, ServiceState> states = new HashMap<>();
    private final Map<String, ServiceHistory> history = new HashMap<>();

    private DiagnosisContext ctx(String yaml, Map<String, String> files) throws Exception {
        ProjectSpec p = ConfigParser.parse(yaml, ROOT, Map.of(), f -> Optional.ofNullable(files.get(f)));
        return new DiagnosisContext(p, states, history, Map.of(), machine, NOW);
    }

    private void printed(String service, List<OutputLine> lines) {
        history.put(
                service,
                new ServiceHistory(lines, List.of(ExitStatus.of(1)), List.of(), List.of(), false, Set.of(), false));
    }

    private static OutputLine err(String text) {
        return new OutputLine(StreamKind.STDERR, text);
    }

    /** Mastodon: "Missing `secret_key_base`", then 20 lines of Ruby stack trace. */
    private static List<OutputLine> mastodonCrash() {
        List<OutputLine> lines = new ArrayList<>();
        lines.add(err("ArgumentError: Missing `secret_key_base` for 'production' environment"));
        for (int i = 0; i < 20; i++) {
            lines.add(err("\tfrom /usr/local/bundle/gems/railties/lib/rails/application.rb:" + i));
        }
        return lines;
    }

    private static final String WEB =
            "version: 1\nservices:\n  web: { command: bundle exec puma, env_files: [.env.production] }\n";

    @Test
    void anEmptySecretIsNamedTheOneTheErrorMentionsFirst() throws Exception {
        states.put("web", new ServiceState.Crashed(ExitStatus.of(1), 0));
        printed("web", mastodonCrash());
        var files = Map.of(".env.production", "OTP_SECRET=\nSECRET_KEY_BASE=\nLOCAL_DOMAIN=localhost\n");

        var f = new SecretRule().evaluate(ctx(WEB, files), "web");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("web's setting SECRET_KEY_BASE is empty");
            assertThat(x.explanation())
                    .startsWith("web's error names it: SECRET_KEY_BASE is empty.")
                    .contains("Also: OTP_SECRET.");
            assertThat(x.evidence())
                    .containsExactly(
                            "its error: ArgumentError: Missing `secret_key_base` for 'production' environment",
                            "empty: OTP_SECRET, SECRET_KEY_BASE");
        });
    }

    @Test
    void aSecretListedInTheExampleButNotSetIsNamed() throws Exception {
        // Twenty: ENCRYPTION_KEY is in .env.example, and nobody copied it over.
        states.put("web", new ServiceState.Unhealthy(10, 0, "no answer"));
        printed("web", List.of(err("Error: something failed")));
        machine.files.put(ROOT.resolve(".env.example"), "ENCRYPTION_KEY=replace_me\nPORT=3000\n");

        var f = new SecretRule().evaluate(ctx(WEB, Map.of(".env.production", "PORT=3000\n")), "web");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("web's setting ENCRYPTION_KEY isn't set (its example file lists it)");
            assertThat(x.evidence()).contains("not set, but in its example: ENCRYPTION_KEY");
        });
    }

    @Test
    void aSecretRuleStaysQuietWhileTheServiceIsFineOrHasItsSecrets() throws Exception {
        var files = Map.of(".env.production", "SECRET_KEY_BASE=\n");
        states.put("web", new ServiceState.Healthy(10, 0));
        printed("web", mastodonCrash());
        assertThat(new SecretRule().evaluate(ctx(WEB, files), "web")).isEmpty();

        states.put("web", new ServiceState.Crashed(ExitStatus.of(1), 0));
        assertThat(new SecretRule().evaluate(ctx(WEB, Map.of(".env.production", "SECRET_KEY_BASE=abc\n")), "web"))
                .isEmpty();
    }

    @Test
    void aCrashNamesTheErrorLineHiddenAboveItsStackTrace() throws Exception {
        states.put("web", new ServiceState.Crashed(ExitStatus.of(1), 0));
        printed("web", mastodonCrash());

        var f = new CrashRule()
                .evaluate(ctx(WEB, Map.of(".env.production", "")), "web")
                .getFirst();

        assertThat(f.evidence())
                .first()
                .isEqualTo("its error: ArgumentError: Missing `secret_key_base` for 'production' environment");
    }

    private static final String TCP_API = "version: 1\nservices:\n"
            + "  api: { command: python manage.py runserver, health: { type: tcp, port: 8000 } }\n"
            + "  ok: { command: x, health: { type: http, url: \"http://127.0.0.1:8000/health\" } }\n";

    @Test
    void aPortThatAnswersWhileTheLogFillsWithErrorsIsSaid() throws Exception {
        // Saleor: its port answered while every request failed to reach the database.
        List<OutputLine> lines = List.of(
                err("ERROR: could not connect to server: Connection refused"),
                err("  File \"/app/db.py\", line 3"),
                err("ERROR: could not connect to server: Connection refused"), // the same line again counts once
                err("Exception in thread worker"),
                err("ERROR: role \"saleor\" does not exist"));
        states.put("api", new ServiceState.Healthy(10, 0));
        printed("api", lines);

        var f = new LogErrorsRule().evaluate(ctx(TCP_API, Map.of()), "api");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("api looks up, but its log shows errors");
            assertThat(x.explanation()).startsWith("api accepts connections on its port, but it keeps printing errors");
            assertThat(x.evidence())
                    .containsExactly(
                            "its log: ERROR: role \"saleor\" does not exist",
                            "3 different error lines in what it printed lately");
        });

        // An http check asks the app itself: when it passes, the log is the app's own business.
        states.put("ok", new ServiceState.Healthy(11, 0));
        printed("ok", lines);
        assertThat(new LogErrorsRule().evaluate(ctx(TCP_API, Map.of()), "ok")).isEmpty();

        // Two errors aren't enough.
        printed("api", lines.subList(0, 2));
        assertThat(new LogErrorsRule().evaluate(ctx(TCP_API, Map.of()), "api")).isEmpty();
    }

    @Test
    void aMissingTableInTheLogIsAHalfMadeDatabase() throws Exception {
        // Twenty: "Unable to load Twenty" while its health check passed.
        states.put("api", new ServiceState.Healthy(10, 0));
        printed("api", List.of(err("[Nest] 1 - ERROR QueryFailedError: relation \"core.workspace\" does not exist")));

        var f = new HalfMadeDatabaseRule().evaluate(ctx(TCP_API, Map.of()), "api");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("api's database is missing tables");
            assertThat(x.explanation()).contains("(relation \"core.workspace\" does not exist)");
        });

        for (String other :
                List.of("Table 'app.users' doesn't exist", "sqlite3.OperationalError: no such table: posts")) {
            printed("api", List.of(err(other)));
            assertThat(new HalfMadeDatabaseRule().evaluate(ctx(TCP_API, Map.of()), "api"))
                    .hasSize(1);
        }
        printed("api", List.of(err("relation established")));
        assertThat(new HalfMadeDatabaseRule().evaluate(ctx(TCP_API, Map.of()), "api"))
                .isEmpty();
    }
}
