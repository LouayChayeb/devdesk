package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each way of not reaching Docker gets its own explanation and next step; no Docker needed for these. */
class DockerConnectionTest {

    @TempDir
    Path dir;

    private static String reason(DockerConnection.Result r) {
        return ((DockerConnection.Result.Unavailable) r).reason();
    }

    @Test
    void noSocketSaysHowToStartDockerOrPodman() {
        var r = DockerConnection.connect(Map.of("DOCKER_HOST", "unix://" + dir.resolve("docker.sock")));

        assertThat(reason(r))
                .startsWith("Docker isn't running: no socket at " + dir.resolve("docker.sock"))
                .contains("sudo systemctl start docker")
                .contains("podman.socket");
    }

    @Test
    void aSocketWeCantWriteToSaysWhichGroupToJoin() throws Exception {
        Path socket = Files.createFile(dir.resolve("docker.sock"));
        Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("r--r--r--"));

        var r = DockerConnection.connect(Map.of("DOCKER_HOST", "unix://" + socket));

        assertThat(reason(r))
                .isEqualTo("no permission to use Docker's socket " + socket + ". Add yourself to the docker "
                        + "group (sudo usermod -aG docker $USER), then log out and back in");
    }

    @Test
    void aSocketNobodyAnswersOnSaysDockerMayBeStuck() throws Exception {
        Path socket = dir.resolve("docker.sock");
        // A real Unix socket that accepts the connection but never speaks HTTP: like a Docker that is still starting.
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            long before = System.nanoTime();
            var r = DockerConnection.connect(Map.of("DOCKER_HOST", "unix://" + socket));

            // Found by this test: without a time limit on the first ping, connect() waited forever.
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - before))
                    .isLessThan(DockerConnection.PING_TIMEOUT.plusSeconds(5));
            assertThat(reason(r))
                    .startsWith("Docker's socket " + socket + " exists but Docker didn't answer (")
                    .endsWith("). Is the Docker daemon starting or stuck? Try: docker info");
        }
    }

    @Test
    void theSocketIsDockerHostThenDockersThenRootlessPodmans() throws Exception {
        assertThat(DockerConnection.socketPath(Map.of("DOCKER_HOST", "unix:///custom.sock")))
                .isEqualTo("unix:///custom.sock");
        // tcp:// isn't supported (it would expose the API on the network): the local socket is used instead.
        boolean docker = Files.exists(Path.of("/var/run/docker.sock"));
        Files.createDirectories(dir.resolve("podman"));
        Files.createFile(dir.resolve("podman/podman.sock"));
        assertThat(DockerConnection.socketPath(
                        Map.of("DOCKER_HOST", "tcp://10.0.0.1:2375", "XDG_RUNTIME_DIR", dir.toString())))
                .isEqualTo(docker ? "unix:///var/run/docker.sock" : "unix://" + dir.resolve("podman/podman.sock"));
        if (!docker) {
            assertThat(DockerConnection.socketPath(Map.of())).isEqualTo("unix:///var/run/docker.sock");
        }
    }
}
