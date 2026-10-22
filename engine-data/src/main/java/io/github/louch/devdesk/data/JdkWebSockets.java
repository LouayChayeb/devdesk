package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.LiveSockets;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** {@link LiveSockets} with the JDK's WebSocket client. */
public final class JdkWebSockets implements LiveSockets {

    /** Headers the WebSocket handshake sets itself; the JDK refuses them from the caller. */
    private static final Set<String> HANDSHAKE = Set.of(
            "connection",
            "upgrade",
            "host",
            "content-length",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions",
            "sec-websocket-accept");

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public Connection open(String url, List<HttpCall.Header> headers, Listener listener) {
        WebSocket.Builder b = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        for (HttpCall.Header h : headers) {
            String name = h.name().toLowerCase(Locale.ROOT);
            if (name.equals("sec-websocket-protocol")) {
                String[] protocols = h.value().split("\\s*,\\s*");
                b.subprotocols(protocols[0], java.util.Arrays.copyOfRange(protocols, 1, protocols.length));
            } else if (!HANDSHAKE.contains(name)) {
                b.header(h.name(), h.value());
            }
        }
        CompletableFuture<WebSocket> socket;
        try {
            socket = b.buildAsync(URI.create(url), new Relay(listener));
        } catch (IllegalArgumentException e) {
            listener.failed("this isn't a WebSocket address DevDesk can open: " + e.getMessage());
            return new Connection() {
                @Override
                public void send(String text) {}

                @Override
                public void close() {}
            };
        }
        socket.whenComplete((ws, error) -> {
            if (error != null) {
                listener.failed(why(error));
            }
        });
        return new Connection() {
            @Override
            public void send(String text) {
                socket.thenAccept(ws -> ws.sendText(text, true));
            }

            @Override
            public void close() {
                if (socket.isDone() && !socket.isCompletedExceptionally()) {
                    socket.join().sendClose(WebSocket.NORMAL_CLOSURE, "closed in DevDesk");
                } else {
                    socket.cancel(true);
                }
            }
        };
    }

    /** "connection refused", "the server answered 404 instead of switching to WebSocket"... */
    static String why(Throwable error) {
        Throwable e = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (e instanceof WebSocketHandshakeException h) {
            return "the server answered HTTP " + h.getResponse().statusCode() + " instead of switching to WebSocket:"
                    + " is this the right path?";
        }
        if (e instanceof ConnectException) {
            return "connection refused: nothing listens there (is the service running?)";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Passes events on. A text message may come in parts ({@code last} false): they're joined first. request(1) asks
     * for the next message once this one is handled (the JDK's back-pressure).
     */
    private static final class Relay implements WebSocket.Listener {
        private final Listener to;
        private final StringBuilder parts = new StringBuilder();

        Relay(Listener to) {
            this.to = to;
        }

        @Override
        public void onOpen(WebSocket ws) {
            to.opened(ws.getSubprotocol());
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            parts.append(data);
            if (last) {
                to.message(parts.toString(), false);
                parts.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            to.message(data.remaining() + " bytes of binary data", true);
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            to.closed(code, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            to.failed(why(error));
        }
    }
}
