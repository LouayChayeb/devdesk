package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockRoutesTest {

    private static MockRoutes.Route route(String method, String path, String body) {
        return new MockRoutes.Route("r", method, path, 200, List.of(), body, Duration.ZERO);
    }

    @Test
    void pathsMatchSegmentsParamsAndWildcards() {
        assertThat(MockRoutes.params("/orders/:id", "/orders/7")).contains(Map.of("id", "7"));
        assertThat(MockRoutes.params("/orders/:id", "/orders/7/items")).isEmpty();
        assertThat(MockRoutes.params("/orders/", "/orders")).contains(Map.of());
        assertThat(MockRoutes.params("/files/*", "/files/a/b.png")).contains(Map.of());
        assertThat(MockRoutes.params("/orders/:id", "/orders/")).isEmpty();
    }

    @Test
    void theFirstMatchingRouteAnswersWithItsParamsFilledIn() {
        var routes = List.of(
                route("POST", "/orders", "{\"created\": true}"),
                route("GET", "/orders/:id", "{\"id\": {{id}}, \"status\": \"paid\"}"),
                route("ANY", "/health", "ok"));

        var a = MockRoutes.answer(
                MockRoutes.match(routes, "GET", "/orders/42?expand=1").orElseThrow());

        assertThat(a.body()).isEqualTo("{\"id\": 42, \"status\": \"paid\"}");
        assertThat(a.headers()).contains(new HttpCall.Header("Content-Type", "application/json"));
        assertThat(MockRoutes.match(routes, "DELETE", "/health")).isPresent();
        assertThat(MockRoutes.match(routes, "GET", "/orders")).isEmpty();
    }

    @Test
    void aMissIsA404ListingTheRoutes() {
        var a = MockRoutes.notFound(List.of(route("GET", "/orders", "")), "GET", "/order");

        assertThat(a.status()).isEqualTo(404);
        assertThat(a.body()).isEqualTo("{\"error\": \"no mock route for GET /order\", \"routes\": [\"GET /orders\"]}");
    }
}
