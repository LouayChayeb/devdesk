package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.RedisEdits;
import io.github.louch.devdesk.data.Resp.Reply;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Reads Redis for the Workbench over {@link Resp}. Like the Postgres browser, every call opens a short connection
 * and closes it again: simple, and a local Redis answers in well under a millisecond.
 */
public final class RedisBrowser implements KeyValueBrowser {

    /** At most this many elements of one value are read (a list can hold millions). */
    static final int MAX_ITEMS = 1000;
    /** At most this many bytes of one string value are read. */
    static final int MAX_BYTES = 256 * 1024;

    @Override
    public KeyPage keys(DatabaseTarget target, String pattern, String cursor, int count) {
        try (Resp r = Resp.connect(target)) {
            // SCAN returns [next cursor, [keys...]]. COUNT is a hint for how much work to do per step, not a limit.
            List<Reply> page = array(r.call("SCAN", cursor, "MATCH", pattern, "COUNT", String.valueOf(count)));
            String next = string(page.get(0));
            List<String> keys =
                    array(page.get(1)).stream().map(RedisBrowser::string).toList();
            List<List<String>> asks = new ArrayList<>();
            for (String k : keys) {
                asks.add(List.of("TYPE", k));
                asks.add(List.of("PTTL", k));
            }
            List<Reply> answers = r.pipeline(asks);
            List<KeyInfo> infos = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                String type = string(answers.get(2 * i));
                if (!type.equals("none")) { // expired between SCAN and TYPE
                    infos.add(new KeyInfo(keys.get(i), type, ttl(answers.get(2 * i + 1))));
                }
            }
            return new KeyPage(infos, next);
        }
    }

    @Override
    public Optional<KeyValue> read(DatabaseTarget target, String key) {
        try (Resp r = Resp.connect(target)) {
            List<Reply> head = r.pipeline(List.of(List.of("TYPE", key), List.of("PTTL", key)));
            String type = string(head.get(0));
            Optional<Duration> ttl = ttl(head.get(1));
            String last = String.valueOf(MAX_ITEMS - 1);
            return Optional.ofNullable(
                    switch (type) {
                        case "none" -> null;
                        case "string" -> {
                            long length = integer(r.call("STRLEN", key));
                            yield new KeyValue(
                                    key,
                                    type,
                                    ttl,
                                    length,
                                    new Value.Text(
                                            string(r.call("GETRANGE", key, "0", String.valueOf(MAX_BYTES - 1)))));
                        }
                        case "list" ->
                            new KeyValue(
                                    key,
                                    type,
                                    ttl,
                                    integer(r.call("LLEN", key)),
                                    new Value.Items(strings(r.call("LRANGE", key, "0", last))));
                        case "set" -> {
                            long length = integer(r.call("SCARD", key));
                            List<String> members = new ArrayList<>(strings(scanAll(r, "SSCAN", key)));
                            members.sort(null); // Redis keeps no order in a set; sorted is easier to read
                            yield new KeyValue(key, type, ttl, length, new Value.Items(members));
                        }
                        case "hash" -> {
                            long length = integer(r.call("HLEN", key));
                            yield new KeyValue(
                                    key, type, ttl, length, new Value.Fields(fields(scanAll(r, "HSCAN", key))));
                        }
                        case "zset" -> {
                            long length = integer(r.call("ZCARD", key));
                            List<String> flat = strings(r.call("ZRANGE", key, "0", last, "WITHSCORES"));
                            List<Member> members = new ArrayList<>();
                            for (int i = 0; i + 1 < flat.size(); i += 2) {
                                members.add(new Member(flat.get(i), Double.parseDouble(flat.get(i + 1))));
                            }
                            yield new KeyValue(key, type, ttl, length, new Value.Scored(members));
                        }
                        case "stream" -> {
                            long length = integer(r.call("XLEN", key));
                            List<StreamEntry> entries = new ArrayList<>();
                            // Newest first: in a stream, the latest events are usually the interesting ones.
                            for (Reply e :
                                    array(r.call("XREVRANGE", key, "+", "-", "COUNT", String.valueOf(MAX_ITEMS)))) {
                                List<Reply> parts = array(e);
                                entries.add(new StreamEntry(string(parts.get(0)), fields(strings(parts.get(1)))));
                            }
                            yield new KeyValue(key, type, ttl, length, new Value.Entries(entries));
                        }
                        // RedisJSON's type: the document as JSON text (the app shows it pretty and edits it).
                        case "ReJSON-RL" -> {
                            String json = string(r.call("JSON.GET", key));
                            yield new KeyValue(key, type, ttl, json.length(), new Value.Text(json));
                        }
                        default -> new KeyValue(key, type, ttl, -1, new Value.Unsupported(type));
                    });
        }
    }

    /** SSCAN/HSCAN until done or {@link #MAX_ITEMS} reached; the flat list of what they returned. */
    private static List<Reply> scanAll(Resp r, String command, String key) {
        List<Reply> out = new ArrayList<>();
        String cursor = "0";
        do {
            List<Reply> page = array(r.call(command, key, cursor, "COUNT", "500"));
            cursor = string(page.get(0));
            out.addAll(array(page.get(1)));
        } while (!cursor.equals("0") && out.size() < MAX_ITEMS * 2);
        return out;
    }

    @Override
    public boolean delete(DatabaseTarget target, String key) {
        try (Resp r = Resp.connect(target)) {
            // UNLINK frees the memory in the background, so deleting a huge key doesn't block Redis.
            return integer(r.call("UNLINK", key)) == 1;
        }
    }

    @Override
    public boolean expire(DatabaseTarget target, String key, Optional<Duration> ttl) {
        try (Resp r = Resp.connect(target)) {
            if (integer(r.call("EXISTS", key)) == 0) {
                return false;
            }
            if (ttl.isPresent()) {
                r.call("PEXPIRE", key, String.valueOf(Math.max(1, ttl.get().toMillis())));
            } else {
                r.call("PERSIST", key);
            }
            return true;
        }
    }

    @Override
    public void edit(DatabaseTarget target, String key, Edit edit) {
        // A marker no real list holds, for removing a list element by position (see RedisEdits).
        String marker = "__devdesk_removed_" + System.nanoTime() + "__";
        try (Resp r = Resp.connect(target)) {
            for (Reply reply : r.pipeline(RedisEdits.commands(key, edit, marker))) {
                failOnError(reply);
            }
        }
    }

    /** An error reply, also one inside EXEC's list of results, becomes a DataException with Redis's words. */
    private static void failOnError(Reply reply) {
        switch (reply) {
            case Reply.Error e -> throw new DataException(e.message() + explain(e.message()));
            case Reply.Array a when a.items() != null -> a.items().forEach(RedisBrowser::failOnError);
            default -> {
                // fine
            }
        }
    }

    private static String explain(String message) {
        if (message.startsWith("WRONGTYPE")) {
            return ". The key holds another type now; reload it.";
        }
        if (message.contains("index out of range")) {
            return ". The list got shorter since it was loaded; reload it.";
        }
        if (message.contains("unknown command 'JSON.")) {
            return ". This Redis has no RedisJSON module.";
        }
        return "";
    }

    @Override
    public String command(DatabaseTarget target, List<String> words) {
        try (Resp r = Resp.connect(target)) {
            r.send(words);
            return format(r.read(), "");
        }
    }

    @Override
    public MemoryReport memory(DatabaseTarget target, String pattern, int maxKeys) {
        try (Resp r = Resp.connect(target)) {
            Map<String, String> info = info(string(r.call("INFO", "memory")) + "\n" + string(r.call("INFO", "stats")));
            long total = integer(r.call("DBSIZE"));
            // The top keys by size, kept in a small heap: the smallest of the top 50 is on top, ready to be replaced.
            PriorityQueue<KeySize> top = new PriorityQueue<>(Comparator.comparingLong(KeySize::bytes));
            Map<String, long[]> prefixes = new HashMap<>();
            long scanned = 0;
            String cursor = "0";
            do {
                List<Reply> page = array(r.call("SCAN", cursor, "MATCH", pattern, "COUNT", "1000"));
                cursor = string(page.get(0));
                List<String> keys = strings(page.get(1));
                List<List<String>> asks = new ArrayList<>();
                for (String k : keys) {
                    asks.add(List.of("MEMORY", "USAGE", k));
                    asks.add(List.of("TYPE", k));
                }
                List<Reply> answers = r.pipeline(asks);
                for (int i = 0; i < keys.size(); i++) {
                    if (!(answers.get(2 * i) instanceof Reply.Int bytes)) {
                        continue; // expired since SCAN saw it
                    }
                    String key = keys.get(i);
                    top.add(new KeySize(key, string(answers.get(2 * i + 1)), bytes.value(), -1));
                    if (top.size() > TOP_KEYS) {
                        top.poll();
                    }
                    long[] sum = prefixes.computeIfAbsent(prefix(key), p -> new long[2]);
                    sum[0]++;
                    sum[1] += bytes.value();
                }
                scanned += keys.size();
            } while (!cursor.equals("0") && scanned < maxKeys);
            List<KeySize> biggest = new ArrayList<>(top);
            biggest.sort(Comparator.comparingLong(KeySize::bytes).reversed());
            // Lengths only for the few keys shown: one more pipelined round trip.
            List<Reply> lengths = r.pipeline(
                    biggest.stream().map(k -> lengthCommand(k.key(), k.type())).toList());
            List<KeySize> withLength = new ArrayList<>();
            for (int i = 0; i < biggest.size(); i++) {
                KeySize k = biggest.get(i);
                long length = lengths.get(i) instanceof Reply.Int n ? n.value() : -1;
                withLength.add(new KeySize(k.key(), k.type(), k.bytes(), length));
            }
            List<PrefixSize> byPrefix = prefixes.entrySet().stream()
                    .map(e -> new PrefixSize(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .sorted(Comparator.comparingLong(PrefixSize::bytes).reversed())
                    .limit(TOP_KEYS)
                    .toList();
            return new MemoryReport(
                    number(info.get("used_memory")),
                    number(info.get("maxmemory")),
                    info.getOrDefault("maxmemory_policy", "?"),
                    number(info.get("evicted_keys")),
                    total,
                    scanned,
                    withLength,
                    byPrefix);
        }
    }

    /** How many top keys and prefixes the memory view lists. */
    static final int TOP_KEYS = 50;

    /** "user:42:profile" → "user:*"; a key without ":" has no prefix. */
    static String prefix(String key) {
        int colon = key.indexOf(':');
        return colon > 0 ? key.substring(0, colon + 1) + "*" : "(no prefix)";
    }

    private static List<String> lengthCommand(String key, String type) {
        return switch (type) {
            case "string" -> List.of("STRLEN", key);
            case "list" -> List.of("LLEN", key);
            case "set" -> List.of("SCARD", key);
            case "zset" -> List.of("ZCARD", key);
            case "hash" -> List.of("HLEN", key);
            case "stream" -> List.of("XLEN", key);
            default -> List.of("EXISTS", key); // answers 1: no meaningful length
        };
    }

    /** INFO's "name:value" lines. */
    private static Map<String, String> info(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith("#")) {
                m.put(line.substring(0, colon), line.substring(colon + 1).strip());
            }
        }
        return m;
    }

    private static long number(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public SlowLog slowLog(DatabaseTarget target) {
        try (Resp r = Resp.connect(target)) {
            List<Reply> config = array(r.call("CONFIG", "GET", "slowlog-log-slower-than"));
            long threshold = config.size() == 2 ? number(string(config.get(1))) : -1;
            List<SlowEntry> entries = new ArrayList<>();
            // Each entry: [id, unix time, microseconds, [command words], client address, client name]
            for (Reply e : array(r.call("SLOWLOG", "GET", "100"))) {
                List<Reply> f = array(e);
                entries.add(new SlowEntry(
                        integer(f.get(0)),
                        java.time.Instant.ofEpochSecond(integer(f.get(1))),
                        integer(f.get(2)),
                        strings(f.get(3)),
                        f.size() > 4 ? string(f.get(4)) : ""));
            }
            return new SlowLog(threshold, entries);
        }
    }

    @Override
    public void resetSlowLog(DatabaseTarget target) {
        try (Resp r = Resp.connect(target)) {
            failOnError(r.call("SLOWLOG", "RESET"));
        }
    }

    @Override
    public Matching matching(DatabaseTarget target, String pattern, int sample) {
        try (Resp r = Resp.connect(target)) {
            List<String> first = new ArrayList<>();
            long count = 0;
            String cursor = "0";
            do {
                List<Reply> page = array(r.call("SCAN", cursor, "MATCH", pattern, "COUNT", "1000"));
                cursor = string(page.get(0));
                for (String k : strings(page.get(1))) {
                    count++;
                    if (first.size() < sample) {
                        first.add(k);
                    }
                }
            } while (!cursor.equals("0"));
            first.sort(null);
            return new Matching(count, first);
        }
    }

    @Override
    public long deleteMatching(DatabaseTarget target, String pattern) {
        if (pattern.isBlank()) {
            throw new DataException("give a pattern, like session:*");
        }
        try (Resp r = Resp.connect(target)) {
            long deleted = 0;
            String cursor = "0";
            do {
                List<Reply> page = array(r.call("SCAN", cursor, "MATCH", pattern, "COUNT", "1000"));
                cursor = string(page.get(0));
                List<String> keys = strings(page.get(1));
                if (!keys.isEmpty()) {
                    // UNLINK many keys at once; Redis frees their memory in the background.
                    List<String> words = new ArrayList<>(keys.size() + 1);
                    words.add("UNLINK");
                    words.addAll(keys);
                    deleted += integer(r.call(words.toArray(String[]::new)));
                }
            } while (!cursor.equals("0"));
            return deleted;
        }
    }

    @Override
    public StreamInfo stream(DatabaseTarget target, String key) {
        try (Resp r = Resp.connect(target)) {
            Reply info = r.call("XINFO", "STREAM", key);
            if (info instanceof Reply.Error e) {
                throw new DataException(explain(e.message()));
            }
            Map<String, Reply> s = map(info);
            List<Group> groups = new ArrayList<>();
            for (Reply g : array(r.call("XINFO", "GROUPS", key))) {
                Map<String, Reply> gm = map(g);
                String name = string(gm.getOrDefault("name", NIL));
                List<Consumer> consumers = new ArrayList<>();
                for (Reply c : array(r.call("XINFO", "CONSUMERS", key, name))) {
                    Map<String, Reply> cm = map(c);
                    consumers.add(new Consumer(
                            string(cm.getOrDefault("name", NIL)),
                            count(cm.getOrDefault("pending", NIL)),
                            Duration.ofMillis(count(cm.getOrDefault("idle", NIL)))));
                }
                // "lag" exists since Redis 7.0, and is nil when Redis can't tell (e.g. after deletions).
                Reply lag = gm.getOrDefault("lag", NIL);
                groups.add(new Group(
                        name,
                        count(gm.getOrDefault("pending", NIL)),
                        string(gm.getOrDefault("last-delivered-id", NIL)),
                        lag instanceof Reply.Int n ? n.value() : -1,
                        consumers));
            }
            return new StreamInfo(
                    count(s.getOrDefault("length", NIL)),
                    entryId(s.getOrDefault("first-entry", NIL)),
                    entryId(s.getOrDefault("last-entry", NIL)),
                    groups);
        }
    }

    /** A field XINFO didn't send (older servers send fewer): read as empty. */
    private static final Reply NIL = new Reply.Bulk(null);

    /** XINFO answers a flat [name, value, name, value...] array. */
    private static Map<String, Reply> map(Reply r) {
        List<Reply> flat = array(r);
        Map<String, Reply> m = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            m.put(string(flat.get(i)), flat.get(i + 1));
        }
        return m;
    }

    /** An entry is [id, [fields...]]; nil for an empty stream. */
    private static String entryId(Reply entry) {
        return entry instanceof Reply.Array a && a.items() != null && !a.items().isEmpty()
                ? string(a.items().getFirst())
                : "";
    }

    @Override
    public ServerStats server(DatabaseTarget target) {
        try (Resp r = Resp.connect(target)) {
            Map<String, String> i = info(string(r.call("INFO")));
            long keys = integer(r.call("DBSIZE"));
            return new ServerStats(
                    java.time.Instant.now(),
                    i.getOrDefault("redis_version", i.getOrDefault("valkey_version", "?")),
                    number(i.get("uptime_in_seconds")),
                    number(i.get("instantaneous_ops_per_sec")),
                    number(i.get("connected_clients")),
                    number(i.get("blocked_clients")),
                    number(i.get("used_memory")),
                    keys,
                    number(i.get("keyspace_hits")),
                    number(i.get("keyspace_misses")),
                    number(i.get("expired_keys")),
                    number(i.get("evicted_keys")),
                    number(i.get("total_net_input_bytes")),
                    number(i.get("total_net_output_bytes")));
        }
    }

    @Override
    public List<KeyDump> dump(DatabaseTarget target, String pattern, int maxKeys) {
        try (Resp r = Resp.connect(target)) {
            List<KeyDump> out = new ArrayList<>();
            String cursor = "0";
            do {
                List<Reply> page = array(r.call("SCAN", cursor, "MATCH", pattern, "COUNT", "500"));
                cursor = string(page.get(0));
                List<String> keys = strings(page.get(1));
                List<List<String>> asks = new ArrayList<>();
                for (String k : keys) {
                    asks.add(List.of("DUMP", k));
                    asks.add(List.of("PTTL", k));
                    asks.add(List.of("TYPE", k));
                }
                List<Reply> answers = r.pipeline(asks);
                for (int i = 0; i < keys.size() && out.size() < maxKeys; i++) {
                    if (!(answers.get(3 * i) instanceof Reply.Bulk b) || b.bytes() == null) {
                        continue; // expired since SCAN saw it
                    }
                    long pttl = integer(answers.get(3 * i + 1));
                    out.add(new KeyDump(keys.get(i), string(answers.get(3 * i + 2)), pttl < 0 ? -1 : pttl, b.bytes()));
                }
            } while (!cursor.equals("0") && out.size() < maxKeys);
            return out;
        }
    }

    @Override
    public long restore(DatabaseTarget target, List<KeyDump> keys, boolean replace) {
        try (Resp r = Resp.connect(target)) {
            long written = 0;
            for (KeyDump k : keys) {
                List<byte[]> words = new ArrayList<>();
                words.add("RESTORE".getBytes(StandardCharsets.UTF_8));
                words.add(k.key().getBytes(StandardCharsets.UTF_8));
                // RESTORE takes the time to live in milliseconds; 0 means none.
                words.add(String.valueOf(Math.max(k.ttlMillis(), 0)).getBytes(StandardCharsets.UTF_8));
                words.add(k.payload());
                if (replace) {
                    words.add("REPLACE".getBytes(StandardCharsets.UTF_8));
                }
                r.sendBytes(words);
                Reply reply = r.read();
                if (reply instanceof Reply.Error e) {
                    if (!replace && e.message().startsWith("BUSYKEY")) {
                        continue; // the key exists and the user chose not to overwrite
                    }
                    throw new DataException("couldn't restore " + k.key() + ": " + e.message()
                            + (e.message().contains("payload version") ? " (the file comes from a newer Redis)" : ""));
                }
                written++;
            }
            return written;
        }
    }

    @Override
    public long publish(DatabaseTarget target, String channel, String message) {
        try (Resp r = Resp.connect(target)) {
            return integer(r.call("PUBLISH", channel, message));
        }
    }

    @Override
    public Watch watch(
            DatabaseTarget target,
            WatchKind kind,
            java.util.function.Consumer<WatchEvent> events,
            java.util.function.Consumer<String> ended) {
        Resp r = Resp.connect(target);
        r.waitForever(); // a quiet channel may send nothing for hours: that's not a broken connection
        List<String> start = switch (kind) {
            case WatchKind.Monitor m -> List.of("MONITOR");
            case WatchKind.Subscribe s -> {
                List<String> words = new ArrayList<>(List.of("PSUBSCRIBE"));
                words.addAll(s.patterns());
                yield words;
            }
        };
        r.send(start);
        java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
        // From now on the connection only ever sends; one virtual thread reads it until it is closed.
        Thread.ofVirtual().name("redis-watch-" + target.service()).start(() -> {
            try {
                while (true) {
                    Reply reply = r.read();
                    watchEvent(reply).ifPresent(events);
                    if (reply instanceof Reply.Error e) {
                        ended.accept(e.message());
                        return;
                    }
                }
            } catch (DataException e) {
                ended.accept(closing.get() ? "stopped" : e.getMessage());
            } finally {
                r.close();
            }
        });
        return () -> {
            closing.set(true);
            r.close(); // the blocked read() fails at once, which ends the thread
        };
    }

    /** MONITOR lines and pub/sub messages; subscription confirmations and "OK" are not events. */
    static Optional<WatchEvent> watchEvent(Reply reply) {
        if (reply instanceof Reply.Simple s && !s.text().equals("OK")) {
            return Optional.of(monitorLine(s.text()));
        }
        if (reply instanceof Reply.Array a && a.items() != null && a.items().size() == 4) {
            // ["pmessage", pattern, channel, message]
            if (string(a.items().get(0)).equals("pmessage")) {
                return Optional.of(new WatchEvent(
                        java.time.Instant.now(),
                        string(a.items().get(2)),
                        string(a.items().get(3))));
            }
        }
        return Optional.empty();
    }

    /** {@code 1700000000.123456 [0 127.0.0.1:52344] "SET" "user:1" "x"} → time, client, the command. */
    static WatchEvent monitorLine(String line) {
        int open = line.indexOf(" [");
        int close = line.indexOf("] ", open);
        if (open < 0 || close < 0) {
            return new WatchEvent(java.time.Instant.now(), "", line);
        }
        java.time.Instant at;
        try {
            double seconds = Double.parseDouble(line.substring(0, open));
            at = java.time.Instant.ofEpochSecond((long) seconds, Math.round((seconds % 1) * 1_000_000) * 1000);
        } catch (NumberFormatException e) {
            at = java.time.Instant.now();
        }
        String client = line.substring(open + 2, close);
        // "[0 127.0.0.1:52344]": database number, then the client's address.
        int space = client.indexOf(' ');
        return new WatchEvent(at, space > 0 ? client.substring(space + 1) : client, line.substring(close + 2));
    }

    /** A reply as redis-cli prints it: {@code "text"}, {@code (integer) 3}, {@code (nil)}, numbered lists. */
    static String format(Reply reply, String indent) {
        return switch (reply) {
            case Reply.Simple s -> s.text();
            case Reply.Error e -> "(error) " + e.message();
            case Reply.Int i -> "(integer) " + i.value();
            case Reply.Bulk b ->
                b.bytes() == null ? "(nil)" : '"' + Resp.text(b.bytes()).replace("\"", "\\\"") + '"';
            case Reply.Array a when a.items() == null -> "(nil)";
            case Reply.Array a when a.items().isEmpty() -> "(empty array)";
            case Reply.Array a -> {
                StringBuilder s = new StringBuilder();
                int width = String.valueOf(a.items().size()).length();
                String pad = indent + " ".repeat(width + 2);
                for (int i = 0; i < a.items().size(); i++) {
                    String number = String.format("%" + width + "d) ", i + 1);
                    s.append(i == 0 ? "" : indent)
                            .append(number)
                            .append(format(a.items().get(i), pad));
                    if (i < a.items().size() - 1) {
                        s.append('\n');
                    }
                }
                yield s.toString();
            }
        };
    }

    /** PTTL: -1 = no expiry, -2 = no such key, else milliseconds left. */
    private static Optional<Duration> ttl(Reply r) {
        long ms = integer(r);
        return ms >= 0 ? Optional.of(Duration.ofMillis(ms)) : Optional.empty();
    }

    private static List<Field> fields(List<?> flat) {
        List<String> s = flat.stream()
                .map(o -> o instanceof Reply r ? string(r) : (String) o)
                .toList();
        List<Field> out = new ArrayList<>();
        for (int i = 0; i + 1 < s.size(); i += 2) {
            out.add(new Field(s.get(i), s.get(i + 1)));
        }
        return out;
    }

    private static List<String> strings(Reply r) {
        return array(r).stream().map(RedisBrowser::string).toList();
    }

    private static List<String> strings(List<Reply> replies) {
        return replies.stream().map(RedisBrowser::string).toList();
    }

    private static List<Reply> array(Reply r) {
        return switch (r) {
            case Reply.Array a when a.items() != null -> a.items();
            case Reply.Error e -> throw new DataException(e.message());
            default -> List.of();
        };
    }

    private static String string(Reply r) {
        return switch (r) {
            case Reply.Bulk b -> b.bytes() == null ? "" : Resp.text(b.bytes());
            case Reply.Simple s -> s.text();
            case Reply.Int i -> String.valueOf(i.value());
            case Reply.Error e -> throw new DataException(e.message());
            case Reply.Array a -> throw new DataException("expected one value, Redis sent a list");
        };
    }

    /** A number XINFO may leave out: 0 then. */
    private static long count(Reply r) {
        return r instanceof Reply.Int i ? i.value() : 0;
    }

    private static long integer(Reply r) {
        return switch (r) {
            case Reply.Int i -> i.value();
            case Reply.Error e -> throw new DataException(e.message());
            default -> throw new DataException("expected a number from Redis, got " + format(r, ""));
        };
    }
}
