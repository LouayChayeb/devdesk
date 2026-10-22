package io.github.louch.devdesk.core.config;

import java.util.List;
import java.util.Map;

/**
 * A YAML value that remembers where it was written. Jackson's own tree forgets positions, but error messages need
 * them ("devdesk.yaml:12:5: ...").
 */
public sealed interface YamlNode {

    Location location();

    /** {@code key: value} pairs, in file order. */
    record Mapping(Map<String, Entry> entries, Location location) implements YamlNode {}

    /** {@code [a, b]} or a {@code - item} list. */
    record Sequence(List<YamlNode> items, Location location) implements YamlNode {}

    /** Text, number, boolean or empty. YAML scalars are kept as text; the parser decides what they must be. */
    record Scalar(String text, Location location) implements YamlNode {
        public boolean isNull() {
            return text == null;
        }
    }

    /** One mapping entry: where the key was written, and its value. */
    record Entry(Location keyLocation, YamlNode value) {}

    static String describe(YamlNode node) {
        return switch (node) {
            case Mapping m -> "a mapping (key: value)";
            case Sequence s -> "a list";
            case Scalar s when s.isNull() -> "empty";
            case Scalar s -> "\"" + s.text() + "\"";
        };
    }
}
