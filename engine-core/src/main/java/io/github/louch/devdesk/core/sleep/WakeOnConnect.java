package io.github.louch.devdesk.core.sleep;

import java.time.Duration;

/**
 * Stands in for a sleeping service on its port: the first connection wakes the service, waits for it to listen
 * again, and is then passed through to it, so the caller just sees a slower first answer.
 */
public interface WakeOnConnect {

    /**
     * @param wake starts the service; called once, on the first connection
     * @param maxWait how long the held connection waits for the service to listen
     * @throws IllegalStateException when the port can't be taken (something else listens on it)
     */
    Waiting listen(int port, Runnable wake, Duration maxWait);

    /** A stand-in waiting on a port. */
    interface Waiting {
        /** Stops listening (the service is being started some other way). Safe to call twice. */
        void close();
    }
}
