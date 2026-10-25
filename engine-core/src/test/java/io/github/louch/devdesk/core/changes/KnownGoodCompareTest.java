package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Each kind of change, one at a time, with its exact wording (what `devdesk why` prints). */
class KnownGoodCompareTest {

    private static KnownGood base() {
        return new KnownGood(
                Instant.EPOCH,
                Optional.of("main"),
                Optional.of("aaaaaaaaaaaaaaaaaaaa"),
                "version: 1\nservices:\n  api: { command: x }\n",
                Map.of(".env", Map.of("A", "h1", "B", "h2")),
                Map.of("api/package-lock.json", "l1"),
                Map.of("postgres:latest", "sha256:1111111111111111aaaa"),
                Map.of("node", "v20"));
    }

    private static KnownGood with(
            Optional<String> branch,
            Optional<String> commit,
            String yaml,
            Map<String, Map<String, String>> env,
            Map<String, String> files,
            Map<String, String> images,
            Map<String, String> tools) {
        return new KnownGood(Instant.EPOCH, branch, commit, yaml, env, files, images, tools);
    }

    private static List<KnownGood.Change> diff(KnownGood now) {
        return KnownGood.compare(base(), now);
    }

    private static String line(KnownGood.Change c) {
        return c.kind() + "|" + c.subject() + "|" + c.area() + "|" + c.what() + "|" + c.detail();
    }

    @Test
    void nothingChangedIsAnEmptyList() {
        assertThat(diff(base())).isEmpty();
    }

    @Test
    void anImageThatPointsElsewhereButNotOneThatAppearedOrWentAway() {
        var b = base();
        var now = with(
                b.branch(),
                b.commit(),
                b.devdeskYaml(),
                b.envFiles(),
                b.files(),
                Map.of("postgres:latest", "sha256:2222222222222222bbbb"),
                b.tools());
        assertThat(diff(now))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly(
                        "IMAGE|postgres:latest|image|postgres:latest|now a different image (111111111111 → 222222222222):"
                                + " it was pulled again or rebuilt since");
        // Not pulled now (Docker gone, image removed): not reported as a change of image.
        var gone = with(b.branch(), b.commit(), b.devdeskYaml(), b.envFiles(), b.files(), Map.of(), b.tools());
        assertThat(diff(gone)).isEmpty();
    }

    @Test
    void toolsChangeOrDisappear() {
        var b = base();
        assertThat(diff(with(
                        b.branch(),
                        b.commit(),
                        b.devdeskYaml(),
                        b.envFiles(),
                        b.files(),
                        b.images(),
                        Map.of("node", "v22", "python", "3.12"))))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly("TOOL|node|tool|node|v20 → v22", "TOOL|python|tool|python|not found → 3.12");
    }

    @Test
    void envFilesKeysAndValuesWithoutEverShowingAValue() {
        var b = base();
        var now = with(
                b.branch(),
                b.commit(),
                b.devdeskYaml(),
                Map.of(".env", Map.of("A", "h1", "B", "CHANGED", "C", "h3"), ".env.local", Map.of("X", "h")),
                b.files(),
                b.images(),
                b.tools());

        assertThat(diff(now))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly(
                        "ENV_VALUE|B|.env|.env: B|value changed (values are never shown)",
                        "ENV_ADDED|C|.env|.env: C|added",
                        "ENV_FILE|.env.local|.env|.env.local|new file");

        var removed = with(
                b.branch(),
                b.commit(),
                b.devdeskYaml(),
                Map.of(".env", Map.of("A", "h1")),
                b.files(),
                b.images(),
                b.tools());
        assertThat(diff(removed))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly("ENV_REMOVED|B|.env|.env: B|removed");

        var deleted = with(b.branch(), b.commit(), b.devdeskYaml(), Map.of(), b.files(), b.images(), b.tools());
        assertThat(diff(deleted))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly("ENV_FILE|.env|.env|.env|deleted");
    }

    @Test
    void lockFilesNewDeletedOrChanged() {
        var b = base();
        var now = with(
                b.branch(),
                b.commit(),
                b.devdeskYaml(),
                b.envFiles(),
                Map.of("api/package-lock.json", "l2", "web/yarn.lock", "y"),
                b.images(),
                b.tools());
        assertThat(diff(now))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly(
                        "LOCKFILE|api/package-lock.json|lock file|api/package-lock.json|changed: dependencies were added or upgraded",
                        "LOCKFILE|web/yarn.lock|lock file|web/yarn.lock|new");
        var gone = with(b.branch(), b.commit(), b.devdeskYaml(), b.envFiles(), Map.of(), b.images(), b.tools());
        assertThat(diff(gone))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly("LOCKFILE|api/package-lock.json|lock file|api/package-lock.json|deleted");
    }

    @Test
    void gitBranchAndCommit() {
        var b = base();
        var now = with(
                Optional.of("feature"),
                Optional.of("bbbbbbbbbbbbbbbbbbbb"),
                b.devdeskYaml(),
                b.envFiles(),
                b.files(),
                b.images(),
                b.tools());
        assertThat(diff(now))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly(
                        "BRANCH|feature|git|branch|main → feature",
                        "CODE|aaaaaaaaaaaaaaaaaaaa|git|commit|aaaaaaaaaaaa → bbbbbbbbbbbb: code changed too (git log aaaaaaaaaaaa..HEAD)");
        // No git now (or then): no commit change can be claimed.
        var noGit = with(
                Optional.empty(), Optional.empty(), b.devdeskYaml(), b.envFiles(), b.files(), b.images(), b.tools());
        assertThat(diff(noGit))
                .extracting(KnownGoodCompareTest::line)
                .containsExactly("BRANCH||git|branch|main → (none)");
    }

    @Test
    void devdeskYamlEditsAreOneChangeWithSecretsHidden() {
        var b = base();
        String yaml = "version: 1\n# a new comment\n\nservices:\n  api: { command: y }\n"
                + "    DB_PASSWORD: hunter2\n    a1: 1\n    a2: 2\n    a3: 3\n    a4: 4\n    a5: 5\n";
        var c = diff(with(b.branch(), b.commit(), yaml, b.envFiles(), b.files(), b.images(), b.tools()));

        assertThat(c).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(KnownGood.Kind.CONFIG);
            assertThat(x.what()).isEqualTo("8 lines changed"); // comments and blank lines don't count
            assertThat(x.detail())
                    .startsWith("- api: { command: x } · + api: { command: y } · + DB_PASSWORD: (hidden)")
                    .endsWith(" · … 2 more");
            assertThat(x.subject()).doesNotContain("hunter2").contains("DB_PASSWORD: (hidden)");
        });
        var one = diff(with(
                b.branch(),
                b.commit(),
                b.devdeskYaml() + "  web: { command: z }\n",
                b.envFiles(),
                b.files(),
                b.images(),
                b.tools()));
        assertThat(one).singleElement().satisfies(x -> {
            assertThat(x.what()).isEqualTo("1 line changed");
            assertThat(x.detail()).isEqualTo("+ web: { command: z }");
        });
    }

    @Test
    void theMostLikelyKindsComeFirst() {
        var now = with(
                Optional.of("x"),
                Optional.of("bbbbbbbbbbbbbbbbbbbb"),
                "version: 2\n",
                Map.of(".env", Map.of("A", "h9", "B", "h2")),
                Map.of("api/package-lock.json", "l9"),
                Map.of("postgres:latest", "sha256:9"),
                Map.of("node", "v9"));

        assertThat(diff(now))
                .extracting(KnownGood.Change::kind)
                .containsExactly(
                        KnownGood.Kind.IMAGE,
                        KnownGood.Kind.TOOL,
                        KnownGood.Kind.CONFIG,
                        KnownGood.Kind.ENV_VALUE,
                        KnownGood.Kind.LOCKFILE,
                        KnownGood.Kind.BRANCH,
                        KnownGood.Kind.CODE);
    }
}
