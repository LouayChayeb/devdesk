package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EnvReportTest {

    private static SetupDraft draft(Map<String, String> env) {
        var api = new SetupDraft.Service(
                "api",
                "command",
                "node server.js --port ${PORT}",
                "",
                "",
                List.of(),
                List.of(),
                env,
                Map.of(),
                SetupDraft.Health.PROCESS,
                "no",
                "",
                List.of());
        return new SetupDraft("x", List.of(".env"), List.of(api), List.of());
    }

    @Test
    void secretsAreHiddenAndMissingVariablesAreNamedWithWhoNeedsThem() {
        Map<String, Optional<String>> files = Map.of(
                ".env", Optional.of("PORT=3000\nDB_PASSWORD=hunter2\n"),
                ".env.example", Optional.of("PORT=\nDB_PASSWORD=\nSTRIPE_KEY=\n"),
                ".gitignore", Optional.of("node_modules\n.env\n"));

        var report = EnvReport.of(
                draft(Map.of("DB", "${DB_PASSWORD}", "API_TOKEN", "${API_TOKEN}", "MODE", "${MODE:-dev}")),
                f -> files.getOrDefault(f, Optional.empty()),
                Set.of("HOME"));

        assertThat(report.variables()).extracting(EnvReport.Variable::key).containsExactly("PORT", "DB_PASSWORD");
        var password = report.variables().get(1);
        assertThat(password.secret()).isTrue();
        assertThat(password.value()).isEmpty(); // never sent to the app
        assertThat(password.usedBy()).containsExactly("api");
        assertThat(report.variables().getFirst().value()).contains("3000");
        assertThat(report.missing())
                .extracting(EnvReport.Missing::key)
                .containsExactly("API_TOKEN", "STRIPE_KEY"); // MODE has a default, so it isn't missing
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void anEmptyValueOnACardIsMissingUnlessAnEnvFileSetsIt() {
        var report = EnvReport.of(
                draft(Map.of("PG_PORT", "", "PORT", "")),
                f -> f.equals(".env") ? Optional.of("PORT=3000\n") : Optional.empty(),
                Set.of());

        assertThat(report.missing()).singleElement().satisfies(m -> {
            assertThat(m.key()).isEqualTo("PG_PORT");
            assertThat(m.reason()).startsWith("api needs a value for it");
        });
    }

    @Test
    void anEnvFileOutsideGitignoreIsAWarning() {
        var report = EnvReport.of(
                draft(Map.of()), f -> f.equals(".env") ? Optional.of("A=1\n") : Optional.empty(), Set.of());

        assertThat(report.warnings()).singleElement().asString().startsWith(".env is not in .gitignore");
    }
}
