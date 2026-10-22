package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecentRequestsTest {

    private static final Instant T = Instant.parse("2026-10-08T10:00:00Z");

    @Test
    void anErrorDuringOrJustAfterARequestPointsToIt() {
        var recent = new RecentRequests();
        var running = recent.start("api", "POST", "http://localhost:18080/orders", T);

        assertThat(recent.cause("api", "Traceback (most recent call last):", T.plusMillis(30)))
                .contains(running);
        recent.end(running, T.plusMillis(50));
        assertThat(recent.cause("api", "ValueError: invalid literal for int()", T.plusSeconds(1)))
                .hasValueSatisfying(s -> assertThat(s.method()).isEqualTo("POST"));
        assertThat(recent.cause("api", "ValueError: x", T.plusSeconds(5))).isEmpty(); // too late
        assertThat(recent.cause("worker", "ValueError: x", T.plusMillis(30))).isEmpty(); // another service
        assertThat(recent.cause("api", "api: GET /orders 200", T.plusMillis(30)))
                .isEmpty(); // not an error
    }

    @Test
    void errorLinesFromCommonRuntimes() {
        assertThat(RecentRequests.looksLikeError("java.lang.IllegalStateException: boom"))
                .isTrue();
        assertThat(RecentRequests.looksLikeError("    at com.shop.Orders.create(Orders.java:42)"))
                .isTrue();
        assertThat(RecentRequests.looksLikeError("[Nest] 1  - ERROR [ExceptionsHandler] Cannot read properties"))
                .isTrue();
        assertThat(RecentRequests.looksLikeError("panic: runtime error: index out of range"))
                .isTrue();
        assertThat(RecentRequests.looksLikeError("127.0.0.1 - - \"GET /x HTTP/1.1\" 500 -"))
                .isFalse();
        assertThat(RecentRequests.looksLikeError("worker: job #3 done")).isFalse();
    }
}
