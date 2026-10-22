package io.github.louch.devdesk.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Finds the Docker (or Podman) API socket and connects to it, or explains why it can't.
 *
 * <p>The API is plain HTTP, spoken over a Unix socket: a special file the Docker daemon listens on. Being able to
 * write to that socket is root-equivalent, which is why it's only open to the {@code docker} group.
 */
public final class DockerConnection {

    /** The outcome: a working client, or a reason in plain words. */
    public sealed interface Result {
        /** Docker answered. docker-java stays hidden inside this module: callers only see DevDesk's own type. */
        record Connected(DockerContainerRuntime runtime, String socket) implements Result {}

        /** No Docker to talk to; {@code reason} says what to do. */
        record Unavailable(String reason) implements Result {}
    }

    private DockerConnection() {}

    /** Tries $DOCKER_HOST, then Docker's socket, then rootless Podman's socket. */
    public static Result connect(Map<String, String> env) {
        String socket = socketPath(env);
        Path file = Path.of(socket.replaceFirst("^unix://", ""));
        if (!Files.exists(file)) {
            return new Result.Unavailable(
                    "Docker isn't running: no socket at " + file
                            + ". Start Docker (sudo systemctl start docker), or for Podman: systemctl --user enable --now podman.socket");
        }
        if (!Files.isWritable(file)) {
            return new Result.Unavailable(
                    "no permission to use Docker's socket " + file + ". Add yourself to the docker "
                            + "group (sudo usermod -aG docker $USER), then log out and back in");
        }
        DockerClient client = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(socket)
                        .build(),
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create(socket))
                        .connectionTimeout(Duration.ofSeconds(3))
                        // Log streams and "wait for exit" calls stay open for as long as a container runs.
                        .responseTimeout(Duration.ZERO)
                        .maxConnections(100)
                        .build());
        try {
            client.pingCmd().exec();
            return new Result.Connected(new DockerContainerRuntime(client), socket);
        } catch (RuntimeException e) {
            closeQuietly(client);
            return new Result.Unavailable("Docker's socket " + file + " exists but Docker didn't answer ("
                    + e.getMessage() + "). Is the Docker daemon starting or stuck? Try: docker info");
        }
    }

    /** Where Docker's (or Podman's) API socket is, as {@code unix:///path}; it may not exist. */
    public static String socketPath(Map<String, String> env) {
        String host = env.getOrDefault("DOCKER_HOST", "");
        if (host.startsWith("unix://")) {
            return host;
        }
        if (Files.exists(Path.of("/var/run/docker.sock"))) {
            return "unix:///var/run/docker.sock";
        }
        String runtime = env.getOrDefault("XDG_RUNTIME_DIR", "");
        if (!runtime.isBlank() && Files.exists(Path.of(runtime, "podman", "podman.sock"))) {
            return "unix://" + Path.of(runtime, "podman", "podman.sock");
        }
        return "unix:///var/run/docker.sock"; // reported as missing by connect()
    }

    private static void closeQuietly(DockerClient client) {
        try {
            client.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }
}
