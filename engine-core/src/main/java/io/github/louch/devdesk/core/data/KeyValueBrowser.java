package io.github.louch.devdesk.core.data;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads and changes a Redis database for the Workbench. Implemented over a plain socket in engine-data; faked in
 * tests. Browsing never changes anything; deleting a key, setting a TTL and the command box are explicit actions.
 */
public interface KeyValueBrowser {

    /**
     * One step of walking the keys that match {@code pattern} ("user:*"), with their type and time to live. Redis
     * walks its keys in steps (SCAN), so a big database never blocks: pass {@link KeyPage#next()} back to continue.
     */
    KeyPage keys(DatabaseTarget target, String pattern, String cursor, int count);

    /** A key's value, whatever its type. Empty when the key doesn't exist (or expired meanwhile). */
    Optional<KeyValue> read(DatabaseTarget target, String key);

    /** @return true if the key existed */
    boolean delete(DatabaseTarget target, String key);

    /** A new time to live; empty removes it, so the key stays until deleted. @return true if the key existed */
    boolean expire(DatabaseTarget target, String key, Optional<Duration> ttl);

    /**
     * Changes one key: a string's text, a hash field, a list element, a set or sorted-set member, a JSON document.
     * Creates the key when it doesn't exist yet (except for edits that need an existing element).
     */
    void edit(DatabaseTarget target, String key, Edit edit);

    /** Any command the user typed, like redis-cli. The reply is shown as text, formatted like redis-cli does. */
    String command(DatabaseTarget target, List<String> words);

    /**
     * Where the memory goes: Redis's own totals, the biggest keys, and the total per key prefix ("user:*"), from
     * walking at most {@code maxKeys} keys (MEMORY USAGE each, in pipelined batches).
     */
    MemoryReport memory(DatabaseTarget target, String pattern, int maxKeys);

    /** The slow log: the latest commands that took longer than the server's threshold, newest first. */
    SlowLog slowLog(DatabaseTarget target);

    /** Empties the slow log, so the next look shows only what happens from now on. */
    void resetSlowLog(DatabaseTarget target);

    /**
     * Counts the keys matching {@code pattern} (walking all of them with SCAN) and returns the first {@code sample}:
     * the preview shown before deleting by pattern.
     */
    Matching matching(DatabaseTarget target, String pattern, int sample);

    /** Deletes every key matching {@code pattern} (SCAN + UNLINK in batches). @return how many were deleted */
    long deleteMatching(DatabaseTarget target, String pattern);

    /**
     * A stream's consumer groups: for each one, how far it has read, how many messages its consumers took but didn't
     * acknowledge yet (pending), and how long each consumer has been quiet.
     */
    StreamInfo stream(DatabaseTarget target, String key);

    /** The server's counters right now (INFO): the stats charts sample this every few seconds. */
    ServerStats server(DatabaseTarget target);

    /**
     * Every key matching {@code pattern} (at most {@code maxKeys}) as Redis's own serialized form (DUMP), with its time
     * to live: an exact copy of any type, restored with {@link #restore}.
     */
    List<KeyDump> dump(DatabaseTarget target, String pattern, int maxKeys);

    /**
     * Puts dumped keys back (RESTORE). With {@code replace} an existing key is overwritten; without, it's skipped.
     *
     * @return how many keys were written
     */
    long restore(DatabaseTarget target, List<KeyDump> keys, boolean replace);

    /** @param lastId the newest entry's id ("1728390000000-0"), "" when empty */
    record StreamInfo(long length, String firstId, String lastId, List<Group> groups) {}

    /**
     * @param lastDelivered the id of the last message handed to one of its consumers
     * @param lag messages added after that and not read by the group yet; -1 when the server doesn't say (before 7.0)
     */
    record Group(String name, long pending, String lastDelivered, long lag, List<Consumer> consumers) {}

    /** @param idle since this consumer last read or acknowledged */
    record Consumer(String name, long pending, Duration idle) {}

    /** Counters from INFO; totals count since the server started. */
    record ServerStats(
            java.time.Instant at,
            String version,
            long uptimeSeconds,
            long opsPerSecond,
            long clients,
            long blockedClients,
            long usedBytes,
            long keys,
            long hits,
            long misses,
            long expiredKeys,
            long evictedKeys,
            long netInBytes,
            long netOutBytes) {}

    /** @param ttlMillis -1 when the key never expires */
    record KeyDump(String key, String type, long ttlMillis, byte[] payload) {}

    /** Sends a message on a pub/sub channel. @return how many subscribers got it */
    long publish(DatabaseTarget target, String channel, String message);

    /**
     * Opens a live stream on its own connection: every command the server runs (MONITOR), or the messages on
     * channels matching the patterns (PSUBSCRIBE). Events go to {@code events} on a background thread until the
     * returned {@link Watch} is closed, or the connection ends ({@code ended} gets the reason).
     */
    Watch watch(
            DatabaseTarget target,
            WatchKind kind,
            java.util.function.Consumer<WatchEvent> events,
            java.util.function.Consumer<String> ended);

    /** What a live stream shows. */
    sealed interface WatchKind {
        /** Every command any client sends. Costs the server time: meant for a short look, not to leave running. */
        record Monitor() implements WatchKind {}

        /** Messages on the channels matching these patterns ("orders.*"). */
        record Subscribe(List<String> patterns) implements WatchKind {}
    }

    /**
     * One event of a stream: for MONITOR the client and the command; for pub/sub the channel and the message.
     *
     * @param at when Redis saw it (MONITOR) or when it arrived (pub/sub)
     */
    record WatchEvent(java.time.Instant at, String source, String text) {}

    /** A running stream; closing it closes its connection. */
    interface Watch extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * @param usedBytes Redis's used_memory
     * @param maxBytes maxmemory; 0 = no limit
     * @param policy what Redis does at the limit (maxmemory-policy), e.g. "noeviction" (writes fail)
     * @param evicted keys Redis threw away because of the limit, since it started
     * @param totalKeys DBSIZE
     * @param scanned keys measured for the lists below; less than totalKeys when the walk stopped early
     */
    record MemoryReport(
            long usedBytes,
            long maxBytes,
            String policy,
            long evicted,
            long totalKeys,
            long scanned,
            List<KeySize> biggest,
            List<PrefixSize> prefixes) {}

    /** @param length elements (characters for a string) */
    record KeySize(String key, String type, long bytes, long length) {}

    /** All keys starting with {@code prefix} ("user:"), added up. */
    record PrefixSize(String prefix, long keys, long bytes) {}

    /** @param thresholdMicros slowlog-log-slower-than: commands slower than this are logged */
    record SlowLog(long thresholdMicros, List<SlowEntry> entries) {}

    /** @param words the command, cut by Redis after 32 arguments */
    record SlowEntry(long id, java.time.Instant at, long micros, List<String> words, String client) {}

    /** @param count keys matching in total; {@code sample} the first of them */
    record Matching(long count, List<String> sample) {}

    /**
     * @param next "0" when the walk is over
     */
    record KeyPage(List<KeyInfo> keys, String next) {}

    /** @param ttl empty when the key never expires */
    record KeyInfo(String key, String type, Optional<Duration> ttl) {}

    /**
     * @param length elements in the value (characters for a string); may be more than were read
     * @param value at most a few thousand elements, so one huge list can't freeze the app
     */
    record KeyValue(String key, String type, Optional<Duration> ttl, long length, Value value) {}

    /** The value in a shape per Redis type. Text is shown as is; binary data comes back with escapes like redis-cli. */
    sealed interface Value {
        /** A string value, cut at a few hundred KB. */
        record Text(String text) implements Value {}

        /** A list in its order, or a set's members sorted. */
        record Items(List<String> items) implements Value {}

        /** A hash's fields, a stream entry's fields: pairs, in Redis's order. */
        record Fields(List<Field> fields) implements Value {}

        /** A sorted set, lowest score first. */
        record Scored(List<Member> members) implements Value {}

        /** A stream, newest entry first. */
        record Entries(List<StreamEntry> entries) implements Value {}

        /** A type DevDesk doesn't show yet (e.g. a module's type): the command box still works. */
        record Unsupported(String type) implements Value {}
    }

    /** One change to a key's value, by type. {@link RedisEdits} turns each into Redis commands. */
    sealed interface Edit {
        /** Replaces a string's text, keeping its TTL. */
        record SetText(String text) implements Edit {}

        /** Replaces a JSON document (RedisJSON module) with this JSON text. */
        record SetJson(String json) implements Edit {}

        /** Adds or changes one field of a hash. */
        record HashSet(String field, String value) implements Edit {}

        /** Removes one field of a hash. */
        record HashDelete(String field) implements Edit {}

        /** Changes the element at {@code index} of a list. */
        record ListSet(long index, String value) implements Edit {}

        /** Adds an element at the start ({@code head}) or end of a list. */
        record ListPush(String value, boolean head) implements Edit {}

        /** Removes the element at {@code index} of a list. */
        record ListRemove(long index) implements Edit {}

        /** Adds a member to a set. */
        record SetAdd(String member) implements Edit {}

        /** Removes a member from a set. */
        record SetRemove(String member) implements Edit {}

        /** Adds a member to a sorted set, or changes its score. */
        record ScoreSet(String member, double score) implements Edit {}

        /** Removes a member from a sorted set. */
        record ScoreRemove(String member) implements Edit {}

        /** Adds an entry to a stream (XADD with an id Redis picks). */
        record StreamAdd(List<Field> fields) implements Edit {}

        /** Creates a consumer group that starts reading after the newest entry ({@code fromStart}: from the first). */
        record GroupCreate(String group, boolean fromStart) implements Edit {}

        /** Removes a consumer group and what it still had pending. */
        record GroupDelete(String group) implements Edit {}
    }

    /** One field of a hash or of a stream entry. */
    record Field(String name, String value) {}

    /** One member of a sorted set. */
    record Member(String member, double score) {}

    /** One stream entry: its id ("1700000000000-0", time-sequence) and fields. */
    record StreamEntry(String id, List<Field> fields) {}
}
