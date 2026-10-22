package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanAdviceTest {

    private static QueryPlan.Node scan(String type, long planRows, long actual, long removed) {
        return new QueryPlan.Node(
                type,
                Optional.of("orders"),
                List.of("Filter: (status = 'failed')"),
                100,
                planRows,
                Optional.of(actual),
                Optional.of(removed),
                Optional.of(12.0),
                false,
                List.of());
    }

    private static List<String> hints(QueryPlan.Node root) {
        return PlanAdvice.hints(new QueryPlan(root, Optional.empty(), Optional.empty(), ""));
    }

    @Test
    void aFullScanThatThrowsAwayMostRowsAsksForAnIndex() {
        assertThat(hints(scan("Seq Scan", 300, 300, 29_700)))
                .singleElement()
                .asString()
                .contains("orders is read row by row (Seq Scan)")
                .contains("29,700 of 30,000")
                .contains("(status = 'failed')");
        assertThat(hints(scan("Table scan", 300, 300, 29_700))).hasSize(1); // MySQL and SQLite say it this way
    }

    @Test
    void wrongEstimatesAskForAnalyze() {
        assertThat(hints(scan("Index Scan", 10, 50_000, 0)))
                .singleElement()
                .asString()
                .contains("expected 10 rows but got 50,000")
                .contains("ANALYZE orders;");
    }

    @Test
    void aSmallOrGoodPlanHasNoHints() {
        assertThat(hints(scan("Seq Scan", 30, 30, 200))).isEmpty();
    }
}
