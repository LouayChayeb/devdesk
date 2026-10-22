package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.container.RunningContainer;
import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Randomized: DevDesk starts, stops and cleans up its containers among "bystander" containers it didn't create:
 * unlabelled ones, one squatting on the exact name DevDesk would use, one labelled for another project with the same
 * name. Whatever the order, every bystander must still be running, unchanged, at the end.
 */
@Tag("docker")
class ContainerOwnershipTest {

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private DockerClient docker;
    private DockerContainerRuntime runtime;
    private final List<String> bystanders = new ArrayList<>();
    /** The project DevDesk runs in this test: whatever it left (a failed assertion midway) is removed after. */
    private ProjectSpec mine;

    @BeforeEach
    void connect() {
        var result = DockerConnection.connect(System.getenv());
        assumeTrue(result instanceof DockerConnection.Result.Connected, "Docker not available: " + result);
        runtime = ((DockerConnection.Result.Connected) result).runtime();
        docker = runtime.client();
    }

    @AfterEach
    void removeBystanders() throws Exception {
        if (docker == null) {
            return;
        }
        if (mine != null) {
            runtime.leftovers().find(mine).forEach(l -> runtime.leftovers().remove(l));
        }
        // The test's own bystanders, by the ids it got when creating them.
        for (String id : bystanders) {
            try {
                docker.removeContainerCmd(id).withForce(true).exec();
            } catch (RuntimeException e) {
                // already gone: the assertions below would have failed first
            }
        }
        docker.close();
    }

    private String bystander(String name, Map<String, String> labels) {
        var create =
                docker.createContainerCmd("alpine:3").withCmd("sleep", "300").withLabels(labels);
        if (name != null) {
            create.withName(name);
        }
        String id = create.exec().getId();
        docker.startContainerCmd(id).exec();
        bystanders.add(id);
        return id;
    }

    @Test
    void neverTouchesAContainerItDidNotStart() throws Exception {
        long seed = new Random().nextLong();
        Random random = new Random(seed);
        String why = "seed " + seed;
        String name = "own" + tag;
        // Two projects with the same name in different folders: different keys.
        mine = new ProjectSpec(name, Path.of("/tmp/" + tag + "/a"), Map.of(), Map.of());
        ProjectSpec other = new ProjectSpec(name, Path.of("/tmp/" + tag + "/b"), Map.of(), Map.of());

        List<Runnable> makers = new ArrayList<>(List.of(
                () -> bystander(null, Map.of()),
                () -> bystander(null, Map.of("devdesk.project", name)), // our name label, but no key: not ours
                () -> bystander("devdesk-" + name + "-squatted", Map.of()), // the name DevDesk would give "squatted"
                // Another project with the same name (other folder) runs "db": same container name DevDesk would use.
                () -> bystander(
                        "devdesk-" + name + "-db",
                        Map.of(
                                DockerContainerRuntime.PROJECT_LABEL,
                                name,
                                DockerContainerRuntime.PROJECT_KEY_LABEL,
                                other.key(),
                                DockerContainerRuntime.SERVICE_LABEL,
                                "db"))));
        Collections.shuffle(makers, random);
        makers.forEach(Runnable::run);

        Workload.Container alpine = new Workload.Container("alpine:3", List.of(), List.of(), Optional.of("sleep 300"));
        List<RunningContainer> ours = new ArrayList<>();
        for (int step = 0; step < 6; step++) {
            switch (random.nextInt(4)) {
                case 0, 1 ->
                    ours.add(runtime.start(
                            new ContainerLaunchSpec(name, mine.key(), "svc" + step, alpine, Map.of()), line -> {}));
                case 2 -> {
                    if (!ours.isEmpty()) {
                        runtime.stop(ours.remove(random.nextInt(ours.size())), Duration.ofSeconds(1));
                    }
                }
                default -> {
                    // The name is taken by someone else's container: DevDesk refuses instead of removing it.
                    String service = random.nextBoolean() ? "squatted" : "db";
                    var refused = catchThrowableOfType(
                            LaunchException.class,
                            () -> runtime.start(
                                    new ContainerLaunchSpec(name, mine.key(), service, alpine, Map.of()), line -> {}));
                    assertThat(refused).as(why).isNotNull();
                    assertThat(refused.getMessage())
                            .containsAnyOf("wasn't created by DevDesk", "another DevDesk project");
                }
            }
        }
        // A DevDesk starting again: its leftovers are exactly its own containers, then they're removed.
        List<Leftover> found = runtime.leftovers().find(mine);
        assertThat(found)
                .extracting(Leftover::id)
                .as(why)
                .containsExactlyInAnyOrderElementsOf(
                        ours.stream().map(RunningContainer::id).toList());
        found.forEach(l -> runtime.leftovers().remove(l));
        assertThat(runtime.leftovers().find(mine)).as(why).isEmpty();

        for (String id : bystanders) {
            var state = docker.inspectContainerCmd(id).exec().getState();
            assertThat(state.getRunning())
                    .as(why + ": bystander " + id + " was stopped")
                    .isTrue();
        }
    }
}
