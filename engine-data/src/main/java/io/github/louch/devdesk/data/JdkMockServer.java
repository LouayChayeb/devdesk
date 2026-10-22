package io.github.louch.devdesk.data;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.MockRoutes;
import io.github.louch.devdesk.core.http.MockServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** {@link MockServer} on the JDK's built-in HTTP server, on 127.0.0.1 only. */
public final class JdkMockServer implements MockServer {

    /** A request body is kept for the hit log up to this size. */
    static final int KEPT_BODY = 2000;

    private final Clock clock;

    public JdkMockServer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Running start(int port, Supplier<List<MockRoutes.Route>> routes, Consumer<Hit> hits) {
        HttpServer server;
        try {
            // 127.0.0.1: the fake API is for this machine, never the network.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (BindException e) {
            throw new IllegalStateException("port " + port + " is already in use: pick another one, or see who holds it"
                    + " on the Ports page");
        } catch (IOException e) {
            throw new IllegalStateException("couldn't open port " + port + ": " + e.getMessage());
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> handle(ex, routes.get(), hits));
        server.start();
        int bound = server.getAddress().getPort();
        return new Running() {
            @Override
            public int port() {
                return bound;
            }

            @Override
            public void stop() {
                server.stop(0);
            }
        };
    }

    private void handle(HttpExchange ex, List<MockRoutes.Route> routes, Consumer<Hit> hits) throws IOException {
        String method = ex.getRequestMethod();
        String target = ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + ex.getRequestURI().getRawQuery());
        byte[] in = ex.getRequestBody().readAllBytes();
        // Browsers ask before a cross-origin call (CORS preflight): a frontend on another port must be allowed in.
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        Optional<MockRoutes.Match> match = MockRoutes.match(routes, method, target);
        if (method.equals("OPTIONS") && match.isEmpty()) {
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        MockRoutes.Answer a =
                match.map(MockRoutes::answer).orElseGet(() -> MockRoutes.notFound(routes, method, target));
        if (!a.delay().isZero()) {
            try {
                Thread.sleep(a.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (HttpCall.Header h : a.headers()) {
            ex.getResponseHeaders().add(h.name(), h.value());
        }
        byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(a.status(), method.equals("HEAD") || out.length == 0 ? -1 : out.length);
        if (!method.equals("HEAD") && out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
        String body = new String(in, StandardCharsets.UTF_8);
        hits.accept(new Hit(
                clock.instant(),
                method,
                target,
                a.status(),
                match.map(m -> m.route().method() + " " + m.route().path()).orElse(""),
                body.length() > KEPT_BODY ? body.substring(0, KEPT_BODY) + "…" : body));
    }
}
