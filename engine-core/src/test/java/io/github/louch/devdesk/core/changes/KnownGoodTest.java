package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnownGoodTest {

    private static KnownGood state(
            String yaml, Map<String, String> env, String lock, String image, String node, String commit) {
        return new KnownGood(
                Instant.EPOCH,
                Optional.of("main"),
                Optional.of(commit),
                yaml,
                Map.of(".env", env),
                Map.of("api/package-lock.json", lock),
                Map.of("postgres:latest", image),
                Map.of("node", node));
    }

    @Test
    void nothingChangedIsAnEmptyList() {
        var a = state(
                "services:\n  api:\n    command: npm start\n", Map.of("DB_URL", "h1"), "l1", "sha256:aaa", "v22", "c1");
        assertThat(KnownGood.compare(a, a)).isEmpty();
    }

    @Test
    void everyKindOfChangeIsNamedAndSecretsStayHidden() {
        var then = state(
                "services:\n  api:\n    command: npm start\n    environment:\n      API_TOKEN: old-secret\n",
                Map.of("DB_URL", "h1", "OLD", "h2"),
                "l1",
                "sha256:aaaaaaaaaaaaaaaa",
                "v20.11.0",
                "c1c1c1c1c1c1c1c1");
        var now = state(
                "services:\n  api:\n    command: npm run start:prod\n    environment:\n      API_TOKEN: new-secret\n",
                Map.of("DB_URL", "h9", "NEW", "h3"),
                "l2",
                "sha256:bbbbbbbbbbbbbbbb",
                "v22.3.0",
                "d2d2d2d2d2d2d2d2");
        List<KnownGood.Change> c = KnownGood.compare(then, now);
        assertThat(c)
                .extracting(KnownGood.Change::area)
                .contains("image", "tool", "devdesk.yaml", ".env", "lock file", "git");
        assertThat(c.getFirst().area()).isEqualTo("image"); // the likeliest cause first
        assertThat(c).anySatisfy(x -> assertThat(x.detail()).isEqualTo("v20.11.0 → v22.3.0"));
        assertThat(c).anySatisfy(x -> assertThat(x.what()).isEqualTo(".env: DB_URL"));
        assertThat(c).anySatisfy(x -> assertThat(x.what()).isEqualTo(".env: NEW"));
        assertThat(c).anySatisfy(x -> assertThat(x.what()).isEqualTo(".env: OLD"));
        assertThat(c).anySatisfy(x -> assertThat(x.detail()).contains("command: npm run start:prod"));
        assertThat(c.toString()).doesNotContain("old-secret", "new-secret");
    }
}
