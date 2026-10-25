package io.github.louch.devdesk.core.changes;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.RestartPolicy;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** The replay's edge cases: what can't be replayed, and how each replica's result is decided. */
class ImageProofEdgesTest {

    private static final String OLD = "sha256:0123456789abcdef0123";
    private static final String NEW = "app:latest";

    /** A replay whose behaviour per image the test sets: passing, failing, exiting, not starting. */
    private static final class Replay implements ImageReplay {
        final Set<String> present = ConcurrentHashMap.newKeySet();
        final Set<String> passing = ConcurrentHashMap.newKeySet();
        final Set<String> exiting = ConcurrentHashMap.newKeySet();
        final Set<String> unstartable = ConcurrentHashMap.newKeySet();
        final Map<String, String> imageOf = new ConcurrentHashMap<>();
        final List<String> removed = new CopyOnWriteArrayList<>();
        Map<Integer, Integer> ports = Map.of(80, 40080);
        List<String> output = List.of();

        @Override
        public boolean has(String image) {
            return present.contains(image);
        }

        @Override
        public Replica start(String image, Workload.Container c, Map<String, String> env) throws LaunchException {
            if (unstartable.contains(image)) {
                throw new LaunchException("no such image " + image, null);
            }
            String id = image + "#replica";
            imageOf.put(id, image);
            return new Replica(id, id, ports);
        }

        @Override
        public boolean running(Replica r) {
            return !exiting.contains(imageOf.get(r.id()));
        }

        @Override
        public ProbeResult dockerHealth(Replica r) {
            return passing.contains(imageOf.get(r.id()))
                    ? new ProbeResult.Passed("healthy")
                    : new ProbeResult.Failed("starting");
        }

        @Override
        public List<String> lastOutput(Replica r, int lines) {
            return output;
        }

        @Override
        public void remove(Replica r) {
            removed.add(r.id());
        }
    }

    private final Replay replay = new Replay();

    /** The http probe passes for a replica whose image passes; it only sees the replica's port. */
    private final HealthProbe probe = (check, dir, env) -> {
        String url = ((HealthCheckSpec.Http) check).url().toString();
        assertThat(url).startsWith("http://127.0.0.1:40080");
        return new ProbeResult.Failed("refused");
    };

    private ImageProof proof(HealthProbe p) {
        return new ImageProof(replay, p, Clock.systemUTC(), Duration.ofMillis(2));
    }

    private static ServiceSpec service(Workload w, HealthCheckSpec health) {
        return new ServiceSpec(
                "web",
                w,
                Path.of("/"),
                Map.of(),
                List.of(),
                List.of(),
                health,
                RestartPolicy.NO,
                Duration.ofSeconds(1));
    }

    private static Workload.Container container(int hostPort, int containerPort) {
        return new Workload.Container(
                NEW,
                List.of(new Workload.PortMapping("127.0.0.1", hostPort, containerPort)),
                List.of(),
                Optional.empty());
    }

    private static HealthCheckSpec http(int port) {
        return new HealthCheckSpec.Http(
                java.net.URI.create("http://127.0.0.1:" + port + "/up"), 200, 299, HealthCheckSpec.Timing.DEFAULT);
    }

    @Test
    void aNativeServiceIsNeverReplayed() throws Exception {
        var r = proof(probe)
                .prove(service(new Workload.Command("npm start"), http(8080)), OLD, Map.of(), Duration.ofMillis(50));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.CANNOT_REPLAY);
        assertThat(r.summary()).isEqualTo("web isn't a container: only image changes can be replayed.");
        assertThat(r.caveat()).isEqualTo("Nothing was replayed.");
    }

    @Test
    void aRemovedOldImageIsNamedByItsShortId() throws Exception {
        var r = proof(probe).prove(service(container(8080, 80), http(8080)), OLD, Map.of(), Duration.ofMillis(50));

        assertThat(r.summary()).contains("(0123456789ab)").contains("docker image prune");
    }

    @Test
    void replicasThatCantStartOrExitFailWithTheirReasonAndOutput() throws Exception {
        replay.present.add(OLD);
        replay.unstartable.add(OLD);
        replay.exiting.add(NEW);

        var r = proof(probe).prove(service(container(8080, 80), http(8080)), OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.BOTH_FAIL);
        assertThat(r.evidence())
                .containsExactly(
                        "before (0123456789ab): could not start: no such image " + OLD,
                        "now (app:latest): exited. It printed nothing.");
        assertThat(replay.removed).containsExactly(NEW + "#replica"); // only what was started is removed
    }

    @Test
    void aHealthPortTheContainerDoesntPublishCantBeChecked() throws Exception {
        replay.present.add(OLD);
        replay.output = List.of("listening on 80");

        var r = proof(probe).prove(service(container(8080, 80), http(9999)), OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.evidence())
                .allSatisfy(e -> assertThat(e).endsWith("its health check's port isn't one of the ports it publishes"));
        assertThat(replay.removed).hasSize(2);
    }

    @Test
    void dockerHealthAndTheOddCaseWhereOnlyTheNewImagePasses() throws Exception {
        replay.present.add(OLD);
        replay.passing.add(NEW);

        var r = proof(probe)
                .prove(
                        service(container(8080, 80), new HealthCheckSpec.Docker(HealthCheckSpec.Timing.DEFAULT)),
                        OLD,
                        Map.of(),
                        Duration.ofMillis(150));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.ODD);
        assertThat(r.evidence())
                .containsExactly(
                        "before (0123456789ab): check never passed: starting. It printed nothing.",
                        "now (app:latest): check passed: healthy");
        assertThat(r.caveat()).contains("without your volume data").contains("on random ports");
    }

    @Test
    void withNoHealthCheckStillRunningAtTheEndIsAPass() throws Exception {
        replay.present.add(OLD);
        replay.exiting.add(NEW);
        replay.output = List.of("panic: missing config", "exit 1");

        var r = proof(probe)
                .prove(
                        service(container(8080, 80), new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT)),
                        OLD,
                        Map.of(),
                        Duration.ofMillis(100));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.PROVEN);
        assertThat(r.evidence())
                .containsExactly(
                        "before (0123456789ab): check passed: still running after the whole replay",
                        "now (app:latest): exited. Last output: panic: missing config | exit 1");
    }

    @Test
    void aPassMustRepeatToCount() throws Exception {
        replay.present.add(OLD);
        int[] calls = {0};
        // Passes only on the first probe of each replica, like Docker's forwarder answering for a dying container.
        HealthProbe flaky =
                (check, dir, env) -> ++calls[0] <= 1 ? new ProbeResult.Passed("ok") : new ProbeResult.Failed("refused");

        var r = proof(flaky).prove(service(container(8080, 80), http(8080)), OLD, Map.of(), Duration.ofMillis(100));

        assertThat(r.outcome()).isEqualTo(ImageProof.Outcome.BOTH_FAIL);
    }

    @Test
    void shortIdsCutAtTwelveAndDropTheAlgorithm() {
        assertThat(ImageProof.shortId("sha256:0123456789abcdef")).isEqualTo("0123456789ab");
        assertThat(ImageProof.shortId("0123456789ab")).isEqualTo("0123456789ab");
        assertThat(ImageProof.shortId("abc")).isEqualTo("abc");
    }
}
