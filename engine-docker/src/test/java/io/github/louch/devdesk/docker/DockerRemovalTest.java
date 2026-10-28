package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.removal.ProjectRemoval;
import io.github.louch.devdesk.core.removal.Removable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Remove on the real Docker: what DevDesk made for the project goes, and nothing of anyone else's. */
@Tag("docker")
class DockerRemovalTest {

    private final String tag = TestJvm.tag();
    private final String project = "rm" + tag;
    private final String keepVolume = "keep-" + tag;
    private final String keepImage = "keep-" + tag + ":mine";
    private DockerClient docker;
    private DockerContainerRuntime runtime;

    @TempDir
    Path folder;

    @BeforeEach
    void connect() {
        var result = DockerConnection.connect(System.getenv());
        assumeTrue(result instanceof DockerConnection.Result.Connected, "Docker not available: " + result);
        TestJvm.reapOrphans();
        docker = ((DockerConnection.Result.Connected) result).runtime().client();
        runtime = new DockerContainerRuntime(docker, System.getenv(), Optional.of(folder.resolve("pulls.txt")));
    }

    @AfterEach
    void removeWhatThisTestMade() throws Exception {
        if (docker == null) {
            return;
        }
        for (String name : runtime.ownContainerNames(project)) {
            docker.removeContainerCmd(name)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        }
        quietly(() -> docker.removeVolumeCmd(keepVolume).exec());
        quietly(() -> docker.removeImageCmd(keepImage).exec());
        quietly(() -> docker.removeVolumeCmd(project + "_data").exec());
        docker.close();
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // already gone
        }
    }

    @Test
    void everythingTheProjectMadeGoesAndTheUsersThingsStay() throws Exception {
        // The user's own volume and image, used by the project but not made by it (no labels).
        docker.createVolumeCmd().withName(keepVolume).exec();
        docker.tagImageCmd("alpine:3", "keep-" + tag, "mine").exec();
        Files.writeString(folder.resolve("Dockerfile"), "FROM alpine:3\nCMD [\"sleep\", \"60\"]\n");
        Files.writeString(
                folder.resolve("devdesk.yaml"),
                "version: 1\nname: " + project + "\nservices:\n  app: { command: x }\n");
        ProjectSpec spec = ConfigParser.parse(
                Files.readString(folder.resolve("devdesk.yaml")), folder, Map.of(), f -> Optional.empty());
        var built = runtime.start(
                new ContainerLaunchSpec(
                        project,
                        spec.key(),
                        "app",
                        new Workload.Container(
                                project + "-app",
                                List.of(),
                                List.of(project + "_data:/data", keepVolume + ":/keep"),
                                Optional.empty(),
                                Optional.of(new Workload.Build(folder, "Dockerfile"))),
                        Map.of()),
                l -> {});
        var mine = runtime.start(
                new ContainerLaunchSpec(
                        project,
                        spec.key(),
                        "tool",
                        new Workload.Container(keepImage, List.of(), List.of(), Optional.of("sleep 60")),
                        Map.of()),
                l -> {});
        runtime.stop(built, Duration.ofSeconds(1));
        runtime.stop(mine, Duration.ofSeconds(1));
        var removal = new ProjectRemoval(List.of(runtime.removal()));

        var plan = removal.plan(spec, false);
        var report = removal.remove(spec, false, plan);

        // Its network went with its last container already; its volume and built image are DevDesk's to delete.
        assertThat(plan.items())
                .extracting(Removable::kind)
                .containsExactly(Removable.Kind.VOLUME, Removable.Kind.IMAGE);
        assertThat(plan.items())
                .extracting(Removable::what)
                .noneMatch(w -> w.contains(keepVolume) || w.contains(keepImage));
        assertThat(plan.losesData()).isTrue(); // its new volume holds data
        assertThat(report.failed()).isEmpty();
        assertThat(report.leftovers()).isEmpty();
        // Checked from the outside too.
        assertThat(docker.listNetworksCmd()
                        .withNameFilter("devdesk-" + spec.key())
                        .exec())
                .isEmpty();
        assertThat(exists(() -> docker.inspectVolumeCmd(project + "_data").exec()))
                .isFalse();
        assertThat(exists(() -> docker.inspectImageCmd(project + "-app").exec()))
                .isFalse();
        assertThat(exists(() -> docker.inspectVolumeCmd(keepVolume).exec())).isTrue();
        assertThat(exists(() -> docker.inspectImageCmd(keepImage).exec())).isTrue();
    }

    @Test
    void filesAContainerWroteAsRootInsideAFolderAreDeletedByTheHelper() throws Exception {
        // What Mastodon's postgres did inside its clone: a folder owned by the database's user.
        java.nio.file.Path clone = folder.resolve("clone");
        java.nio.file.Files.createDirectories(clone.resolve("postgres14"));
        java.nio.file.Path outside = folder.resolve("outside.txt");
        java.nio.file.Files.writeString(outside, "stays");
        var writer = new ProcessBuilder(
                        "docker",
                        "run",
                        "--rm",
                        "--label",
                        TestJvm.LABEL + "=" + TestJvm.ID,
                        "-v",
                        clone.resolve("postgres14") + ":/data",
                        "alpine:3",
                        "sh",
                        "-c",
                        "mkdir -p /data/base && echo 14 > /data/base/PG_VERSION && chmod 700 /data/base")
                .redirectErrorStream(true)
                .start();
        assertThat(writer.waitFor()).isZero();
        assertThat(clone.resolve("postgres14/base").toFile().canWrite()).isFalse(); // not deletable by this user

        var left = runtime.deleteInside(clone);

        assertThat(left).isEmpty();
        try (var files = java.nio.file.Files.list(clone)) {
            assertThat(files).isEmpty();
        }
        assertThat(outside).hasContent("stays"); // only the folder it was given
    }

    private static boolean exists(Runnable inspect) {
        try {
            inspect.run();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }
}
