package io.github.louch.devdesk.core.leftovers;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Something a previous DevDesk started for this project and never stopped (it was killed or crashed).
 *
 * @param id the proof of ownership: a systemd scope name or a container id, both carrying the project's key
 * @param description what it is, for people ("python3 api.py", "container postgres:18.1")
 * @param since when it started, if known
 */
public record Leftover(
        String service, Kind kind, String id, Set<Long> pids, String description, Optional<Instant> since) {

    public Leftover {
        pids = Set.copyOf(pids);
    }

    /** What kind of thing was left behind. */
    public enum Kind {
        PROCESS,
        CONTAINER
    }
}
