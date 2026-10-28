package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ImageProofTest {

    private static final String OLD = "sha256:1111111111111111111111";
    private static final String NEW = "postgres:latest";

    /** A fake Docker: replicas of "good" images listen, others don't. Remembers what it started and removed. */
    static final class FakeReplay implements ImageReplay {
        final Set<String> present = ConcurrentHashMap.newKeySet();
        final Set<String> good = ConcurrentHashMap.newKeySet();
        final List<String> started = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> removed = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, String> imageOf = new ConcurrentHashMap<>();
        /** Images whose replica answers one probe, then is gone (Docker's forwarder fooling a TCP check). */
        final Set<String> dying = ConcurrentHashMap.newKeySet();

        final Set<String> probed = ConcurrentHashMap.newKeySet();
        /** Images whose replica exits at once. */
        final Set<String> exits = ConcurrentHashMap.newKeySet();
        /** The database copies each replica was given. */
        final List<List<String>> databases = new java.util.concurrent.CopyOnWriteArrayList<>();

        private final AtomicInteger next = new AtomicInteger(40000);

        @Override
        public boolean has(String image) {
            return present.contains(image);
        }

        @Override
        public Replica start(String image, Workload.Container c, Map<String, String> env) throws LaunchException {
            int port = next.getAndIncrement();
            String id = "replica-" + port;
            started.add(image);
            imageOf.put(id, image);
            return new Replica(id, id, Map.of(5432, port));
        }

        @Override
        public Replica start(String image, Workload.Container c, Map<String, String> env, List<Helper> helpers)
                throws LaunchException {
            databases.add(helpers.stream()
                    .map(h -> h.service() + "=" + h.container().image() + " " + h.environment())
                    .toList());
            return start(image, c, env);
        }

        @Override
        public boolean running(Replica r) {
            return !exits.contains(imageOf.get(r.id()))
                    && !(dying.contains(imageOf.get(r.id())) && probed.contains(r.id()));
        }

        @Override
        public ProbeResult dockerHealth(Replica r) {
            return new ProbeResult.Failed("no");
        }

        @Override
        public List<String> lastOutput(Replica r, int lines) {
            return List.of("FATAL: database files are incompatible");
        }

        @Override
        public void remove(Replica r) {
            removed.add(r.id());
        }
    }

    private final FakeReplay replay = new FakeReplay();
    /** Port → is a "good" replica listening there? Probes see only the replica's random port, never 5432. */
    private final List<Integer> probedPorts = new ArrayList<>();

    private final HealthProbe probe = (check, dir, env) -> {
        int port = ((HealthCheckSpec.Tcp) check).port();
        synchronized (probedPorts) {
            probedPorts.add(port);
        }
        String image = replay.imageOf.get("replica-" + port);
        replay.probed.add("replica-" + port);
        return replay.good.contains(image) || replay.dying.contains(image)
                ? new ProbeResult.Passed("connected")
                : new ProbeResult.Failed("connection refused");
    };

    private final ImageProof proof = new ImageProof(replay, probe, Clock.systemUTC(), Duration.ofMillis(5));
    private final ServiceSpec db = VerdictTest.container("db", NEW, 5432, 5432);

    @Test
    void oldPassesAndNewFailsIsProvenAndBothReplicasAreRemoved() throws Exception {
        replay.present.add(OLD);
        replay.good.add(OLD);

        var r = proof.prove(db, OLD, Map.of(), Duration.ofMillis(200));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.PROVEN);
        assertThat(r.caveat()).contains("without your volume data");
        assertThat(r.evidence()).hasSize(2);
        assertThat(replay.started).containsExactlyInAnyOrder(OLD, NEW);
        assertThat(replay.removed).hasSize(2);
        // The real port 5432 is never probed: only the replicas' random ports.
        assertThat(probedPorts).isNotEmpty().doesNotContain(5432);
    }

    @Test
    void aSinglePassFromAReplicaThatThenExitsDoesNotCount() throws Exception {
        replay.present.add(OLD);
        replay.good.add(OLD);
        replay.dying.add(NEW); // its check "passes" once, then the container is gone

        var r = proof.prove(db, OLD, Map.of(), Duration.ofMillis(300));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.PROVEN);
    }

    @Test
    void bothFailingLowersTheSuspect() throws Exception {
        replay.present.add(OLD);

        var r = proof.prove(db, OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.BOTH_FAIL);
        var s = new Suspect(
                new KnownGood.Change(KnownGood.Kind.IMAGE, NEW, "image", NEW, "changed"),
                Suspect.Confidence.LIKELY,
                100,
                List.of("db"),
                List.of("db runs this image and fails its health check"),
                "x");
        assertThat(r.applyTo(s).confidence()).isEqualTo(Suspect.Confidence.UNKNOWN);
        assertThat(replay.removed).hasSize(2);
    }

    @Test
    void bothPassingSaysItIsProbablyYourData() throws Exception {
        replay.present.add(OLD);
        replay.good.add(OLD);
        replay.good.add(NEW);

        var r = proof.prove(db, OLD, Map.of(), Duration.ofMillis(200));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.BOTH_PASS);
        assertThat(r.summary()).contains("your data");
    }

    @Test
    void aRemovedOldImageCantBeReplayedAndNothingStarts() throws Exception {
        var r = proof.prove(db, OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.CANNOT_REPLAY);
        assertThat(r.summary()).contains("no longer on this machine");
        assertThat(replay.started).isEmpty();
    }

    @Test
    void aCommandHealthCheckIsNeverRunAgainstACopyItBecomesACrashCheck() throws Exception {
        replay.present.add(OLD);
        replay.exits.add(NEW);
        ServiceSpec withCommand = VerdictTest.service(
                "db",
                db.workload(),
                db.directory(),
                new HealthCheckSpec.Command("pg_isready -h 127.0.0.1", HealthCheckSpec.Timing.DEFAULT));

        var r = proof.prove(withCommand, OLD, Map.of(), Duration.ofMillis(100));

        // The command (it would talk to the real db) never ran: the probe here only knows tcp and would throw.
        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.PROVEN);
        assertThat(r.kind()).isEqualTo(ImageProof.Kind.CRASH_CHECK);
        assertThat(r.summary()).startsWith("Proven by a crash check: the image db used before keeps running");
        assertThat(r.caveat())
                .startsWith("Crash check only (its health check is a command, which would talk to the" + " real db)");
        assertThat(replay.removed).hasSize(2);
    }

    private static io.github.louch.devdesk.core.project.ProjectSpec project(String api) throws Exception {
        return io.github.louch.devdesk.core.config.ConfigParser.parse(
                """
                version: 1
                services:
                  db:
                    container: { image: "postgres:18", ports: ["5432:5432"] }
                    environment: { POSTGRES_PASSWORD: secret }
                    health: { type: tcp, port: 5432 }
                  mail:
                    command: mailpit
                  api:
                """ + api, java.nio.file.Path.of("/work/shop"), Map.of(), f -> java.util.Optional.empty());
    }

    @Test
    void eachCopyGetsItsOwnFreshDatabasesAndKeepsItsHealthCheck() throws Exception {
        replay.present.add(OLD);
        replay.good.add(OLD);
        var p = project("""
                    container: { image: "postgres:latest", ports: ["5432:5432"] }
                    depends_on: { db: { condition: healthy } }
                    health: { type: tcp, port: 5432 }
                """);

        var r = proof.prove(p, p.services().get("api"), OLD, Map.of(), Duration.ofMillis(200));

        assertThat(r.kind()).isEqualTo(ImageProof.Kind.HEALTH_CHECK);
        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.PROVEN);
        // Two copies, each with its own empty db, which gets the real db's settings (its password).
        assertThat(replay.databases)
                .hasSize(2)
                .allSatisfy(d -> assertThat(d).containsExactly("db=postgres:18 {POSTGRES_PASSWORD=secret}"));
        assertThat(r.caveat()).contains("with fresh, empty copies of db (no data)");
    }

    @Test
    void aServiceThatNeedsMoreThanDatabasesIsJudgedByCrashesAndSaysSo() throws Exception {
        replay.present.add(OLD);
        var p = project("""
                    container: { image: "postgres:latest", ports: ["5432:5432"] }
                    depends_on: [db, mail]
                    health: { type: tcp, port: 5432 }
                """);

        var r = proof.prove(p, p.services().get("api"), OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.kind()).isEqualTo(ImageProof.Kind.CRASH_CHECK);
        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.BOTH_PASS);
        assertThat(r.summary()).contains("A crash check can't tell whether they work");
        assertThat(r.caveat()).contains("it also needs mail, which a test copy can't have");
        assertThat(replay.databases).allSatisfy(d -> assertThat(d).hasSize(1)); // db is still copied
        assertThat(probedPorts).isEmpty();
    }

    @Test
    void anHttpCheckIsAimedAtTheReplicasPortKeepingThePath() {
        var c = (Workload.Container)
                VerdictTest.container("api", "api:1", 8080, 80).workload();
        var check = new HealthCheckSpec.Http(
                java.net.URI.create("http://localhost:8080/health?deep=1"), 200, 299, HealthCheckSpec.Timing.DEFAULT);

        var pointed = ImageProof.pointAt(check, c, new ImageReplay.Replica("x", "x", Map.of(80, 49153)));

        assertThat(pointed)
                .get()
                .isInstanceOfSatisfying(
                        HealthCheckSpec.Http.class,
                        h -> assertThat(h.url().toString()).isEqualTo("http://127.0.0.1:49153/health?deep=1"));
    }
}
