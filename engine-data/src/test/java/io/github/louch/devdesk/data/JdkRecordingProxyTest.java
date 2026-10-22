package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.traffic.Exchange;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A real service (the JDK's HTTP server) behind the real recording proxy. */
class JdkRecordingProxyTest {

    private HttpServer service;
    private final AtomicInteger counter = new AtomicInteger();
    private final JdkRecordingProxy proxy = new JdkRecordingProxy(Clock.systemUTC());

    @BeforeEach
    void startService() throws Exception {
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String body =
                    "{\"n\": " + counter.incrementAndGet() + ", \"got\": \"" + new String(in, StandardCharsets.UTF_8)
                            + "\", \"auth\": \"" + ex.getRequestHeaders().getFirst("Authorization") + "\"}";
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/missing") ? 404 : 200, body.length());
            ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        service.start();
    }

    @AfterEach
    void stopService() {
        service.stop(0);
    }

    @Test
    void callsPassThroughUnchangedAndAreRecorded() throws Exception {
        List<Exchange> recorded = new CopyOnWriteArrayList<>();
        var running = proxy.start(0, "127.0.0.1", service.getAddress().getPort(), from -> "web", recorded::add);
        try {
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            var r = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + "/orders?x=1"))
                            .header("Authorization", "Bearer abc")
                            .POST(HttpRequest.BodyPublishers.ofString("hello"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("\"got\": \"hello\"", "Bearer abc");
            var missing = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + "/missing"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(404);

            // The proxy writes the record right after relaying the answer, so the client can be a moment ahead.
            for (int i = 0; i < 100 && recorded.size() < 2; i++) {
                Thread.sleep(20);
            }
            assertThat(recorded).hasSize(2);
            Exchange e = recorded.getFirst();
            assertThat(e.caller()).isEqualTo("web");
            assertThat(e.method()).isEqualTo("POST");
            assertThat(e.path()).isEqualTo("/orders?x=1");
            assertThat(e.requestBody()).isEqualTo("hello");
            assertThat(e.responseBody()).contains("\"n\": 1");

            // Replaying sends the same call again, straight to the service.
            var again = proxy.send("127.0.0.1", service.getAddress().getPort(), e);
            assertThat(again.status()).isEqualTo(200);
            assertThat(again.body()).contains("\"n\": 3", "\"got\": \"hello\"", "Bearer abc");
        } finally {
            running.stop();
        }
    }
}
