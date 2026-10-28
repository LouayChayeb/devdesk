package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real builds with the docker command. They run on a builder of their own (a BuildKit container), removed with its
 * cache at the end, so the machine's default build cache is never touched.
 */
@Tag("docker")
class DockerBuildTest {

    private static final String BUILDER = "devdesk-test-" + TestJvm.ID;
    private static boolean builderReady;

    private final String project = "build-" + TestJvm.tag();
    private final Queue<OutputLine> lines = new ConcurrentLinkedQueue<>();
    private DockerClient docker;
    private DockerContainerRuntime runtime;

    @TempDir
    Path folder;

    @BeforeAll
    static void ownBuilder() throws Exception {
        // On the host's network: on WSL, Docker's own networks can't reach the machine's DNS server.
        builderReady = run(
                        "docker",
                        "buildx",
                        "create",
                        "--name",
                        BUILDER,
                        "--driver",
                        "docker-container",
                        "--driver-opt",
                        "network=host")
                == 0;
    }

    @AfterAll
    static void removeBuilder() throws Exception {
        if (builderReady) {
            run("docker", "buildx", "rm", "--force", BUILDER); // its container, and its cache with it
        }
    }

    @BeforeEach
    void connect() {
        var result = DockerConnection.connect(System.getenv());
        assumeTrue(result instanceof DockerConnection.Result.Connected, "Docker not available: " + result);
        assumeTrue(builderReady, "docker buildx isn't available");
        TestJvm.reapOrphans();
        docker = ((DockerConnection.Result.Connected) result).runtime().client();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("BUILDX_BUILDER", BUILDER);
        runtime = new DockerContainerRuntime(docker, env);
    }

    @AfterEach
    void removeWhatThisTestMade() throws Exception {
        if (runtime == null) {
            return;
        }
        for (String name : runtime.ownContainerNames(project)) {
            docker.removeContainerCmd(name)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        }
        for (var image : docker.listImagesCmd()
                .withLabelFilter(Map.of(DockerContainerRuntime.PROJECT_LABEL, project))
                .exec()) {
            docker.removeImageCmd(image.getId()).withForce(true).exec();
        }
        docker.close();
    }

    private ContainerLaunchSpec spec(String dockerfile) throws Exception {
        Files.writeString(folder.resolve("Dockerfile"), dockerfile);
        Files.writeString(folder.resolve("hello.txt"), "built by devdesk\n");
        String image = project + "-app";
        return new ContainerLaunchSpec(
                project,
                "app",
                new Workload.Container(
                        image,
                        List.of(),
                        List.of(),
                        Optional.empty(),
                        Optional.of(new Workload.Build(folder, "Dockerfile"))),
                Map.of());
    }

    @Test
    void aBuildKitDockerfileIsBuiltLabelledAndRun() throws Exception {
        // The syntax line and the cache mount only work with BuildKit: Docker's plain build API rejects them.
        var spec = spec("""
                # syntax=docker/dockerfile:1
                FROM alpine:3
                RUN --mount=type=cache,target=/var/cache/apk echo cached
                COPY hello.txt /hello.txt
                CMD ["cat", "/hello.txt"]
                """);

        var c = runtime.start(spec, lines::add);

        assertThat(runtime.onExit(c).get(60, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(0));
        awaitLine("built by devdesk");
        assertThat(lines).anyMatch(l -> l.text().startsWith("[build] "));
        var labels = docker.inspectImageCmd(spec.container().image())
                .exec()
                .getConfig()
                .getLabels();
        assertThat(labels).containsEntry(DockerContainerRuntime.PROJECT_KEY_LABEL, project);
        runtime.stop(c, Duration.ofSeconds(1));
    }

    @Test
    void theSecondStartReusesTheImage() throws Exception {
        var spec = spec("FROM alpine:3\nCMD [\"true\"]\n");
        runtime.stop(runtime.start(spec, lines::add), Duration.ofSeconds(1));
        lines.clear();

        runtime.stop(runtime.start(spec, lines::add), Duration.ofSeconds(1));

        assertThat(lines).noneMatch(l -> l.text().startsWith("[build] "));
    }

    @Test
    void aFailedBuildSaysWhichStepAndWhy() throws Exception {
        var spec = spec("FROM alpine:3\nRUN echo about to fail && exit 3\n");

        var e = catchThrowableOfType(LaunchException.class, () -> runtime.start(spec, lines::add));

        assertThat(e.getMessage())
                .contains("building image " + spec.container().image() + " failed at step 2/2")
                .contains("A command in the Dockerfile failed");
        assertThat(catchThrowableOfType(
                        NotFoundException.class,
                        () -> docker.inspectImageCmd(spec.container().image()).exec()))
                .isNotNull();
    }

    private void awaitLine(String text) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (lines.stream().anyMatch(l -> l.text().equals(text))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(lines).anyMatch(l -> l.text().equals(text));
    }

    private static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        return p.waitFor();
    }
}
