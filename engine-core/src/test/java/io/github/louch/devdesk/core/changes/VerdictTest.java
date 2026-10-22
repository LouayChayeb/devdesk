package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.RestartPolicy;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VerdictTest {

    private static final Path ROOT = Path.of("/work/shop");

    static ServiceSpec service(String name, Workload w, Path dir, HealthCheckSpec health) {
        return new ServiceSpec(
                name, w, dir, Map.of(), List.of(), List.of(), health, RestartPolicy.NO, Duration.ofSeconds(1));
    }

    static ServiceSpec container(String name, String image, int hostPort, int containerPort) {
        return service(
                name,
                new Workload.Container(
                        image,
                        List.of(new Workload.PortMapping("127.0.0.1", hostPort, containerPort)),
                        List.of("data:/var/lib/data"),
                        Optional.empty()),
                ROOT,
                new HealthCheckSpec.Tcp("127.0.0.1", hostPort, HealthCheckSpec.Timing.DEFAULT));
    }

    private static final ProjectSpec PROJECT;

    static {
        Map<String, ServiceSpec> services = new LinkedHashMap<>();
        services.put("db", container("db", "postgres:latest", 5432, 5432));
        services.put(
                "api",
                service(
                        "api",
                        new Workload.Command("npm start"),
                        ROOT.resolve("api"),
                        new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT)));
        services.put(
                "web",
                service(
                        "web",
                        new Workload.Command("npm run dev"),
                        ROOT.resolve("web"),
                        new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT)));
        PROJECT = new ProjectSpec("shop", ROOT, Map.of(), services);
    }

    private static KnownGood.Change change(KnownGood.Kind kind, String subject) {
        return new KnownGood.Change(kind, subject, kind.name().toLowerCase(), subject, "changed");
    }

    @Test
    void anImageChangeOnTheServiceWhoseHealthFailsComesFirst() {
        var lock = change(KnownGood.Kind.LOCKFILE, "web/package-lock.json");
        var image = change(KnownGood.Kind.IMAGE, "postgres:latest");
        var symptoms = List.of(new Symptom("db", "health", "db never became healthy"));

        List<Suspect> ranked = Verdict.rank(PROJECT, List.of(lock, image), symptoms);

        assertThat(ranked.getFirst().change()).isEqualTo(image);
        assertThat(ranked.getFirst().confidence()).isEqualTo(Suspect.Confidence.LIKELY);
        assertThat(ranked.getFirst().reasons()).containsExactly("db runs this image and fails its health check");
        assertThat(ranked.getFirst().provable()).isTrue();
        // web's lock file doesn't reach db: listed, but nothing points at it.
        assertThat(ranked.get(1).confidence()).isEqualTo(Suspect.Confidence.UNKNOWN);
    }

    @Test
    void aRemovedSettingMatchesTheMissingSettingFindingThatNamesIt() {
        var removed = change(KnownGood.Kind.ENV_REMOVED, "STRIPE_KEY");
        var other = change(KnownGood.Kind.ENV_REMOVED, "OTHER");
        var symptoms = List.of(new Symptom("api", "missing-env", "Setting STRIPE_KEY is not set"));

        List<Suspect> ranked = Verdict.rank(PROJECT, List.of(other, removed), symptoms);

        assertThat(ranked.getFirst().change()).isEqualTo(removed);
        assertThat(ranked.getFirst().confidence()).isEqualTo(Suspect.Confidence.LIKELY);
        assertThat(ranked.get(1).confidence()).isEqualTo(Suspect.Confidence.UNKNOWN);
    }

    @Test
    void aLockFileReachesOnlyTheServiceInItsFolder() {
        var webLock = change(KnownGood.Kind.LOCKFILE, "web/package-lock.json");
        var apiLock = change(KnownGood.Kind.LOCKFILE, "api/package-lock.json");
        var symptoms = List.of(new Symptom("api", "crash", "api crashed"));

        List<Suspect> ranked = Verdict.rank(PROJECT, List.of(webLock, apiLock), symptoms);

        assertThat(ranked.getFirst().change()).isEqualTo(apiLock);
        assertThat(ranked.getFirst().confidence()).isEqualTo(Suspect.Confidence.LIKELY);
        assertThat(ranked.getFirst().services()).containsExactly("api");
        assertThat(ranked.get(1).services()).containsExactly("web");
        assertThat(ranked.get(1).confidence()).isEqualTo(Suspect.Confidence.UNKNOWN);
    }

    @Test
    void aRootLockFileReachesEveryCommandService() {
        var rootLock = change(KnownGood.Kind.LOCKFILE, "pnpm-lock.yaml");

        assertThat(Verdict.reached(PROJECT, rootLock)).containsExactly("api", "web");
    }

    @Test
    void codeAndLockFilesAreNeverMoreThanLikely() {
        var code = change(KnownGood.Kind.CODE, "a1b2c3d4");
        var lock = change(KnownGood.Kind.LOCKFILE, "api/package-lock.json");
        var symptoms = List.of(new Symptom("api", "crash", "api crashed"));

        Verdict.rank(PROJECT, List.of(code, lock), symptoms).forEach(s -> {
            assertThat(s.confidence()).isNotEqualTo(Suspect.Confidence.PROVEN);
            assertThat(s.provable()).isFalse();
        });
    }

    @Test
    void withNothingFailingEveryChangeIsUnknownAndSaysWhy() {
        var image = change(KnownGood.Kind.IMAGE, "postgres:latest");

        Suspect s = Verdict.rank(PROJECT, List.of(image), List.of()).getFirst();

        assertThat(s.confidence()).isEqualTo(Suspect.Confidence.UNKNOWN);
        assertThat(s.caveat()).contains("Nothing fails right now");
    }

    @Test
    void everySuspectCarriesACaveat() {
        var symptoms = List.of(new Symptom("api", "crash", "api crashed"));
        for (KnownGood.Kind kind : KnownGood.Kind.values()) {
            Suspect s =
                    Verdict.rank(PROJECT, List.of(change(kind, "x")), symptoms).getFirst();
            assertThat(s.caveat()).as(kind.name()).isNotBlank();
        }
    }

    @Test
    void aMissingProgramNamesTheToolThatChanged() {
        var node = change(KnownGood.Kind.TOOL, "node");
        var python = change(KnownGood.Kind.TOOL, "python");
        var symptoms = List.of(new Symptom("api", "program-missing", "api can't start: node is not installed"));

        List<Suspect> ranked = Verdict.rank(PROJECT, List.of(python, node), symptoms);

        assertThat(ranked.getFirst().change()).isEqualTo(node);
        assertThat(ranked.getFirst().reasons()).containsExactly("api can't find node");
    }
}
