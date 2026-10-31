package io.github.louch.devdesk.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLParser;

/** Turns YAML text into a {@link YamlNode} tree using Jackson's token stream, keeping every position. */
public final class YamlReader {

    private static final YAMLFactory FACTORY = YAMLFactory.builder().build();

    private YamlReader() {}

    /** Parses the whole document. Syntax errors and duplicate keys become problems with a position. */
    public static YamlNode read(String yaml) throws ConfigException {
        try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), yaml)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw ConfigException.single(new Location(1, 1), "", "the file is empty");
            }
            return node(parser, new java.util.HashMap<>());
        } catch (JacksonException e) {
            TokenStreamLocation at = e.getLocation();
            Location where = at == null ? new Location(1, 1) : new Location(at.getLineNr(), at.getColumnNr());
            // SnakeYAML's message mixes explanation lines with its own indented excerpt of the file
            // (" in reader, line 3...", the line, a "^" marker). We already give the position, so keep only the
            // unindented explanation lines, joined into one line.
            String message = e.getOriginalMessage()
                    .lines()
                    .filter(line -> !line.isBlank() && !Character.isWhitespace(line.charAt(0)))
                    .collect(Collectors.joining(": "));
            throw ConfigException.single(where, "", "not valid YAML: " + message);
        } catch (IndexOutOfBoundsException | IllegalStateException e) {
            // The YAML library itself failing on odd input (found by fuzzing: a lone half of a two-part character
            // at the end of its 1024-character read buffer). Any text must get an answer, never a crash.
            throw ConfigException.single(
                    new Location(1, 1), "", "not valid YAML: the file has characters the YAML reader can't read");
        }
    }

    /**
     * Reads the value starting at the current token, recursively. Anchors ({@code &base}) are remembered and
     * aliases ({@code *base}) read as the node they name, like docker compose does: Chatwoot's compose file gives
     * its services their image through {@code <<: *base}.
     */
    private static YamlNode node(JsonParser parser, Map<String, YamlNode> anchors) throws ConfigException {
        Location at = location(parser);
        JsonToken token = parser.currentToken();
        if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias()) {
            String alias = parser.getString();
            YamlNode target = anchors.get(alias);
            if (target == null) {
                throw ConfigException.single(at, "", "*" + alias + " refers to no &" + alias + " above it");
            }
            return target;
        }
        String anchor = parser instanceof YAMLParser yaml && yaml.getObjectId() instanceof String id ? id : null;
        YamlNode node = switch (token) {
            case START_OBJECT -> mapping(parser, at, anchors);
            case START_ARRAY -> sequence(parser, at, anchors);
            case VALUE_NULL -> new YamlNode.Scalar(null, at);
            default -> new YamlNode.Scalar(parser.getString(), at);
        };
        if (anchor != null) {
            anchors.put(anchor, node);
        }
        return node;
    }

    private static YamlNode mapping(JsonParser parser, Location at, Map<String, YamlNode> anchors)
            throws ConfigException {
        Map<String, YamlNode.Entry> entries = new LinkedHashMap<>();
        List<YamlNode.Mapping> merged = new ArrayList<>();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String key = parser.getString();
            Location keyAt = location(parser);
            parser.nextToken();
            YamlNode value = node(parser, anchors);
            if (key.equals("<<")) {
                // A merge key: the keys of the mapping(s) it names, unless this mapping sets them itself.
                switch (value) {
                    case YamlNode.Mapping m -> merged.add(m);
                    case YamlNode.Sequence seq -> {
                        for (YamlNode item : seq.items()) {
                            if (!(item instanceof YamlNode.Mapping m)) {
                                throw ConfigException.single(keyAt, key, "<< takes mappings (like *base) to merge");
                            }
                            merged.add(m);
                        }
                    }
                    case YamlNode.Scalar sc ->
                        throw ConfigException.single(keyAt, key, "<< takes a mapping (like *base) to merge");
                }
                continue;
            }
            if (entries.containsKey(key)) {
                // YAML parsers silently keep the last one; a typo-duplicated service would vanish without a word.
                throw ConfigException.single(
                        keyAt,
                        key,
                        "\"" + key + "\" appears twice; the first is at line "
                                + entries.get(key).keyLocation().line());
            }
            entries.put(key, new YamlNode.Entry(keyAt, value));
        }
        // Keys written here win; among merged mappings, the first one named wins (YAML's merge rule).
        for (YamlNode.Mapping m : merged) {
            m.entries().forEach(entries::putIfAbsent);
        }
        return new YamlNode.Mapping(entries, at);
    }

    private static YamlNode sequence(JsonParser parser, Location at, Map<String, YamlNode> anchors)
            throws ConfigException {
        List<YamlNode> items = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            items.add(node(parser, anchors));
        }
        return new YamlNode.Sequence(items, at);
    }

    private static Location location(JsonParser parser) {
        TokenStreamLocation l = parser.currentTokenLocation();
        return new Location(l.getLineNr(), l.getColumnNr());
    }
}
