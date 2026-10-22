package io.github.louch.devdesk.core.http;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The API client's last requests and when each one ran, so a crash in a service's log can be traced back to the
 * request that caused it: "this traceback came while POST /orders was running against api".
 */
public final class RecentRequests {

    /** A service often logs its error just after answering (or a moment after timing out): still counts. */
    static final Duration AFTER = Duration.ofSeconds(2);

    /** Requests remembered; older ones can no longer be linked. */
    public static final int KEPT = 50;

    /**
     * Lines that start an error report: a Python traceback, a Java/Node exception, Go's panic, "ERROR"/"FATAL" log
     * levels. Plain access-log lines that only contain a status code don't count.
     */
    private static final Pattern ERROR = Pattern.compile(
            "Traceback \\(most recent call last\\)|Exception|[A-Za-z]*Error\\b|\\bERROR\\b|\\bFATAL\\b|^panic:|Unhandled|"
                    + "\\bat .+\\(.+:\\d+\\)");

    /** One request: which service it went to (if it went to one of the project's), and when. */
    public record Sent(long id, String service, String method, String url, Instant started, Instant ended) {}

    /** Newest first. */
    private final List<Sent> sent = new ArrayList<>();

    private long next = 1;

    /** @return the request's id, for {@link #cause} */
    public synchronized Sent record(String service, String method, String url, Instant started, Instant ended) {
        Sent s = new Sent(next++, service, method, url, started, ended);
        sent.addFirst(s);
        if (sent.size() > KEPT) {
            sent.removeLast();
        }
        return s;
    }

    /** Marks a request as running now (before its answer), so a crash during it is caught too. */
    public synchronized Sent start(String service, String method, String url, Instant started) {
        return record(service, method, url, started, Instant.MAX);
    }

    /** The answer came: the request's window closes. */
    public synchronized void end(Sent s, Instant ended) {
        sent.replaceAll(
                x -> x.id() == s.id() ? new Sent(x.id(), x.service(), x.method(), x.url(), x.started(), ended) : x);
    }

    /**
     * The request most likely behind an error line {@code service} printed {@code at}: the latest one to that service
     * that was running then (or ended at most 2 s before). Empty when the line isn't an error, or nothing ran.
     */
    public synchronized Optional<Sent> cause(String service, String line, Instant at) {
        if (!looksLikeError(line)) {
            return Optional.empty();
        }
        for (Sent s : sent) {
            boolean during = !at.isBefore(s.started())
                    && (s.ended().equals(Instant.MAX) || !at.isAfter(s.ended().plus(AFTER)));
            if (during && service.equals(s.service())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Sent> find(long id) {
        return sent.stream().filter(s -> s.id() == id).findFirst();
    }

    public static boolean looksLikeError(String line) {
        return ERROR.matcher(line).find();
    }
}
