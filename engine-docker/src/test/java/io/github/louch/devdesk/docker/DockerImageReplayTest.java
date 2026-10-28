package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import io.github.louch.devdesk.core.changes.ImageProof;
import io.github.louch.devdesk.core.changes.ImageReplay;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.RestartPolicy;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real replicas on the local Docker. Skipped without Docker. Builds two tiny images from alpine:3. */
@Tag("docker")
class DockerImageReplayTest {

    /** Answers HTTP on 8080 forever, with busybox nc. */
    private static final String SERVE =
            "while true; do printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nok' | nc -l -p 8080; done";

    private final String tag = "devdesk-test-" + UUID.randomUUID().toString().substring(0, 8);
    private DockerClient docker;
    private DockerImageReplay replay;
    private String bystander;

    @TempDir
    Path dir;

    @BeforeEach
    void connect() {
        var result = DockerConnection.connect(System.getenv());
        assumeTrue(result instanceof DockerConnection.Result.Connected, "Docker not available: " + result);
        TestJvm.reapOrphans(); // containers of test JVMs that were killed mid-test
        var runtime = ((DockerConnection.Result.Connected) result).runtime();
        docker = runtime.client();
        // A fresh machine (CI) may not have it yet: these tests create containers from it directly.
        try {
            docker.inspectImageCmd("alpine:3").exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            try {
                docker.pullImageCmd("alpine:3")
                        .exec(new com.github.dockerjava.api.command.PullImageResultCallback())
                        .awaitCompletion();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        replay = runtime.imageReplay(new SecureRandom());
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (docker == null) {
            return;
        }
        if (bystander != null) {
            docker.removeContainerCmd(bystander)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        }
        for (String t : List.of(tag + ":good", tag + ":bad")) {
            try {
                docker.removeImageCmd(t).withForce(true).exec();
            } catch (RuntimeException e) {
                // not built in this test
            }
        }
        docker.close();
    }

    private String build(String name, String cmd) throws Exception {
        Path ctx = Files.createDirectories(dir.resolve(name));
        Files.writeString(ctx.resolve("Dockerfile"), "FROM alpine:3\nCMD [\"sh\", \"-c\", \"" + cmd + "\"]\n");
        return docker.buildImageCmd(ctx.toFile())
                .withTags(Set.of(tag + ":" + name))
                .exec(new BuildImageResultCallback())
                .awaitImageId();
    }

    private static final HealthProbe TCP = (check, d, env) -> {
        var t = (HealthCheckSpec.Tcp) check;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(t.host(), t.port()), 500);
            return new ProbeResult.Passed("connected to " + t.port());
        } catch (Exception e) {
            return new ProbeResult.Failed("port " + t.port() + ": " + e.getMessage());
        }
    };

    private static ServiceSpec api(String image) {
        return new ServiceSpec(
                "api",
                new Workload.Container(
                        image,
                        List.of(new Workload.PortMapping("127.0.0.1", 18080, 8080)),
                        List.of("devdesk-test-data:/data"),
                        Optional.empty()),
                Path.of("/"),
                Map.of(),
                List.of(),
                List.of(18080),
                new HealthCheckSpec.Tcp("127.0.0.1", 18080, HealthCheckSpec.Timing.DEFAULT),
                RestartPolicy.NO,
                Duration.ofSeconds(1));
    }

    @Test
    void aReplicaGetsARandomPortItsOwnNetworkNoVolumesAndLeavesNothingBehind() throws Exception {
        // Someone else's container on the same Docker: the replay must never touch it.
        bystander = docker.createContainerCmd("alpine:3")
                .withCmd("sleep", "60")
                .withName(tag + "-bystander")
                .exec()
                .getId();
        var container = (Workload.Container) api("alpine:3").workload();
        var r = replay.start(
                "alpine:3",
                new Workload.Container(
                        "alpine:3", container.ports(), container.volumes(), Optional.of("sh -c \"" + SERVE + "\"")),
                Map.of());
        try {
            assertThat(r.hostPorts()).containsKey(8080);
            assertThat(r.hostPorts().get(8080)).isNotEqualTo(18080);
            var inspect = docker.inspectContainerCmd(r.id()).exec();
            assertThat(inspect.getMounts())
                    .allSatisfy(m -> assertThat(m.getName()).isNotEqualTo("devdesk-test-data"));
            assertThat(inspect.getHostConfig().getNetworkMode()).startsWith("devdesk-proof-");
            assertThat(inspect.getConfig().getLabels()).containsEntry("devdesk.proof", "true");
        } finally {
            replay.remove(r);
        }
        assertThat(replay.leftoverProofs()).noneMatch(n -> n.equals(r.name()));
        assertThat(docker.inspectContainerCmd(bystander).exec().getId()).isEqualTo(bystander);
    }

    @Test
    void provesThatTheImageChangeBrokeIt() throws Exception {
        String good = build("good", SERVE.replace("\"", "\\\""));
        build("bad", "echo 'config error: PORT missing' >&2; exit 1");

        var proof = new ImageProof(replay, TCP, Clock.systemUTC(), Duration.ofMillis(500));
        var result = proof.prove(api(tag + ":bad"), good, Map.of(), Duration.ofSeconds(20));

        assertThat(result.outcome())
                .as(result.summary() + " " + result.evidence())
                .isEqualTo(ImageProof.Outcome.PROVEN);
        assertThat(String.join(" ", result.evidence())).contains("config error");
        assertThat(replay.leftoverProofs()).isEmpty();
    }

    @Test
    void eachCopyGetsItsOwnEmptyDatabaseReachableByNameAndNothingIsLeft() throws Exception {
        // Both wait for "db:5432" first, as an app waits for its database; only the old one then serves.
        String wait = "until nc -z db 5432; do sleep 0.2; done; ";
        String good = build("good", (wait + SERVE).replace("\"", "\\\""));
        build("bad", wait + "echo 'migration failed: relation users does not exist' >&2; exit 1");
        var project = io.github.louch.devdesk.core.config.ConfigParser.parse(
                """
                version: 1
                services:
                  db:
                    container: { image: "postgres:18", ports: ["15432:5432"] }
                    environment: { POSTGRES_PASSWORD: test }
                    health: { type: tcp, port: 15432 }
                  api:
                    container: { image: "%s:bad", ports: ["18080:8080"] }
                    depends_on: { db: { condition: healthy } }
                    health: { type: tcp, port: 18080 }
                """.formatted(tag), dir, Map.of(), f -> Optional.empty());
        var connected = (DockerConnection.Result.Connected) DockerConnection.connect(System.getenv());
        var forProject = connected.runtime().imageReplay(new SecureRandom(), project.key());
        var proof = new ImageProof(forProject, TCP, Clock.systemUTC(), Duration.ofMillis(500));

        long started = System.nanoTime();
        var result = proof.prove(project, project.services().get("api"), good, Map.of(), Duration.ofSeconds(30));
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("prove-it with a database copy took " + millis + " ms");

        assertThat(result.outcome())
                .as(result.summary() + " " + result.evidence())
                .isEqualTo(ImageProof.Outcome.PROVEN);
        assertThat(result.kind()).isEqualTo(ImageProof.Kind.HEALTH_CHECK);
        assertThat(result.caveat()).contains("fresh, empty copies of db");
        assertThat(String.join(" ", result.evidence())).contains("migration failed");
        // The copies, their databases and networks are all gone; nothing with this project's key is left.
        assertThat(replay.leftoverProofs()).isEmpty();
        assertThat(docker.listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(DockerContainerRuntime.PROJECT_KEY_LABEL, project.key()))
                        .exec())
                .isEmpty();
    }

    @Test
    void aDatabaseImageIsMadeSureOfBeforeItsCopyStarts() throws Exception {
        // CI's fresh machine had no postgres:18, and both copies failed with "No such image".
        List<String> fetched = new java.util.concurrent.CopyOnWriteArrayList<>();
        var replay =
                new DockerImageReplay(docker, new SecureRandom(), Optional.of("shop-test"), () -> true, fetched::add);
        var db = new ImageReplay.Helper(
                "db",
                new Workload.Container("postgres:18", List.of(), List.of(), Optional.empty()),
                Map.of("POSTGRES_PASSWORD", "test"));
        var app = new Workload.Container("alpine:3", List.of(), List.of(), Optional.of("sleep 30"));

        var r = replay.start("alpine:3", app, Map.of(), List.of(db));
        replay.remove(r);

        assertThat(fetched).containsExactly("postgres:18");
        assertThat(replay.leftoverProofs()).isEmpty();
    }

    @Test
    void onlyReplicasWhoseDevDeskDiedAreRemoved() throws Exception {
        // A dead owner (a PID that doesn't exist), a live one (this test's own JVM), and no owner label at all.
        String dead = Long.MAX_VALUE / 2 + "@2020-01-01T00:00:00Z";
        String live = DockerImageReplay.owner(ProcessHandle.current());
        List<String> ids = new java.util.ArrayList<>();
        List<String> networks = new java.util.ArrayList<>();
        try {
            for (var labels : List.of(
                    Map.of(DockerImageReplay.PROOF_LABEL, "true", DockerImageReplay.OWNER_LABEL, dead),
                    Map.of(DockerImageReplay.PROOF_LABEL, "true", DockerImageReplay.OWNER_LABEL, live),
                    Map.of(DockerImageReplay.PROOF_LABEL, "true"),
                    // This JVM's PID with another start time: the PID was reused, so its DevDesk is dead.
                    Map.of(
                            DockerImageReplay.PROOF_LABEL,
                            "true",
                            DockerImageReplay.OWNER_LABEL,
                            ProcessHandle.current().pid() + "@2020-01-01T00:00:00Z"),
                    // Owners that prove nothing: no '@', or not a number before it.
                    Map.of(DockerImageReplay.PROOF_LABEL, "true", DockerImageReplay.OWNER_LABEL, "nonsense"),
                    Map.of(DockerImageReplay.PROOF_LABEL, "true", DockerImageReplay.OWNER_LABEL, "abc@x"))) {
                ids.add(docker.createContainerCmd("alpine:3")
                        .withCmd("sleep", "60")
                        .withName(tag + "-" + ids.size())
                        .withLabels(labels)
                        .exec()
                        .getId());
            }

            String deadNetwork = docker.createNetworkCmd()
                    .withName(tag + "-dead-net")
                    .withLabels(Map.of(DockerImageReplay.PROOF_LABEL, "true", DockerImageReplay.OWNER_LABEL, dead))
                    .exec()
                    .getId();
            networks.add(deadNetwork);
            networks.add(docker.createNetworkCmd()
                    .withName(tag + "-live-net")
                    .withLabels(Map.of(DockerImageReplay.PROOF_LABEL, "true", DockerImageReplay.OWNER_LABEL, live))
                    .exec()
                    .getId());

            List<String> removed = replay.removeAbandoned();

            assertThat(removed)
                    .contains(tag + "-0", tag + "-3", "network " + tag + "-dead-net")
                    .doesNotContain(tag + "-1", tag + "-2", tag + "-4", tag + "-5", "network " + tag + "-live-net");
            assertThat(ids).extracting(this::exists).containsExactly(false, true, true, false, true, true);
            assertThat(replay.leftoverProofs())
                    .contains(tag + "-1", tag + "-live-net")
                    .doesNotContain(tag + "-0", tag + "-dead-net");
        } finally {
            for (String id : networks) {
                try {
                    docker.removeNetworkCmd(id).exec(); // this test's own networks
                } catch (RuntimeException e) {
                    // already removed by the cleanup under test
                }
            }
            for (String id : ids) {
                try {
                    docker.removeContainerCmd(id)
                            .withForce(true)
                            .withRemoveVolumes(true)
                            .exec(); // this test's own containers
                } catch (RuntimeException e) {
                    // already removed by the cleanup under test
                }
            }
        }
    }

    private boolean exists(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return false;
        }
    }
}
