package io.github.louch.devdesk.linux.resources;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.resources.ResourceSampler.Usage;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProcResourcesTest {

    private final ProcResources resources = new ProcResources();
    private final long me = ProcessHandle.current().pid();

    @Test
    void ownMemoryAndCpuAreVisible() {
        Usage usage = resources.sample(Set.of(me));

        assertThat(usage.memoryBytes()).isGreaterThan(10L * 1024 * 1024); // a JVM uses well over 10 MB
        assertThat(usage.cpuSeconds()).isPositive();
    }

    @Test
    void busyLoopShowsUpAsCpuPercent() {
        Usage before = resources.sample(Set.of(me));
        long start = System.nanoTime();
        long sum = 0;
        while (System.nanoTime() - start < 300_000_000L) {
            sum += System.nanoTime() % 7; // keep one core busy for 0.3 s
        }
        Usage after = resources.sample(Set.of(me));

        assertThat(ResourceSample.cpuPercent(before.cpuSeconds(), after.cpuSeconds(), Duration.ofMillis(300)))
                .as("sum %d", sum)
                .isGreaterThan(30);
    }

    @Test
    void vanishedProcessesCountAsZero() {
        assertThat(resources.sample(Set.of(9_999_999L))).isEqualTo(new Usage(0, 0, 0));
    }

    @Test
    void openSocketsAreCounted() throws Exception {
        int before = resources.sample(Set.of(me)).sockets();
        try (var a = new java.net.ServerSocket(0);
                var b = new java.net.ServerSocket(0)) {
            assertThat(a.isBound() && b.isBound()).isTrue();
            assertThat(resources.sample(Set.of(me)).sockets())
                    .isGreaterThanOrEqualTo(before + 2); // ≥: another thread of the test JVM may open one too
        }
    }
}
