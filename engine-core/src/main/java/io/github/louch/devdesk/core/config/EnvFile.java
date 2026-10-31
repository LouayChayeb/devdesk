package io.github.louch.devdesk.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads a {@code .env} file: {@code KEY=value} lines, {@code #} comments, optional quotes, optional "export". */
public final class EnvFile {

    private EnvFile() {}

    /** The variables in the file's text, in file order; a later line wins. Lines that aren't variables are skipped. */
    public static Map<String, String> parse(String content) {
        Map<String, String> vars = new LinkedHashMap<>();
        // lines() splits at \n, \r\n and a lone \r, exactly like EnvFileEdit, so both see the same lines.
        for (String raw : content.lines().toList()) {
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
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                // Inside double quotes \" and \\ stand for " and \ (what EnvFileEdit writes); others stay as typed.
                value = value.substring(1, value.length() - 1).replaceAll("\\\\([\"\\\\])", "$1");
            } else if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1); // single quotes: every character literally
            }
            vars.put(line.substring(0, eq).strip(), value);
        }
        return vars;
    }
}
