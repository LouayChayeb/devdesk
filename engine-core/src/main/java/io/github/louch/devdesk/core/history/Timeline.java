package io.github.louch.devdesk.core.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a project's history as a story: which entries are routine, and where a DevDesk stopped without saying so.
 * Shared by `devdesk history` and the app's Timeline tab, so both tell the same story.
 */
public final class Timeline {

    /** Routine in-between states: they rarely explain anything, so views hide them by default. */
    static final Set<String> ROUTINE_STATES = Set.of("waiting", "stopping");

    /**
     * One entry, read in context.
     *
     * @param afterUncleanStop a session start with no recorded stop before it: the previous DevDesk was killed,
     *     crashed or lost power, and its services may have kept running
     */
    public record Item(HistoryEntry entry, boolean routine, boolean afterUncleanStop) {}

    private Timeline() {}

    /** {@code entries} oldest first, as the store returns them. */
    public static List<Item> of(List<HistoryEntry> entries) {
        List<Item> items = new ArrayList<>();
        boolean sessionOpen = false;
        for (HistoryEntry e : entries) {
            boolean unclean = false;
            if (e.kind() == HistoryEntry.Kind.SESSION) {
                boolean starting = "started".equals(e.data().get("event"));
                unclean = starting && sessionOpen;
                sessionOpen = starting;
            }
            boolean routine = e.kind() == HistoryEntry.Kind.STATE
                    && ROUTINE_STATES.contains(String.valueOf(e.data().get("to")));
            items.add(new Item(e, routine, unclean));
        }
        return items;
    }
}
