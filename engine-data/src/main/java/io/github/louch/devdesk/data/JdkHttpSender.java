package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.http.Curl;
import io.github.louch.devdesk.core.http.GraphQl;
import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.HttpSender;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends the API client's requests with the JDK's own HTTP client. The engine sends them, not the window: a browser
 * window may only call servers that allow it (CORS), while a developer wants to call anything on their machine.
 */
public final class JdkHttpSender implements HttpSender {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);
    /** Bigger bodies are cut, so one huge download can't freeze the app. */
    static final int MAX_BODY = 5 * 1024 * 1024;

    /** Cookies the services set: kept in memory only, so they're gone when DevDesk closes (like a private window). */
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    // HTTP/1.1, and redirects are shown, not followed: the user sees exactly what their service answered.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .cookieHandler(cookies)
            .build();

    @Override
    public List<Cookie> cookies() {
        return cookies.getCookieStore().getCookies().stream()
                .map(c -> new Cookie(
                        Optional.ofNullable(c.getDomain()).orElse(""),
                        Optional.ofNullable(c.getPath()).orElse("/"),
                        c.getName(),
                        c.getValue(),
                        c.isHttpOnly()))
                .toList();
    }

    @Override
    public void clearCookies() {
        cookies.getCookieStore().removeAll();
    }

    @Override
    public Outcome send(HttpCall call) {
        long started = System.nanoTime();
        HttpRequest request;
        try {
            List<HttpCall.Header> headers = new ArrayList<>(call.sentHeaders());
            HttpRequest.BodyPublisher body = switch (call.body()) {
                case HttpCall.Body.None n -> HttpRequest.BodyPublishers.noBody();
                case HttpCall.Body.Text t -> HttpRequest.BodyPublishers.ofString(t.text());
                case HttpCall.Body.GraphQl g ->
                    HttpRequest.BodyPublishers.ofString(GraphQl.payload(g.query(), g.variables()));
                case HttpCall.Body.Form f -> HttpRequest.BodyPublishers.ofString(Curl.formEncoded(f.fields()));
                case HttpCall.Body.Multipart m -> {
                    // The boundary separates the parts; it must not appear inside them, so it's random.
                    String boundary = "devdesk-" + UUID.randomUUID();
                    headers.removeIf(h -> h.name().equalsIgnoreCase("Content-Type"));
                    headers.add(new HttpCall.Header("Content-Type", "multipart/form-data; boundary=" + boundary));
                    yield HttpRequest.BodyPublishers.ofByteArray(Multipart.body(m.fields(), boundary));
                }
            };
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(call.url()))
                    .timeout(ANSWER_TIMEOUT)
                    .method(call.method(), body);
            for (HttpCall.Header h : headers) {
                b.header(h.name(), h.value());
            }
            request = b.build();
        } catch (IllegalArgumentException e) {
            // A malformed URL, or a header the client sets itself (Host, Content-Length, Connection).
            return new Outcome.Failed(Reason.BAD_REQUEST, e.getMessage(), took(started));
        } catch (UncheckedIOException e) {
            return new Outcome.Failed(Reason.BAD_REQUEST, e.getCause().getMessage(), took(started));
        }
        try {
            HttpResponse<InputStream> res = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            boolean cut;
            try (InputStream in = res.body()) {
                bytes = in.readNBytes(MAX_BODY + 1);
                cut = bytes.length > MAX_BODY;
            }
            if (cut) {
                bytes = Arrays.copyOf(bytes, MAX_BODY);
            }
            List<HttpCall.Header> headers = new ArrayList<>();
            res.headers()
                    .map()
                    .forEach((name, values) -> values.forEach(v -> headers.add(new HttpCall.Header(name, v))));
            Optional<String> text = text(bytes);
            String body = text.map(t -> cut ? t + "\n… (cut at " + MAX_BODY / 1024 / 1024 + " MB)" : t)
                    .orElse("");
            return new Outcome.Answered(
                    res.statusCode(),
                    res.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1",
                    headers,
                    body,
                    text.isEmpty(),
                    bytes.length,
                    took(started));
        } catch (HttpConnectTimeoutException e) {
            return new Outcome.Failed(
                    Reason.TIMEOUT, "no connection within " + CONNECT_TIMEOUT.toSeconds() + " s", took(started));
        } catch (HttpTimeoutException e) {
            return new Outcome.Failed(
                    Reason.TIMEOUT, "no answer within " + ANSWER_TIMEOUT.toSeconds() + " s", took(started));
        } catch (ConnectException e) {
            // The JDK wraps the real cause; "connection refused" means the kernel found no socket on that port.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Reason reason = cause instanceof UnresolvedAddressException || cause instanceof UnknownHostException
                    ? Reason.UNKNOWN_HOST
                    : Reason.REFUSED;
            String message = cause.getMessage() != null
                    ? cause.getMessage()
                    : reason == Reason.REFUSED
                            ? "connection refused"
                            : cause.getClass().getSimpleName();
            return new Outcome.Failed(reason, message, took(started));
        } catch (IOException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Reason reason = e.getCause() instanceof UnresolvedAddressException
                    ? Reason.UNKNOWN_HOST
                    : closedEarly(message) ? Reason.CLOSED : Reason.OTHER;
            return new Outcome.Failed(reason, message, took(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Failed(Reason.OTHER, "interrupted", took(started));
        }
    }

    /**
     * How the JDK words "the server hung up before answering": the connection was accepted (so something listens),
     * then closed (FIN: "no bytes"/EOF) or reset (RST) before a status line came.
     */
    static boolean closedEarly(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("received no bytes")
                || m.contains("eof reached")
                || m.contains("connection reset")
                || m.contains("connection closed")
                || m.contains("connection was closed");
    }

    /** The body as text if it is valid UTF-8 (JSON, HTML, plain text); empty for images, zips and the like. */
    static Optional<String> text(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static Duration took(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
