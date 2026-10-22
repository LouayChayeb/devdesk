package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DevDesk's file of exported Redis keys: JSON with each key's name, type, time to live and its DUMP (Redis's own
 * serialized form, base64). Any type is copied exactly; it can be read back by the same or a newer Redis.
 */
public final class KeyDumpFile {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FORMAT = "devdesk-redis-keys";

    private KeyDumpFile() {}

    public static String write(List<KeyValueBrowser.KeyDump> keys) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (KeyValueBrowser.KeyDump k : keys) {
            list.add(Map.of(
                    "key", k.key(),
                    "type", k.type(),
                    "ttlMillis", k.ttlMillis(),
                    "dump", Base64.getEncoder().encodeToString(k.payload())));
        }
        return JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("format", FORMAT, "version", 1, "keys", list));
    }

    /** @throws DataException when the text isn't such a file */
    public static List<KeyValueBrowser.KeyDump> read(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new DataException("the file isn't valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !FORMAT.equals(root.path("format").asString(""))) {
            throw new DataException(
                    "that isn't a file of Redis keys exported by DevDesk (no \"format\": \"" + FORMAT + "\" in it)");
        }
        List<KeyValueBrowser.KeyDump> out = new ArrayList<>();
        for (JsonNode k : root.path("keys")) {
            try {
                out.add(new KeyValueBrowser.KeyDump(
                        k.path("key").asString(),
                        k.path("type").asString(""),
                        k.path("ttlMillis").asLong(-1),
                        Base64.getDecoder().decode(k.path("dump").asString(""))));
            } catch (IllegalArgumentException e) {
                throw new DataException("the key " + k.path("key").asString("?") + " in the file is damaged (its dump"
                        + " isn't base64)");
            }
        }
        return out;
    }
}
