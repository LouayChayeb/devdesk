package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.KeyValueBrowser.WatchEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * The Redis live views (MONITOR, pub/sub): each one is a connection the engine keeps open, buffering what arrives.
 * The app asks "what's new since event N" every half second. A watch nobody asks about for a while is closed, so a
 * closed tab (or a crashed window) never leaves a MONITOR running on the server.
 */
final class RedisWatches {

    /** Events kept per watch; older ones drop off (the app shows the newest anyway). */
    static final int KEPT = 2000;
    /** No poll for this long: the app is gone, close the connection. */
    static final Duration ABANDONED = Duration.ofSeconds(20);

    private final Clock clock;
    private final RandomGenerator random;
    private final Map<String, Running> watches = new ConcurrentHashMap<>();

    RedisWatches(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /** One open stream and what it received. Guarded by its own lock: the reader thread and polls both touch it. */
    private static final class Running {
        final String service;
        final Deque<Map<String, Object>> events = new ArrayDeque<>();
        long next = 1;
        Optional<String> ended = Optional.empty();
        Instant lastPoll;
        KeyValueBrowser.Watch watch;

        Running(String service, Instant now) {
            this.service = service;
            this.lastPoll = now;
        }

        synchronized void add(WatchEvent e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", next++);
            m.put("at", e.at().toString());
            m.put("source", e.source());
            m.put("text", e.text());
            events.addLast(m);
            if (events.size() > KEPT) {
                events.removeFirst();
            }
        }

        synchronized void end(String reason) {
            ended = Optional.of(reason);
        }
    }

    Map<String, Object> start(KeyValueBrowser redis, DatabaseTarget t, KeyValueBrowser.WatchKind kind) {
        closeAbandoned();
        String id = newId();
        Running r = new Running(t.service(), clock.instant());
        r.watch = redis.watch(t, kind, r::add, r::end);
        watches.put(id, r);
        return Map.of("id", id);
    }

    /** Events after {@code after}, and whether the stream has ended (and why). */
    Optional<Map<String, Object>> poll(String service, String id, long after) {
        closeAbandoned();
        Running r = watches.get(id);
        if (r == null || !r.service.equals(service)) {
            return Optional.empty();
        }
        synchronized (r) {
            r.lastPoll = clock.instant();
            List<Map<String, Object>> fresh = new ArrayList<>();
            for (Map<String, Object> e : r.events) {
                if ((long) e.get("seq") > after) {
                    fresh.add(e);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("events", fresh);
            m.put("ended", r.ended.orElse(null));
            return Optional.of(m);
        }
    }

    boolean stop(String service, String id) {
        Running r = watches.get(id);
        if (r == null || !r.service.equals(service)) {
            return false;
        }
        watches.remove(id);
        r.watch.close();
        return true;
    }

    /** On shutdown: every stream's connection is closed. */
    void closeAll() {
        watches.values().forEach(r -> r.watch.close());
        watches.clear();
    }

    private void closeAbandoned() {
        Instant now = clock.instant();
        watches.entrySet().removeIf(e -> {
            Running r = e.getValue();
            boolean gone;
            synchronized (r) {
                gone = Duration.between(r.lastPoll, now).compareTo(ABANDONED) > 0;
            }
            if (gone) {
                r.watch.close();
            }
            return gone;
        });
    }

    private String newId() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
