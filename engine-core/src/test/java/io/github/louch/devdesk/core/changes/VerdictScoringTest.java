package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The exact score, confidence and reason of each kind of change against each kind of failure: the numbers
 * EXPLANATION.md §12.3 documents. A change of scoring must change this test on purpose.
 */
class VerdictScoringTest {

    private static final Path ROOT = Path.of("/work/shop");
    private static final ProjectSpec PROJECT;

    static {
        Map<String, ServiceSpec> s = new LinkedHashMap<>();
        s.put("db", VerdictTest.container("db", "postgres:latest", 5432, 5432));
        s.put("cache", VerdictTest.container("cache", "postgres:latest", 6380, 6379)); // same image, second user
        s.put("api", command("api"));
        s.put("web", command("web"));
        PROJECT = new ProjectSpec("shop", ROOT, Map.of(), s);
    }

    private static ServiceSpec command(String name) {
        return VerdictTest.service(
                name,
                new Workload.Command("npm start"),
                ROOT.resolve(name),
                new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT));
    }

    private static KnownGood.Change change(KnownGood.Kind kind, String subject) {
        return new KnownGood.Change(kind, subject, "area", subject, "detail");
    }

    private static Suspect judge(KnownGood.Kind kind, String subject, Symptom... symptoms) {
        return Verdict.rank(PROJECT, List.of(change(kind, subject)), List.of(symptoms))
                .getFirst();
    }

    private static void expect(Suspect s, int score, Suspect.Confidence confidence, String... reasons) {
        assertThat(s.score()).as("score").isEqualTo(score);
        assertThat(s.confidence()).as("confidence").isEqualTo(confidence);
        assertThat(s.reasons()).as("reasons").containsExactly(reasons);
    }

    private static Symptom on(String service, String rule, String text) {
        return new Symptom(service, rule, text);
    }

    @Test
    void imageChanges() {
        expect(
                judge(KnownGood.Kind.IMAGE, "postgres:latest", on("db", "health", "db never became healthy")),
                100,
                Suspect.Confidence.LIKELY,
                "db runs this image and fails its health check");
        expect(
                judge(
                        KnownGood.Kind.IMAGE,
                        "postgres:latest",
                        on("db", "crash", "x"),
                        on("cache", "killed-from-outside", "x")),
                100,
                Suspect.Confidence.LIKELY,
                "db runs this image and crashes",
                "cache runs this image and was killed");
        expect(
                judge(KnownGood.Kind.IMAGE, "postgres:latest", on("db", "memory-growth", "x")),
                60,
                Suspect.Confidence.LIKELY,
                "db runs this image and has a problem (memory-growth)");
        // A failure elsewhere doesn't point at this image.
        expect(judge(KnownGood.Kind.IMAGE, "postgres:latest", on("api", "crash", "x")), 10, Suspect.Confidence.UNKNOWN);
        assertThat(judge(KnownGood.Kind.IMAGE, "postgres:latest").services()).containsExactly("db", "cache");
    }

    @Test
    void settingChanges() {
        expect(
                judge(
                        KnownGood.Kind.ENV_REMOVED,
                        "JWT_SECRET",
                        on("api", "missing-env", "Setting JWT_SECRET is not set")),
                95,
                Suspect.Confidence.LIKELY,
                "api expects a setting that is gone now (missing-env)");
        // Whole words only: PORT isn't PORTAL.
        expect(
                judge(KnownGood.Kind.ENV_REMOVED, "PORT", on("api", "missing-env", "Setting PORTAL is not set")),
                10,
                Suspect.Confidence.UNKNOWN);
        expect(
                judge(KnownGood.Kind.ENV_REMOVED, "PORT", on("api", "crash", "x")),
                45,
                Suspect.Confidence.UNKNOWN,
                "api crashes, and it changed before");
        expect(
                judge(KnownGood.Kind.ENV_FILE, ".env", on("web", "missing-env", "2 settings are not set")),
                95,
                Suspect.Confidence.LIKELY,
                "web expects a setting that is gone now (missing-env)");
        expect(
                judge(KnownGood.Kind.ENV_ADDED, "NEW", on("api", "crash", "x")),
                25,
                Suspect.Confidence.UNKNOWN,
                "api crashes, and a new setting appeared before");
        expect(
                judge(KnownGood.Kind.ENV_VALUE, "DATABASE_URL", on("api", "health", "x")),
                50,
                Suspect.Confidence.LIKELY,
                "api fails its health check, and this setting's value changed before");
        expect(judge(KnownGood.Kind.ENV_VALUE, "DATABASE_URL"), 10, Suspect.Confidence.UNKNOWN);
    }

    @Test
    void lockFileChanges() {
        expect(
                judge(KnownGood.Kind.LOCKFILE, "api/package-lock.json", on("api", "crash", "x")),
                75,
                Suspect.Confidence.LIKELY,
                "api uses these dependencies and crashes");
        expect(
                judge(KnownGood.Kind.LOCKFILE, "api/package-lock.json", on("api", "program-missing", "x")),
                70,
                Suspect.Confidence.LIKELY,
                "api uses these dependencies and can't find its program");
        expect(
                judge(KnownGood.Kind.LOCKFILE, "api/package-lock.json", on("web", "crash", "x")),
                15,
                Suspect.Confidence.UNKNOWN);
        assertThat(Verdict.reached(PROJECT, change(KnownGood.Kind.LOCKFILE, "pnpm-lock.yaml")))
                .containsExactly("api", "web");
        assertThat(Verdict.reached(PROJECT, change(KnownGood.Kind.LOCKFILE, "tools/x/package-lock.json")))
                .isEmpty(); // a folder no service runs in reaches nobody
    }

    @Test
    void toolChanges() {
        expect(
                judge(
                        KnownGood.Kind.TOOL,
                        "node",
                        on("api", "program-missing", "api can't start: node isn't installed")),
                90,
                Suspect.Confidence.LIKELY,
                "api can't find node");
        expect(
                judge(KnownGood.Kind.TOOL, "node", on("web", "crash", "x")),
                55,
                Suspect.Confidence.LIKELY,
                "web crashes, and node changed version before");
        // A container's failure isn't caused by a tool on this machine.
        expect(judge(KnownGood.Kind.TOOL, "node", on("db", "crash", "x")), 10, Suspect.Confidence.UNKNOWN);
    }

    @Test
    void devdeskYamlChanges() {
        expect(
                judge(KnownGood.Kind.CONFIG, "    command: npm run api", on("api", "crash", "x")),
                60,
                Suspect.Confidence.LIKELY,
                "this line is about api, which crashes");
        expect(
                judge(
                        KnownGood.Kind.CONFIG,
                        "    ports: [3001]",
                        on("web", "port-conflict", "Port 3001 is already in use")),
                60,
                Suspect.Confidence.LIKELY,
                "this line is about web, which has a problem (port-conflict)");
        expect(
                judge(KnownGood.Kind.CONFIG, "    ports: [3002]", on("x", "crash", "port 3001")),
                30,
                Suspect.Confidence.UNKNOWN);
        expect(judge(KnownGood.Kind.CONFIG, "    ports: [3002]"), 10, Suspect.Confidence.UNKNOWN);
    }

    @Test
    void codeAndBranchChanges() {
        expect(
                judge(KnownGood.Kind.CODE, "a1b2c3d", on("api", "health", "x")),
                65,
                Suspect.Confidence.LIKELY,
                "api runs this code and fails its health check");
        // Only native services run the repository's code.
        expect(judge(KnownGood.Kind.CODE, "a1b2c3d", on("db", "crash", "x")), 15, Suspect.Confidence.UNKNOWN);
        expect(
                judge(KnownGood.Kind.BRANCH, "feature", on("db", "crash", "x")),
                50,
                Suspect.Confidence.LIKELY,
                "db crashes, and the branch changed before");
    }

    @Test
    void theCaveatSaysWhatTheJudgementRestsOn() {
        var failing = new Symptom("api", "crash", "x");
        assertThat(judge(KnownGood.Kind.IMAGE, "postgres:latest", failing).caveat())
                .contains("Prove it");
        assertThat(judge(KnownGood.Kind.CODE, "a1", failing).caveat()).contains("git bisect");
        assertThat(judge(KnownGood.Kind.BRANCH, "b", failing).caveat()).contains("git bisect");
        assertThat(judge(KnownGood.Kind.LOCKFILE, "a/b", failing).caveat()).contains("reinstall");
        assertThat(judge(KnownGood.Kind.ENV_VALUE, "K", failing).caveat()).contains("fingerprints");
        assertThat(judge(KnownGood.Kind.TOOL, "node", failing).caveat()).contains("not replayed");
        assertThat(judge(KnownGood.Kind.CODE, "a1").caveat()).startsWith("Nothing fails right now");
    }

    @Test
    void moreLikelyFirstAndEqualScoresKeepTheirOrder() {
        var symptoms = List.of(new Symptom("api", "crash", "x"));
        var ranked = Verdict.rank(
                PROJECT,
                List.of(
                        change(KnownGood.Kind.ENV_ADDED, "A"), // 25
                        change(KnownGood.Kind.CODE, "c1"), // 65
                        change(KnownGood.Kind.ENV_ADDED, "B"), // 25
                        change(KnownGood.Kind.LOCKFILE, "api/package-lock.json")), // 75
                symptoms);

        assertThat(ranked)
                .extracting(s -> s.change().subject())
                .containsExactly("api/package-lock.json", "c1", "A", "B");
    }
}
