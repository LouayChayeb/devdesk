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

/** Turns YAML text into a {@link YamlNode} tree using Jackson's token stream, keeping every position. */
final class YamlReader {

    private static final YAMLFactory FACTORY = YAMLFactory.builder().build();

    private YamlReader() {}

    /** Parses the whole document. Syntax errors and duplicate keys become problems with a position. */
    static YamlNode read(String yaml) throws ConfigException {
        try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), yaml)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw ConfigException.single(new Location(1, 1), "", "the file is empty");
            }
            return node(parser);
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
        }
    }

    /** Reads the value starting at the current token, recursively. */
    private static YamlNode node(JsonParser parser) throws ConfigException {
        Location at = location(parser);
        JsonToken token = parser.currentToken();
        return switch (token) {
            case START_OBJECT -> mapping(parser, at);
            case START_ARRAY -> sequence(parser, at);
            case VALUE_NULL -> new YamlNode.Scalar(null, at);
            default -> new YamlNode.Scalar(parser.getString(), at);
        };
    }

    private static YamlNode mapping(JsonParser parser, Location at) throws ConfigException {
        Map<String, YamlNode.Entry> entries = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String key = parser.getString();
            Location keyAt = location(parser);
            parser.nextToken();
            if (entries.containsKey(key)) {
                // YAML parsers silently keep the last one; a typo-duplicated service would vanish without a word.
                throw ConfigException.single(
                        keyAt,
                        key,
                        "\"" + key + "\" appears twice; the first is at line "
                                + entries.get(key).keyLocation().line());
            }
            entries.put(key, new YamlNode.Entry(keyAt, node(parser)));
        }
        return new YamlNode.Mapping(entries, at);
    }

    private static YamlNode sequence(JsonParser parser, Location at) throws ConfigException {
        List<YamlNode> items = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            items.add(node(parser));
        }
        return new YamlNode.Sequence(items, at);
    }

    private static Location location(JsonParser parser) {
        TokenStreamLocation l = parser.currentTokenLocation();
        return new Location(l.getLineNr(), l.getColumnNr());
    }
}
