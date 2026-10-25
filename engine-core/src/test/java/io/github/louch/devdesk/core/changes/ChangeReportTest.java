package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChangeReportTest {

    private static final Path ROOT = Path.of("/work/shop");
    private static final Instant THEN = Instant.parse("2026-10-20T10:00:00Z");

    private final ProjectSpec project;

    ChangeReportTest() {
        Map<String, ServiceSpec> s = new LinkedHashMap<>();
        s.put("db", VerdictTest.container("db", "postgres:latest", 5432, 5432));
        s.put(
                "api",
                VerdictTest.service(
                        "api",
                        new Workload.Command("npm start"),
                        ROOT.resolve("services/api"),
                        new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT)));
        s.put(
                "web",
                VerdictTest.service(
                        "web",
                        new Workload.Command("npm run dev"),
                        ROOT.resolve("web"),
                        new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT)));
        project = new ProjectSpec("shop", ROOT, Map.of(), s);
    }

    private static KnownGood snapshot(String commit, String imageId) {
        return new KnownGood(
                THEN,
                Optional.of("main"),
                Optional.of(commit),
                "version: 1\n",
                Map.of(),
                Map.of(),
                Map.of("postgres:latest", imageId),
                Map.of());
    }

    @Test
    void withoutACleanStartThereIsNothingToCompare() {
        var report = new ChangeReport(p -> Optional.empty(), p -> snapshot("b", "i2"), (r, g) -> Optional.empty());

        assertThat(report.build(project, List.of())).isEmpty();
        assertThat(report.previousImage(project, "postgres:latest")).isEmpty();
    }

    @Test
    void aCodeChangeGetsABisectPlanAimedAtTheFailingServicesFolders() {
        List<String> asked = new ArrayList<>();
        var commits = List.of(
                new Bisect.Commit("c3", "docs", List.of("README.md")),
                new Bisect.Commit("c2", "fix api", List.of("services/api/main.ts")));
        var report = new ChangeReport(
                p -> Optional.of(snapshot("aaaaaaa", "i1")), p -> snapshot("bbbbbbb", "i1"), (root, good) -> {
                    asked.add(root + " " + good);
                    return Optional.of(commits);
                });

        var built =
                report.build(project, List.of(new Symptom("api", "crash", "x"))).orElseThrow();

        assertThat(built.since()).isEqualTo(THEN);
        var code = built.items().stream()
                .filter(i -> i.suspect().change().kind() == KnownGood.Kind.CODE)
                .findFirst()
                .orElseThrow();
        assertThat(asked).containsExactly(ROOT + " aaaaaaa");
        var plan = code.bisect().orElseThrow();
        // The failing service's folder relative to the root: its commit comes first.
        assertThat(plan.commits()).extracting(r -> r.commit().id()).containsExactly("c2", "c3");
        assertThat(plan.commits().getFirst().touching()).isEqualTo(1);
        assertThat(plan.commands()).contains("git bisect good aaaaaaa");
    }

    @Test
    void withNoFailingServiceTheBisectLooksAtEveryServiceTheCodeReaches() {
        var commits = List.of(
                new Bisect.Commit("c2", "web change", List.of("web/app.ts")),
                new Bisect.Commit("c1", "other", List.of("tools/x")));
        var report = new ChangeReport(
                p -> Optional.of(snapshot("aaaaaaa", "i1")),
                p -> snapshot("bbbbbbb", "i1"),
                (root, good) -> Optional.of(commits));

        var code = report.build(project, List.of()).orElseThrow().items().getFirst();

        assertThat(code.bisect().orElseThrow().commits().getFirst().touching()).isEqualTo(1);
    }

    @Test
    void onlyCodeChangesGetAPlanAndGitFailingMeansNoPlan() {
        var report = new ChangeReport(
                p -> Optional.of(snapshot("aaaaaaa", "i1")),
                p -> snapshot("bbbbbbb", "i2"),
                (root, good) -> Optional.empty()); // git missing, or the commit is gone

        var items = report.build(project, List.of()).orElseThrow().items();

        assertThat(items)
                .extracting(i -> i.suspect().change().kind())
                .containsExactlyInAnyOrder(KnownGood.Kind.IMAGE, KnownGood.Kind.CODE);
        assertThat(items).allSatisfy(i -> assertThat(i.bisect()).isEmpty());
        assertThat(report.previousImage(project, "postgres:latest")).contains("i1");
        assertThat(report.previousImage(project, "redis:8")).isEmpty();
    }
}
