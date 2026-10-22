package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BisectTest {

    private final Bisect.Commit docs = new Bisect.Commit("c3", "docs", List.of("README.md"));
    private final Bisect.Commit api = new Bisect.Commit("c2", "change port", List.of("api/src/main.ts", "api/.env"));
    private final Bisect.Commit both = new Bisect.Commit("c1", "rename", List.of("api2/x.ts", "web/y.ts"));

    @Test
    void commitsTouchingTheFailingServiceComeFirstAndTheCommandsStartFromTheGoodCommit() {
        Bisect.Plan plan = Bisect.plan(List.of(docs, api, both), List.of("api"), "c0");

        assertThat(plan.commits()).extracting(r -> r.commit().id()).containsExactly("c2", "c3", "c1");
        assertThat(plan.commits().getFirst().touching()).isEqualTo(2);
        // "api2/x.ts" isn't inside "api".
        assertThat(plan.commits().get(2).touching()).isZero();
        assertThat(plan.commands())
                .containsSubsequence("git bisect start", "git bisect bad HEAD", "git bisect good c0");
    }

    @Test
    void noCommitsMeansNoCommands() {
        assertThat(Bisect.plan(List.of(), List.of("api"), "c0").commands()).isEmpty();
    }
}
