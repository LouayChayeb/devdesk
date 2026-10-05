package io.github.louch.devdesk.core.history;

import java.time.Instant;
import java.util.List;

/** Where history is kept. On Linux: files under $XDG_STATE_HOME; in tests: memory. */
public interface HistoryStore {

    /** Must not throw: losing one entry is better than breaking the project it describes. */
    void append(HistoryEntry entry);

    /** Every entry at or after {@code since}, oldest first. */
    List<HistoryEntry> read(Instant since);
}
