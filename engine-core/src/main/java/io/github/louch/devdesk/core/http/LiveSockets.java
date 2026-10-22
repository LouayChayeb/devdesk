package io.github.louch.devdesk.core.http;

import java.util.List;

/**
 * Opens WebSocket connections for the API client (port; the JDK's client is the adapter). A WebSocket stays open
 * after the handshake, and both sides send messages whenever they like, so what arrives goes to a {@link Listener}.
 */
public interface LiveSockets {

    /** Starts connecting; {@link Listener#opened} or {@link Listener#failed} follows on another thread. */
    Connection open(String url, List<HttpCall.Header> headers, Listener listener);

    /** What happens on a connection, as it happens. */
    interface Listener {
        void opened(String protocol);

        /** @param binary the message was binary: {@code text} then describes it ("12 bytes") */
        void message(String text, boolean binary);

        /** The other side (or we) closed it: WebSocket close code and reason, e.g. 1000 "bye". */
        void closed(int code, String reason);

        /** It never opened, or broke: in words for people. */
        void failed(String why);
    }

    /** An open (or opening) connection. */
    interface Connection {
        void send(String text);

        /** Closes politely (code 1000); a connection that never opened is just dropped. */
        void close();
    }
}
