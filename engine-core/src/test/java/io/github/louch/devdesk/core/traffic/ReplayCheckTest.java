package io.github.louch.devdesk.core.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayCheckTest {

    private static Exchange recorded(int status, String body) {
        return new Exchange(1, Instant.EPOCH, "web", "GET", "/orders", Map.of(), "", status, Map.of(), body, 5);
    }

    @Test
    void theSameAnswerIsTheSameEvenWithNewIdsAndTimes() {
        var r = recorded(
                200, "{\"id\": 1, \"createdAt\": \"2026-01-01\", \"total\": 12, \"items\": [{\"sku\": \"a\"}]}");
        var check = ReplayCheck.compare(
                r, 200, "{\"id\": 2, \"createdAt\": \"2026-10-16\", \"total\": 12, \"items\": [{\"sku\": \"a\"}]}");
        assertThat(check.same()).isTrue();
    }

    @Test
    void differencesHaveAName() {
        var r = recorded(200, "{\"total\": 12, \"items\": [{\"price\": 3}, {\"price\": 4}], \"coupon\": null}");
        var check = ReplayCheck.compare(r, 500, "{\"total\": 14, \"items\": [{\"price\": 5}], \"error\": \"x\"}");
        assertThat(check.same()).isFalse();
        assertThat(check.differences())
                .contains(
                        "status 200 → 500",
                        "$.total: 12 → 14",
                        "$.items: 2 → 1 items",
                        "$.items[0].price: 3 → 5",
                        "$.coupon: gone",
                        "$.error: new");
        assertThat(ReplayCheck.compare(recorded(200, "plain"), 200, "other").differences())
                .containsExactly("body differs (5 → 5 characters)");
    }
}
