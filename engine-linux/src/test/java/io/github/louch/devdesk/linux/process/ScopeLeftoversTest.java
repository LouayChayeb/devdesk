package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopeLeftoversTest {

    @Test
    void processLeftInItsScopeIsFoundByProjectKeyAndRemoved() throws Exception {
        var runtime = LinuxProcessRuntime.detect(Clock.systemUTC(), new Random());
        assumeTrue(runtime.usesSystemdScope(), "no systemd user session");
        // A project with a unique folder, so its key can't match anything real on this machine.
        var project = new ProjectSpec(
                "leftover-test", Path.of("/tmp", UUID.randomUUID().toString()), Map.of(), Map.of());
        var p = runtime.start(
                new ProcessLaunchSpec(
                        project.key(), "api", "sleep 1000 & sleep 1000 & wait", Path.of("/tmp"), System.getenv()),
                line -> {});
        Thread.sleep(300);

        // A fresh finder, like a new DevDesk after the old one was killed: it knows nothing but the project.
        var finder = new ScopeLeftovers();
        var found = finder.find(project);

        assertThat(found).singleElement().satisfies(l -> {
            assertThat(l.service()).isEqualTo("api");
            assertThat(l.kind()).isEqualTo(Leftover.Kind.PROCESS);
            assertThat(l.id()).startsWith("devdesk-" + project.key() + "--api-");
            assertThat(l.pids()).contains(p.pid());
            assertThat(l.description()).isEqualTo("sleep 1000");
            assertThat(l.since()).isPresent();
        });
        assertThat(finder.remove(found.getFirst())).isEqualTo("stopped");
        assertThat(finder.find(project)).isEmpty();
    }

    @Test
    void otherProjectsLeftoversAreNotClaimed() {
        var project = new ProjectSpec(
                "leftover-test", Path.of("/tmp", UUID.randomUUID().toString()), Map.of(), Map.of());

        assertThat(new ScopeLeftovers().find(project)).isEmpty();
    }
}
