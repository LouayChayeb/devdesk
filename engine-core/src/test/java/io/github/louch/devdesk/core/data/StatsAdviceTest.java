package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatsAdviceTest {

    private static DatabaseStats.TableStats table(
            String name, long rows, long seq, long idx, long dead, long total, long index) {
        return new DatabaseStats.TableStats(
                "public", name, rows, total, index, seq, idx, dead, Optional.empty(), Optional.empty());
    }

    @Test
    void aBigTableReadWholeOverAndOverPointsAtAMissingIndex() {
        var hints =
                StatsAdvice.of(new DatabaseStats(0, List.of(table("orders", 50_000, 2_000, 12, 0, 0, 0)), List.of()));

        assertThat(hints).singleElement().satisfies(h -> {
            assertThat(h.table()).isEqualTo("public.orders");
            assertThat(h.text()).contains("read from start to end 2000 times").contains("only 12 times");
        });
    }

    @Test
    void manyDeadRowsSuggestVacuum() {
        var hints = StatsAdvice.of(new DatabaseStats(0, List.of(table("jobs", 4_000, 0, 0, 3_000, 0, 0)), List.of()));

        assertThat(hints).singleElement().satisfies(h -> assertThat(h.text()).contains("VACUUM public.jobs"));
    }

    @Test
    void smallOrHealthyTablesGetNoHint() {
        var hints = StatsAdvice.of(new DatabaseStats(
                0,
                List.of(
                        table("users", 40, 9_000, 0, 0, 0, 0), // small: scanning is fine
                        table("orders", 50_000, 600, 400, 10, 0, 0)),
                List.of()));

        assertThat(hints).isEmpty();
    }
}
