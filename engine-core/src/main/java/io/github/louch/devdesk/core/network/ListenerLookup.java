package io.github.louch.devdesk.core.network;

import java.util.Optional;

/** Finds who listens on a TCP port on this machine. Implemented with /proc on Linux. */
public interface ListenerLookup {

    /** The listener on {@code port} (any address), if one exists right now. */
    Optional<Listener> listenerOn(int port);
}
