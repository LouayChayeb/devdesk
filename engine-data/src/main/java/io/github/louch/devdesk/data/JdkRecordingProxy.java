package io.github.louch.devdesk.data;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.traffic.Exchange;
import io.github.louch.devdesk.core.traffic.TrafficProxy;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link TrafficProxy} with the JDK's own HTTP server in front and its HTTP client behind: each call is read whole,
 * passed on, answered with what the service said, and reported. Bodies over {@link #KEPT} are passed on in full but
 * kept cut, so a big download can't fill the memory.
 */
public final class JdkRecordingProxy implements TrafficProxy {

    /** At most this much of a body is kept in a recording (all of it is still passed on). */
    static final int KEPT = 256 * 1024;

    /** Hop-by-hop headers belong to one connection, not to the call: the JDK sets its own (and refuses these). */
    private static final Set<String> HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-connection",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length",
            "expect",
            "te",
            "trailer");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER) // the caller sees redirects as the service sent them
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Clock clock;

    public JdkRecordingProxy(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Running start(
            int port,
            String targetHost,
            int targetPort,
            Function<InetSocketAddress, String> callerOf,
            Consumer<Exchange> recorded) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (BindException e) {
            throw new IllegalStateException("port " + port + " is already taken: pick another, or leave it empty");
        } catch (IOException e) {
            throw new IllegalStateException("couldn't listen on port " + port + ": " + e.getMessage(), e);
        }
        AtomicInteger seq = new AtomicInteger();
        server.createContext("/", ex -> forward(ex, targetHost, targetPort, seq.incrementAndGet(), callerOf, recorded));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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

    private void forward(
            HttpExchange ex,
            String targetHost,
            int targetPort,
            int seq,
            Function<InetSocketAddress, String> callerOf,
            Consumer<Exchange> recorded)
            throws IOException {
        Instant at = clock.instant();
        long started = System.nanoTime();
        // Ask who called while the connection is still open: afterwards it's gone from the socket tables.
        String caller = callerOf.apply(ex.getRemoteAddress());
        byte[] body = readAll(ex.getRequestBody());
        String path = ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + ex.getRequestURI().getRawQuery());
        Map<String, List<String>> reqHeaders = headers(ex.getRequestHeaders());
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://" + targetHost + ":" + targetPort + path))
                .timeout(Duration.ofMinutes(2))
                .method(
                        ex.getRequestMethod(),
                        body.length == 0 && !hasBody(ex.getRequestMethod())
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        reqHeaders.forEach((k, vs) -> {
            if (!HOP.contains(k.toLowerCase(Locale.ROOT))) {
                vs.forEach(v -> req.header(k, v));
            }
        });
        int status;
        byte[] answer;
        Map<String, List<String>> respHeaders;
        try {
            HttpResponse<byte[]> r = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            status = r.statusCode();
            answer = r.body();
            respHeaders = new LinkedHashMap<>(r.headers().map());
        } catch (IOException e) {
            // The service didn't answer: say so to the caller (502, like any proxy), and record it.
            status = 502;
            answer = ("DevDesk's recorder couldn't reach the service on port " + targetPort + ": " + e.getMessage())
                    .getBytes(StandardCharsets.UTF_8);
            respHeaders = Map.of("content-type", List.of("text/plain; charset=utf-8"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.close();
            return;
        }
        respHeaders.forEach((k, vs) -> {
            if (!HOP.contains(k.toLowerCase(Locale.ROOT)) && !k.startsWith(":")) {
                vs.forEach(v -> ex.getResponseHeaders().add(k, v));
            }
        });
        // -1 = no body (HEAD, 204, 304); otherwise the exact length.
        boolean empty = answer.length == 0 || ex.getRequestMethod().equals("HEAD") || status == 204 || status == 304;
        ex.sendResponseHeaders(status, empty ? -1 : answer.length);
        if (!empty) {
            ex.getResponseBody().write(answer);
        }
        ex.close();
        recorded.accept(new Exchange(
                seq,
                at,
                caller,
                ex.getRequestMethod(),
                path,
                reqHeaders,
                text(body),
                status,
                respHeaders,
                text(answer),
                (System.nanoTime() - started) / 1_000_000));
    }

    @Override
    public Answer send(String host, int port, Exchange call) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + call.path()))
                .timeout(Duration.ofMinutes(1))
                .method(
                        call.method(),
                        call.requestBody().isEmpty() && !hasBody(call.method())
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(call.requestBody()));
        call.requestHeaders().forEach((k, vs) -> {
            if (!HOP.contains(k.toLowerCase(Locale.ROOT))) {
                vs.forEach(v -> req.header(k, v));
            }
        });
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> r = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Answer(r.statusCode(), text(r.body()), (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            return new Answer(0, "no answer: " + e.getMessage(), (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(0, "interrupted", 0);
        }
    }

    private static boolean hasBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }

    private static Map<String, List<String>> headers(com.sun.net.httpserver.Headers h) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        h.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    /** The kept part of a body, as text (binary bodies are kept as a note of their size). */
    private static String text(byte[] body) {
        for (int i = 0; i < Math.min(body.length, 2048); i++) {
            if (body[i] == 0) {
                return "(binary, " + body.length + " bytes)";
            }
        }
        int n = Math.min(body.length, KEPT);
        String s = new String(body, 0, n, StandardCharsets.UTF_8);
        return body.length > KEPT ? s + "\n… (" + body.length + " bytes in all)" : s;
    }
}
