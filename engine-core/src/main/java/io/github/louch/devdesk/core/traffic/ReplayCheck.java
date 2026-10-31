package io.github.louch.devdesk.core.traffic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Compares a replayed call's answer with the recorded one: same status, and the same body apart from what changes on
 * every call anyway (ids, timestamps, tokens). JSON bodies are compared field by field, so the difference has a name.
 */
public final class ReplayCheck {

    /** Field names whose values differ on every run without anything being wrong. */
    static final Set<String> VOLATILE = Set.of(
            "id",
            "uuid",
            "_id",
            "createdat",
            "created_at",
            "updatedat",
            "updated_at",
            "timestamp",
            "time",
            "date",
            "now",
            "token",
            "access_token",
            "refresh_token",
            "expires",
            "expiresat",
            "expires_at",
            "requestid",
            "request_id",
            "traceid",
            "trace_id",
            "etag",
            "nonce",
            "csrf");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReplayCheck() {}

    /**
     * @param differences in plain words ("status 200 → 500", "$.items[2].price: 12 → 14"); empty = it behaved the same
     */
    public record Result(boolean same, List<String> differences) {}

    /**
     * The differences between the recorded answer and this one; {@code same} is true when only ids and times changed.
     */
    public static Result compare(Exchange recorded, int status, String body) {
        List<String> diffs = new ArrayList<>();
        if (recorded.status() != status) {
            diffs.add("status " + recorded.status() + " → " + status);
        }
        Optional<JsonNode> a = json(recorded.responseBody());
        Optional<JsonNode> b = json(body);
        if (a.isPresent() && b.isPresent()) {
            diff("$", a.get(), b.get(), diffs);
        } else if (!recorded.responseBody().equals(body)) {
            diffs.add("body differs (" + recorded.responseBody().length() + " → " + body.length() + " characters)");
        }
        return new Result(diffs.isEmpty(), diffs.size() > 20 ? diffs.subList(0, 20) : diffs);
    }

    private static Optional<JsonNode> json(String text) {
        String t = text.strip();
        if (!t.startsWith("{") && !t.startsWith("[")) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(t));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void diff(String path, JsonNode a, JsonNode b, List<String> out) {
        if (a.isObject() && b.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = a.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (VOLATILE.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!b.has(e.getKey())) {
                    out.add(path + "." + e.getKey() + ": gone");
                } else {
                    diff(path + "." + e.getKey(), e.getValue(), b.get(e.getKey()), out);
                }
            }
            for (Iterator<String> it = b.propertyNames().iterator(); it.hasNext(); ) {
                String k = it.next();
                if (!a.has(k) && !VOLATILE.contains(k.toLowerCase(Locale.ROOT))) {
                    out.add(path + "." + k + ": new");
                }
            }
        } else if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                out.add(path + ": " + a.size() + " → " + b.size() + " items");
            }
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                diff(path + "[" + i + "]", a.get(i), b.get(i), out);
            }
        } else if (!a.equals(b)) {
            out.add(path + ": " + shortly(a) + " → " + shortly(b));
        }
    }

    private static String shortly(JsonNode n) {
        String s = n.toString();
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
