package io.github.louch.devdesk.core.traffic;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A recording stand-in in front of a service: it listens on its own port, passes every HTTP call on to the service
 * unchanged, and reports each call with its answer. Callers that should be recorded talk to its port.
 */
public interface TrafficProxy {

    /**
     * @param port the port to listen on (0 = any free one)
     * @param callerOf names the caller from its address (which service holds that connection), "" when unknown
     * @throws IllegalStateException when the port can't be used, with a message for people
     */
    Running start(
            int port,
            String targetHost,
            int targetPort,
            Function<InetSocketAddress, String> callerOf,
            Consumer<Exchange> recorded);

    /** Sends a recorded call again, straight to the service: its status and body. */
    Answer send(String host, int port, Exchange call);

    /** What the service answered to a replayed call ({@code status} 0 = it didn't answer; {@code body} says why). */
    record Answer(int status, String body, long ms) {}

    /** A started proxy. */
    interface Running {
        /** The local port callers use instead of the service's port. */
        int port();

        /** Stops the proxy at once; calls still running are cut off. */
        void stop();
    }
}
