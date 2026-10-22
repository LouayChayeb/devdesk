package io.github.louch.devdesk.core.http;

import java.time.Duration;
import java.util.List;

/** Sends a request for the API client. Implemented with the JDK's HTTP client in engine-data; faked in tests. */
public interface HttpSender {

    /** Never throws for network trouble: a failure is an {@link Outcome}, so it can be explained. */
    Outcome send(HttpCall call);

    /**
     * Cookies the services set so far. They are sent back with the next requests, like a browser does, so logging in
     * once is enough for the requests after it.
     */
    List<Cookie> cookies();

    /** Forgets every cookie: the next request starts logged out. */
    void clearCookies();

    /** One cookie a service set. */
    record Cookie(String domain, String path, String name, String value, boolean httpOnly) {}

    /** What happened to one request. */
    sealed interface Outcome {
        /**
         * The server answered (any status, 500 included).
         *
         * @param body as text (UTF-8), cut at a few MB; {@code binary} when it isn't text
         * @param size bytes received in total
         */
        record Answered(
                int status,
                String version,
                List<HttpCall.Header> headers,
                String body,
                boolean binary,
                long size,
                Duration took)
                implements Outcome {}

        /** No answer: the reason, plus the low-level message. */
        record Failed(Reason reason, String message, Duration took) implements Outcome {}
    }

    /** Why a request got no answer. Each one gets its own explanation. */
    enum Reason {
        /** Nothing listens on that port (the kernel answered "connection refused"). */
        REFUSED,
        /** Connected, or tried to, but no answer in time. */
        TIMEOUT,
        /** The host name doesn't resolve. */
        UNKNOWN_HOST,
        /** Connected, but the server closed the connection (or reset it) without answering: usually a crash. */
        CLOSED,
        /** Not a URL the client can send to, or a file to upload that can't be read. */
        BAD_REQUEST,
        /** TLS, a reset connection, anything else. */
        OTHER
    }
}
