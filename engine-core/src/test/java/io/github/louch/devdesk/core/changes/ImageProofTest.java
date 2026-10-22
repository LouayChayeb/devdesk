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
        public boolean running(Replica r) {
            return true;
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
        return replay.good.contains(image)
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
    void aCommandHealthCheckIsNeverReplayedBecauseItWouldHitTheRealService() throws Exception {
        replay.present.add(OLD);
        ServiceSpec withCommand = VerdictTest.service(
                "db",
                db.workload(),
                db.directory(),
                new HealthCheckSpec.Command("pg_isready -h 127.0.0.1", HealthCheckSpec.Timing.DEFAULT));

        var r = proof.prove(withCommand, OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.CANNOT_REPLAY);
        assertThat(replay.started).isEmpty();
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
