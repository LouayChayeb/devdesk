package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.http.MockRoutes;
import io.github.louch.devdesk.core.http.MockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class JdkMockServerTest {

    private final List<MockServer.Hit> hits = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void routesAnswerAndEveryRequestIsLogged() throws Exception {
        var routes = List.of(
                new MockRoutes.Route("1", "GET", "/orders/:id", 200, List.of(), "{\"id\": {{id}}}", Duration.ZERO));
        var running = new JdkMockServer(Clock.systemUTC()).start(0, () -> routes, hits::add);
        try {
            String base = "http://127.0.0.1:" + running.port();
            var ok = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/orders/9")).build(),
                    HttpResponse.BodyHandlers.ofString());
            var miss = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/nope"))
                            .POST(HttpRequest.BodyPublishers.ofString("x"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("{\"id\": 9}");
            assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
            assertThat(miss.statusCode()).isEqualTo(404);
            assertThat(miss.body()).contains("GET /orders/:id");
            // The server logs a request right after answering it, so the client can be a moment ahead.
            for (int i = 0; i < 100 && hits.size() < 2; i++) {
                Thread.sleep(20);
            }
            assertThat(hits)
                    .extracting(h -> h.method() + " " + h.target() + " " + h.status() + " " + h.route())
                    .containsExactly("GET /orders/9 200 GET /orders/:id", "POST /nope 404 ");
            assertThat(hits.get(1).body()).isEqualTo("x");
        } finally {
            running.stop();
        }
    }

    @Test
    void aTakenPortIsSaidPlainly() throws Exception {
        var first = new JdkMockServer(Clock.systemUTC()).start(0, List::of, hits::add);
        try {
            assertThatThrownBy(() -> new JdkMockServer(Clock.systemUTC()).start(first.port(), List::of, hits::add))
                    .hasMessageContaining("already in use");
        } finally {
            first.stop();
        }
    }
}
