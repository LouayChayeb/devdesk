package io.github.louch.devdesk.core.data;

import io.github.louch.devdesk.core.data.KeyValueBrowser.Edit;
import java.util.ArrayList;
import java.util.List;

/** The Redis commands for each value edit of the Workbench: no hidden magic, the same commands you'd type. */
public final class RedisEdits {

    private RedisEdits() {}

    /**
     * The commands to run, in order. {@code marker} is a value nobody else uses, for removing a list element by
     * position (Redis can only remove list elements by value).
     */
    public static List<List<String>> commands(String key, Edit edit, String marker) {
        return switch (edit) {
            // KEEPTTL: editing the text must not silently make an expiring key permanent.
            case Edit.SetText t -> List.of(List.of("SET", key, t.text(), "KEEPTTL"));
            case Edit.SetJson j -> List.of(List.of("JSON.SET", key, "$", j.json()));
            case Edit.HashSet h -> List.of(List.of("HSET", key, h.field(), h.value()));
            case Edit.HashDelete h -> List.of(List.of("HDEL", key, h.field()));
            case Edit.ListSet l -> List.of(List.of("LSET", key, String.valueOf(l.index()), l.value()));
            case Edit.ListPush l -> List.of(List.of(l.head() ? "LPUSH" : "RPUSH", key, l.value()));
            // Mark the element, then remove the (only) element with that mark. Two commands in one MULTI/EXEC
            // transaction, so no other client can see or change the list in between.
            case Edit.ListRemove l ->
                List.of(
                        List.of("MULTI"),
                        List.of("LSET", key, String.valueOf(l.index()), marker),
                        List.of("LREM", key, "1", marker),
                        List.of("EXEC"));
            case Edit.SetAdd s -> List.of(List.of("SADD", key, s.member()));
            case Edit.SetRemove s -> List.of(List.of("SREM", key, s.member()));
            case Edit.ScoreSet z -> List.of(List.of("ZADD", key, score(z.score()), z.member()));
            case Edit.ScoreRemove z -> List.of(List.of("ZREM", key, z.member()));
            case Edit.StreamAdd a -> {
                List<String> words = new ArrayList<>(List.of("XADD", key, "*"));
                a.fields().forEach(f -> {
                    words.add(f.name());
                    words.add(f.value());
                });
                yield List.of(words);
            }
            // MKSTREAM: creating a group on a stream nobody wrote to yet makes the (empty) stream.
            case Edit.GroupCreate g ->
                List.of(List.of("XGROUP", "CREATE", key, g.group(), g.fromStart() ? "0" : "$", "MKSTREAM"));
            case Edit.GroupDelete g -> List.of(List.of("XGROUP", "DESTROY", key, g.group()));
        };
    }

    /** 2.0 → "2", 1.5 → "1.5": what a user would type. */
    static String score(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
