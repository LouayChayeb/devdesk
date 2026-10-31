package io.github.louch.devdesk.core.data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-words hints about a Redis stream's consumer groups: messages a consumer took and never acknowledged (it
 * probably crashed while handling them), a group that falls behind, a group nobody reads.
 */
public final class StreamAdvice {

    /** A consumer quiet this long while holding messages is likely gone. */
    static final Duration STUCK = Duration.ofMinutes(5);

    static final long FAR_BEHIND = 1_000;

    private StreamAdvice() {}

    /**
     * Hints about stream {@code key}: stuck consumers, groups falling behind, groups nobody reads. Empty when all is
     * well.
     */
    public static List<String> of(String key, KeyValueBrowser.StreamInfo s) {
        List<String> out = new ArrayList<>();
        for (KeyValueBrowser.Group g : s.groups()) {
            for (KeyValueBrowser.Consumer c : g.consumers()) {
                if (c.pending() > 0 && c.idle().compareTo(STUCK) >= 0) {
                    out.add("In group " + g.name() + ", consumer " + c.name() + " took " + c.pending() + " message"
                            + (c.pending() == 1 ? "" : "s") + " and never acknowledged them, and has been quiet for "
                            + human(c.idle()) + ": it probably crashed while handling them. Another consumer can take"
                            + " them over with XAUTOCLAIM " + key + " " + g.name() + " <consumer> 60000 0.");
                }
            }
            if (g.consumers().isEmpty() && (g.lag() > 0 || g.pending() > 0)) {
                out.add("Nobody reads group " + g.name() + ": it has no consumers, and "
                        + (g.lag() > 0
                                ? g.lag() + " new messages wait for it."
                                : g.pending() + " messages are pending."));
            } else if (g.lag() >= FAR_BEHIND) {
                out.add("Group " + g.name() + " is " + g.lag() + " messages behind: its consumers don't keep up, or"
                        + " are stopped.");
            }
        }
        return out;
    }

    /** "3 h 12 min", "4 min", "40 s". */
    static String human(Duration d) {
        long s = d.toSeconds();
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min";
        }
        long h = s / 3600;
        long m = (s % 3600) / 60;
        return h + " h" + (m > 0 ? " " + m + " min" : "");
    }
}
