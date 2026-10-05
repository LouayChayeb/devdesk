package io.github.louch.devdesk.core.history;

import java.time.Instant;
import java.util.Map;

/**
 * One recorded fact about a project.
 *
 * @param service empty for project-wide entries (a DevDesk session starting or ending)
 * @param summary one human line, e.g. "crashed: exited with error code 1"
 * @param data the numbers behind it (state label, memory bytes...), never environment values
 */
public record HistoryEntry(Instant at, String service, Kind kind, String summary, Map<String, Object> data) {

    public HistoryEntry {
        data = Map.copyOf(data);
    }

    /** What kind of fact. */
    public enum Kind {
        /** DevDesk started or stopped watching the project. */
        SESSION,
        /** A service changed state. */
        STATE,
        /** A diagnosis finding. */
        FINDING,
        /** A warning (e.g. force-killed, processes left behind). */
        WARNING,
        /** CPU and memory at that moment. */
        RESOURCES
    }
}
