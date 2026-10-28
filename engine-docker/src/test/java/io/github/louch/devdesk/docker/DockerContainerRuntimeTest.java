package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.project.Workload;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real containers on the local Docker. Skipped when there's no Docker. Uses the tiny alpine image (~4 MB). */
@Tag("docker")
class DockerContainerRuntimeTest {

    private static final String IMAGE = "alpine:3";

    private final String project = "test-" + TestJvm.tag();
    private final Queue<OutputLine> lines = new ConcurrentLinkedQueue<>();
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
    void removeOurContainers() throws Exception {
        if (runtime != null) {
            // Only this test's own containers (by label), never anything else on the machine.
            for (String name : runtime.ownContainerNames(project)) {
                docker.removeContainerCmd(name)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
            }
            docker.close();
        }
    }

    private ContainerLaunchSpec spec(String service, String command, List<Workload.PortMapping> ports) {
        return new ContainerLaunchSpec(
                project,
                service,
                new Workload.Container(IMAGE, ports, List.of(), Optional.of(command)),
                Map.of("GREETING", "hi"));
    }

    @Test
    void outputAndExitCodeComeBackAndTheContainerIsLabelled() throws Exception {
        var c = runtime.start(spec("job", "sh -c 'echo $GREETING; echo oops >&2; exit 3'", List.of()), lines::add);

        assertThat(runtime.onExit(c).get(30, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(3));
        awaitTrue(() -> lines.contains(new OutputLine(StreamKind.STDOUT, "hi"))
                && lines.contains(new OutputLine(StreamKind.STDERR, "oops")));
        assertThat(c.name()).isEqualTo("devdesk-" + project + "-job");
        assertThat(runtime.ownContainerNames(project)).containsExactly(c.name());
    }

    @Test
    void environmentReachesTheContainerAndLogsAreSplitIntoLines() throws Exception {
        var spec = new ContainerLaunchSpec(
                project,
                "env",
                new Workload.Container(IMAGE, List.of(), List.of(), Optional.of("printenv GREETING")),
                Map.of("GREETING", "héllo wörld"));
        var c = runtime.start(spec, lines::add);
        runtime.onExit(c).get(30, TimeUnit.SECONDS);

        awaitTrue(() -> lines.contains(new OutputLine(StreamKind.STDOUT, "héllo wörld")));
    }

    @Test
    void stopRemovesTheContainerAndPid1IgnoringSigtermIsForceKilled() throws Exception {
        // `sleep` as PID 1 in a container ignores SIGTERM: the kernel gives PID 1 no default signal handlers.
        var c = runtime.start(spec("sleeper", "sleep 1000", List.of()), lines::add);

        StopResult result = runtime.stop(c, Duration.ofSeconds(1));

        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(137), true));
        assertThat(runtime.ownContainerNames(project)).isEmpty();
    }

    @Test
    void publishedPortIsReachableOnLocalhost() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var c = runtime.start(
                spec("web", "nc -lk -p 80 -e echo ok", List.of(new Workload.PortMapping("127.0.0.1", port, 80))),
                lines::add);
        int finalPort = port;
        awaitTrue(() -> {
            try (Socket s = new Socket("127.0.0.1", finalPort)) {
                return s.getInputStream().read() == 'o'; // the container answered "ok"
            } catch (Exception e) {
                return false;
            }
        });
        runtime.stop(c, Duration.ofSeconds(1));
    }

    @Test
    void aHealthCheckReachesTheContainerItselfNotDockersPortForwarder() throws Exception {
        // Docker's forwarder accepts on the published port even when nothing listens inside (found on paperless).
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var c = runtime.start(
                spec(
                        "web",
                        "sh -c 'sleep 4; nc -lk -p 80 -e echo ok'",
                        List.of(new Workload.PortMapping("127.0.0.1", port, 80))),
                lines::add);

        var inside = runtime.inside(c, port);
        assumeTrue(inside.isPresent(), "container addresses aren't reachable here (Docker Desktop or rootless)");
        assertThat(inside.get().getPort()).isEqualTo(80);
        assertThat(connects(inside.get())).isFalse(); // nothing listens yet: the honest answer
        awaitTrue(() -> connects(inside.get()));
        assertThat(runtime.inside(c, port + 1)).isEmpty(); // not one of its published ports
        runtime.stop(c, Duration.ofSeconds(1));
    }

    private static boolean connects(java.net.InetSocketAddress address) {
        try (Socket s = new Socket()) {
            s.connect(address, 500);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Test
    void theEntrypointReplacesTheImagesAndTheCommandFollowsIt() throws Exception {
        // compose's entrypoint: + command: (Chatwoot's rails.sh runs before "rails s").
        var c = runtime.start(
                new ContainerLaunchSpec(
                        project,
                        "ep",
                        new Workload.Container(
                                IMAGE,
                                List.of(),
                                List.of(),
                                Optional.of("second"),
                                Optional.empty(),
                                Optional.of("echo first")),
                        Map.of()),
                lines::add);

        assertThat(runtime.onExit(c).get(30, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(0));
        awaitTrue(() -> lines.contains(new OutputLine(StreamKind.STDOUT, "first second")));
    }

    @Test
    void aUdpPortIsPublishedAsUdp() throws Exception {
        int port;
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) {
            port = s.getLocalPort();
        }
        var c = runtime.start(
                spec("udp", "sleep 30", List.of(new Workload.PortMapping("127.0.0.1", port, 6831, true))), lines::add);

        var bindings = docker.inspectContainerCmd(c.id())
                .exec()
                .getHostConfig()
                .getPortBindings()
                .getBindings();
        assertThat(bindings).containsKey(com.github.dockerjava.api.model.ExposedPort.udp(6831));
        assertThat(bindings.get(com.github.dockerjava.api.model.ExposedPort.udp(6831))[0].getHostPortSpec())
                .isEqualTo(String.valueOf(port));
        runtime.stop(c, Duration.ofSeconds(1));
    }

    @Test
    void ourLeftoverIsReplacedButSomeoneElsesContainerIsNeverTouched() throws Exception {
        var first = runtime.start(spec("api", "sleep 1000", List.of()), lines::add);
        // DevDesk "crashed" without stopping it: the next start replaces its own leftover.
        var second = runtime.start(spec("api", "sleep 1000", List.of()), lines::add);
        assertThat(second.id()).isNotEqualTo(first.id());

        String foreign = "devdesk-" + project + "-db";
        docker.createContainerCmd(IMAGE)
                .withName(foreign)
                .withCmd("sleep", "1000")
                .exec(); // no DevDesk labels
        try {
            var refused = catchThrowableOfType(
                    LaunchException.class, () -> runtime.start(spec("db", "sleep 1000", List.of()), lines::add));
            assertThat(refused.getMessage())
                    .contains("wasn't created by DevDesk")
                    .contains("docker rm -f " + foreign);
            assertThat(docker.inspectContainerCmd(foreign).exec().getName()).isEqualTo("/" + foreign);
        } finally {
            docker.removeContainerCmd(foreign)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        }
    }

    @Test
    void missingImageIsExplained() {
        var spec = new ContainerLaunchSpec(
                project,
                "x",
                new Workload.Container("devdesk-test/does-not-exist:1", List.of(), List.of(), Optional.empty()),
                Map.of());

        var e = catchThrowableOfType(LaunchException.class, () -> runtime.start(spec, lines::add));

        assertThat(e.getMessage())
                .startsWith("could not pull image devdesk-test/does-not-exist:1")
                .contains("Check the name and tag");
    }

    @Test
    void imageWithoutHealthcheckSaysWhatToUseInstead() throws Exception {
        var c = runtime.start(spec("nohealth", "sleep 1000", List.of()), lines::add);

        assertThat(runtime.health(c).detail()).contains("has no HEALTHCHECK").contains("type: tcp");
        runtime.stop(c, Duration.ZERO);
    }

    @Test
    void imageDefaultCommandRunsWhenNoCommandIsGiven() throws Exception {
        // alpine's default command is /bin/sh, which exits at once with 0 when it has no terminal.
        var spec = new ContainerLaunchSpec(
                project, "plain", new Workload.Container(IMAGE, List.of(), List.of(), Optional.empty()), Map.of());

        var c = runtime.start(spec, lines::add);

        assertThat(runtime.onExit(c).get(30, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(0));
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
