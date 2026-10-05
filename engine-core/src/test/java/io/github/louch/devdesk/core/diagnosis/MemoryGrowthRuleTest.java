package io.github.louch.devdesk.core.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntToLongFunction;
import org.junit.jupiter.api.Test;

class MemoryGrowthRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-04T15:00:00Z");

    private static final long MB = 1024 * 1024;
    private static final Instant T0 = Instant.parse("2026-10-04T14:00:00Z");

    /** One sample every 10 s for {@code minutes}, memory given per sample index. */
    private static List<ResourceSample> samples(int minutes, IntToLongFunction memory) {
        List<ResourceSample> list = new ArrayList<>();
        for (int i = 0; i <= minutes * 6; i++) {
            list.add(new ResourceSample(T0.plusSeconds(i * 10L), 5, memory.applyAsLong(i), 1, 3));
        }
        return list;
    }

    private static List<Finding> evaluate(List<ResourceSample> samples) throws Exception {
        var project = ConfigParser.parse(
                "version: 1\nservices:\n  api: { command: node server.js }\n",
                Path.of("/p"),
                Map.of(),
                f -> Optional.empty());
        var history = new ServiceHistory(List.of(), List.of(), List.of(), samples, true, Set.of(1L), false);
        var ctx = new DiagnosisContext(
                project,
                Map.of("api", new ServiceState.Healthy(1, 0)),
                Map.of("api", history),
                Map.of(),
                new FakeIntrospection(),
                NOW);
        return new MemoryGrowthRule().evaluate(ctx, "api");
    }

    @Test
    void steadyClimbIsReportedWithRateAndProjection() throws Exception {
        // 300 MB rising by 1 MB every 10 s: +6 MB/min for 40 minutes, with a little noise.
        var findings = evaluate(samples(40, i -> 300 * MB + i * MB + (i % 3) * MB / 2));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("api's memory keeps growing");
            assertThat(f.explanation())
                    .contains("grew from 312 MB to 529 MB") // averages of the first and last tenth of the samples
                    .contains("in 40 minutes")
                    .contains("6 MB per minute");
            assertThat(f.severity()).isEqualTo(Finding.Severity.WARNING);
            assertThat(f.evidence().getFirst()).contains("r² = 1.00");
        });
    }

    @Test
    void flatMemoryIsFine() throws Exception {
        assertThat(evaluate(samples(40, i -> 500 * MB + (i % 5) * MB))).isEmpty();
    }

    @Test
    void oneSpikeIsNotALeak() throws Exception {
        // Flat at 300 MB, one burst to 1.5 GB near the end, then back down.
        assertThat(evaluate(samples(40, i -> i >= 200 && i < 210 ? 1500 * MB : 300 * MB)))
                .isEmpty();
    }

    @Test
    void tooLittleHistoryIsNotATrend() throws Exception {
        assertThat(evaluate(samples(5, i -> 100 * MB + i * 20 * MB))).isEmpty();
    }

    @Test
    void smallGrowthIsIgnoredEvenIfSteady() throws Exception {
        // +40 MB over 40 minutes: steady, but too small to matter.
        assertThat(evaluate(samples(40, i -> 300 * MB + i * MB / 6))).isEmpty();
    }
}
