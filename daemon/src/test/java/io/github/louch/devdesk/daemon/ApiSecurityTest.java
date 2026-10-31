package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local API's defences, against the real engine: the token (HTTP and WebSocket), the Host check (DNS rebinding),
 * the Origin allow-list (other web pages), the event stream's snapshot-then-sequence promise, and every route
 * answering without crashing. Extras are on, so their routes are covered too.
 */
class ApiSecurityTest {

    @TempDir
    static Path home;

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.start(home, "on");
    }

    @AfterAll
    static void stop() throws Exception {
        engine.close();
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(engine.base() + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return engine.http().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theTokenIsRequiredAndMustBeExact() throws Exception {
        String token = engine.token();
        // Same length, last character changed: a constant-time compare must still say no.
        String almost = token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0");

        assertThat(get("/project", null).statusCode()).isEqualTo(401);
        assertThat(get("/project", "").statusCode()).isEqualTo(401);
        assertThat(get("/project", almost).statusCode()).isEqualTo(401);
        assertThat(get("/project", token + "x").statusCode()).isEqualTo(401);
        assertThat(get("/project", token.substring(1)).statusCode()).isEqualTo(401);
        assertThat(get("/project", token).statusCode()).isNotEqualTo(401);
    }

    @Test
    void aForeignHostHeaderIsRefusedEvenWithTheToken() throws Exception {
        // DNS rebinding: a page on evil.example whose name now points to 127.0.0.1 sends Host: evil.example.
        String evil = engine.raw("GET /api/v1/project HTTP/1.1\r\nHost: evil.example:" + engine.port()
                + "\r\nAuthorization: Bearer " + engine.token() + "\r\nConnection: close\r\n\r\n");
        String good = engine.raw("GET /api/v1/project HTTP/1.1\r\nHost: 127.0.0.1:" + engine.port()
                + "\r\nAuthorization: Bearer " + engine.token() + "\r\nConnection: close\r\n\r\n");

        assertThat(evil).startsWith("HTTP/1.1 403").contains("wrong Host header");
        assertThat(good).doesNotStartWith("HTTP/1.1 403");
    }

    @Test
    void onlyTheAppsOwnOriginsAreAllowed() throws Exception {
        String evil = engine.raw("GET /api/v1/project HTTP/1.1\r\nHost: 127.0.0.1:" + engine.port()
                + "\r\nOrigin: https://evil.example\r\nAuthorization: Bearer " + engine.token()
                + "\r\nConnection: close\r\n\r\n");
        String app = engine.raw("GET /api/v1/project HTTP/1.1\r\nHost: 127.0.0.1:" + engine.port()
                + "\r\nOrigin: tauri://localhost\r\nAuthorization: Bearer " + engine.token()
                + "\r\nConnection: close\r\n\r\n");

        assertThat(evil).startsWith("HTTP/1.1 403").doesNotContainIgnoringCase("Access-Control-Allow-Origin");
        assertThat(app)
                .doesNotStartWith("HTTP/1.1 403")
                .containsIgnoringCase("Access-Control-Allow-Origin: tauri://localhost");
        // A browser's preflight from another site gets no CORS permission either.
        String preflight = engine.raw(
                "OPTIONS /api/v1/project HTTP/1.1\r\nHost: 127.0.0.1:" + engine.port()
                        + "\r\nOrigin: https://evil.example\r\nAccess-Control-Request-Method: POST\r\nConnection: close\r\n\r\n");
        assertThat(preflight).doesNotContainIgnoringCase("Access-Control-Allow-Origin");
    }

    /** Collects a WebSocket's text frames and how it was closed. */
    private static final class Listener implements WebSocket.Listener {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            closed.complete(status);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    private static Listener connect(String token, String origin) throws Exception {
        var listener = new Listener();
        var b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (origin != null) {
            b.header("Origin", origin);
        }
        b.buildAsync(URI.create("ws://127.0.0.1:" + engine.port() + "/api/v1/events?token=" + token), listener)
                .get(10, TimeUnit.SECONDS);
        return listener;
    }

    @Test
    void theEventStreamClosesWith4401OnABadTokenOrAForeignPage() throws Exception {
        assertThat(connect("wrong", null).closed.get(10, TimeUnit.SECONDS)).isEqualTo(4401);
        assertThat(connect(engine.token(), "https://evil.example").closed.get(10, TimeUnit.SECONDS))
                .isEqualTo(4401);
    }

    @Test
    void theStreamSendsASnapshotFirstThenNumberedEventsInOrder() throws Exception {
        Listener l = connect(engine.token(), null);
        Map<?, ?> first = ApiJson.MAPPER.readValue(l.frames.poll(10, TimeUnit.SECONDS), Map.class);
        assertThat(first.get("type")).isEqualTo("snapshot");
        long seq = ((Number) first.get("seq")).longValue();

        // Opening a project makes events; each must come after the snapshot, numbered one by one.
        String project = Path.of("src", "test", "resources", "projects", "three-tier")
                .toAbsolutePath()
                .normalize()
                .toString();
        engine.call("POST /project/open", "{\"path\":\"" + project + "\"}");
        String frame;
        int seen = 0;
        while ((frame = l.frames.poll(3, TimeUnit.SECONDS)) != null) {
            Map<?, ?> e = ApiJson.MAPPER.readValue(frame, Map.class);
            assertThat(((Number) e.get("seq")).longValue()).as(frame).isEqualTo(seq + 1);
            seq++;
            seen++;
        }
        assertThat(seen).isPositive();
    }

    /** Every route the engine registers, from ApiServer's source: "GET /api/v1/project", ... */
    static List<String> routes() throws Exception {
        String src = Files.readString(Path.of("src/main/java/io/github/louch/devdesk/daemon/ApiServer.java"));
        Matcher m = Pattern.compile("config\\.routes\\.(get|post)\\(\\s*\"(/api/v1/[^\"]+)\"")
                .matcher(src);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1).toUpperCase() + " " + m.group(2));
        }
        return out;
    }

    @Test
    void everyRouteNeedsTheTokenAndNoneCrashesOnAnEmptyRequest() throws Exception {
        List<String> routes = routes();
        assertThat(routes)
                .hasSizeGreaterThan(100)
                .contains("GET /api/v1/project", "POST /api/v1/project/verdict/prove");
        List<String> crashed = new ArrayList<>();
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            // Path parameters get a name that matches nothing: the route must say so, not crash.
            String path = parts[1].replaceAll("\\{[^}]+}", "nothing-here").replace("/api/v1", "");
            var unauthenticated = engine.http()
                    .send(
                            HttpRequest.newBuilder(URI.create(engine.base() + path))
                                    .method(parts[0], HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(unauthenticated.statusCode())
                    .as(route + " without token")
                    .isEqualTo(401);

            var r = engine.call(parts[0] + " " + path, parts[0].equals("POST") ? "{}" : null);
            if (r.statusCode() >= 500 || r.body().contains("Endpoint ")) {
                crashed.add(route + " → " + r.statusCode() + " " + r.body());
            }
        }
        assertThat(crashed).as("routes that crashed or don't exist").isEmpty();
    }
}
