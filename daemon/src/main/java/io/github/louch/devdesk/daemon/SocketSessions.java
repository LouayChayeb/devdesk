package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.LiveSockets;
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
 * The API client's open WebSocket connections. Like the Redis live views: the engine keeps the connection and
 * buffers what happens on it; the app asks "what's new since N" twice a second, and a connection nobody asks about
 * for a while is closed.
 */
final class SocketSessions {

    static final int KEPT = 2000;
    static final Duration ABANDONED = Duration.ofSeconds(20);

    private final LiveSockets sockets;
    private final Clock clock;
    private final RandomGenerator random;
    private final Map<String, Session> open = new ConcurrentHashMap<>();

    SocketSessions(LiveSockets sockets, Clock clock, RandomGenerator random) {
        this.sockets = sockets;
        this.clock = clock;
        this.random = random;
    }

    /** One connection and its log: "in" (received), "out" (sent), "info" (opened, closed, failed). */
    private final class Session implements LiveSockets.Listener {
        final Deque<Map<String, Object>> events = new ArrayDeque<>();
        long next = 1;
        String state = "connecting";
        Instant lastPoll = clock.instant();
        LiveSockets.Connection connection;

        synchronized void add(String direction, String text) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", next++);
            m.put("at", clock.instant().toString());
            m.put("direction", direction);
            m.put("text", text);
            events.addLast(m);
            if (events.size() > KEPT) {
                events.removeFirst();
            }
        }

        @Override
        public synchronized void opened(String protocol) {
            state = "open";
            add("info", "connected" + (protocol == null || protocol.isEmpty() ? "" : " (protocol " + protocol + ")"));
        }

        @Override
        public void message(String text, boolean binary) {
            add("in", text);
        }

        @Override
        public synchronized void closed(int code, String reason) {
            state = "closed";
            add("info", "closed by the server: code " + code + (reason.isEmpty() ? "" : ", " + reason));
        }

        @Override
        public synchronized void failed(String why) {
            state = "closed";
            add("info", "failed: " + why);
        }
    }

    /** @param call the request with variables already filled in; its URL starts with ws:// or wss:// */
    Map<String, Object> open(HttpCall call) {
        closeAbandoned();
        String id = newId();
        Session s = new Session();
        open.put(id, s);
        s.add("info", "connecting to " + call.url());
        s.connection = sockets.open(call.url(), call.sentHeaders(), s);
        return Map.of("id", id);
    }

    Optional<Map<String, Object>> poll(String id, long after) {
        closeAbandoned();
        Session s = open.get(id);
        if (s == null) {
            return Optional.empty();
        }
        synchronized (s) {
            s.lastPoll = clock.instant();
            List<Map<String, Object>> fresh = new ArrayList<>();
            for (Map<String, Object> e : s.events) {
                if ((long) e.get("seq") > after) {
                    fresh.add(e);
                }
            }
            return Optional.of(Map.of("events", fresh, "state", s.state));
        }
    }

    boolean send(String id, String text) {
        Session s = open.get(id);
        if (s == null) {
            return false;
        }
        synchronized (s) {
            if (!s.state.equals("open")) {
                return false;
            }
        }
        s.connection.send(text);
        s.add("out", text);
        return true;
    }

    boolean close(String id) {
        Session s = open.remove(id);
        if (s == null) {
            return false;
        }
        s.connection.close();
        return true;
    }

    void closeAll() {
        open.values().forEach(s -> s.connection.close());
        open.clear();
    }

    private void closeAbandoned() {
        Instant now = clock.instant();
        open.entrySet().removeIf(e -> {
            Session s = e.getValue();
            boolean gone;
            synchronized (s) {
                gone = Duration.between(s.lastPoll, now).compareTo(ABANDONED) > 0;
            }
            if (gone) {
                s.connection.close();
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
