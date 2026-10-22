package io.github.louch.devdesk.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads a {@code .env} file: {@code KEY=value} lines, {@code #} comments, optional quotes, optional "export". */
public final class EnvFile {

    private EnvFile() {}

    public static Map<String, String> parse(String content) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue; // not KEY=value; .env files in the wild contain all sorts of things
            }
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2
                    && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            vars.put(line.substring(0, eq).strip(), value);
        }
        return vars;
    }
}
