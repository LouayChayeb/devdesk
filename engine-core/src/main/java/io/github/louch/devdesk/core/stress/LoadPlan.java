package io.github.louch.devdesk.core.stress;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What to send: one request, sent by more and more callers at once (from 1 up to {@code maxConcurrency} over
 * {@code seconds}), so the point where things break shows.
 */
public record LoadPlan(
        String method, String url, Map<String, String> headers, String body, int maxConcurrency, int seconds) {

    public static final int MAX_CONCURRENCY = 500;
    public static final int MAX_SECONDS = 300;

    /** Hosts that are this machine: the lab never sends load anywhere else. */
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public LoadPlan {
        headers = Map.copyOf(headers);
    }

    /** Why this plan can't run, if it can't: a target that isn't on this machine, or limits out of range. */
    public Optional<String> problem() {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.of("that isn't a URL: " + url);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            return Optional.of("use an http:// or https:// URL");
        }
        if (uri.getHost() == null || !LOCAL.contains(uri.getHost().toLowerCase())) {
            return Optional.of("the stress lab only sends load to this machine (localhost or 127.0.0.1): never to"
                    + " someone else's server");
        }
        if (maxConcurrency < 1 || maxConcurrency > MAX_CONCURRENCY) {
            return Optional.of("callers at once: 1 to " + MAX_CONCURRENCY);
        }
        if (seconds < 5 || seconds > MAX_SECONDS) {
            return Optional.of("duration: 5 to " + MAX_SECONDS + " seconds");
        }
        return Optional.empty();
    }

    /** How many callers send at once during second {@code s} (0-based): a steady climb to the maximum. */
    public int concurrencyAt(int s) {
        return Math.max(1, (int) Math.ceil((double) maxConcurrency * (s + 1) / seconds));
    }
}
