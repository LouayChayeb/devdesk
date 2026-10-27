package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.HealthCheck;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.container.RunningContainer;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Leftovers after a crash, stopping what already ended, Docker's own health, and the setup scan. */
@Tag("docker")
class DockerRuntimeEdgesTest {

    private static final String IMAGE = "alpine:3";

    private final String tag = TestJvm.tag();
    private final Queue<OutputLine> lines = new ConcurrentLinkedQueue<>();
    /** Every container this test made, by name: removed at the end, and nothing else. */
    private final List<String> made = new ArrayList<>();
    /** The same for volumes. */
    private final List<String> madeVolumes = new ArrayList<>();

    private DockerClient docker;
    private DockerContainerRuntime runtime;

    @BeforeEach
    void connect() {
        var result = DockerConnection.connect(System.getenv());
        assumeTrue(result instanceof DockerConnection.Result.Connected, "Docker not available: " + result);
        TestJvm.reapOrphans(); // containers of test JVMs that were killed mid-test
        runtime = ((DockerConnection.Result.Connected) result).runtime();
        docker = runtime.client();
    }

    @AfterEach
    void removeWhatThisTestMade() throws Exception {
        if (runtime != null) {
            for (String name : made) {
                try {
                    docker.removeContainerCmd(name)
                            .withForce(true)
                            .withRemoveVolumes(true)
                            .exec();
                } catch (DockerException e) {
                    // already removed by the test itself
                }
            }
            for (String volume : madeVolumes) {
                try {
                    docker.removeVolumeCmd(volume).exec();
                } catch (DockerException e) {
                    // never created: the test failed before
                }
            }
            docker.close();
        }
    }

    private ProjectSpec project(String folder) {
        return new ProjectSpec("edge" + tag, Path.of("/tmp", folder + tag), Map.of(), Map.of());
    }

    private RunningContainer start(ProjectSpec p, String service, String command) throws LaunchException {
        var spec = new ContainerLaunchSpec(
                p.name(),
                p.key(),
                service,
                new Workload.Container(IMAGE, List.of(), List.of(), Optional.of(command)),
                Map.of("GREETING", "hi"));
        made.add(DockerContainerRuntime.containerName(p.name(), service));
        return runtime.start(spec, lines::add);
    }

    @Test
    void leftoversAreFoundByProjectKeyAndRemovingOneTouchesNothingElse() throws Exception {
        var mine = project("a");
        var sameNameOtherFolder = project("b"); // same project name, other key
        var api = start(mine, "api", "sleep 120");
        var other = start(sameNameOtherFolder, "web", "sleep 120");
        var finder = runtime.leftovers();

        List<Leftover> found = finder.find(mine);

        assertThat(found).singleElement().satisfies(l -> {
            assertThat(l.id()).isEqualTo(api.id());
            assertThat(l.service()).isEqualTo("api");
            assertThat(l.kind()).isEqualTo(Leftover.Kind.CONTAINER);
            assertThat(l.pids()).containsExactly(api.pid());
            assertThat(l.description()).startsWith("container " + api.name() + " (alpine:3, Up");
            assertThat(l.since()).isPresent();
        });
        assertThat(finder.remove(found.getFirst())).isEqualTo("stopped and removed");
        assertThat(finder.find(mine)).isEmpty();
        assertThat(finder.find(sameNameOtherFolder)).extracting(Leftover::id).containsExactly(other.id());
        assertThat(docker.inspectContainerCmd(other.id()).exec().getState().getRunning())
                .isTrue();
        // Removing something already gone is fine, and says so.
        assertThat(finder.remove(found.getFirst())).isEqualTo("stopped and removed");
    }

    @Test
    void anotherFoldersContainerWithTheSameNameIsNeverReplaced() throws Exception {
        var first = project("a");
        var second = project("b"); // same name, so the same container name, but another key
        var running = start(first, "db", "sleep 120");

        var refused = catchThrowableOfType(LaunchException.class, () -> start(second, "db", "sleep 120"));

        assertThat(refused.getMessage())
                .contains("already runs for another DevDesk project also called \"" + first.name() + "\"")
                .contains("in another folder");
        assertThat(docker.inspectContainerCmd(running.id()).exec().getState().getRunning())
                .isTrue();
    }

    @Test
    void stoppingWhatAlreadyEndedOrVanishedIsAPlainStop() throws Exception {
        var p = project("a");
        var job = start(p, "job", "sh -c 'exit 3'");
        assertThat(runtime.onExit(job).get(30, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(3));

        assertThat(runtime.stop(job, Duration.ofSeconds(1))).isEqualTo(new StopResult.Stopped(ExitStatus.of(3), false));
        assertThat(runtime.ownContainerNames(p.name())).isEmpty();

        var gone = start(p, "gone", "sleep 120");
        docker.removeContainerCmd(gone.id()).withForce(true).exec(); // removed behind DevDesk's back
        assertThat(runtime.stop(gone, Duration.ofSeconds(1))).isInstanceOf(StopResult.Stopped.class);
    }

    @Test
    void dockerHealthIsReportedWithTheChecksLastOutput() throws Exception {
        String healthy = "devdesk-edge" + tag + "-healthy";
        String sick = "devdesk-edge" + tag + "-sick";
        made.add(healthy);
        made.add(sick);
        String okId = withHealthcheck(healthy, "echo all good");
        String badId = withHealthcheck(sick, "echo db down; exit 1");
        var ok = new RunningContainer("healthy", okId, healthy, 0);
        var bad = new RunningContainer("sick", badId, sick, 0);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!(runtime.health(ok) instanceof ProbeResult.Passed) && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertThat(runtime.health(ok))
                .isEqualTo(new ProbeResult.Passed("Docker reports " + healthy + " healthy: all good"));
        while (!runtime.health(bad).detail().contains("unhealthy") && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertThat(runtime.health(bad))
                .isEqualTo(new ProbeResult.Failed("Docker reports " + sick + " unhealthy: db down"));
        assertThat(runtime.health(new RunningContainer("x", "no-such-container-" + tag, "x", 0))
                        .detail())
                .startsWith("could not ask Docker about x: ");
    }

    private String withHealthcheck(String name, String check) {
        String id = docker.createContainerCmd(IMAGE)
                .withName(name)
                .withCmd("sleep", "120")
                .withHealthcheck(new HealthCheck()
                        .withTest(List.of("CMD-SHELL", check))
                        .withInterval(Duration.ofMillis(300).toNanos())
                        .withRetries(1))
                .exec()
                .getId();
        docker.startContainerCmd(id).exec();
        return id;
    }

    @Test
    void theSetupScanSeesARunningContainersPortsChosenSettingsAndVolumes() throws Exception {
        String name = "devdesk-edge" + tag + "-scan";
        String volume = "devdesk-edge" + tag + "-data";
        made.add(name);
        madeVolumes.add(volume);
        int port;
        try (var s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String id = docker.createContainerCmd(IMAGE)
                .withName(name)
                .withCmd("sleep", "120")
                .withEnv("POSTGRES_PASSWORD=dev", "PG_VERSION=18.1")
                .withLabels(Map.of("com.docker.compose.project.working_dir", "/work/shop"))
                .withExposedPorts(com.github.dockerjava.api.model.ExposedPort.tcp(5432))
                .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                        .withPortBindings(
                                com.github.dockerjava.api.model.PortBinding.parse("127.0.0.1:" + port + ":5432"))
                        .withBinds(com.github.dockerjava.api.model.Bind.parse(volume + ":/var/lib/data")))
                .exec()
                .getId();
        docker.startContainerCmd(id).exec();
        var seen = runtime.running().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow();

        assertThat(seen.image()).isEqualTo(IMAGE);
        assertThat(seen.ports()).containsExactly(port + ":5432");
        assertThat(seen.environment())
                .isEqualTo(Map.of("POSTGRES_PASSWORD", "dev")); // PG_VERSION is the image's own, not a choice
        assertThat(seen.volumes()).containsExactly(volume + ":/var/lib/data");
        assertThat(seen.composeFolder()).isEqualTo("/work/shop");
        assertThat(seen.devdesk()).isFalse();
    }

    @Test
    void stoppingRemovesTheImagesHiddenVolumeButKeepsANamedOne() throws Exception {
        // redis:7-alpine declares VOLUME /data, so Docker makes an anonymous volume on every start, like Postgres.
        String named = "devdesk-test-" + tag + "-keep";
        madeVolumes.add(named);
        var p = project("a");
        made.add(DockerContainerRuntime.containerName(p.name(), "cache"));
        var spec = new ContainerLaunchSpec(
                p.name(),
                p.key(),
                "cache",
                new Workload.Container("redis:7-alpine", List.of(), List.of(named + ":/keep"), Optional.empty()),
                Map.of());
        var c = runtime.start(spec, lines::add);
        String anonymous = docker.inspectContainerCmd(c.id()).exec().getMounts().stream()
                .filter(m -> "/data".equals(m.getDestination().getPath()))
                .findFirst()
                .orElseThrow()
                .getName();

        runtime.stop(c, Duration.ofSeconds(5));

        var left = docker.listVolumesCmd().exec().getVolumes().stream()
                .map(v -> v.getName())
                .toList();
        assertThat(left).contains(named).doesNotContain(anonymous);
    }

    @Test
    void aProjectsContainersReachEachOtherByServiceNameAndTheNetworkGoesWithTheLast() throws Exception {
        var p = project("a");
        var db = start(p, "db", "sleep 60");
        var client = start(p, "client", "sh -c 'ping -c 1 -W 3 db && echo REACHED-DB'");

        assertThat(runtime.onExit(client).get(30, TimeUnit.SECONDS).code()).isZero();
        assertThat(lines).anyMatch(l -> l.text().contains("REACHED-DB"));
        String network = ProjectNetwork.name(p.key());
        assertThat(docker.inspectNetworkCmd().withNetworkId(network).exec().getLabels())
                .containsEntry(DockerContainerRuntime.PROJECT_KEY_LABEL, p.key());

        runtime.stop(client, Duration.ofSeconds(1));
        assertThat(networkExists(network)).as("db still uses it").isTrue();
        runtime.stop(db, Duration.ofSeconds(1));
        assertThat(networkExists(network)).isFalse();
    }

    @Test
    void aNetworkOfTheSameNameThatDevDeskDidntCreateIsNeverUsed() {
        var p = project("a");
        String network = ProjectNetwork.name(p.key());
        docker.createNetworkCmd().withName(network).exec(); // someone else's, no labels
        try {
            var e = catchThrowableOfType(LaunchException.class, () -> start(p, "db", "sleep 60"));

            assertThat(e).hasMessageContaining("wasn't created by DevDesk");
            assertThat(networkExists(network)).as("left alone").isTrue();
        } finally {
            docker.removeNetworkCmd(network).exec();
        }
    }

    @Test
    void afterACrashTheLeftoverCleanupAlsoRemovesTheNetwork() throws Exception {
        var p = project("a");
        start(p, "db", "sleep 60");
        // A new runtime knows nothing of the first one's containers: like the next DevDesk after a SIGKILL.
        var next = new DockerContainerRuntime(docker);

        next.leftovers().find(p).forEach(l -> next.leftovers().remove(l));

        assertThat(networkExists(ProjectNetwork.name(p.key()))).isFalse();
    }

    private boolean networkExists(String name) {
        try {
            docker.inspectNetworkCmd().withNetworkId(name).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return false;
        }
    }

    @Test
    void whenAQuickCrashIsReportedItsLastOutputIsAlreadyThere() throws Exception {
        // Like MySQL without a password setting: one error line, then exit 1, within a second of starting.
        for (int i = 0; i < 5; i++) {
            lines.clear();
            var p = project("q" + i);
            var c = start(p, "db", "sh -c 'echo You need to specify MYSQL_ROOT_PASSWORD >&2; exit 1'");

            assertThat(runtime.onExit(c).get(30, TimeUnit.SECONDS).code()).isEqualTo(1);
            assertThat(lines)
                    .as("output known when the exit is")
                    .anyMatch(l -> l.text().contains("MYSQL_ROOT_PASSWORD"));
            runtime.stop(c, Duration.ofSeconds(1));
        }
    }

    @Test
    void imagesOnThisMachineAndWhatTheyPointTo() {
        assertThat(runtime.localImages()).contains(IMAGE);
        var ids = runtime.imageIds(List.of(IMAGE, "devdesk-test/not-pulled:" + tag));
        assertThat(ids).containsOnlyKeys(IMAGE);
        assertThat(ids.get(IMAGE)).startsWith("sha256:");
    }

    @Test
    void dockerErrorBodiesAreShortenedToTheirMessage() {
        assertThat(DockerContainerRuntime.clean(new DockerException(
                        "Status 409: {\"message\":\"Conflict. The name is already in use\"}\n", 409)))
                .isEqualTo("Conflict. The name is already in use");
        assertThat(DockerContainerRuntime.clean(new DockerException("{\"message\":\"cut", 500)))
                .isEqualTo("cut");
        assertThat(DockerContainerRuntime.clean(new DockerException("plain text  ", 500)))
                .isEqualTo("Status 500: plain text"); // no JSON body: the whole message, trimmed
    }
}
