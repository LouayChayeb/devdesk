package io.github.louch.devdesk.core.http;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Serves {@link MockRoutes} on a port of this machine (port; the JDK's small HTTP server is the adapter). Routes are
 * read on every request, so an edit applies right away without a restart.
 */
public interface MockServer {

    /**
     * @throws IllegalStateException when the port can't be used (taken, or below 1024), with a message for people
     */
    Running start(int port, Supplier<List<MockRoutes.Route>> routes, Consumer<Hit> hits);

    /** A started server. */
    interface Running {
        int port();

        void stop();
    }

    /** One request the mock server answered: what came in, and which route answered (empty: none, a 404). */
    record Hit(Instant at, String method, String target, int status, String route, String body) {}
}
